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
        focusAllowedPackages: Set<String> = emptySet(),
        lockScreenAllowedPackages: Set<String> = emptySet(),
        cloneAppsInheritPrimary: Boolean = true,
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
                sorted,
                packages,
                selfPackage,
                network,
                allowSystemAppsByDefault,
                trustedSigners,
                focusAllowedPackages,
                lockScreenAllowedPackages,
            )
            byUid[uid] = routing
            if (routing.decision == AppRoutingDecision.Bypass) bypass.addAll(packages)
        }

        return VpnRoutingPlan(mode, bypass, byUid, skipped, cloneAppsInheritPrimary)
    }

    fun isEnforceable(rule: Rule): Boolean = getSkipReason(rule) == null

    /**
     * 目前已啟用規則中允許聯網的應用包名集合。
     * 優先按目前網路查找該應用生效的規則；若目前網路無匹配（例如只設了 Wi-Fi 允許但當下在行動網路或離線），
     * 則回退檢查該應用在已啟用規則中最高優先級的規則是否為允許。
     */
    fun allowedByRulePackages(
        rules: RuleSet,
        apps: List<InstalledApp>,
        network: NetworkContext = NetworkContext.None,
    ): Set<String> = apps.asSequence()
        .filter { app ->
            val identity = toIdentity(app)
            val rule = rules.findAppRule(identity, network) {
                it.app != null && isEnforceable(it)
            }.rule ?: rules.findAppRule(identity, ignoreNetwork = true) {
                it.app != null && isEnforceable(it)
            }.rule
            rule?.action == VerdictAction.Allow
        }
        .map { it.packageName }
        .toSet()

    /**
     * 目前已啟用規則中設有禁止聯網規則（含按網路或網域條件）的應用包名集合。
     * 用於在排除應用選擇框中標註衝突，避免使用者誤以為禁止規則仍然生效。
     */
    fun deniedByRulePackages(
        rules: RuleSet,
        apps: List<InstalledApp>,
    ): Set<String> {
        val denyAppPaths = rules.rules.asSequence()
            .filter { it.enabled && it.action == VerdictAction.Deny && it.app != null }
            .mapNotNull { it.app?.pathPattern?.lowercase() }
            .toSet()
        if (denyAppPaths.isEmpty()) return emptySet()
        return apps.asSequence()
            .filter { app -> toIdentity(app).path.lowercase() in denyAppPaths }
            .map { it.packageName }
            .toSet()
    }

    /** 這次新加入排除名單、且目前含有禁止規則的應用包名集合。 */
    fun newlyExcludedWithDenyRules(
        initial: Set<String>,
        next: Set<String>,
        deniedByRulePackages: Set<String>,
    ): Set<String> = (next - initial) intersect deniedByRulePackages

    fun filterPickerApps(
        apps: List<InstalledApp>,
        query: String,
        onlyRuleAllowed: Boolean,
        allowedByRulePackages: Set<String>,
    ): List<InstalledApp> = apps.asSequence()
        .filter { !onlyRuleAllowed || it.packageName in allowedByRulePackages }
        .filter {
            query.isBlank() ||
                it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        .toList()

    fun selectAllVisible(
        selected: Set<String>,
        visibleApps: List<InstalledApp>,
    ): Set<String> = selected + visibleApps.map { it.packageName }

    fun clearVisibleOrAll(
        selected: Set<String>,
        visibleApps: List<InstalledApp>,
    ): Set<String> {
        val visiblePackages = visibleApps.map { it.packageName }.toSet()
        val visibleSelected = selected intersect visiblePackages
        return if (visibleSelected.isNotEmpty()) selected - visibleSelected else emptySet()
    }

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
        group: List<InstalledApp>,
        packages: List<String>,
        selfPackage: String,
        network: NetworkContext,
        allowSystemAppsByDefault: Boolean,
        trustedSigners: Set<String>,
        focusAllowedPackages: Set<String>,
        lockScreenAllowedPackages: Set<String>,
    ): AppRouting {
        if (packages.contains(selfPackage)) {
            return AppRouting(uid, primary.packageName, packages, AppRoutingDecision.Bypass, AppRoutingReason.Self)
        }
        if (isSystemUid(uid)) {
            return AppRouting(uid, primary.packageName, packages, AppRoutingDecision.Bypass, AppRoutingReason.SystemUid)
        }
        if (focusAllowedPackages.isNotEmpty() && packages.any { it in focusAllowedPackages }) {
            return AppRouting(
                uid,
                primary.packageName,
                packages,
                AppRoutingDecision.Bypass,
                AppRoutingReason.Focus,
            )
        }
        if (lockScreenAllowedPackages.isNotEmpty() && packages.any { it in lockScreenAllowedPackages }) {
            return AppRouting(
                uid,
                primary.packageName,
                packages,
                AppRoutingDecision.Bypass,
                AppRoutingReason.LockScreen,
            )
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

        // 共享 UID：遍歷組內所有套件，按規則優先級（拒絕優先）選出勝出規則，避免非首個套件的規則被靜默忽略。
        var winningCompiled: com.networkcontrol.android.kotlin.core.rules.CompiledRule? = null
        var firstTampered: Rule? = null
        for (app in group) {
            val m = rules.findAppRule(toIdentity(app), network) { isEnforceable(it) }
            if (firstTampered == null && m.tamperedRule != null) firstTampered = m.tamperedRule
            val r = m.rule ?: continue
            val compiled = com.networkcontrol.android.kotlin.core.rules.CompiledRule(r)
            if (winningCompiled == null ||
                com.networkcontrol.android.kotlin.core.rules.RulePriority.compare(compiled, winningCompiled) < 0
            ) {
                winningCompiled = compiled
            }
        }
        winningCompiled?.rule?.let { rule ->
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
                tamperedRuleId = firstTampered?.id,
            )
        }

        val tampered = firstTampered != null
        val trusted = !tampered && group.all { app ->
            val signer = app.signerSha256
            (allowSystemAppsByDefault && app.isSystem) ||
                (signer != null && trustedSigners.contains(signer))
        }
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
            tamperedRuleId = firstTampered?.id,
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
