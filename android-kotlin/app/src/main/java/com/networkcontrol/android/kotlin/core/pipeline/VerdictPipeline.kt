package com.networkcontrol.android.kotlin.core.pipeline

import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.matching.DomainName
import com.networkcontrol.android.kotlin.core.matching.NetworkZones
import com.networkcontrol.android.kotlin.core.model.ConnectionEvent
import com.networkcontrol.android.kotlin.core.model.DomainCandidates
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.FlowOrigin
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.NetworkZone
import com.networkcontrol.android.kotlin.core.model.Verdict
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.model.VerdictReason
import com.networkcontrol.android.kotlin.core.prompts.PromptBroker
import com.networkcontrol.android.kotlin.core.rules.RuleService
import com.networkcontrol.android.kotlin.core.settings.RuntimeStateService
import com.networkcontrol.android.kotlin.core.settings.SettingsService
import com.networkcontrol.android.kotlin.core.storage.ActivityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class VerdictPipeline(
    private val rules: RuleService,
    private val runtime: RuntimeStateService,
    private val settings: SettingsService,
    private val prompts: PromptBroker,
    private val quiet: com.networkcontrol.android.kotlin.core.prompts.QuietPeriodTracker,
    private val activity: ActivityStore,
    private val scope: CoroutineScope,
    private val selfPackage: String,
    private val currentNetwork: () -> NetworkContext = { NetworkContext.None },
    /**
     * 實際生效的模式。專注模式、鎖屏斷網會讓路由計畫變成「全部阻止」而不改動使用者選的模式；
     * 判決必須跟隨實際生效的模式，否則這段時間會對被攔的應用彈出詢問、記錄也會寫成允許。
     */
    private val effectiveMode: () -> FilterMode = { runtime.mode },
    /** 本應用合成的位址（轉發模式的映射 DNS 位址）；它們落在 CGNAT 網段，但實際目標是公網域名。 */
    private val isSyntheticAddress: (java.net.InetAddress) -> Boolean = { false },
) {
    private val selfPath = "android-app://$selfPackage"

    suspend fun decide(flow: FlowInfo): Verdict {
        val app = flow.app
        val domains = flow.engineHostname
            ?.let { DomainCandidates(listOf(DomainName.normalize(it))) }
            ?: DomainCandidates.Empty
        val zone = if (flow.engineHostname != null && isSyntheticAddress(flow.remote.address)) {
            NetworkZone.Internet
        } else {
            NetworkZones.classify(flow.remote.address)
        }

        val decided = try {
            decideInternal(flow, domains, zone)
        } catch (_: Exception) {
            Verdict.allow(VerdictReason.FailSafe)
        }
        // 引擎上報的「已被丟棄」連線：例如黑洞模式下只對部分域名放行的應用無法按應用排除，
        // 規則雖然判定允許，連線實際上仍被丟棄。記錄必須寫成實際結果，否則使用者會誤以為放行了。
        val verdict = if (flow.origin == FlowOrigin.BlockedEvent && decided.action == VerdictAction.Allow) {
            Verdict.deny(VerdictReason.Mode)
        } else {
            decided
        }

        publish(flow, verdict)
        if (verdict.reason == VerdictReason.DoNotDisturb) {
            quiet.onBlocked(flow.app?.displayName ?: flow.app?.name)
        }
        return verdict
    }

    /**
     * 轉發模式下已經放行的連線：不判決、不詢問，只寫入記錄。
     * 有符合的允許規則時記錄規則 id（並計入命中次數），否則記為模式放行。
     */
    fun recordAllowed(flow: FlowInfo) {
        val domains = flow.engineHostname
            ?.let { DomainCandidates(listOf(DomainName.normalize(it))) }
            ?: DomainCandidates.Empty
        val zone = if (flow.engineHostname != null && isSyntheticAddress(flow.remote.address)) {
            NetworkZone.Internet
        } else {
            NetworkZones.classify(flow.remote.address)
        }
        val rule = runCatching {
            rules.ruleSet.value.evaluate(flow, flow.app, domains, zone, currentNetwork()).rule
        }.getOrNull()?.takeIf { it.action == VerdictAction.Allow }
        rule?.let { rules.recordHit(it.id) }
        publish(flow, if (rule != null) Verdict(VerdictAction.Allow, VerdictReason.Rule, rule.id) else Verdict.allow(VerdictReason.Mode))
    }

    /** 命中攔截列表的連線：記錄為拒絕，規則欄記 `blocklist:<名單 id>`。 */
    fun recordBlocklisted(flow: FlowInfo, listId: String?) {
        publish(flow, Verdict(VerdictAction.Deny, VerdictReason.Blocklist, listId?.let { "blocklist:$it" }))
    }

    private suspend fun decideInternal(
        flow: FlowInfo,
        domains: DomainCandidates,
        zone: NetworkZone,
    ): Verdict {
        val mode = effectiveMode()
        if (mode == FilterMode.Disabled) {
            return Verdict.allow(VerdictReason.Mode)
        }

        if (isBuiltin(flow, zone)) {
            return Verdict.allow(VerdictReason.Builtin)
        }

        if (mode == FilterMode.BlockAll) {
            return Verdict.deny(VerdictReason.Mode)
        }

        val ruleSet = rules.ruleSet.value
        val match = ruleSet.evaluate(flow, flow.app, domains, zone, currentNetwork())
        match.rule?.let { rule ->
            rules.recordHit(rule.id)
            return Verdict(rule.action, VerdictReason.Rule, rule.id)
        }

        val tampered = match.tamperedRule != null
        when (mode) {
            FilterMode.Blacklist -> if (!tampered) return Verdict.allow(VerdictReason.Mode)
            FilterMode.Whitelist -> if (!tampered) return Verdict.deny(VerdictReason.Mode)
            else -> Unit
        }

        val defaultAction = settings.state.value.promptDefaultAction
        if (runtime.isDoNotDisturb) {
            return Verdict(defaultAction, VerdictReason.DoNotDisturb)
        }

        val deferred = prompts.request(
            flow,
            flow.app,
            domains,
            tampered,
            holdConnection = flow.origin == FlowOrigin.Pending,
        )
        if (flow.origin == FlowOrigin.BlockedEvent) {
            return Verdict.deny(VerdictReason.Mode)
        }
        return deferred.await()
    }

    private fun isBuiltin(flow: FlowInfo, zone: NetworkZone): Boolean {
        if (zone == NetworkZone.Loopback) return true
        if (flow.app?.path == selfPath) return true
        if (flow.protocol == L4Protocol.IcmpV6) return true
        if (flow.protocol == L4Protocol.Icmp && settings.state.value.allowIcmpByDefault) return true
        return flow.protocol == L4Protocol.Udp &&
            flow.remote.port in setOf(67, 68, 546, 547)
    }

    private fun publish(flow: FlowInfo, verdict: Verdict) {
        val event = ConnectionEvent(
            timestamp = flow.timestamp,
            appId = flow.app?.appId,
            appPath = flow.app?.path ?: flow.executablePath,
            appName = flow.app?.displayName ?: flow.app?.name,
            direction = flow.direction,
            protocol = flow.protocol,
            local = flow.local,
            remote = flow.remote,
            hostname = flow.engineHostname,
            verdict = verdict,
        )
        scope.launch {
            runCatching { activity.write(event) }
        }
    }
}
