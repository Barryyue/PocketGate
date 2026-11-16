package com.networkcontrol.android.kotlin

import android.content.Context
import com.networkcontrol.android.kotlin.core.pipeline.VerdictPipeline
import com.networkcontrol.android.kotlin.core.profiles.ProfileService
import com.networkcontrol.android.kotlin.core.prompts.PromptBroker
import com.networkcontrol.android.kotlin.core.rules.RuleLifetimeManager
import com.networkcontrol.android.kotlin.core.rules.RuleService
import com.networkcontrol.android.kotlin.core.settings.RuntimeStateService
import com.networkcontrol.android.kotlin.core.settings.SettingsService
import com.networkcontrol.android.kotlin.data.db.ActivityDatabase
import com.networkcontrol.android.kotlin.data.db.ConfigDatabase
import com.networkcontrol.android.kotlin.data.repository.SqliteActivityStore
import com.networkcontrol.android.kotlin.data.repository.SqliteAppStore
import com.networkcontrol.android.kotlin.data.repository.SqliteProfileStore
import com.networkcontrol.android.kotlin.data.repository.SqliteRuleStore
import com.networkcontrol.android.kotlin.data.repository.SqliteSettingsStore
import com.networkcontrol.android.kotlin.data.repository.SqliteTrafficStore
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog
import com.networkcontrol.android.kotlin.engine.apps.ConnectionOwnerResolver
import com.networkcontrol.android.kotlin.engine.network.NetworkMonitor
import com.networkcontrol.android.kotlin.engine.protection.ProtectionController
import com.networkcontrol.android.kotlin.engine.traffic.TrafficCollector
import com.networkcontrol.android.kotlin.engine.vpn.VpnEngine
import com.networkcontrol.android.kotlin.engine.vpn.VpnForegroundNotifier
import com.networkcontrol.android.kotlin.engine.vpn.VpnTunnelController
import com.networkcontrol.android.kotlin.data.backup.ConfigBackup
import com.networkcontrol.android.kotlin.platform.DiagnosticsExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val preferences = AppPreferences(appContext)
    val theme = com.networkcontrol.android.kotlin.ui.theme.ThemeController(preferences)
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        com.networkcontrol.android.kotlin.platform.AppLog.e("scope", "uncaught coroutine exception", throwable)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    val configDatabase = ConfigDatabase(appContext)
    val activityDatabase = ActivityDatabase(appContext)

    val settingsStore = SqliteSettingsStore(configDatabase)
    val ruleStore = SqliteRuleStore(configDatabase)
    val profileStore = SqliteProfileStore(configDatabase)
    val appStore = SqliteAppStore(configDatabase)
    val activityStore = SqliteActivityStore(activityDatabase)
    val trafficStore = SqliteTrafficStore(activityDatabase)

    val settings = SettingsService(settingsStore)
    val runtime = RuntimeStateService(settingsStore)
    val rules = RuleService(ruleStore, runtime, scope)
    val appSessionTracker = com.networkcontrol.android.kotlin.engine.apps.TargetAppSessionTracker(appContext, rules, scope)
    val profiles = ProfileService(profileStore, runtime, rules)
    val prompts = PromptBroker(rules, runtime, settings, scope)
    val appCatalog = AppCatalog(appContext)
    val owners = ConnectionOwnerResolver(appContext)
    val network = NetworkMonitor(appContext)
    val screenState = com.networkcontrol.android.kotlin.engine.network.ScreenStateMonitor(appContext)
    val profileAutoSwitcher = com.networkcontrol.android.kotlin.core.profiles.ProfileAutoSwitcher(
        profiles = profiles,
        runtime = runtime,
        networkState = network.state,
        currentNetwork = { network.current },
        scope = scope,
    )
    val protection = ProtectionController(preferences)
    val focus = com.networkcontrol.android.kotlin.core.focus.FocusController(preferences, scope)
    val focusScheduler = com.networkcontrol.android.kotlin.core.focus.FocusScheduler(preferences, focus, scope)
    val vpnNotifier = VpnForegroundNotifier(appContext)
    val vpnTunnel = VpnTunnelController(appContext, vpnNotifier)
    val quietPeriods = com.networkcontrol.android.kotlin.core.prompts.QuietPeriodTracker(runtime, scope)
    val quietNotifier = com.networkcontrol.android.kotlin.platform.QuietPeriodNotifier(appContext, quietPeriods, scope)

    val pipeline = VerdictPipeline(
        rules = rules,
        runtime = runtime,
        settings = settings,
        prompts = prompts,
        quiet = quietPeriods,
        activity = activityStore,
        scope = scope,
        selfPackage = appContext.packageName,
        currentNetwork = { network.current },
        effectiveMode = {
            if (vpnEngine.plan.value.mode == com.networkcontrol.android.kotlin.core.model.FilterMode.BlockAll) {
                com.networkcontrol.android.kotlin.core.model.FilterMode.BlockAll
            } else {
                runtime.mode
            }
        },
        isSyntheticAddress = { address -> vpnEngine.forwardingFilter.hostnameFor(address) != null },
    )

    val vpnEngine: VpnEngine = VpnEngine(
        context = appContext,
        scope = scope,
        rules = rules,
        runtime = runtime,
        settings = settings,
        catalog = appCatalog,
        apps = appStore,
        owners = owners,
        pipeline = pipeline,
        protection = protection,
        tunnel = vpnTunnel,
        network = network,
        screenState = screenState,
        blockOnLockScreen = { preferences.blockOnLockScreen },
        focus = focus,
        preferences = preferences,
        notifier = vpnNotifier,
        selfPackage = appContext.packageName,
    )

    val trafficCollector = TrafficCollector(appContext, appCatalog, appStore, trafficStore)
    val speedMonitor = com.networkcontrol.android.kotlin.engine.traffic.SpeedMonitor()
    val speedIsland = com.networkcontrol.android.kotlin.platform.overlay.SpeedIslandController(
        context = appContext,
        preferences = preferences,
        protection = protection,
        speedMonitor = speedMonitor,
        screenState = screenState,
        themeColor = theme.color,
        trafficStore = trafficStore,
        activityStore = activityStore,
        prompts = prompts,
        network = network,
        scope = scope,
    )
    val ruleLifetime = RuleLifetimeManager(rules, scope)
    val promptNotifier = com.networkcontrol.android.kotlin.platform.PromptNotifier(appContext, preferences)
    val appUiVisibility = com.networkcontrol.android.kotlin.platform.prompt.AppUiVisibility(appContext)
    val foregroundTracker = com.networkcontrol.android.kotlin.platform.prompt.PromptForegroundTracker(appContext)
    val promptOverlay = com.networkcontrol.android.kotlin.platform.prompt.PromptOverlayController(
        context = appContext,
        preferences = preferences,
        timedMinutesProvider = { settings.state.value.promptTimedMinutes },
    )
    val promptRouter = com.networkcontrol.android.kotlin.platform.prompt.PromptRouter(
        scope = scope,
        broker = prompts,
        notifier = promptNotifier,
        overlay = promptOverlay,
        uiVisibility = appUiVisibility,
        tracker = foregroundTracker,
        protection = protection,
        preferences = preferences,
        timedMinutes = { settings.state.value.promptTimedMinutes },
    )
    val backend = AppBackend(this)
    val configBackup = ConfigBackup(appContext, configDatabase)
    val diagnostics = DiagnosticsExporter(appContext, this)

    private val started = CompletableDeferred<Unit>()

    init {
        scope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            runCatching { settings.initialize() }
                .onFailure { com.networkcontrol.android.kotlin.platform.AppLog.e("core", "settings init failed", it) }
            runCatching { runtime.initialize() }
                .onFailure { com.networkcontrol.android.kotlin.platform.AppLog.e("core", "runtime init failed", it) }
            runCatching { profiles.initialize() }
                .onFailure { com.networkcontrol.android.kotlin.platform.AppLog.e("core", "profiles init failed", it) }
            runCatching { rules.initialize() }
                .onFailure { com.networkcontrol.android.kotlin.platform.AppLog.e("core", "rules init failed", it) }
            runCatching { migrateLegacySharedAppRules() }
                .onFailure { com.networkcontrol.android.kotlin.platform.AppLog.e("core", "rule migration failed", it) }
            runCatching { configBackup.backupIfNeeded() }
                .onFailure { com.networkcontrol.android.kotlin.platform.AppLog.e("core", "backup failed", it) }
            runCatching { ruleLifetime.start() }
            runCatching { network.start() }
            runCatching { screenState.start() }
            runCatching { vpnEngine.start() }
                .onFailure {
                    protection.setEnabled(false)
                    com.networkcontrol.android.kotlin.platform.AppLog.e("core", "VPN engine start failed; protection disabled", it)
                }
            runCatching { promptRouter.start() }
            runCatching { quietNotifier.start() }
            runCatching { speedMonitor.start(scope, screenState.interactive) }
            runCatching { speedIsland.start() }
            scope.launch {
                speedMonitor.speed.collect { speed ->
                    vpnNotifier.setSpeed(speed)
                    if (protection.isEnabled && vpnTunnel.state == com.networkcontrol.android.kotlin.engine.vpn.VpnTunnelState.Active) {
                        vpnTunnel.refreshNotification()
                    }
                }
            }
            com.networkcontrol.android.kotlin.platform.AppLog.i(
                "core",
                "initialized rules=${rules.ruleSet.value.count} apps=${appCatalog.getApps().size} " +
                    "in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
            )
            started.complete(Unit)
            startTrafficLoop()
        }
    }

    suspend fun awaitStarted() = started.await()

    private suspend fun startTrafficLoop() {
        while (scope.isActive) {
            delay(10 * 60 * 1000L)
            runCatching { trafficCollector.collectNow() }
            runCatching { rules.flushHits() }
            runCatching { configBackup.backupIfNeeded() }
            runCatching {
                activityStore.applyRetention(
                    System.currentTimeMillis() - settings.state.value.logRetentionDays * 24L * 60 * 60 * 1000,
                )
            }
        }
    }

    /**
     * v0.2 修正：應用頁三態規則以前沒有寫 profile_id，變成所有配置文件共享。
     * 把舊的「使用者 / 詢問產生的應用級規則」遷移到默認配置文件。
     * 只執行一次：之後使用者刻意建立（或匯入）的全域規則不能每次啟動都被搬走。
     */
    private suspend fun migrateLegacySharedAppRules() {
        if (preferences.legacyRulesMigrated) return
        val target = com.networkcontrol.android.kotlin.core.model.Profile.DEFAULT_ID
        val legacy = rules.all.filter { rule ->
            (rule.source == com.networkcontrol.android.kotlin.core.model.RuleSource.User ||
                rule.source == com.networkcontrol.android.kotlin.core.model.RuleSource.Prompt) &&
                rule.profileId == null &&
                rule.app != null &&
                rule.conditions.direction == com.networkcontrol.android.kotlin.core.model.Direction.Outbound &&
                rule.conditions.remote == null &&
                rule.conditions.remotePort == null &&
                rule.conditions.localPort == null &&
                rule.conditions.protocol == null
        }
        legacy.forEach { rule ->
            runCatching {
                rules.update(rule.copy(profileId = target))
            }
        }
        if (legacy.isNotEmpty()) {
            com.networkcontrol.android.kotlin.platform.AppLog.i(
                "core",
                "migrated ${legacy.size} shared app rules to default profile",
            )
        }
        preferences.legacyRulesMigrated = true
    }
}
