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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    val profiles = ProfileService(profileStore, runtime, rules)
    val prompts = PromptBroker(rules, runtime, settings, scope)
    val appCatalog = AppCatalog(appContext)
    val owners = ConnectionOwnerResolver(appContext)
    val network = NetworkMonitor(appContext)
    val protection = ProtectionController(preferences)
    val vpnNotifier = VpnForegroundNotifier(appContext)
    val vpnTunnel = VpnTunnelController(appContext, vpnNotifier)
    val quietPeriods = com.networkcontrol.android.kotlin.core.prompts.QuietPeriodTracker(runtime, scope)

    val pipeline = VerdictPipeline(
        rules = rules,
        runtime = runtime,
        settings = settings,
        prompts = prompts,
        quiet = quietPeriods,
        activity = activityStore,
        scope = scope,
        selfPackage = appContext.packageName,
    )

    val vpnEngine = VpnEngine(
        context = appContext,
        scope = scope,
        rules = rules,
        runtime = runtime,
        catalog = appCatalog,
        apps = appStore,
        owners = owners,
        pipeline = pipeline,
        protection = protection,
        tunnel = vpnTunnel,
        notifier = vpnNotifier,
        selfPackage = appContext.packageName,
    )

    val trafficCollector = TrafficCollector(appContext, appCatalog, appStore, trafficStore)
    val ruleLifetime = RuleLifetimeManager(rules, scope)
    val promptNotifier = com.networkcontrol.android.kotlin.platform.PromptNotifier(appContext, prompts, scope, preferences)
    val backend = AppBackend(this)
    val configBackup = ConfigBackup(appContext, configDatabase)
    val diagnostics = DiagnosticsExporter(appContext, this)

    private val started = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                settings.initialize()
                runtime.initialize()
                profiles.initialize()
                rules.initialize()
                configBackup.backupIfNeeded()
                ruleLifetime.start()
                network.start()
                vpnEngine.start()
                promptNotifier.start()
                com.networkcontrol.android.kotlin.platform.AppLog.i(
                    "core",
                    "initialized rules=${rules.ruleSet.value.count} apps=${appCatalog.getApps().size}",
                )
            } catch (ex: Throwable) {
                com.networkcontrol.android.kotlin.platform.AppLog.e("core", "initialization failed", ex)
            } finally {
                started.complete(Unit)
            }
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
        }
    }
}
