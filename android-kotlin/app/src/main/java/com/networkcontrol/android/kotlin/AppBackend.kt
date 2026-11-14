package com.networkcontrol.android.kotlin

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.AppDecision
import com.networkcontrol.android.kotlin.core.model.AppEntry
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.AppRoutingReason
import com.networkcontrol.android.kotlin.core.model.AppTraffic
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.LogEntry
import com.networkcontrol.android.kotlin.core.model.LogQuery
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.ProtectionStatus
import com.networkcontrol.android.kotlin.core.model.PromptDecision
import com.networkcontrol.android.kotlin.core.model.PromptSnapshot
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleEndReason
import com.networkcontrol.android.kotlin.core.model.RuleImportMode
import com.networkcontrol.android.kotlin.core.model.RuleImportPreview
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.TrafficUsage
import com.networkcontrol.android.kotlin.core.model.UserSettings
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.rules.AppMatcherFactory
import com.networkcontrol.android.kotlin.data.transfer.RuleTransferService
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog
import com.networkcontrol.android.kotlin.engine.vpn.AndroidRuleCompatibility
import com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class AppBackend(val container: AppContainer) {
    private val context get() = container.appContext
    private val scope get() = container.scope

    val settings = container.settings.state
    val runtime = container.runtime.state
    val profiles = container.profiles.profiles
    val prompts = container.prompts.pending
    val plan = container.vpnEngine.plan
    val protection = container.protection.enabled
    val tunnelState = container.vpnEngine.tunnelState
    val tunnelError = container.vpnEngine.tunnelError
    val quietPeriod = container.quietPeriods.summary

    fun protectionStatus(): ProtectionStatus = container.vpnEngine.status()

    fun setProtection(enabled: Boolean) = container.vpnEngine.setProtection(enabled)

    fun createConsentIntent(): Intent? = container.vpnEngine.createConsentIntent()

    fun setMode(mode: FilterMode) {
        container.scope.launch { container.profiles.setActiveMode(mode) }
    }

    fun setBlockAll(blockAll: Boolean) {
        container.scope.launch {
            if (blockAll) container.runtime.setMode(FilterMode.BlockAll)
            else container.runtime.restoreFromBlockAll()
        }
    }

    fun setDoNotDisturb(until: Long?) {
        container.scope.launch { container.runtime.setDoNotDisturb(until) }
    }

    fun appEntries(): List<AppEntry> {
        val plan = container.vpnEngine.plan.value
        val rules = container.rules.ruleSet.value.rules
        val self = context.packageName
        return container.appCatalog.getApps()
            .asSequence()
            .filter { it.packageName != self }
            .map { app ->
                val path = AppCatalog.appPath(app.packageName)
                val own = ownRule(rules, path)
                val routing = plan.find(app.uid)
                AppEntry(
                    app = app,
                    decision = when (own?.action) {
                        VerdictAction.Allow -> AppDecision.Allow
                        VerdictAction.Deny -> AppDecision.Deny
                        null -> AppDecision.Unset
                    },
                    effective = routing?.decision ?: AppRoutingDecision.BlockAndAsk,
                    reason = routing?.reason ?: AppRoutingReason.Mode,
                    expiresAt = own?.expiresAt?.takeIf { own.lifetime == RuleLifetime.Timed },
                    isTemporary = own != null && (!own.isPersistent || own.lifetime == RuleLifetime.Timed),
                    isTampered = routing?.tamperedRuleId != null,
                )
            }
            .sortedWith(compareBy({ it.app.label.lowercase() }, { it.app.packageName }))
            .toList()
    }

    suspend fun setAppDecision(packageName: String, decision: AppDecision) {
        val path = AppCatalog.appPath(packageName)
        val existing = container.rules.all
            .filter { it.source == RuleSource.User || it.source == RuleSource.Prompt }
            .filter { isOwnRule(it, path) }
            .map { it.id }
        if (existing.isNotEmpty()) {
            container.rules.remove(existing, RuleEndReason.Replaced)
        }
        if (decision == AppDecision.Unset) return
        val app = container.appCatalog.find(packageName) ?: return
        val now = Time.now()
        container.rules.add(
            Rule(
                id = java.util.UUID.randomUUID().toString(),
                app = AppMatcherFactory.create(container.appCatalog.toIdentity(app)),
                conditions = RuleConditions(direction = Direction.Outbound),
                action = if (decision == AppDecision.Allow) VerdictAction.Allow else VerdictAction.Deny,
                lifetime = RuleLifetime.Permanent,
                source = RuleSource.User,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun getLogs(limit: Int = 300, keyword: String? = null): List<LogEntry> =
        container.activityStore.query(
            LogQuery(action = VerdictAction.Deny, keyword = keyword, limit = limit),
        )

    suspend fun countBlockedToday(): Long {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return container.activityStore.countBlockedSince(start)
    }

    suspend fun getTrafficToday(): TrafficUsage {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return getTraffic(start, Time.now())
    }

    suspend fun getTraffic(from: Long, to: Long): TrafficUsage {
        if (!container.trafficCollector.isAvailable) return TrafficUsage.Empty
        runCatching { container.trafficCollector.collectNow() }
        val byApp = container.trafficStore.byApp(from, to)
        var totalIn = 0L
        var totalOut = 0L
        val byPackage = HashMap<String, Pair<Long, Long>>()
        byApp.forEach { (appId, usage) ->
            totalIn += usage.bytesIn
            totalOut += usage.bytesOut
            val identity = container.appStore.get(appId) ?: return@forEach
            val packageName = identity.path.removePrefix("android-app://").takeIf { it != identity.path } ?: return@forEach
            val current = byPackage[packageName] ?: (0L to 0L)
            byPackage[packageName] = (current.first + usage.bytesIn) to (current.second + usage.bytesOut)
        }
        return TrafficUsage(totalIn, totalOut, byPackage)
    }

    fun activeProfile(): Profile = container.profiles.active

    fun activateProfile(id: String) {
        container.scope.launch { container.profiles.activate(id) }
    }

    fun createProfile(name: String, mode: FilterMode) {
        container.scope.launch { container.profiles.create(name, mode) }
    }

    fun updateProfile(id: String, name: String, mode: FilterMode, autoActivate: com.networkcontrol.android.kotlin.core.model.AutoActivateCondition? = null) {
        container.scope.launch { container.profiles.update(id, name, mode, autoActivate) }
    }

    fun deleteProfile(id: String) {
        container.scope.launch { container.profiles.delete(id) }
    }

    fun getRuleCount(profileId: String): Int = container.profiles.getRuleCount(profileId)

    fun answerPrompt(
        id: String,
        action: VerdictAction,
        lifetime: RuleLifetime,
        durationSeconds: Int? = null,
    ) {
        container.scope.launch {
            container.prompts.answer(
                id,
                PromptDecision(
                    action = action,
                    lifetime = lifetime,
                    durationSeconds = durationSeconds,
                    scope = com.networkcontrol.android.kotlin.core.model.PromptScope.AnyConnection,
                ),
            )
        }
    }

    fun holdPrompt(id: String) {
        container.scope.launch { container.prompts.pauseTimer(id) }
    }

    fun dismissPrompt(id: String) {
        container.scope.launch { container.prompts.dismiss(id) }
    }

    fun dismissQuietPeriod() = container.quietPeriods.dismiss()

    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        container.scope.launch { container.settings.update(transform) }
    }

    fun hasTrafficAccess(): Boolean = container.trafficCollector.isAvailable

    fun trafficAccessIntent(): Intent = container.trafficCollector.createAccessIntent()

    fun openAlwaysOnVpnSettings(): Intent =
        Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openBatteryOptimizationSettings(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openNotificationSettings(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun setLanguage(tag: String?) {
        container.preferences.languageTag = tag
        val locales = if (tag.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun setTheme(mode: String) {
        container.preferences.themeMode = mode
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    fun exportRules(): String = RuleTransferService.export(container.rules.all)

    fun previewImport(json: String, mode: RuleImportMode): RuleImportPreview {
        val imported = RuleTransferService.importForPlan(json, container.profiles.all)
        val plan = RuleTransferService.plan(
            current = container.rules.all,
            imported = imported,
            mode = mode,
        )
        return RuleImportPreview(
            total = imported.size,
            added = plan.toAdd.size,
            updated = plan.toUpdate.size,
            removed = plan.toRemove.size,
            unchanged = plan.unchanged,
            ineffective = AndroidRuleCompatibility.countIneffective(imported),
        )
    }

    suspend fun applyImport(json: String, mode: RuleImportMode): RuleImportPreview {
        val imported = RuleTransferService.importForPlan(json, container.profiles.all)
        val plan = RuleTransferService.plan(container.rules.all, imported, mode)
        if (plan.toAdd.isNotEmpty()) container.rules.addRange(plan.toAdd)
        plan.toUpdate.forEach { container.rules.update(it) }
        if (plan.toRemove.isNotEmpty()) {
            container.rules.remove(plan.toRemove.map { it.id }, RuleEndReason.Replaced)
        }
        return RuleImportPreview(
            total = imported.size,
            added = plan.toAdd.size,
            updated = plan.toUpdate.size,
            removed = plan.toRemove.size,
            unchanged = plan.unchanged,
            ineffective = AndroidRuleCompatibility.countIneffective(imported),
        )
    }

    fun exportDiagnostics(): File = container.diagnostics.export()

    fun diagnosticsShareUri(file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

    fun refreshNotifications() {
        container.vpnEngine.refreshNotification()
        container.promptNotifier.refreshPending()
    }

    fun scheduleTrafficRefresh() {
        container.scope.launch { container.trafficCollector.collectNow() }
    }

    private fun ownRule(rules: List<Rule>, path: String): Rule? =
        rules.firstOrNull { isOwnRule(it, path) }

    private fun isOwnRule(rule: Rule, path: String): Boolean =
        rule.app?.let { !it.hasWildcard && it.pathPattern == path } == true &&
            VpnRoutingPlanner.isEnforceable(rule)
}
