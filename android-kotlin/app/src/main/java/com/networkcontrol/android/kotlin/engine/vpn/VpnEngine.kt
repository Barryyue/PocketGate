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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
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
    private val owners: ConnectionOwnerResolver,
    private val pipeline: VerdictPipeline,
    private val protection: ProtectionController,
    private val tunnel: VpnTunnelController,
    private val network: com.networkcontrol.android.kotlin.engine.network.NetworkMonitor,
    private val screenState: com.networkcontrol.android.kotlin.engine.network.ScreenStateMonitor,
    private val blockOnLockScreen: () -> Boolean,
    private val focus: com.networkcontrol.android.kotlin.core.focus.FocusController,
    private val preferences: com.networkcontrol.android.kotlin.AppPreferences,
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
    private val _forwardingActive = MutableStateFlow(false)

    /** 隧道目前是否真的在轉發到上游代理。 */
    val forwardingActive: StateFlow<Boolean> = _forwardingActive.asStateFlow()
    private var appliedFallback: VpnTunnelConfig? = null
    private var lastForwardingRetry = 0L

    private val replanSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var applied: VpnTunnelConfig? = null

    private val reporter: BlockedFlowReporter = BlockedFlowReporter(
        owners = owners,
        catalog = catalog,
        apps = apps,
        currentPlan = { _plan.value },
        fakeDns = fakeDns,
        counters = counters,
        onFlow = { flow: FlowInfo -> pipeline.decide(flow) },
        extraHostname = { address -> forwardingFilter.hostnameFor(address) },
    )

    /** 轉發模式的逐連線過濾層：路由計畫決定放行（轉發）、阻止或詢問。 */
    val forwardingFilter: ForwardingPacketFilter = ForwardingPacketFilter(
        ownerOf = { protocol, local, remote -> owners.getOwnerUid(protocol, local, remote) },
        decide = { uid -> ForwardingPolicy.decide(_plan.value, uid) },
        onBlocked = { info, uid, _ -> reporter.report(info, uid) },
        blockUdp = { !preferences.socks5Udp },
    )

    fun start() {
        tunnel.onStateChanged = {
            _tunnelState.value = tunnel.state
            _tunnelError.value = tunnel.lastError
            _forwardingActive.value = tunnel.isForwarding
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
        scope.launch {
            delay(5_000)
            while (isActive) {
                runCatching { checkForwarding() }
                    .onFailure { com.networkcontrol.android.kotlin.platform.AppLog.w("forwarding", "health check failed: ${it.message}") }
                delay(UPSTREAM_CHECK_INTERVAL_MS)
            }
        }
        requestReplan()
    }

    /**
     * 轉發模式的健康檢查：
     * - 正在轉發：探測上游 SOCKS5，無法連線時在總覽顯示提示（連線會失敗，不會悄悄直連）；
     * - 轉發曾啟動失敗並回退到黑洞模式：定期重試。
     */
    private suspend fun checkForwarding() {
        if (!preferences.forwardingEnabled || !protection.isEnabled) return
        if (tunnel.state != VpnTunnelState.Active) return
        if (!tunnel.isForwarding) {
            val now = System.currentTimeMillis()
            if (now - lastForwardingRetry >= FORWARDING_RETRY_INTERVAL_MS) {
                lastForwardingRetry = now
                applied = null
                requestReplan()
            }
            return
        }
        val host = preferences.socks5Host
        val port = preferences.socks5Port
        val error = com.networkcontrol.android.kotlin.platform.Socks5Probe.handshake(host, port)
        if (error != null) {
            com.networkcontrol.android.kotlin.platform.AppLog.w("forwarding", "upstream $host:$port unavailable: $error")
        }
        tunnel.setWarning(error?.let { "上游代理 $host:$port 無法連線：$it" })
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
        forwardingFilter.invalidate()

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

        val config = if (preferences.forwardingEnabled) {
            VpnTunnelConfig(
                bypassPackages = buildSet {
                    add(selfPackage)
                    preferences.socks5BypassPackage?.let(::add)
                    addAll(preferences.forwardingDirectPackages)
                },
                forwarding = true,
                upstreamHost = preferences.socks5Host,
                upstreamPort = preferences.socks5Port,
                upstreamUdp = preferences.socks5Udp,
            )
        } else {
            VpnTunnelConfig(plan.bypassPackages)
        }
        // 轉發啟動失敗時隧道在黑洞模式運行，排除名單要跟著路由計畫更新。
        val fallback = VpnTunnelConfig(plan.bypassPackages)
        if (tunnel.state == VpnTunnelState.Active &&
            config.equivalentTo(applied) &&
            (!config.forwarding || tunnel.isForwarding || fallback.equivalentTo(appliedFallback))
        ) {
            return
        }

        tunnel.apply(config, reporter, forwardingFilter, fallback)
        applied = config
        appliedFallback = if (config.forwarding && !tunnel.isForwarding) fallback else null
        _forwardingActive.value = tunnel.isForwarding
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

    private companion object {
        const val UPSTREAM_CHECK_INTERVAL_MS = 30_000L
        const val FORWARDING_RETRY_INTERVAL_MS = 120_000L
    }

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
