package com.networkcontrol.android.kotlin.engine.vpn

import android.content.Context
import android.content.Intent
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.VpnRoutingPlan
import com.networkcontrol.android.kotlin.core.pipeline.VerdictPipeline
import com.networkcontrol.android.kotlin.core.rules.RuleService
import com.networkcontrol.android.kotlin.core.settings.RuntimeStateService
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog
import com.networkcontrol.android.kotlin.engine.apps.ConnectionOwnerResolver
import com.networkcontrol.android.kotlin.engine.protection.ProtectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class VpnEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val rules: RuleService,
    private val runtime: RuntimeStateService,
    private val settings: com.networkcontrol.android.kotlin.core.settings.SettingsService,
    private val catalog: AppCatalog,
    private val apps: com.networkcontrol.android.kotlin.core.storage.AppStore,
    owners: ConnectionOwnerResolver,
    private val pipeline: VerdictPipeline,
    private val protection: ProtectionController,
    private val tunnel: VpnTunnelController,
    private val network: com.networkcontrol.android.kotlin.engine.network.NetworkMonitor,
    private val screenState: com.networkcontrol.android.kotlin.engine.network.ScreenStateMonitor,
    private val blockOnLockScreen: () -> Boolean,
    private val focus: com.networkcontrol.android.kotlin.core.focus.FocusController,
    private val notifier: VpnForegroundNotifier,
    private val selfPackage: String,
) {
    val counters = VpnEngineCounters()
    val fakeDns = FakeDnsPool()
    private val _plan = MutableStateFlow(VpnRoutingPlan.Empty)
    val plan: StateFlow<VpnRoutingPlan> = _plan.asStateFlow()
    private val _tunnelState = MutableStateFlow(tunnel.state)
    val tunnelState: StateFlow<VpnTunnelState> = _tunnelState.asStateFlow()
    private val _tunnelError = MutableStateFlow<String?>(null)
    val tunnelError: StateFlow<String?> = _tunnelError.asStateFlow()

    private val replanSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var applied: VpnTunnelConfig? = null

    private val reporter = BlockedFlowReporter(
        owners = owners,
        catalog = catalog,
        apps = apps,
        currentPlan = { _plan.value },
        fakeDns = fakeDns,
        counters = counters,
        onFlow = { flow: FlowInfo -> pipeline.decide(flow) },
    )

    fun start() {
        tunnel.onStateChanged = {
            _tunnelState.value = tunnel.state
            _tunnelError.value = tunnel.lastError
        }
        tunnel.onRevoked = {
            protection.setEnabled(false)
            _tunnelState.value = VpnTunnelState.Revoked
            _tunnelError.value = tunnel.lastError
        }
        catalog.onChange = { requestReplan() }

        scope.launch { protection.enabled.collect { requestReplan() } }
        scope.launch { rules.ruleSet.collect { requestReplan() } }
        scope.launch { runtime.state.collect { requestReplan() } }
        scope.launch { settings.state.collect { requestReplan() } }
        // 網路類型 / SIM 變更會改變帶網路條件的應用規則，必須重新規劃路由。
        scope.launch { network.state.collect { requestReplan() } }
        scope.launch { screenState.locked.collect { requestReplan() } }
        scope.launch { focus.session.collect { requestReplan() } }
        scope.launch {
            replanSignal.debounce(300).collect {
                runCatching { replanNow() }.onFailure { error ->
                    com.networkcontrol.android.kotlin.platform.AppLog.e("vpn", "replan failed", error)
                }
            }
        }

        reporter.start()
        requestReplan()
    }

    fun requestReplan() {
        replanSignal.tryEmit(Unit)
    }

    private suspend fun replanNow() {
        val focusSession = focus.active()
        val plan = VpnRoutingPlanner.plan(
            rules = rules.ruleSet.value,
            mode = if (focusSession != null || (screenState.locked.value && blockOnLockScreen())) {
                com.networkcontrol.android.kotlin.core.model.FilterMode.BlockAll
            } else {
                runtime.mode
            },
            apps = catalog.getApps(),
            selfPackage = selfPackage,
            network = network.current,
            allowSystemAppsByDefault = settings.state.value.allowSystemAppsByDefault,
            trustedSigners = settings.state.value.trustedSigners,
            focusAllowedPackages = focusSession?.allowedPackages ?: emptySet(),
        )
        _plan.value = plan

        if (!protection.isEnabled) {
            applied = null
            tunnel.close()
            _tunnelState.value = tunnel.state
            return
        }

        if (!tunnel.isPrepared) {
            _tunnelError.value = "尚未取得建立 VPN 的授權。"
            _tunnelState.value = VpnTunnelState.NotPrepared
            return
        }

        val config = VpnTunnelConfig(plan.bypassPackages)
        if (tunnel.state == VpnTunnelState.Active && config.equivalentTo(applied)) {
            return
        }

        tunnel.apply(config, reporter)
        applied = config
        _tunnelState.value = tunnel.state
        _tunnelError.value = tunnel.lastError
        com.networkcontrol.android.kotlin.platform.AppLog.i(
            "vpn",
            "replan mode=${plan.mode} protection=${protection.isEnabled} bypass=${plan.bypassPackages.size} " +
                "uid=${plan.byUid.size} skipped=${plan.skippedRules.size} state=${tunnel.state}",
        )
    }

    fun setProtection(enabled: Boolean) {
        protection.setEnabled(enabled)
        if (enabled) {
            requestReplan()
        } else {
            scope.launch { tunnel.close() }
        }
    }

    fun refreshNotification() {
        tunnel.refreshNotification()
    }

    fun onSystemStart() {
        protection.setEnabled(true)
        requestReplan()
    }

    fun createConsentIntent(): Intent? = tunnel.createPrepareIntent()

    fun status(): com.networkcontrol.android.kotlin.core.model.ProtectionStatus {
        val state = tunnel.state
        if (!protection.isEnabled) {
            return if (state == VpnTunnelState.Revoked) {
                com.networkcontrol.android.kotlin.core.model.ProtectionStatus.Revoked
            } else {
                com.networkcontrol.android.kotlin.core.model.ProtectionStatus.Off
            }
        }
        return when {
            state == VpnTunnelState.Active -> com.networkcontrol.android.kotlin.core.model.ProtectionStatus.On
            state == VpnTunnelState.Failed -> com.networkcontrol.android.kotlin.core.model.ProtectionStatus.Failed
            state == VpnTunnelState.NotPrepared || !tunnel.isPrepared ->
                com.networkcontrol.android.kotlin.core.model.ProtectionStatus.NeedsConsent
            else -> com.networkcontrol.android.kotlin.core.model.ProtectionStatus.Starting
        }
    }
}
