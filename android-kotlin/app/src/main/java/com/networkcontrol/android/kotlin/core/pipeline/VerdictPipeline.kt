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
) {
    private val selfPath = "android-app://$selfPackage"

    suspend fun decide(flow: FlowInfo): Verdict {
        val app = flow.app
        val domains = flow.engineHostname
            ?.let { DomainCandidates(listOf(DomainName.normalize(it))) }
            ?: DomainCandidates.Empty
        val zone = NetworkZones.classify(flow.remote.address)

        val verdict = try {
            decideInternal(flow, domains, zone)
        } catch (_: Exception) {
            Verdict.allow(VerdictReason.FailSafe)
        }

        publish(flow, verdict)
        if (verdict.reason == VerdictReason.DoNotDisturb) {
            quiet.onBlocked(flow.app?.displayName ?: flow.app?.name)
        }
        return verdict
    }

    private suspend fun decideInternal(
        flow: FlowInfo,
        domains: DomainCandidates,
        zone: NetworkZone,
    ): Verdict {
        val mode = runtime.mode
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
