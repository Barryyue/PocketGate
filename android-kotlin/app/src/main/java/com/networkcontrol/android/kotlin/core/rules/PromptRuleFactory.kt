package com.networkcontrol.android.kotlin.core.rules

import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.DomainCandidates
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.FlowOrigin
import com.networkcontrol.android.kotlin.core.model.PortRange
import com.networkcontrol.android.kotlin.core.model.PromptDecision
import com.networkcontrol.android.kotlin.core.model.PromptScope
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import java.util.UUID

object AppMatcherFactory {
    fun create(app: AppIdentity): AppMatcher = AppMatcher(
        pathPattern = app.path,
        requiredSignerId = app.signature?.signerId,
        requiredSha256 = null,
        serviceName = app.serviceName,
        packageId = app.packageId,
    )
}

object PromptRuleFactory {
    const val DEFAULT_TIMED_DURATION_SECONDS = 15 * 60
    const val ONCE_FALLBACK_SECONDS = 60

    fun createConditions(
        decision: PromptDecision,
        flow: FlowInfo,
        domains: DomainCandidates,
    ): RuleConditions {
        var scope = decision.scope
        if (scope == PromptScope.Domain && decision.selectedDomain.isNullOrEmpty() && domains.isEmpty) {
            scope = PromptScope.Address
        }
        val baseline = RuleConditions(direction = flow.direction)
        return when (scope) {
            PromptScope.AnyConnection -> baseline
            PromptScope.Domain -> baseline.copy(
                remote = RemoteTarget.Domain(decision.selectedDomain ?: domains.primary!!),
            )
            PromptScope.Address -> baseline.copy(
                remote = RemoteTarget.Address.single(flow.remote.address),
            )
            PromptScope.AddressAndPort -> baseline.copy(
                remote = RemoteTarget.Address.single(flow.remote.address),
                remotePort = PortRange.single(flow.remote.port),
                protocol = flow.protocol,
            )
            PromptScope.Port -> baseline.copy(
                remotePort = PortRange.single(flow.remote.port),
                protocol = flow.protocol,
            )
        }
    }

    fun createRule(
        decision: PromptDecision,
        flow: FlowInfo,
        app: AppIdentity?,
        domains: DomainCandidates,
        profileId: String?,
        now: Long,
        onceFallbackSeconds: Int = ONCE_FALLBACK_SECONDS,
    ): Rule? {
        if (app == null) return null
        var conditions = createConditions(decision, flow, domains)
        var lifetime = decision.lifetime
        var expiresAt: Long? = null
        var boundProcess: com.networkcontrol.android.kotlin.core.model.ProcessRef? = null

        when (lifetime) {
            RuleLifetime.Once -> {
                if (flow.origin == FlowOrigin.Pending) return null
                lifetime = RuleLifetime.Timed
                expiresAt = now + onceFallbackSeconds * 1000L
                conditions = conditions.copy(
                    remote = RemoteTarget.Address.single(flow.remote.address),
                    remotePort = PortRange.single(flow.remote.port),
                    protocol = flow.protocol,
                )
            }
            RuleLifetime.Timed -> {
                val duration = decision.durationSeconds ?: DEFAULT_TIMED_DURATION_SECONDS
                expiresAt = now + duration * 1000L
            }
            RuleLifetime.UntilProcessExit -> {
                if (app.pid != null) {
                    boundProcess = com.networkcontrol.android.kotlin.core.model.ProcessRef(app.pid, 0)
                } else {
                    lifetime = RuleLifetime.UntilRestart
                }
            }
            RuleLifetime.UntilRestart, RuleLifetime.Permanent -> Unit
        }

        return Rule(
            id = UUID.randomUUID().toString(),
            profileId = profileId,
            app = AppMatcherFactory.create(app),
            conditions = conditions,
            action = decision.action,
            lifetime = lifetime,
            expiresAt = expiresAt,
            boundProcess = boundProcess,
            source = RuleSource.Prompt,
            createdAt = now,
            updatedAt = now,
        )
    }
}
