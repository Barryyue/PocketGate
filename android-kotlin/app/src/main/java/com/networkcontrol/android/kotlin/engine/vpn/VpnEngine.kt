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
import kotlinx.coroutines.flow.receiveAsFlow
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
    /** 目前生效的攔截列表（不可變快照）。 */
    private val blocklist: () -> com.networkcontrol.android.kotlin.engine.blocklist.BlocklistMatcher = {
        com.networkcontrol.android.kotlin.engine.blocklist.BlocklistMatcher.Empty
    },
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

    /**
     * 重新規劃的信號。用 CONFLATED Channel 而不是無 replay 的 SharedFlow：
     * 收集端開始收集之前發出的信號不能遺失，否則啟動時可能一次都沒有規劃，隧道不會建立。
     */
    private val replanSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** 也會被服務生命週期回呼（主執行緒）清空，所以要 volatile。 */
    @Volatile
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
        onAllowed = { flow: FlowInfo -> pipeline.recordAllowed(flow) },
        onBlocklisted = { flow: FlowInfo -> pipeline.recordBlocklisted(flow, blockedListId(flow)) },
    )

    /** 轉發模式的逐連線過濾層：路由計畫決定放行（轉發）、阻止或詢問。 */
    val forwardingFilter: ForwardingPacketFilter = ForwardingPacketFilter(
        ownerOf = { protocol, local, remote -> owners.getOwnerUid(protocol, local, remote) },
        decide = { uid, info, hostname -> decideForwarding(uid, info, hostname) },
        onBlocked = { info, uid, _ -> reporter.report(info, uid) },
        blockUdp = { !preferences.socks5Udp },
        redirectDns = { preferences.dnsBypassProtection },
        onForwarded = { info, uid -> reporter.reportAllowed(info, uid) },
        onBlocklisted = { info, uid -> reporter.reportBlocklisted(info, uid) },
    )

    /** 內建直連出口；只在逐連線模式且選擇直連時執行。 */
    @Volatile
    private var directServer: DirectSocksServer? = null

    val directCounters: DirectSocksCounters? get() = directServer?.counters

    private val underlying = UnderlyingNetwork(context)

    /** 內建出口的域名解析：選了加密 DNS 時走 DoH，否則（或允許退回時失敗）用實體網路的系統 DNS。 */
    val exitResolver = com.networkcontrol.android.kotlin.engine.dns.DohResolver(
        url = {
            com.networkcontrol.android.kotlin.engine.dns.DohProviders.find(preferences.exitDnsProvider)?.url
                ?: preferences.exitDnsCustomUrl?.takeIf { preferences.exitDnsProvider == "custom" && it.startsWith("https://") }
        },
        open = { url -> underlying.openConnection(url) as java.net.HttpURLConnection },
        fallback = { if (preferences.exitDnsFallback) underlying::resolve else null },
    )

    private fun ensureDirectServer(): DirectSocksServer =
        directServer ?: DirectSocksServer(
            resolve = exitResolver::resolve,
            bindSocket = { underlying.bind(it) },
            bindDatagram = { underlying.bind(it) },
        ).also {
            it.start()
            directServer = it
        }

    private fun stopDirectServer() {
        directServer?.close()
        directServer = null
    }

    /** 轉發判決用的應用身分，按 UID 緩存；重新規劃時清空。 */
    private val identities = java.util.concurrent.ConcurrentHashMap<Int, com.networkcontrol.android.kotlin.core.model.AppIdentity>()
    private var lastDetachRebuild = 0L
    private var lastFailureRebuild = 0L

    /** 在虛擬網卡讀取執行緒上呼叫：只有存在條件規則或攔截列表時才建立 FlowInfo 比對。 */
    private fun decideForwarding(uid: Int?, info: PacketInfo, hostname: String?): ForwardingDecision {
        val plan = _plan.value
        val ruleSet = rules.ruleSet.value
        val lists = blocklist()
        val useLists = hostname != null && !lists.isEmpty
        // 防止繞過本機 DNS：一般應用的 DoT / 知名 DoH 連線視為命中名單（系統核心 UID 例外，避免影響私人 DNS）。
        val encryptedDns = preferences.dnsBypassProtection && uid != null && !VpnRoutingPlanner.isSystemUid(uid) &&
            com.networkcontrol.android.kotlin.engine.dns.EncryptedDnsTargets.matches(info.destination.port, info.destination.address, hostname)
        if (!ruleSet.hasFlowConditions && !useLists && !encryptedDns) return ForwardingPolicy.decide(plan, uid)
        val context by lazy { flowContext(plan, uid, info, hostname) }
        return ForwardingPolicy.decide(
            plan,
            uid,
            blocklisted = {
                (encryptedDns || (useLists && lists.match(hostname) != null)) &&
                    !ruleSet.allowsDomain(context.flow, context.flow.app, context.domains, context.zone, network.current)
            },
        ) {
            if (!ruleSet.hasFlowConditions) {
                null
            } else {
                ForwardingPolicy.conditionalAction(ruleSet, context.flow, context.domains, context.zone, network.current)
            }
        }
    }

    /** 記錄用：命中哪份名單；加密 DNS 防繞過記為 `dns-bypass`。 */
    private fun blockedListId(flow: FlowInfo): String? =
        blocklist().match(flow.engineHostname)
            ?: if (com.networkcontrol.android.kotlin.engine.dns.EncryptedDnsTargets.matches(flow.remote.port, flow.remote.address, flow.engineHostname)) {
                DNS_BYPASS_LIST_ID
            } else {
                null
            }

    private class FlowContext(
        val flow: FlowInfo,
        val domains: com.networkcontrol.android.kotlin.core.model.DomainCandidates,
        val zone: com.networkcontrol.android.kotlin.core.model.NetworkZone,
    )

    private fun flowContext(plan: VpnRoutingPlan, uid: Int?, info: PacketInfo, hostname: String?): FlowContext {
        run {
            val app = uid?.let { identityFor(plan, it) }
            val flow = FlowInfo(
                flowId = 0,
                direction = com.networkcontrol.android.kotlin.core.model.Direction.Outbound,
                protocol = info.protocol,
                local = info.source,
                remote = info.destination,
                app = app,
                executablePath = app?.path,
                engineHostname = hostname,
                timestamp = System.currentTimeMillis(),
                origin = com.networkcontrol.android.kotlin.core.model.FlowOrigin.BlockedEvent,
            )
            val domains = hostname
                ?.let { com.networkcontrol.android.kotlin.core.model.DomainCandidates(listOf(com.networkcontrol.android.kotlin.core.matching.DomainName.normalize(it))) }
                ?: com.networkcontrol.android.kotlin.core.model.DomainCandidates.Empty
            // 映射位址（100.64.0.0/10）屬於 CGNAT 網段，但實際目標是域名對應的公網主機。
            val zone = if (hostname != null) {
                com.networkcontrol.android.kotlin.core.model.NetworkZone.Internet
            } else {
                com.networkcontrol.android.kotlin.core.matching.NetworkZones.classify(info.destination.address)
            }
            return FlowContext(flow, domains, zone)
        }
    }

    private fun identityFor(plan: VpnRoutingPlan, uid: Int): com.networkcontrol.android.kotlin.core.model.AppIdentity? {
        identities[uid]?.let { return it }
        val packageName = plan.find(uid)?.packageName ?: catalog.packagesForUid(uid).firstOrNull() ?: return null
        val identity = catalog.find(packageName)?.let(catalog::toIdentity) ?: return null
        identities[uid] = identity
        return identity
    }

    fun start() {
        tunnel.onDetached = {
            // 系統結束了 VPN 服務（例如記憶體不足）而防護仍開啟：重建隧道。限制頻率，避免反覆崩潰時打轉。
            val now = android.os.SystemClock.elapsedRealtime()
            if (protection.isEnabled && now - lastDetachRebuild >= DETACH_REBUILD_INTERVAL_MS) {
                lastDetachRebuild = now
                applied = null
                com.networkcontrol.android.kotlin.platform.AppLog.w("vpn", "service detached while protection on; rebuilding")
                requestReplan()
            }
        }
        tunnel.onStateChanged = {
            _tunnelState.value = tunnel.state
            _tunnelError.value = tunnel.lastError
            _forwardingActive.value = tunnel.isForwarding
            // 轉送執行緒或虛擬網卡讀取失敗：重新建立隧道，避免 VPN 保持 Active 但實際已斷。
            if (tunnel.state == VpnTunnelState.Failed && protection.isEnabled) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFailureRebuild >= FAILURE_REBUILD_INTERVAL_MS) {
                    lastFailureRebuild = now
                    applied = null
                    com.networkcontrol.android.kotlin.platform.AppLog.w(
                        "vpn",
                        "tunnel failed while protection on; rebuilding",
                    )
                    requestReplan()
                }
            }
        }
        tunnel.onRevoked = {
            protection.setEnabled(false)
            _tunnelState.value = VpnTunnelState.Revoked
            _tunnelError.value = tunnel.lastError
        }
        catalog.onChange = {
            identities.clear()
            requestReplan()
        }

        scope.launch { protection.enabled.collect { requestReplan() } }
        scope.launch { rules.ruleSet.collect { requestReplan() } }
        scope.launch { runtime.state.collect { requestReplan() } }
        scope.launch { settings.state.collect { requestReplan() } }
        // 網路類型 / SIM 變更會改變帶網路條件的應用規則，必須重新規劃路由。
        scope.launch { network.state.collect { requestReplan() } }
        scope.launch { screenState.locked.collect { requestReplan() } }
        scope.launch { focus.session.collect { requestReplan() } }
        scope.launch {
            replanSignal.receiveAsFlow().debounce(300).collect {
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
        if (preferences.usesDirectUpstream) {
            // 內建出口在本進程內，不需要探測；只確認它還在。
            tunnel.setWarning(if (directServer == null) "內建直連出口未啟動。" else null)
            return
        }
        val host = preferences.socks5Host
        val port = preferences.socks5Port
        val error = com.networkcontrol.android.kotlin.platform.Socks5Probe.handshake(
            host,
            port,
            username = preferences.socks5Username,
            password = preferences.socks5Password,
        )
        if (error != null) {
            com.networkcontrol.android.kotlin.platform.AppLog.w("forwarding", "upstream $host:$port unavailable: $error")
        }
        tunnel.setWarning(error?.let { "上游代理 $host:$port 無法連線：$it" })
    }

    fun requestReplan() {
        replanSignal.trySend(Unit)
    }

    private suspend fun replanNow() {
        val focusSession = focus.active()
        val lockBlocking = screenState.locked.value && blockOnLockScreen()
        val plan = VpnRoutingPlanner.plan(
            rules = rules.ruleSet.value,
            mode = if (focusSession != null || lockBlocking) {
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
            lockScreenAllowedPackages = if (lockBlocking && focusSession == null) {
                preferences.lockScreenWhitelist
            } else {
                emptySet()
            },
        )
        _plan.value = plan
        identities.clear()
        forwardingFilter.invalidate()

        if (!protection.isEnabled) {
            applied = null
            tunnel.close()
            stopDirectServer()
            _tunnelState.value = tunnel.state
            return
        }

        if (!tunnel.isPrepared) {
            _tunnelError.value = "尚未取得建立 VPN 的授權。"
            _tunnelState.value = VpnTunnelState.NotPrepared
            return
        }

        val direct = preferences.forwardingEnabled && preferences.usesDirectUpstream
        val server = if (direct) ensureDirectServer() else null
        val config = if (preferences.forwardingEnabled) {
            VpnTunnelConfig(
                bypassPackages = buildSet {
                    // 本應用必須排除：內建出口的對外連線才不會回到虛擬網卡。
                    add(selfPackage)
                    if (!direct) preferences.socks5BypassPackage?.let(::add)
                    addAll(preferences.forwardingDirectPackages)
                },
                forwarding = true,
                upstreamHost = if (server != null) "127.0.0.1" else preferences.socks5Host,
                upstreamPort = server?.port ?: preferences.socks5Port,
                upstreamUdp = preferences.socks5Udp,
                upstreamUsername = server?.username ?: preferences.socks5Username,
                upstreamPassword = server?.password ?: preferences.socks5Password,
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
        if (server == null) stopDirectServer()
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
        const val DETACH_REBUILD_INTERVAL_MS = 10_000L
        const val DNS_BYPASS_LIST_ID = "dns-bypass"
        const val FAILURE_REBUILD_INTERVAL_MS = 10_000L
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
