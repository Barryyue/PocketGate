package com.networkcontrol.android.kotlin.core.rules

import com.networkcontrol.android.kotlin.core.matching.DomainPattern
import com.networkcontrol.android.kotlin.core.matching.IpNetwork
import com.networkcontrol.android.kotlin.core.matching.NetworkZones
import com.networkcontrol.android.kotlin.core.matching.PathPattern
import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.AppMatchOutcome
import com.networkcontrol.android.kotlin.core.model.DomainCandidates
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.NetworkZone
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleMatch
import com.networkcontrol.android.kotlin.core.model.VerdictAction

object RuleSpecificity {
    fun compute(rule: Rule): Int {
        var score = 0
        rule.app?.let { app ->
            score += if (app.serviceName != null || app.packageId != null) 2000 else 1000
        }

        val conditions = rule.conditions
        score += when (val remote = conditions.remote) {
            is RemoteTarget.Address -> if (remote.networks.size == 1 && remote.networks[0].isHost()) 400 else 300
            is RemoteTarget.Domain -> if (!remote.pattern.startsWith("*.")) 400 else 300
            is RemoteTarget.Zone -> 200
            null -> 0
        }

        score += when {
            conditions.remotePort?.isSingle == true -> 40
            conditions.remotePort != null -> 30
            else -> 0
        }
        score += if (conditions.localPort != null) 20 else 0
        score += if (conditions.protocol != null) 10 else 0
        score += if (conditions.direction != null) 5 else 0
        return score
    }

    private fun IpNetwork.isHost(): Boolean =
        prefixLength == if (addressFamily == 4) 32 else 128
}

class CompiledRule(val rule: Rule) {
    val specificity: Int = RuleSpecificity.compute(rule)
    private val pathPattern: PathPattern? = rule.app?.let { PathPattern.parse(it.pathPattern) }
    private val domainPattern: DomainPattern? = (rule.conditions.remote as? RemoteTarget.Domain)
        ?.let { DomainPattern.parse(it.pattern) }
    private val networks: List<IpNetwork>? = (rule.conditions.remote as? RemoteTarget.Address)?.networks
    private val zone: NetworkZone? = (rule.conditions.remote as? RemoteTarget.Zone)?.zone

    val exactPathKey: String? get() = pathPattern?.literalPath
    val isGlobal: Boolean get() = rule.app == null

    fun matchApp(app: AppIdentity?): AppMatchOutcome {
        val matcher = rule.app ?: return AppMatchOutcome.Match
        if (app == null || pathPattern == null || !pathPattern.matches(app.path)) {
            return AppMatchOutcome.NoMatch
        }
        if (matcher.serviceName != null &&
            !matcher.serviceName.equals(app.serviceName, ignoreCase = true)
        ) {
            return AppMatchOutcome.NoMatch
        }
        if (matcher.packageId != null && !matcher.packageId.equals(app.packageId, ignoreCase = true)) {
            return AppMatchOutcome.NoMatch
        }
        if (matcher.requiredSignerId != null &&
            matcher.requiredSignerId != app.signature?.signerId
        ) {
            return AppMatchOutcome.Tampered
        }
        if (matcher.requiredSha256 != null && app.sha256 != null &&
            !matcher.requiredSha256.equals(app.sha256, ignoreCase = true)
        ) {
            return AppMatchOutcome.Tampered
        }
        return AppMatchOutcome.Match
    }

    fun matchConditions(flow: FlowInfo, domains: DomainCandidates, remoteZone: NetworkZone): Boolean {
        val conditions = rule.conditions
        if (conditions.direction != null && conditions.direction != flow.direction) return false
        if (conditions.protocol != null && conditions.protocol != flow.protocol) return false
        if (conditions.remotePort != null && !conditions.remotePort.contains(flow.remote.port)) return false
        if (conditions.localPort != null && !conditions.localPort.contains(flow.local.port)) return false

        if (domainPattern != null) {
            val matched = domains.names.any { domainPattern.matches(it) }
            if (!matched) return false
        }

        if (networks != null) {
            val address = flow.remote.address
            if (networks.none { it.contains(address) }) return false
        }

        return zone == null || zone == remoteZone
    }
}

class RuleSet(rules: List<Rule>, val version: Long = 0) {
    private val compiled: List<CompiledRule> = rules
        .map { CompiledRule(it) }
        .sortedWith(RulePriority)

    val rules: List<Rule> = compiled.map { it.rule }
    val count: Int get() = rules.size

    fun evaluate(
        flow: FlowInfo,
        app: AppIdentity?,
        domains: DomainCandidates,
        remoteZone: NetworkZone,
    ): RuleMatch {
        var tampered: Rule? = null
        for (candidate in compiled) {
            if (!candidate.matchConditions(flow, domains, remoteZone)) continue
            when (candidate.matchApp(app)) {
                AppMatchOutcome.Match -> return RuleMatch(candidate.rule, tampered)
                AppMatchOutcome.Tampered -> if (tampered == null) tampered = candidate.rule
                AppMatchOutcome.NoMatch -> Unit
            }
        }
        return RuleMatch(null, tampered)
    }

    fun findAppRule(app: AppIdentity, includeRule: (Rule) -> Boolean): RuleMatch {
        var tampered: Rule? = null
        for (candidate in compiled) {
            if (!includeRule(candidate.rule)) continue
            when (candidate.matchApp(app)) {
                AppMatchOutcome.Match -> return RuleMatch(candidate.rule, tampered)
                AppMatchOutcome.Tampered -> if (tampered == null) tampered = candidate.rule
                AppMatchOutcome.NoMatch -> Unit
            }
        }
        return RuleMatch(null, tampered)
    }

    companion object {
        val Empty = RuleSet(emptyList())
    }
}

object RulePriority : Comparator<CompiledRule> {
    override fun compare(x: CompiledRule, y: CompiledRule): Int {
        if (x === y) return 0
        var c = y.rule.manualPriority.compareTo(x.rule.manualPriority)
        if (c != 0) return c
        c = y.specificity.compareTo(x.specificity)
        if (c != 0) return c
        c = y.rule.action.code.compareTo(x.rule.action.code)
        if (c != 0) return c
        c = y.rule.updatedAt.compareTo(x.rule.updatedAt)
        if (c != 0) return c
        return x.rule.id.compareTo(y.rule.id)
    }
}

fun Rule.actionIs(action: VerdictAction): Boolean = this.action == action
