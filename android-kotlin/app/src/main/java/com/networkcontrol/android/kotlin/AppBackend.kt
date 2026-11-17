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
import com.networkcontrol.android.kotlin.core.rules.RuleIdentity
import com.networkcontrol.android.kotlin.data.transfer.RuleTransferService
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog
import com.networkcontrol.android.kotlin.engine.vpn.AndroidRuleCompatibility
import com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class AppBackend(val container: AppContainer) {
    private val context get() = container.appContext
    private val scope get() = container.scope
    private val _profileErrors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val profileErrors = _profileErrors.asSharedFlow()

    val settings = container.settings.state
    val runtime = container.runtime.state
    val profiles = container.profiles.profiles
    val prompts = container.prompts.pending
    val plan = container.vpnEngine.plan
    val protection = container.protection.enabled
    val tunnelState = container.vpnEngine.tunnelState
    val tunnelError = container.vpnEngine.tunnelError
    val quietPeriod = container.quietPeriods.summary
    val speed = container.speedMonitor.speed
    val network = container.network.state
    val focus = container.focus.session
    val focusSchedules = container.focusScheduler.schedules

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

    suspend fun setAppDecision(
        packageName: String,
        decision: AppDecision,
        networkKinds: Set<com.networkcontrol.android.kotlin.core.model.NetworkKind> = emptySet(),
        subscriptionIds: Set<Int> = emptySet(),
        simSlots: Set<Int> = emptySet(),
        replaceAllScopes: Boolean = networkKinds.isEmpty() && subscriptionIds.isEmpty() && simSlots.isEmpty(),
    ) {
        val path = AppCatalog.appPath(packageName)
        val app = if (decision == AppDecision.Unset) null else container.appCatalog.find(packageName) ?: return
        val activeProfileId = container.profiles.active.id
        val existing = container.rules.all
            .filter { it.source == RuleSource.User || it.source == RuleSource.Prompt }
            .filter { isOwnRule(it, path) }
            .filter { it.profileId == null || it.profileId == activeProfileId }
            .filter { rule ->
                replaceAllScopes ||
                    (rule.conditions.networkKinds == networkKinds &&
                        rule.conditions.subscriptionIds == subscriptionIds &&
                        rule.conditions.simSlots == simSlots)
            }
            .map { it.id }
        if (existing.isNotEmpty()) {
            container.rules.remove(existing, RuleEndReason.Replaced)
        }
        if (decision == AppDecision.Unset || app == null) return
        val now = Time.now()
        container.rules.add(
            Rule(
                id = java.util.UUID.randomUUID().toString(),
                profileId = activeProfileId,
                app = AppMatcherFactory.create(container.appCatalog.toIdentity(app)),
                conditions = RuleConditions(
                    direction = Direction.Outbound,
                    networkKinds = networkKinds,
                    subscriptionIds = subscriptionIds,
                    simSlots = simSlots,
                ),
                action = if (decision == AppDecision.Allow) VerdictAction.Allow else VerdictAction.Deny,
                lifetime = RuleLifetime.Permanent,
                source = RuleSource.User,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** 目前配置文件下，這個應用所有的三態規則（含不同網路條件）。 */
    fun appRules(packageName: String): List<Rule> {
        val path = AppCatalog.appPath(packageName)
        val activeProfileId = container.profiles.active.id
        return container.rules.all
            .filter { it.source == RuleSource.User || it.source == RuleSource.Prompt }
            .filter { isOwnRule(it, path) }
            .filter { it.profileId == null || it.profileId == activeProfileId }
    }

    /** 應用詳情用的最近連線記錄。 */
    suspend fun appRecentLogs(packageName: String, limit: Int = 100): List<LogEntry> {
        val identity = container.appStore.find(AppCatalog.appPath(packageName)) ?: return emptyList()
        return container.activityStore.query(LogQuery(appId = identity.appId, limit = limit))
    }

    /** 目前配置文件（含全域）的規則，按應用分組。 */
    fun ruleGroups(): List<com.networkcontrol.android.kotlin.core.model.RuleGroup> {
        val activeProfileId = container.profiles.active.id
        val visible = container.rules.all.filter {
            it.profileId == null || it.profileId == activeProfileId
        }
        return visible.groupBy { it.app?.pathPattern ?: "*" }
            .map { (key, group) ->
                val path = group.firstNotNullOfOrNull { it.app?.pathPattern }
                val packageName = path
                    ?.removePrefix("android-app://")
                    ?.takeIf { path.startsWith("android-app://") && it.isNotBlank() }
                val label = packageName?.let { container.appCatalog.find(it)?.label } ?: packageName
                com.networkcontrol.android.kotlin.core.model.RuleGroup(
                    key = key,
                    label = label ?: "Global",
                    packageName = packageName,
                    rules = group
                        .map { rule ->
                            val stats = container.rules.getHitStats(rule.id)
                            rule.copy(hitCount = stats.first, lastHitAt = stats.second)
                        }
                        .sortedWith(
                            compareByDescending<Rule> { it.manualPriority }
                                .thenByDescending { it.updatedAt },
                        ),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun setRuleEnabled(id: String, enabled: Boolean) {
        runCatching { container.rules.setEnabled(id, enabled) }
    }

    suspend fun deleteRule(id: String) {
        runCatching { container.rules.remove(listOf(id), RuleEndReason.Deleted) }
    }

    /** @param action 只查詢指定判決；null 表示全部（逐連線模式下也會記錄已允許的連線）。 */
    suspend fun getLogs(limit: Int = 300, keyword: String? = null, action: VerdictAction? = VerdictAction.Deny): List<LogEntry> =
        container.activityStore.query(
            LogQuery(action = action, keyword = keyword, limit = limit),
        )

    /** 匯出用：分頁讀取所有記錄，避開單次查詢上限。 */
    suspend fun getAllLogsForExport(pageSize: Int = 1000): List<LogEntry> {
        val all = mutableListOf<LogEntry>()
        var beforeId: Long? = null
        while (true) {
            val page = container.activityStore.query(
                LogQuery(beforeId = beforeId, limit = pageSize),
            )
            if (page.isEmpty()) break
            all += page
            if (page.size < pageSize) break
            beforeId = page.last().id
        }
        return all
    }

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
        val byNetwork = container.trafficStore.byNetwork(from, to)
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
        return TrafficUsage(totalIn, totalOut, byPackage, byNetwork)
    }

    fun activeProfile(): Profile = container.profiles.active

    fun activateProfile(id: String) {
        container.profileAutoSwitcher.suppressForCurrentNetwork()
        container.scope.launch {
            runCatching { container.profiles.activate(id) }
                .onFailure { _profileErrors.tryEmit(it.message ?: "切換配置文件失敗") }
        }
    }

    fun createProfile(
        name: String,
        mode: FilterMode,
        autoActivate: com.networkcontrol.android.kotlin.core.model.AutoActivateCondition? = null,
    ) {
        container.scope.launch {
            runCatching { container.profiles.create(name, mode, autoActivate) }
                .onFailure { _profileErrors.tryEmit(it.message ?: "建立配置文件失敗") }
        }
    }

    /**
     * 建立長期「專注」白名單配置文件。
     * - allowedPackages 非 null：只把這些應用寫成 Allow 規則（從專注會話保存）。
     * - copyFromCurrent：複製目前配置文件的所有規則（模板）。
     */
    fun createFocusProfile(
        nameBase: String = "專注",
        allowedPackages: Set<String>? = null,
        copyFromCurrent: Boolean = allowedPackages == null,
    ) {
        container.scope.launch {
            runCatching {
                var name = nameBase
                var index = 2
                while (container.profiles.all.any { it.name.equals(name, ignoreCase = true) }) {
                    name = "$nameBase $index"
                    index++
                }
                val profile = container.profiles.create(
                    name = name,
                    mode = FilterMode.Whitelist,
                    autoActivate = null,
                    copyRulesFrom = if (copyFromCurrent) container.profiles.active.id else null,
                )
                allowedPackages?.forEach { packageName ->
                    val app = container.appCatalog.find(packageName) ?: return@forEach
                    val now = Time.now()
                    container.rules.add(
                        Rule(
                            id = java.util.UUID.randomUUID().toString(),
                            profileId = profile.id,
                            app = AppMatcherFactory.create(container.appCatalog.toIdentity(app)),
                            conditions = RuleConditions(direction = Direction.Outbound),
                            action = VerdictAction.Allow,
                            lifetime = RuleLifetime.Permanent,
                            source = RuleSource.User,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
                container.profiles.activate(profile.id)
            }.onFailure { _profileErrors.tryEmit(it.message ?: "建立專注配置文件失敗") }
        }
    }

    fun updateProfile(id: String, name: String, mode: FilterMode, autoActivate: com.networkcontrol.android.kotlin.core.model.AutoActivateCondition? = null) {
        container.scope.launch {
            runCatching { container.profiles.update(id, name, mode, autoActivate) }
                .onFailure { _profileErrors.tryEmit(it.message ?: "更新配置文件失敗") }
        }
    }

    fun deleteProfile(id: String) {
        container.scope.launch {
            runCatching { container.profiles.delete(id) }
                .onFailure { _profileErrors.tryEmit(it.message ?: "刪除配置文件失敗") }
        }
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

    fun answerPrompts(
        ids: Collection<String>,
        action: VerdictAction,
        lifetime: RuleLifetime,
        durationSeconds: Int? = null,
    ) {
        if (ids.isEmpty()) return
        container.scope.launch {
            container.prompts.answerMany(
                ids,
                com.networkcontrol.android.kotlin.core.model.PromptDecision(
                    action = action,
                    lifetime = lifetime,
                    durationSeconds = durationSeconds,
                    scope = com.networkcontrol.android.kotlin.core.model.PromptScope.AnyConnection,
                ),
            )
        }
    }

    fun dismissPrompts(ids: Collection<String>) {
        if (ids.isEmpty()) return
        container.scope.launch { container.prompts.dismissMany(ids) }
    }

    fun dismissQuietPeriod() = container.quietPeriods.dismiss()

    fun startFocus(minutes: Int, allowedPackages: Set<String>) {
        container.preferences.focusLastAllowed = allowedPackages
        container.focus.start(minutes, allowedPackages)
        container.vpnEngine.requestReplan()
    }

    fun extendFocus(minutes: Int) {
        container.focus.extend(minutes)
        container.vpnEngine.requestReplan()
    }

    fun stopFocus() {
        container.focus.stop()
        container.vpnEngine.requestReplan()
    }

    fun saveFocusSchedule(schedule: com.networkcontrol.android.kotlin.core.focus.FocusSchedule) {
        container.focusScheduler.replace(schedule)
    }

    fun deleteFocusSchedule(id: String) {
        container.focusScheduler.remove(id)
    }

    fun setFocusScheduleEnabled(id: String, enabled: Boolean) {
        container.focusScheduler.setEnabled(id, enabled)
    }

    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        container.scope.launch { container.settings.update(transform) }
    }

    fun setAllowSystemAppsByDefault(enabled: Boolean) {
        updateSettings { it.copy(allowSystemAppsByDefault = enabled) }
    }

    fun trustPublisher(signerSha256: String) {
        if (signerSha256.isBlank()) return
        updateSettings { it.copy(trustedSigners = it.trustedSigners + signerSha256) }
    }

    fun untrustPublisher(signerSha256: String) {
        updateSettings { it.copy(trustedSigners = it.trustedSigners - signerSha256) }
    }

    fun clearTrustedPublishers() {
        updateSettings { it.copy(trustedSigners = emptySet()) }
    }

    fun hasTrafficAccess(): Boolean = container.trafficCollector.isAvailable

    fun notificationsAvailable(): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun setNotificationsEnabled(enabled: Boolean) {
        container.preferences.notificationsEnabled = enabled
        container.promptRouter.refresh()
    }

    fun protectionNotificationTitle(): String = container.preferences.protectionNotificationTitle

    fun setProtectionNotificationTitle(value: String) {
        container.preferences.protectionNotificationTitle = value
        container.vpnEngine.refreshNotification()
    }

    fun promptPresentationMode(): String = container.preferences.promptPresentationMode

    fun setPromptPresentationMode(mode: String) {
        container.preferences.promptPresentationMode = mode
        container.promptRouter.refresh()
    }

    fun promptOverlayPosition(): String = container.preferences.promptOverlayPosition

    fun setPromptOverlayPosition(position: String) {
        container.preferences.promptOverlayPosition = position
        container.promptRouter.refresh()
    }

    fun promptOverlayCustomRatio(): Float = container.preferences.promptOverlayCustomRatio

    fun setPromptOverlayCustomRatio(ratio: Float) {
        container.preferences.promptOverlayCustomRatio = ratio
        container.promptRouter.refresh()
    }

    fun speedIslandEnabled(): Boolean = container.preferences.speedIslandEnabled

    fun setSpeedIslandEnabled(enabled: Boolean) {
        container.preferences.speedIslandEnabled = enabled
        container.speedIsland.refresh()
    }

    fun refreshSpeedIsland() {
        container.speedIsland.refresh()
    }

    fun isVivoDevice(): Boolean = container.preferences.isVivoDevice()

    fun vivoIslandCompatible(): Boolean = container.preferences.vivoIslandCompatible

    fun setVivoIslandCompatible(enabled: Boolean) {
        container.preferences.vivoIslandCompatible = enabled
        container.speedIsland.refresh()
    }

    fun speedIslandOffsetXDp(): Int = container.preferences.speedIslandOffsetXDp

    fun setSpeedIslandOffsetXDp(value: Int) {
        container.preferences.speedIslandOffsetXDp = value
        container.speedIsland.refresh()
    }

    fun speedIslandOffsetYDp(): Int = container.preferences.speedIslandOffsetYDp

    fun setSpeedIslandOffsetYDp(value: Int) {
        container.preferences.speedIslandOffsetYDp = value
        container.speedIsland.refresh()
    }

    fun speedIslandOpacity(): Float = container.preferences.speedIslandOpacity

    fun setSpeedIslandOpacity(value: Float) {
        container.preferences.speedIslandOpacity = value
        container.speedIsland.refresh()
    }

    fun speedIslandMode(): com.networkcontrol.android.kotlin.core.model.SpeedIslandMode =
        container.preferences.speedIslandMode

    fun setSpeedIslandMode(value: com.networkcontrol.android.kotlin.core.model.SpeedIslandMode) {
        container.preferences.speedIslandMode = value
        container.speedIsland.refresh()
    }

    fun speedIslandContent(): com.networkcontrol.android.kotlin.core.model.SpeedIslandContent =
        container.preferences.speedIslandContent

    fun setSpeedIslandContent(value: com.networkcontrol.android.kotlin.core.model.SpeedIslandContent) {
        container.preferences.speedIslandContent = value
        container.speedIsland.refresh()
    }

    fun speedIslandFontScalePercent(): Int = container.preferences.speedIslandFontScalePercent

    fun setSpeedIslandFontScalePercent(value: Int) {
        container.preferences.speedIslandFontScalePercent = value
        container.speedIsland.refresh()
    }

    fun speedIslandFontWeight(): com.networkcontrol.android.kotlin.core.model.SpeedIslandFontWeight =
        container.preferences.speedIslandFontWeight

    fun setSpeedIslandFontWeight(
        value: com.networkcontrol.android.kotlin.core.model.SpeedIslandFontWeight,
    ) {
        container.preferences.speedIslandFontWeight = value
        container.speedIsland.refresh()
    }

    fun speedIslandStatusBarMode(): Boolean = container.preferences.speedIslandStatusBarMode

    fun setSpeedIslandStatusBarMode(enabled: Boolean) {
        container.preferences.speedIslandStatusBarMode = enabled
        if (enabled && container.preferences.speedIslandTextColorMode == "theme") {
            container.preferences.speedIslandTextColorMode = "statusbar"
        } else if (!enabled && container.preferences.speedIslandTextColorMode == "statusbar") {
            container.preferences.speedIslandTextColorMode = "theme"
        }
        container.speedIsland.refresh()
    }

    fun speedIslandTextColorMode(): String = container.preferences.speedIslandTextColorMode

    fun setSpeedIslandTextColorMode(mode: String) {
        container.preferences.speedIslandTextColorMode = mode
        container.speedIsland.refresh()
    }

    fun speedIslandTextColor(): Int = container.preferences.speedIslandTextColor

    fun setSpeedIslandTextColor(color: Int) {
        container.preferences.speedIslandTextColor = color
        container.speedIsland.refresh()
    }

    fun speedIslandShowDot(): Boolean = container.preferences.speedIslandShowDot

    fun setSpeedIslandShowDot(show: Boolean) {
        container.preferences.speedIslandShowDot = show
        container.speedIsland.refresh()
    }

    fun speedIslandSwapOrder(): Boolean = container.preferences.speedIslandSwapOrder

    fun setSpeedIslandSwapOrder(swap: Boolean) {
        container.preferences.speedIslandSwapOrder = swap
        container.speedIsland.refresh()
    }

    fun speedIslandDotColor(): Int = container.preferences.speedIslandDotColor

    fun setSpeedIslandDotColor(color: Int) {
        container.preferences.speedIslandDotColor = color
        container.speedIsland.refresh()
    }

    fun promptShowTimedOption(): Boolean = container.preferences.promptShowTimedOption

    fun setPromptShowTimedOption(show: Boolean) {
        container.preferences.promptShowTimedOption = show
        container.promptRouter.refresh()
    }

    /** 是否已用 adb 授予 DUMP，能偵測其他應用真正關閉。 */
    fun processExitDetection(): Boolean = container.appSessionTracker.processExits.available

    /** 用 Shizuku 或 root 授予 DUMP；成功後關閉偵測立即生效。 */
    suspend fun grantExitDetection(): com.networkcontrol.android.kotlin.platform.permissions.DumpGrantOutcome =
        com.networkcontrol.android.kotlin.platform.permissions.DumpPermissionGranter.grant(context.packageName)

    fun exitDetectionAdbCommand(): String =
        com.networkcontrol.android.kotlin.platform.permissions.DumpPermissionGranter.adbCommand(context.packageName)

    fun onceBackgroundMinutes(): Int = container.preferences.onceBackgroundMinutes

    fun setOnceBackgroundMinutes(minutes: Int) {
        container.preferences.onceBackgroundMinutes = minutes
    }

    fun promptDenyPermanent(): Boolean = container.preferences.promptDenyPermanent

    fun setPromptDenyPermanent(permanent: Boolean) {
        container.preferences.promptDenyPermanent = permanent
        container.promptRouter.refresh()
    }

    fun promptShowDetails(): Boolean = container.preferences.promptShowDetails

    fun setPromptShowDetails(show: Boolean) {
        container.preferences.promptShowDetails = show
        container.promptRouter.refresh()
    }

    fun promptShowExplanation(): Boolean = container.preferences.promptShowExplanation

    fun setPromptShowExplanation(show: Boolean) {
        container.preferences.promptShowExplanation = show
        container.promptRouter.refresh()
    }

    fun promptHideDetailsOnLockScreen(): Boolean = container.preferences.promptHideDetailsOnLockScreen

    fun setPromptHideDetailsOnLockScreen(hide: Boolean) {
        container.preferences.promptHideDetailsOnLockScreen = hide
        container.promptRouter.refresh()
    }

    fun blockOnLockScreen(): Boolean = container.preferences.blockOnLockScreen

    fun setBlockOnLockScreen(enabled: Boolean) {
        container.preferences.blockOnLockScreen = enabled
        container.vpnEngine.requestReplan()
    }

    fun isScreenLocked(): Boolean = container.screenState.locked.value

    fun privateDnsMode(): String =
        runCatching {
            Settings.Global.getString(context.contentResolver, "private_dns_mode").orEmpty()
        }.getOrDefault("off")

    fun isAppLockEnabled(): Boolean = container.preferences.appLockEnabled

    fun isDeviceSecure(): Boolean =
        (context.getSystemService(android.app.KeyguardManager::class.java))?.isDeviceSecure == true

    fun isWorkProfile(): Boolean =
        (context.getSystemService(android.os.UserManager::class.java))?.isManagedProfile == true

    fun setAppLockEnabled(enabled: Boolean) {
        container.preferences.appLockEnabled = enabled
    }

    fun forwardingEnabled(): Boolean = container.preferences.forwardingEnabled

    fun setForwardingEnabled(enabled: Boolean) {
        container.preferences.forwardingEnabled = enabled
        container.vpnEngine.requestReplan()
    }

    // ---- 攔截列表 ----

    val blocklistState = container.blocklists.state
    val blocklistMatcher = container.blocklists.matcher

    fun setBlocklistsEnabled(enabled: Boolean) {
        container.scope.launch { container.blocklists.setEnabled(enabled) }
    }

    fun setBlocklistSourceEnabled(id: String, enabled: Boolean) {
        container.scope.launch { container.blocklists.setSourceEnabled(id, enabled) }
    }

    fun setBlocklistWifiOnly(value: Boolean) {
        container.scope.launch { container.blocklists.setWifiOnly(value) }
    }

    fun setBlocklistIntervalDays(days: Int) {
        container.scope.launch { container.blocklists.setIntervalDays(days) }
    }

    suspend fun updateBlocklistsNow() = container.blocklists.updateNow()

    suspend fun addBlocklist(name: String, url: String): String? = container.blocklists.addCustom(name, url)

    suspend fun removeBlocklist(id: String) = container.blocklists.remove(id)

    suspend fun importBlocklist(uri: android.net.Uri, name: String): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val input = container.appContext.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("cannot open file")
            input.use { container.blocklists.import(name, it) }
        }

    /** 從記錄一鍵放行被名單誤攔的域名：建立「所有應用、允許、該域名」的規則（屬於目前的配置文件）。 */
    suspend fun allowDomain(hostname: String) {
        val now = com.networkcontrol.android.kotlin.core.Time.now()
        container.rules.add(
            com.networkcontrol.android.kotlin.core.model.Rule(
                id = java.util.UUID.randomUUID().toString(),
                profileId = container.profiles.active.id,
                app = null,
                conditions = com.networkcontrol.android.kotlin.core.model.RuleConditions(
                    direction = com.networkcontrol.android.kotlin.core.model.Direction.Outbound,
                    remote = com.networkcontrol.android.kotlin.core.model.RemoteTarget.Domain(hostname.lowercase()),
                ),
                action = com.networkcontrol.android.kotlin.core.model.VerdictAction.Allow,
                lifetime = com.networkcontrol.android.kotlin.core.model.RuleLifetime.Permanent,
                source = com.networkcontrol.android.kotlin.core.model.RuleSource.User,
                note = "blocklist exception",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    fun setTrackerDatabaseEnabled(enabled: Boolean) {
        container.scope.launch {
            container.blocklists.setSourceEnabled(com.networkcontrol.android.kotlin.engine.blocklist.BlocklistCatalog.TRACKER_SOURCE_ID, enabled)
        }
    }

    fun setTrackerCategoryBlocked(category: String, blocked: Boolean) {
        container.scope.launch { container.blocklists.setTrackerCategoryBlocked(category, blocked) }
    }

    /** 記錄頁顯示用：域名的追蹤器歸屬。 */
    fun trackerOf(hostname: String?): com.networkcontrol.android.kotlin.engine.blocklist.TrackerInfo? =
        container.blocklists.matcher.value.tracker(hostname)

    fun blocklistName(id: String): String? =
        container.blocklists.state.value.sources.firstOrNull { it.id == id }?.name

    fun dnsBypassProtection(): Boolean = container.preferences.dnsBypassProtection

    fun setDnsBypassProtection(enabled: Boolean) {
        container.preferences.dnsBypassProtection = enabled
        container.vpnEngine.requestReplan()
    }

    fun exitDnsProvider(): String = container.preferences.exitDnsProvider

    fun setExitDnsProvider(id: String) {
        container.preferences.exitDnsProvider = id
        container.vpnEngine.exitResolver.clear()
    }

    fun exitDnsCustomUrl(): String = container.preferences.exitDnsCustomUrl.orEmpty()

    fun setExitDnsCustomUrl(url: String) {
        container.preferences.exitDnsCustomUrl = url.trim()
        container.vpnEngine.exitResolver.clear()
    }

    fun exitDnsFallback(): Boolean = container.preferences.exitDnsFallback

    fun setExitDnsFallback(enabled: Boolean) {
        container.preferences.exitDnsFallback = enabled
    }

    fun forwardingUpstream(): String = container.preferences.forwardingUpstream

    fun setForwardingUpstream(upstream: String) {
        container.preferences.forwardingUpstream = upstream
        container.vpnEngine.requestReplan()
    }

    fun forwardingAvailable(): Boolean = com.networkcontrol.android.kotlin.engine.vpn.VpnForwardingTransport.isAvailable()

    fun socks5Host(): String = container.preferences.socks5Host

    fun setSocks5Host(host: String) {
        container.preferences.socks5Host = host.trim()
        container.vpnEngine.requestReplan()
    }

    fun socks5Port(): Int = container.preferences.socks5Port

    fun setSocks5Port(port: Int) {
        container.preferences.socks5Port = port.coerceIn(1, 65535)
        container.vpnEngine.requestReplan()
    }

    fun socks5Username(): String = container.preferences.socks5Username.orEmpty()

    fun socks5Password(): String = container.preferences.socks5Password.orEmpty()

    fun setSocks5Credentials(username: String, password: String) {
        container.preferences.socks5Username = username.trim()
        container.preferences.socks5Password = password
        container.vpnEngine.requestReplan()
    }

    fun socks5Udp(): Boolean = container.preferences.socks5Udp

    fun setSocks5Udp(enabled: Boolean) {
        container.preferences.socks5Udp = enabled
        container.vpnEngine.requestReplan()
    }

    fun forwardingDirectPackages(): Set<String> = container.preferences.forwardingDirectPackages

    fun setForwardingDirectPackages(packages: Set<String>) {
        container.preferences.forwardingDirectPackages = packages
        container.vpnEngine.requestReplan()
    }

    /** 隧道目前是否真的在轉發（啟動失敗回退到黑洞模式時為 false）。 */
    val forwardingActive = container.vpnEngine.forwardingActive

    fun socks5BypassPackage(): String? = container.preferences.socks5BypassPackage

    fun setSocks5BypassPackage(packageName: String?) {
        container.preferences.socks5BypassPackage = packageName
        container.vpnEngine.requestReplan()
    }

    fun updateSource(): com.networkcontrol.android.kotlin.core.update.UpdateSource =
        container.preferences.updateSource

    fun setUpdateSource(source: com.networkcontrol.android.kotlin.core.update.UpdateSource) {
        container.preferences.updateSource = source
    }

    fun updateRepo(): String = container.preferences.updateRepo

    fun setUpdateRepo(repo: String) {
        container.preferences.updateRepo = repo
    }

    fun updateCustomUrl(): String = container.preferences.updateCustomUrl

    fun setUpdateCustomUrl(url: String) {
        container.preferences.updateCustomUrl = url
    }

    fun installedApps(): List<com.networkcontrol.android.kotlin.core.model.InstalledApp> =
        container.appCatalog.getApps()
            .filter { it.packageName != context.packageName }
            .sortedBy { it.label.lowercase() }

    fun canDrawOverlays(): Boolean = container.promptOverlay.canDrawOverlays()

    fun overlayPermissionIntent(): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            openAppSettings(),
        )
        return candidates.firstOrNull { intent ->
            runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        } ?: openAppSettings()
    }

    fun appListVisibility(): com.networkcontrol.android.kotlin.engine.apps.AppCatalog.AppListVisibility =
        container.appCatalog.visibility()

    fun subscriptions(): List<com.networkcontrol.android.kotlin.core.model.SimInfo> =
        container.network.subscriptions()

    fun hasPhoneStatePermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun openAppSettings(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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

    fun setThemeColor(color: androidx.compose.ui.graphics.Color) = container.theme.set(color)

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
        // Replace 模式先刪除未匯入的規則，再新增/更新；避免身份合併後又被刪掉。
        if (plan.toRemove.isNotEmpty()) {
            container.rules.remove(plan.toRemove.map { it.id }, RuleEndReason.Replaced)
        }
        if (plan.toAdd.isNotEmpty()) container.rules.addRange(plan.toAdd)
        plan.toUpdate.forEach { container.rules.update(it) }
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
        container.promptRouter.refresh()
    }

    fun scheduleTrafficRefresh() {
        container.scope.launch { container.trafficCollector.collectNow() }
    }

    private fun ownRule(rules: List<Rule>, path: String): Rule? =
        rules.firstOrNull {
            isOwnRule(it, path) && !it.conditions.hasNetworkScope
        }

    private fun isOwnRule(rule: Rule, path: String): Boolean =
        rule.app?.let { !it.hasWildcard && it.pathPattern == path } == true &&
            VpnRoutingPlanner.isEnforceable(rule)
}
