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
    val updateState = container.updateManager.state

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

    fun statusBarSpeedIcon(): Boolean = container.preferences.statusBarSpeedIcon

    fun setStatusBarSpeedIcon(enabled: Boolean) {
        container.preferences.statusBarSpeedIcon = enabled
        container.vpnEngine.refreshNotification()
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
        if (container.preferences.speedIslandTextColorMode == "statusbar") {
            container.preferences.speedIslandTextColorMode = "theme"
        }
        container.speedIsland.refresh()
    }

    fun speedIslandTextColorMode(): String =
        container.preferences.speedIslandTextColorMode.let { if (it == "statusbar") "theme" else it }

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

    fun lockScreenWhitelist(): Set<String> = container.preferences.lockScreenWhitelist

    fun setLockScreenWhitelist(packages: Set<String>) {
        container.preferences.lockScreenWhitelist = packages
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

    /** 從記錄一鍵放行域名：支援「全域所有應用」或「僅指定應用」（屬於目前的配置文件）。 */
    suspend fun allowDomain(hostname: String, packageName: String? = null) {
        val now = com.networkcontrol.android.kotlin.core.Time.now()
        val appMatcher = packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
            com.networkcontrol.android.kotlin.core.model.AppMatcher(
                pathPattern = AppCatalog.appPath(pkg),
                requiredSignerId = container.appCatalog.find(pkg)?.signerSha256,
            )
        }
        container.rules.add(
            com.networkcontrol.android.kotlin.core.model.Rule(
                id = java.util.UUID.randomUUID().toString(),
                profileId = container.profiles.active.id,
                app = appMatcher,
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

    /** 從記錄一鍵攔截指定域名：支援「全域所有應用」或「僅指定應用」（含系統核心 UID 的逐連線精準攔截）。 */
    suspend fun denyDomain(hostname: String, packageName: String? = null) {
        val now = com.networkcontrol.android.kotlin.core.Time.now()
        val appMatcher = packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
            com.networkcontrol.android.kotlin.core.model.AppMatcher(
                pathPattern = AppCatalog.appPath(pkg),
                requiredSignerId = container.appCatalog.find(pkg)?.signerSha256,
            )
        }
        container.rules.add(
            com.networkcontrol.android.kotlin.core.model.Rule(
                id = java.util.UUID.randomUUID().toString(),
                profileId = container.profiles.active.id,
                app = appMatcher,
                conditions = com.networkcontrol.android.kotlin.core.model.RuleConditions(
                    direction = com.networkcontrol.android.kotlin.core.model.Direction.Outbound,
                    remote = com.networkcontrol.android.kotlin.core.model.RemoteTarget.Domain(hostname.lowercase()),
                ),
                action = com.networkcontrol.android.kotlin.core.model.VerdictAction.Deny,
                lifetime = com.networkcontrol.android.kotlin.core.model.RuleLifetime.Permanent,
                source = com.networkcontrol.android.kotlin.core.model.RuleSource.User,
                note = "domain deny from logs",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** 手動新建或編輯規則（支援全域/指定應用、域名、遠端連接埠、網路類型與備註）。 */
    suspend fun saveCustomRule(
        existing: Rule? = null,
        packageName: String? = null,
        action: VerdictAction,
        domainPattern: String? = null,
        remotePort: com.networkcontrol.android.kotlin.core.model.PortRange? = null,
        networkKinds: Set<com.networkcontrol.android.kotlin.core.model.NetworkKind> = emptySet(),
        note: String? = null,
    ) {
        val now = com.networkcontrol.android.kotlin.core.Time.now()
        val appMatcher = if (existing != null) {
            existing.app
        } else {
            packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
                com.networkcontrol.android.kotlin.core.model.AppMatcher(
                    pathPattern = AppCatalog.appPath(pkg),
                    requiredSignerId = container.appCatalog.find(pkg)?.signerSha256,
                )
            }
        }
        val cleanedDomain = domainPattern?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val remoteTarget = cleanedDomain?.let {
            com.networkcontrol.android.kotlin.core.model.RemoteTarget.Domain(it)
        } ?: existing?.conditions?.remote?.takeIf { domainPattern == null }
        val conditions = RuleConditions(
            direction = existing?.conditions?.direction ?: com.networkcontrol.android.kotlin.core.model.Direction.Outbound,
            protocol = existing?.conditions?.protocol,
            remote = remoteTarget,
            remotePort = remotePort,
            localPort = existing?.conditions?.localPort,
            networkKinds = networkKinds,
            subscriptionIds = existing?.conditions?.subscriptionIds ?: emptySet(),
            simSlots = existing?.conditions?.simSlots ?: emptySet(),
        )
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        if (existing != null) {
            container.rules.update(
                existing.copy(
                    action = action,
                    conditions = conditions,
                    note = cleanNote,
                    updatedAt = now,
                ),
            )
        } else {
            container.rules.add(
                Rule(
                    id = java.util.UUID.randomUUID().toString(),
                    profileId = container.profiles.active.id,
                    app = appMatcher,
                    conditions = conditions,
                    action = action,
                    lifetime = RuleLifetime.Permanent,
                    source = RuleSource.User,
                    note = cleanNote,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    /** 批量設定多個應用的聯網決定（允許 / 禁止 / 未設定）。 */
    suspend fun setAppsDecisionBatch(packageNames: Set<String>, decision: AppDecision) {
        packageNames.forEach { pkg ->
            setAppDecision(pkg, decision, replaceAllScopes = false)
        }
    }

    /** 清空全部連線記錄（保留流量統計）。 */
    suspend fun clearConnectionLogs() {
        container.activityStore.applyRetention(Long.MAX_VALUE)
    }

    fun allowLan(): Boolean = container.preferences.allowLan

    fun setAllowLan(enabled: Boolean) {
        container.preferences.allowLan = enabled
        container.vpnEngine.requestReplan()
    }

    fun blockOnRoaming(): Boolean = container.preferences.blockOnRoaming

    fun setBlockOnRoaming(enabled: Boolean) {
        container.preferences.blockOnRoaming = enabled
        container.vpnEngine.requestReplan()
    }

    fun blockQuic(): Boolean = container.preferences.blockQuic

    fun setBlockQuic(enabled: Boolean) {
        container.preferences.blockQuic = enabled
    }

    fun isAlwaysOnVpn(): Boolean = com.networkcontrol.android.kotlin.engine.vpn.NcVpnService.alwaysOnActive

    fun isLockdownVpn(): Boolean = com.networkcontrol.android.kotlin.engine.vpn.NcVpnService.lockdownActive

    fun setLogRetentionDays(days: Int) {
        container.scope.launch {
            container.settings.update { it.copy(logRetentionDays = days.coerceIn(1, 365)) }
        }
    }

    fun activityDbSizeBytes(): Long {
        val db = context.getDatabasePath(com.networkcontrol.android.kotlin.data.db.ActivityDatabase.NAME)
        val wal = java.io.File("${db.path}-wal")
        return (if (db.isFile) db.length() else 0L) + (if (wal.isFile) wal.length() else 0L)
    }

    suspend fun compactActivityDb(): Long {
        val days = container.settings.state.value.logRetentionDays
        container.activityStore.applyRetention(System.currentTimeMillis() - days * 86_400_000L)
        container.activityStore.compact()
        return activityDbSizeBytes()
    }

    fun appLabelOf(packageName: String): String =
        container.appCatalog.find(packageName)?.label ?: packageName

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

    /** 一次原子儲存 SOCKS5 代理設定並只觸發一次隧道重規劃。 */
    fun setSocks5Config(host: String, port: Int, username: String, password: String) {
        container.preferences.socks5Host = host.trim()
        container.preferences.socks5Port = port.coerceIn(1, 65535)
        container.preferences.socks5Username = username.trim()
        container.preferences.socks5Password = password
        container.vpnEngine.requestReplan()
    }

    fun socks5Udp(): Boolean = container.preferences.socks5Udp

    fun setSocks5Udp(enabled: Boolean) {
        container.preferences.socks5Udp = enabled
        // UDP 開關完全由過濾層動態控制，清空決策緩存即可無感生效，不必重建 VPN 與 hev。
        container.vpnEngine.forwardingFilter.invalidate()
    }

    fun isUsingExternalProxy(): Boolean =
        container.preferences.forwardingEnabled && !container.preferences.usesDirectUpstream

    /** 即時活躍連線快照（逐連線模式專用）。 */
    data class LiveConnection(
        val uid: Int?,
        val packageName: String?,
        val appLabel: String,
        val hostname: String?,
        val local: java.net.InetSocketAddress,
        val remote: java.net.InetSocketAddress,
        val ageMillis: Long,
    )

    fun liveConnections(): List<LiveConnection> {
        val now = System.nanoTime() / 1_000_000L
        return container.vpnEngine.forwardingFilter.activeConnections().map { item ->
            val pkg = item.uid?.let { container.appCatalog.packagesForUid(it).firstOrNull() }
            val label = pkg?.let { container.appCatalog.find(it)?.label } ?: pkg ?: "UID ${item.uid ?: "?"}"
            LiveConnection(
                uid = item.uid,
                packageName = pkg,
                appLabel = label,
                hostname = item.hostname,
                local = item.local,
                remote = item.remote,
                ageMillis = (now - item.createdAt).coerceAtLeast(0L),
            )
        }
    }

    fun cutLiveConnection(local: java.net.InetSocketAddress, remote: java.net.InetSocketAddress): Boolean =
        container.vpnEngine.forwardingFilter.cutConnection(local, remote)

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    fun isVpnPrepared(): Boolean = container.vpnTunnel.isPrepared

    fun offlineMode(): Boolean = container.preferences.offlineMode

    fun setOfflineMode(enabled: Boolean) {
        container.preferences.offlineMode = enabled
    }

    private val _excludedPackages = kotlinx.coroutines.flow.MutableStateFlow(container.preferences.forwardingDirectPackages)
    /** 排除的應用：不經過本應用，所有防火牆規則與記錄均暫停生效。 */
    val excludedPackages: kotlinx.coroutines.flow.StateFlow<Set<String>> = _excludedPackages

    private val _proxyBypassPackage = kotlinx.coroutines.flow.MutableStateFlow(container.preferences.socks5BypassPackage)
    /** 外部 SOCKS5 代理應用（用於避免迴路）。 */
    val proxyBypassPackage: kotlinx.coroutines.flow.StateFlow<String?> = _proxyBypassPackage

    fun forwardingDirectPackages(): Set<String> = container.preferences.forwardingDirectPackages

    fun setForwardingDirectPackages(packages: Set<String>) {
        val next = packages.toSet()
        container.preferences.forwardingDirectPackages = next
        _excludedPackages.value = next
        container.vpnEngine.requestReplan()
    }

    /** 將單一應用加入或移出「排除的應用」。 */
    fun setAppExcluded(packageName: String, excluded: Boolean) {
        val current = container.preferences.forwardingDirectPackages
        val next = if (excluded) current + packageName else current - packageName
        if (next != current) setForwardingDirectPackages(next)
    }

    /** 隧道目前是否真的在轉發（啟動失敗回退到黑洞模式時為 false）。 */
    val forwardingActive = container.vpnEngine.forwardingActive

    fun socks5BypassPackage(): String? = container.preferences.socks5BypassPackage

    fun setSocks5BypassPackage(packageName: String?) {
        container.preferences.socks5BypassPackage = packageName
        _proxyBypassPackage.value = packageName
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

    /** 目前已啟用規則中允許聯網的應用包名集合。 */
    fun allowedByRulePackages(
        apps: List<com.networkcontrol.android.kotlin.core.model.InstalledApp> = installedApps(),
    ): Set<String> = VpnRoutingPlanner.allowedByRulePackages(
        rules = container.rules.ruleSet.value,
        apps = apps,
        network = container.network.current,
    )

    /** 目前已啟用規則中設有禁止聯網規則的應用包名集合。 */
    fun deniedByRulePackages(
        apps: List<com.networkcontrol.android.kotlin.core.model.InstalledApp> = installedApps(),
    ): Set<String> = VpnRoutingPlanner.deniedByRulePackages(
        rules = container.rules.ruleSet.value,
        apps = apps,
    )

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

    fun permissionGuideShown(): Boolean = container.preferences.permissionGuideShown

    fun setPermissionGuideShown(shown: Boolean) {
        container.preferences.permissionGuideShown = shown
    }

    fun permissionBannerDismissed(): Boolean = container.preferences.permissionBannerDismissed

    fun setPermissionBannerDismissed(dismissed: Boolean) {
        container.preferences.permissionBannerDismissed = dismissed
    }

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

    fun themeStyle(): com.networkcontrol.android.kotlin.core.model.AppThemeStyle =
        container.theme.themeStyle.value

    fun setThemeStyle(style: com.networkcontrol.android.kotlin.core.model.AppThemeStyle) =
        container.theme.setThemeStyle(style)

    fun toolbarStyle(): com.networkcontrol.android.kotlin.core.model.ToolbarStyle =
        container.theme.toolbarStyle.value

    fun setToolbarStyle(style: com.networkcontrol.android.kotlin.core.model.ToolbarStyle) =
        container.theme.setToolbarStyle(style)

    fun appIconStyle(): com.networkcontrol.android.kotlin.platform.AppLauncherIcon {
        val saved = com.networkcontrol.android.kotlin.platform.AppLauncherIcon.fromKey(container.preferences.appIconStyle)
        return com.networkcontrol.android.kotlin.platform.AppIconSwitcher.current(context, fallback = saved)
    }

    fun setAppIconStyle(icon: com.networkcontrol.android.kotlin.platform.AppLauncherIcon) {
        container.preferences.appIconStyle = icon.key
        com.networkcontrol.android.kotlin.platform.AppIconSwitcher.apply(context, icon)
    }

    fun autoHideBottomBar(): Boolean = container.theme.autoHideBottomBar.value

    fun setAutoHideBottomBar(enabled: Boolean) = container.theme.setAutoHideBottomBar(enabled)

    fun checkUpdateOnEnter() = container.updateManager.checkOnEnter()

    fun updateChannel(): com.networkcontrol.android.kotlin.core.update.UpdateChannel =
        container.preferences.updateChannel

    fun setUpdateChannel(channel: com.networkcontrol.android.kotlin.core.update.UpdateChannel) {
        container.preferences.updateChannel = channel
    }

    fun checkUpdateManually(
        onResult: ((com.networkcontrol.android.kotlin.core.update.UpdateInfo) -> Unit)? = null,
    ) = container.updateManager.checkManually(onResult)

    fun startUpdateDownload(
        info: com.networkcontrol.android.kotlin.core.update.UpdateInfo? = null,
        onError: ((String) -> Unit)? = null,
    ) = container.updateManager.startDownload(info, onError)

    fun dismissUpdateAvailablePrompt() = container.updateManager.dismissAvailablePrompt()

    fun dismissUpdateReadyPrompt() = container.updateManager.dismissReadyPrompt()

    fun exportRules(): String = RuleTransferService.export(container.rules.all)

    /** 匯出完整快照（規則 + 配置檔案 + 設定 + 可移植偏好設定，不含敏感密碼）。 */
    fun exportFullBackup(): String {
        val prefs = container.preferences
        val autoUrls = runCatching {
            container.automationStore.listSubscriptions().mapNotNull { it.updateUrl?.takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
        return com.networkcontrol.android.kotlin.data.backup.FullBackupCodec.export(
            rules = container.rules.all,
            profiles = container.profiles.all,
            activeProfileId = container.profiles.active.id,
            userSettings = container.settings.state.value,
            portablePrefs = com.networkcontrol.android.kotlin.data.backup.PortablePrefsSnapshot(
                forwardingEnabled = prefs.forwardingEnabled,
                forwardingUpstream = prefs.forwardingUpstream,
                socks5Host = prefs.socks5Host,
                socks5Port = prefs.socks5Port,
                socks5Udp = prefs.socks5Udp,
                socks5BypassPackage = prefs.socks5BypassPackage,
                forwardingDirectPackages = prefs.forwardingDirectPackages,
                dnsBypassProtection = prefs.dnsBypassProtection,
                exitDnsProvider = prefs.exitDnsProvider,
                exitDnsCustomUrl = prefs.exitDnsCustomUrl,
                exitDnsFallback = prefs.exitDnsFallback,
                blockOnLockScreen = prefs.blockOnLockScreen,
                lockScreenWhitelist = prefs.lockScreenWhitelist,
                cloneAppsInheritPrimary = prefs.cloneAppsInheritPrimary,
                focusSchedulesJson = prefs.focusSchedulesJson,
                autoHideBottomBar = prefs.autoHideBottomBar,
                offlineMode = prefs.offlineMode,
            ),
            automationSubscriptionUrls = autoUrls,
        )
    }

    fun cloneAppsInheritPrimary(): Boolean = container.preferences.cloneAppsInheritPrimary

    fun setCloneAppsInheritPrimary(inherit: Boolean) {
        container.preferences.cloneAppsInheritPrimary = inherit
        container.vpnEngine.requestReplan()
    }

    fun webdavUrl(): String = container.preferences.webdavUrl
    fun webdavUsername(): String = container.preferences.webdavUsername
    fun webdavPassword(): String = container.preferences.webdavPassword
    fun webdavPassphrase(): String = container.preferences.webdavPassphrase
    fun webdavLastSyncAt(): Long = container.preferences.webdavLastSyncAt

    fun saveWebdavConfig(url: String, username: String, password: String, passphrase: String) {
        container.preferences.webdavUrl = url
        container.preferences.webdavUsername = username
        container.preferences.webdavPassword = password
        container.preferences.webdavPassphrase = passphrase
    }

    suspend fun uploadWebdavBackup(forceOverwrite: Boolean = false): Result<String> {
        if (offlineMode()) return Result.failure(IllegalStateException("offline_mode"))
        val prefs = container.preferences
        val result = com.networkcontrol.android.kotlin.data.backup.WebDavBackupClient.uploadWithMeta(
            baseUrl = prefs.webdavUrl,
            username = prefs.webdavUsername,
            password = prefs.webdavPassword,
            passphrase = prefs.webdavPassphrase,
            jsonContent = exportFullBackup(),
            expectedEtag = if (forceOverwrite) null else prefs.webdavLastEtag,
        )
        result.onSuccess { meta ->
            prefs.webdavLastSyncAt = System.currentTimeMillis()
            if (!meta.etag.isNullOrBlank()) prefs.webdavLastEtag = meta.etag
        }
        return result.map { it.targetUrl }
    }

    suspend fun downloadWebdavBackup(): Result<String> {
        if (offlineMode()) return Result.failure(IllegalStateException("offline_mode"))
        val prefs = container.preferences
        val result = com.networkcontrol.android.kotlin.data.backup.WebDavBackupClient.downloadWithMeta(
            baseUrl = prefs.webdavUrl,
            username = prefs.webdavUsername,
            password = prefs.webdavPassword,
            passphrase = prefs.webdavPassphrase,
        )
        result.onSuccess { meta ->
            if (!meta.etag.isNullOrBlank()) prefs.webdavLastEtag = meta.etag
        }
        return result.map { it.content }
    }

    fun saveLocalBackupToDownloads(): Result<String> =
        com.networkcontrol.android.kotlin.data.backup.LocalRulesBackup.saveToDownloads(context, exportFullBackup())

    fun readLocalBackupFromDownloads(): Result<String> =
        com.networkcontrol.android.kotlin.data.backup.LocalRulesBackup.readLatestFromDownloads(context)

    fun previewImport(json: String, mode: RuleImportMode): RuleImportPreview {
        val snapshot = com.networkcontrol.android.kotlin.data.backup.FullBackupCodec.parse(json)
        val mergedProfiles = (container.profiles.all + snapshot.profiles).distinctBy { it.id }
        val imported = RuleTransferService.importForPlan(snapshot.rulesJson, mergedProfiles)
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
        val snapshot = com.networkcontrol.android.kotlin.data.backup.FullBackupCodec.parse(json)
        if (snapshot.isFullSnapshot) {
            if (snapshot.profiles.isNotEmpty()) {
                container.profiles.upsertImported(snapshot.profiles)
            }
            snapshot.userSettings?.let { importedSettings ->
                container.settings.update { importedSettings }
            }
            snapshot.portablePrefs?.let { p ->
                val prefs = container.preferences
                p.forwardingEnabled?.let { prefs.forwardingEnabled = it }
                p.forwardingUpstream?.let { prefs.forwardingUpstream = it }
                p.socks5Host?.let { prefs.socks5Host = it }
                p.socks5Port?.let { prefs.socks5Port = it }
                p.socks5Udp?.let { prefs.socks5Udp = it }
                p.socks5BypassPackage?.let { prefs.socks5BypassPackage = it }
                p.forwardingDirectPackages?.let { prefs.forwardingDirectPackages = it }
                p.dnsBypassProtection?.let { prefs.dnsBypassProtection = it }
                p.exitDnsProvider?.let { prefs.exitDnsProvider = it }
                p.exitDnsCustomUrl?.let { prefs.exitDnsCustomUrl = it }
                p.exitDnsFallback?.let { prefs.exitDnsFallback = it }
                p.blockOnLockScreen?.let { prefs.blockOnLockScreen = it }
                p.lockScreenWhitelist?.let { prefs.lockScreenWhitelist = it }
                p.cloneAppsInheritPrimary?.let { prefs.cloneAppsInheritPrimary = it }
                p.focusSchedulesJson?.let { prefs.focusSchedulesJson = it }
                p.autoHideBottomBar?.let {
                    prefs.autoHideBottomBar = it
                    container.theme.setAutoHideBottomBar(it)
                }
                p.offlineMode?.let { prefs.offlineMode = it }
            }
        }
        val imported = RuleTransferService.importForPlan(snapshot.rulesJson, container.profiles.all)
        val plan = RuleTransferService.plan(container.rules.all, imported, mode)
        // Replace 模式先刪除未匯入的規則，再新增/更新；避免身份合併後又被刪掉。
        if (plan.toRemove.isNotEmpty()) {
            container.rules.remove(plan.toRemove.map { it.id }, RuleEndReason.Replaced)
        }
        if (plan.toAdd.isNotEmpty()) container.rules.addRange(plan.toAdd)
        plan.toUpdate.forEach { container.rules.update(it) }
        if (snapshot.isFullSnapshot) {
            container.vpnEngine.requestReplan()
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
        container.promptRouter.refresh()
    }

    fun scheduleTrafficRefresh() {
        container.scope.launch { container.trafficCollector.collectNow() }
    }

    // --- 實驗功能：螢幕自動跳過（GKD 相容訂閱） ---

    fun automationFeatureEnabled(): Boolean = container.automationStore.featureEnabled

    fun setAutomationFeatureEnabled(enabled: Boolean) {
        container.automationStore.featureEnabled = enabled
        com.networkcontrol.android.automation.service.GkdAccessibilityService.notifyReload(context)
    }

    fun automationServiceActive(): Boolean =
        com.networkcontrol.android.automation.platform.AccessibilityPrivilegeHelper.isServiceEnabled(context)

    fun automationShizukuRunning(): Boolean =
        com.networkcontrol.android.automation.platform.AccessibilityPrivilegeHelper.isShizukuRunning()

    fun openAccessibilitySettingsIntent(): android.content.Intent =
        com.networkcontrol.android.automation.platform.AccessibilityPrivilegeHelper.openAccessibilitySettingsIntent()

    suspend fun setAutomationAccessibilityViaShizuku(
        enable: Boolean,
    ): com.networkcontrol.android.automation.platform.AccessibilityGrantOutcome {
        val res = com.networkcontrol.android.automation.platform.AccessibilityPrivilegeHelper
            .setServiceEnabledViaPrivilege(context, enable)
        if (res is com.networkcontrol.android.automation.platform.AccessibilityGrantOutcome.Enabled) {
            com.networkcontrol.android.automation.service.GkdAccessibilityService.notifyReload(context)
        }
        return res
    }

    fun listAutomationSubscriptions(): List<com.networkcontrol.android.automation.model.SubscriptionRecord> {
        container.automationStore.ensureDefaultSubscriptionSeeded()
        return container.automationStore.listSubscriptions()
    }

    fun restoreDefaultAutomationSubscription(): Result<com.networkcontrol.android.automation.model.SubscriptionRecord> {
        val result = container.automationStore.restoreDefaultSubscription()
        if (result.isSuccess) {
            com.networkcontrol.android.automation.service.GkdAccessibilityService.notifyReload(context)
        }
        return result
    }

    suspend fun importAutomationSubscription(
        url: String,
        existing: com.networkcontrol.android.automation.model.SubscriptionRecord? = null,
    ): com.networkcontrol.android.automation.network.SubscriptionFetcher.FetchResult {
        if (container.preferences.offlineMode) {
            return com.networkcontrol.android.automation.network.SubscriptionFetcher.FetchResult.Error(
                "Offline mode is enabled",
            )
        }
        val result = container.automationFetcher.fetch(url, existing)
        if (result is com.networkcontrol.android.automation.network.SubscriptionFetcher.FetchResult.Success) {
            container.automationStore.saveSubscription(
                parsed = result.parsed,
                rawJson5 = result.rawText,
                updateUrl = url.trim(),
            )
            com.networkcontrol.android.automation.service.GkdAccessibilityService.notifyReload(context)
        }
        return result
    }

    fun setAutomationSubscriptionEnabled(id: Long, enabled: Boolean) {
        container.automationStore.setSubscriptionEnabled(id, enabled)
        com.networkcontrol.android.automation.service.GkdAccessibilityService.notifyReload(context)
    }

    fun deleteAutomationSubscription(id: Long) {
        container.automationStore.deleteSubscription(id)
        com.networkcontrol.android.automation.service.GkdAccessibilityService.notifyReload(context)
    }

    fun loadAutomationSubscriptionDetail(id: Long): com.networkcontrol.android.automation.model.RawSubscription? =
        container.automationStore.loadParsedSubscription(id)

    fun listAutomationGroupOverrides(subscriptionId: Long): List<com.networkcontrol.android.automation.model.GroupOverride> =
        container.automationStore.listGroupOverrides(subscriptionId)

    fun setAutomationGroupOverride(subscriptionId: Long, appId: String, groupKey: Int, enabled: Boolean) {
        container.automationStore.setGroupOverride(subscriptionId, appId, groupKey, enabled)
        com.networkcontrol.android.automation.service.GkdAccessibilityService.notifyReload(context)
    }

    fun listAutomationTriggerLogs(limit: Int = 80): List<com.networkcontrol.android.automation.model.TriggerLogEntry> =
        container.automationStore.listTriggerLogs(limit)

    fun clearAutomationTriggerLogs() {
        container.automationStore.clearTriggerLogs()
    }

    fun automationTodayTriggers(): Int = container.automationStore.todayTriggerCount()

    private fun ownRule(rules: List<Rule>, path: String): Rule? =
        rules.firstOrNull {
            isOwnRule(it, path) && !it.conditions.hasNetworkScope
        }

    private fun isOwnRule(rule: Rule, path: String): Boolean =
        rule.app?.let { !it.hasWildcard && it.pathPattern == path } == true &&
            VpnRoutingPlanner.isEnforceable(rule)
}
