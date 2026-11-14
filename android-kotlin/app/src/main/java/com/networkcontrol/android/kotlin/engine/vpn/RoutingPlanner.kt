package com.networkcontrol.android.kotlin.engine.vpn

import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.AppRouting
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.AppRoutingReason
import com.networkcontrol.android.kotlin.core.model.CodeSignature
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.InstalledApp
import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleSkipReason
import com.networkcontrol.android.kotlin.core.model.SkippedRule
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.model.VpnRoutingPlan
import com.networkcontrol.android.kotlin.core.rules.RuleSet
import com.networkcontrol.android.kotlin.engine.apps.AppCatalog

object VpnRoutingPlanner {
    const val FIRST_APPLICATION_UID = 10_000
    const val PER_USER_RANGE = 100_000

    fun isSystemUid(uid: Int): Boolean = uid % PER_USER_RANGE < FIRST_APPLICATION_UID

    fun plan(
        rules: RuleSet,
        mode: FilterMode,
        apps: List<InstalledApp>,
        selfPackage: String,
        network: NetworkContext = NetworkContext.None,
        allowSystemAppsByDefault: Boolean = false,
        trustedSigners: Set<String> = emptySet(),
    ): VpnRoutingPlan {
        val skipped = rules.rules.mapNotNull { rule ->
            getSkipReason(rule)?.let { SkippedRule(rule.id, it) }
        }
        val bypass = linkedSetOf(selfPackage)
        val byUid = linkedMapOf<Int, AppRouting>()

        apps.groupBy { it.uid }.forEach { (uid, group) ->
            val sorted = group.sortedBy { it.packageName }
            val primary = sorted.first()
            val packages = sorted.map { it.packageName }
            val routing = decide(
                rules,
                mode,
                uid,
                primary,
                packages,
                selfPackage,
                network,
                allowSystemAppsByDefault,
                trustedSigners,
            )
            byUid[uid] = routing
            if (routing.decision == AppRoutingDecision.Bypass) bypass.addAll(packages)
        }

        return VpnRoutingPlan(mode, bypass, byUid, skipped)
    }

    fun isEnforceable(rule: Rule): Boolean = getSkipReason(rule) == null

    fun getSkipReason(rule: Rule): RuleSkipReason? {
        val conditions = rule.conditions
        if (conditions.direction == Direction.Inbound) return RuleSkipReason.Inbound
        // 網路類型 / SIM 條件可以在 VPN 路由層按目前網路套用，不算不支援。
        return if (conditions.remote == null &&
            conditions.remotePort == null &&
            conditions.localPort == null &&
            conditions.protocol == null
        ) {
            null
        } else {
            RuleSkipReason.UnsupportedConditions
        }
    }

    fun toIdentity(app: InstalledApp): AppIdentity = AppIdentity(
        path = AppCatalog.appPath(app.packageName),
        displayName = app.label,
        signature = CodeSignature(
            signerId = app.signerSha256.orEmpty(),
            isPlatformBinary = app.isSystem,
        ),
    )

    private fun decide(
        rules: RuleSet,
        mode: FilterMode,
        uid: Int,
        primary: InstalledApp,
        packages: List<String>,
        selfPackage: String,
        network: NetworkContext,
        allowSystemAppsByDefault: Boolean,
        trustedSigners: Set<String>,
    ): AppRouting {
        if (packages.contains(selfPackage)) {
            return AppRouting(uid, primary.packageName, packages, AppRoutingDecision.Bypass, AppRoutingReason.Self)
        }
        if (isSystemUid(uid)) {
            return AppRouting(uid, primary.packageName, packages, AppRoutingDecision.Bypass, AppRoutingReason.SystemUid)
        }
        when (mode) {
            FilterMode.Disabled -> return AppRouting(
                uid, primary.packageName, packages, AppRoutingDecision.Bypass, AppRoutingReason.Mode,
            )
            FilterMode.BlockAll -> return AppRouting(
                uid, primary.packageName, packages, AppRoutingDecision.Block, AppRoutingReason.Mode,
            )
            else -> Unit
        }

        val match = rules.findAppRule(toIdentity(primary), network) { isEnforceable(it) }
        match.rule?.let { rule ->
            val decision = if (rule.action == VerdictAction.Allow) {
                AppRoutingDecision.Bypass
            } else {
                AppRoutingDecision.Block
            }
            return AppRouting(
                uid = uid,
                packageName = primary.packageName,
                packages = packages,
                decision = decision,
                reason = AppRoutingReason.Rule,
                ruleId = rule.id,
                tamperedRuleId = match.tamperedRule?.id,
            )
        }

        val tampered = match.tamperedRule != null
        val signer = primary.signerSha256
        val trusted = !tampered &&
            ((allowSystemAppsByDefault && primary.isSystem) ||
                (signer != null && trustedSigners.contains(signer)))
        if (trusted) {
            return AppRouting(
                uid = uid,
                packageName = primary.packageName,
                packages = packages,
                decision = AppRoutingDecision.Bypass,
                reason = AppRoutingReason.Trusted,
                tamperedRuleId = null,
            )
        }
        val fallback = when {
            mode == FilterMode.Blacklist && !tampered -> AppRoutingDecision.Bypass
            mode == FilterMode.Ask || mode == FilterMode.Blacklist -> AppRoutingDecision.BlockAndAsk
            else -> AppRoutingDecision.Block
        }
        return AppRouting(
            uid = uid,
            packageName = primary.packageName,
            packages = packages,
            decision = fallback,
            reason = AppRoutingReason.Mode,
            tamperedRuleId = match.tamperedRule?.id,
        )
    }
}

object AndroidRuleCompatibility {
    fun isEffectiveOnAndroid(rule: Rule): Boolean = getIneffectiveReason(rule) == null

    fun getIneffectiveReason(rule: Rule): com.networkcontrol.android.kotlin.core.model.AndroidRuleIneffectiveReason? {
        VpnRoutingPlanner.getSkipReason(rule)?.let { reason ->
            return if (reason == RuleSkipReason.Inbound) {
                com.networkcontrol.android.kotlin.core.model.AndroidRuleIneffectiveReason.Inbound
            } else {
                com.networkcontrol.android.kotlin.core.model.AndroidRuleIneffectiveReason.UnsupportedConditions
            }
        }
        val path = rule.app?.pathPattern
        return if (path != null && !path.startsWith("android-app://")) {
            com.networkcontrol.android.kotlin.core.model.AndroidRuleIneffectiveReason.NotAndroidApp
        } else {
            null
        }
    }

    fun countIneffective(rules: List<Rule>): Int = rules.count { !isEffectiveOnAndroid(it) }
}
