package com.networkcontrol.android.kotlin.core.model

import com.networkcontrol.android.kotlin.core.matching.IpNetwork
import java.net.InetSocketAddress

data class CodeSignature(
    val signerId: String,
    val signingId: String? = null,
    val isPlatformBinary: Boolean = false,
)

data class AppKey(
    val path: String,
    val serviceName: String? = null,
    val packageId: String? = null,
)

data class AppIdentity(
    val appId: Long = 0,
    val path: String,
    val displayName: String? = null,
    val signature: CodeSignature? = null,
    val sha256: String? = null,
    val packageId: String? = null,
    val serviceName: String? = null,
    val commandLine: String? = null,
    val pid: Int? = null,
) {
    val key: AppKey get() = AppKey(path, serviceName, packageId)
    val isSigned: Boolean get() = signature != null
    val name: String get() = displayName ?: path.substringAfterLast('/').substringBeforeLast('.')
}

data class AppMatcher(
    val pathPattern: String,
    val requiredSignerId: String? = null,
    val requiredSha256: String? = null,
    val serviceName: String? = null,
    val packageId: String? = null,
) {
    val hasWildcard: Boolean get() = pathPattern.contains('*')
}

data class PortRange(val from: Int, val to: Int) {
    init {
        require(from in 0..65535 && to in 0..65535 && from <= to) {
            "端口範圍不合法：$from-$to"
        }
    }

    val isSingle: Boolean get() = from == to
    fun contains(port: Int): Boolean = port in from..to

    override fun toString(): String = if (isSingle) from.toString() else "$from-$to"

    companion object {
        fun single(port: Int): PortRange = PortRange(port, port)
    }
}

sealed interface RemoteTarget {
    data class Domain(val pattern: String) : RemoteTarget
    data class Address(val networks: List<IpNetwork>) : RemoteTarget {
        companion object {
            fun single(address: java.net.InetAddress): Address =
                Address(listOf(IpNetwork.single(address)))
        }
    }
    data class Zone(val zone: NetworkZone) : RemoteTarget
}

data class RuleConditions(
    val direction: Direction? = null,
    val protocol: L4Protocol? = null,
    val remote: RemoteTarget? = null,
    val remotePort: PortRange? = null,
    val localPort: PortRange? = null,
    /** 只在這些網路類型上生效；空集合表示不限制。 */
    val networkKinds: Set<NetworkKind> = emptySet(),
    /** 只在這些 SIM 訂閱上生效；空集合表示不限制。 */
    val subscriptionIds: Set<Int> = emptySet(),
    /** 只在這些 SIM 卡槽上生效；空集合表示不限制。 */
    val simSlots: Set<Int> = emptySet(),
) {
    val hasNetworkScope: Boolean
        get() = networkKinds.isNotEmpty() || subscriptionIds.isNotEmpty() || simSlots.isNotEmpty()

    fun matchesNetwork(network: NetworkContext): Boolean {
        if (networkKinds.isNotEmpty() && network.kind !in networkKinds) return false
        if (subscriptionIds.isNotEmpty()) {
            val subscription = network.subscriptionId
            if (subscription != null) {
                if (subscription !in subscriptionIds) return false
            } else {
                if (simSlots.isEmpty()) return false
                if (network.simSlotIndex == null || network.simSlotIndex !in simSlots) return false
            }
        } else if (simSlots.isNotEmpty()) {
            if (network.simSlotIndex == null || network.simSlotIndex !in simSlots) return false
        }
        return true
    }

    companion object {
        val Any = RuleConditions()
    }
}

data class ProcessRef(val pid: Int, val startTimeTicks: Long)

data class Rule(
    val id: String,
    val profileId: String? = null,
    val app: AppMatcher? = null,
    val conditions: RuleConditions = RuleConditions.Any,
    val action: VerdictAction,
    val lifetime: RuleLifetime,
    val expiresAt: Long? = null,
    val boundProcess: ProcessRef? = null,
    val source: RuleSource,
    val enabled: Boolean = true,
    val manualPriority: Int = 0,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastHitAt: Long? = null,
    val hitCount: Long = 0,
) {
    val isPersistent: Boolean
        get() = lifetime != RuleLifetime.Once &&
            lifetime != RuleLifetime.UntilRestart &&
            lifetime != RuleLifetime.UntilAppExit

    fun isLiveAt(now: Long): Boolean =
        lifetime != RuleLifetime.Timed || expiresAt == null || expiresAt > now
}

data class AutoActivateCondition(
    val kind: NetworkKind? = null,
    val ssids: List<String> = emptyList(),
    val networkIds: List<String> = emptyList(),
    val subscriptionIds: List<Int> = emptyList(),
    val simSlots: List<Int> = emptyList(),
) {
    val isEmpty: Boolean
        get() = kind == null && ssids.isEmpty() && networkIds.isEmpty() &&
            subscriptionIds.isEmpty() && simSlots.isEmpty()

    fun matches(context: NetworkContext): Boolean {
        if (isEmpty) return false
        if (kind != null && context.kind != kind) return false
        if (ssids.isNotEmpty() && (context.ssid == null || ssids.none { it.equals(context.ssid, ignoreCase = true) })) {
            return false
        }
        if (networkIds.isNotEmpty() && (context.networkId == null || !networkIds.contains(context.networkId))) {
            return false
        }
        if (subscriptionIds.isNotEmpty()) {
            val subscription = context.subscriptionId
            if (subscription != null) {
                if (subscription !in subscriptionIds) return false
            } else {
                if (simSlots.isEmpty()) return false
                if (context.simSlotIndex == null || context.simSlotIndex !in simSlots) return false
            }
        } else if (simSlots.isNotEmpty()) {
            if (context.simSlotIndex == null || context.simSlotIndex !in simSlots) return false
        }
        return true
    }
}

data class Profile(
    val id: String,
    val name: String,
    val mode: FilterMode = FilterMode.Ask,
    val sortOrder: Int = 0,
    val autoActivate: AutoActivateCondition? = null,
) {
    companion object {
        const val DEFAULT_ID = "00000000-0000-0000-0000-000000000001"
        val Default = Profile(DEFAULT_ID, "默認", FilterMode.Ask, 0)
    }
}

data class NetworkContext(
    val kind: NetworkKind = NetworkKind.None,
    val ssid: String? = null,
    val networkId: String? = null,
    val displayName: String? = null,
    val subscriptionId: Int? = null,
    val simSlotIndex: Int? = null,
    val carrierName: String? = null,
    val isMetered: Boolean? = null,
) {
    val fingerprint: String
        get() = "${kind.name}|${ssid.orEmpty()}|${networkId.orEmpty()}|${subscriptionId ?: -1}|${simSlotIndex ?: -1}"

    companion object {
        val None = NetworkContext()
    }
}

data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: String?,
    val displayName: String?,
) {
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: carrierName?.takeIf { it.isNotBlank() }?.let { "SIM ${slotIndex + 1} · $it" }
            ?: "SIM ${slotIndex + 1}"
}

data class UserSettings(
    val promptTimeoutSeconds: Int = 30,
    val promptDefaultAction: VerdictAction = VerdictAction.Deny,
    val maxPromptLifetimeSeconds: Int = 300,
    val onceFallbackSeconds: Int = 60,
    val maxPendingFlows: Int = 1000,
    val allowIcmpByDefault: Boolean = true,
    val logRetentionDays: Int = 30,
    /** 詢問選「限時」時的預設時長（分鐘）。 */
    val promptTimedMinutes: Int = 60,
    /** 系統應用（預裝）在沒有明確規則時默認放行。 */
    val allowSystemAppsByDefault: Boolean = false,
    /** 受信任的簽章者（Android 為簽章 SHA-256）；其應用在沒有明確規則時默認放行。 */
    val trustedSigners: Set<String> = emptySet(),
) {
    fun validated(): UserSettings = copy(
        promptTimeoutSeconds = promptTimeoutSeconds.coerceIn(5, 300),
        maxPromptLifetimeSeconds = maxPromptLifetimeSeconds.coerceIn(10, 1800),
        onceFallbackSeconds = onceFallbackSeconds.coerceIn(10, 600),
        maxPendingFlows = maxPendingFlows.coerceIn(10, 100_000),
        logRetentionDays = logRetentionDays.coerceIn(1, 3650),
        promptTimedMinutes = promptTimedMinutes.coerceIn(5, 1440),
    )

    companion object {
        val Default = UserSettings()
    }
}

data class RuntimeState(
    val activeProfileId: String = Profile.DEFAULT_ID,
    val mode: FilterMode = FilterMode.Ask,
    val modeBeforeBlockAll: FilterMode? = null,
    val doNotDisturbUntil: Long? = null,
) {
    fun isDoNotDisturb(now: Long): Boolean = doNotDisturbUntil != null && doNotDisturbUntil > now
}

data class FocusSession(
    val startedAt: Long,
    val endsAt: Long,
    val allowedPackages: Set<String> = emptySet(),
) {
    fun isActive(now: Long): Boolean = endsAt > now
}

data class FlowInfo(
    val flowId: Long,
    val direction: Direction,
    val protocol: L4Protocol,
    val local: InetSocketAddress,
    val remote: InetSocketAddress,
    val app: AppIdentity? = null,
    val executablePath: String? = null,
    val userId: String? = null,
    val engineHostname: String? = null,
    val timestamp: Long,
    val origin: FlowOrigin = FlowOrigin.Pending,
    val tcpFlags: Int = 0,
    val tcpSequence: Long = 0,
)

data class Verdict(
    val action: VerdictAction,
    val reason: VerdictReason,
    val ruleId: String? = null,
) {
    companion object {
        fun allow(reason: VerdictReason, ruleId: String? = null) = Verdict(VerdictAction.Allow, reason, ruleId)
        fun deny(reason: VerdictReason, ruleId: String? = null) = Verdict(VerdictAction.Deny, reason, ruleId)
    }
}

data class RuleMatch(
    val rule: Rule? = null,
    val tamperedRule: Rule? = null,
) {
    val isMatch: Boolean get() = rule != null

    companion object {
        val None = RuleMatch()
    }
}

enum class AppMatchOutcome {
    NoMatch,
    Match,
    Tampered,
}

data class DomainCandidates(val names: List<String>) {
    val isEmpty: Boolean get() = names.isEmpty()
    val primary: String? get() = names.firstOrNull()

    companion object {
        val Empty = DomainCandidates(emptyList())
    }
}

data class PromptFlowSnapshot(
    val flowId: Long,
    val direction: Direction,
    val protocol: L4Protocol,
    val local: InetSocketAddress,
    val remote: InetSocketAddress,
    val domains: List<String>,
    val timestamp: Long,
)

data class PromptSnapshot(
    val id: String,
    val app: AppIdentity?,
    val executablePath: String?,
    val process: ProcessRef?,
    val targetUserId: String?,
    val flows: List<PromptFlowSnapshot>,
    val createdAt: Long,
    val deadline: Long,
    val isTamperAlert: Boolean,
    val isConnectionHeld: Boolean,
)

data class PromptEvent(val kind: PromptEventKind, val prompt: PromptSnapshot)

data class PromptDecision(
    val action: VerdictAction,
    val scope: PromptScope = PromptScope.AnyConnection,
    val lifetime: RuleLifetime = RuleLifetime.Permanent,
    val durationSeconds: Int? = null,
    val selectedDomain: String? = null,
    val applyToAllFlows: Boolean = true,
)

data class PromptAnswerResult(
    val found: Boolean,
    val createdRuleId: String?,
    val resolvedFlows: Int,
    val remainingFlows: Int,
)

data class PromptBatchResult(
    val answered: Int,
    val resolvedFlows: Int,
    val remainingPrompts: Int,
)

data class AppRouting(
    val uid: Int,
    val packageName: String,
    val packages: List<String>,
    val decision: AppRoutingDecision,
    val reason: AppRoutingReason,
    val ruleId: String? = null,
    val tamperedRuleId: String? = null,
)

data class SkippedRule(val ruleId: String, val reason: RuleSkipReason)

data class VpnRoutingPlan(
    val mode: FilterMode,
    val bypassPackages: Set<String>,
    val byUid: Map<Int, AppRouting>,
    val skippedRules: List<SkippedRule>,
) {
    fun find(uid: Int): AppRouting? = byUid[uid]

    companion object {
        val Empty = VpnRoutingPlan(FilterMode.Ask, emptySet(), emptyMap(), emptyList())
    }
}

data class InstalledApp(
    val packageName: String,
    val uid: Int,
    val label: String,
    val isSystem: Boolean,
    val versionCode: Long,
    val lastUpdated: Long,
    val signerSha256: String?,
)

data class AppEntry(
    val app: InstalledApp,
    val decision: AppDecision,
    val effective: AppRoutingDecision,
    val reason: AppRoutingReason,
    val expiresAt: Long?,
    val isTemporary: Boolean,
    val isTampered: Boolean,
)

data class ConnectionEvent(
    val timestamp: Long,
    val appId: Long?,
    val appPath: String?,
    val appName: String?,
    val direction: Direction,
    val protocol: L4Protocol,
    val local: InetSocketAddress,
    val remote: InetSocketAddress,
    val hostname: String?,
    val verdict: Verdict,
)

data class LogEntry(
    val id: Long,
    val firstTs: Long,
    val lastTs: Long,
    val appId: Long?,
    val appPath: String?,
    val appName: String?,
    val direction: Direction,
    val protocol: L4Protocol,
    val remoteIp: String,
    val remotePort: Int,
    val hostname: String?,
    val verdict: Verdict,
    val count: Int,
    val local: InetSocketAddress?,
)

data class LogQuery(
    val from: Long? = null,
    val to: Long? = null,
    val appId: Long? = null,
    val action: VerdictAction? = null,
    val reason: VerdictReason? = null,
    val keyword: String? = null,
    val beforeId: Long? = null,
    val limit: Int = 200,
)

data class ActivityBucket(val ts: Long, val allowed: Long, val blocked: Long)
data class ActivityTopItem(
    val key: String,
    val label: String,
    val appId: Long?,
    val allowed: Long,
    val blocked: Long,
    val path: String? = null,
)

data class ActivitySummary(
    val allowed: Long,
    val blocked: Long,
    val appCount: Int,
    val destinationCount: Int,
    val timeline: List<ActivityBucket>,
    val topApps: List<ActivityTopItem>,
    val topDestinations: List<ActivityTopItem>,
)

data class ActivitySummaryQuery(
    val from: Long,
    val to: Long,
    val bucketMillis: Long,
    val topCount: Int = 5,
)

data class AppActivity(
    val appId: Long?,
    val appPath: String?,
    val appName: String?,
    val allowed: Long,
    val blocked: Long,
    val lastSeen: Long,
)

data class TrafficBucket(val ts: Long, val bytesIn: Long, val bytesOut: Long)
data class AppTraffic(val appId: Long, val bytesIn: Long, val bytesOut: Long)
data class TrafficSummary(
    val bytesIn: Long,
    val bytesOut: Long,
    val timeline: List<TrafficBucket>,
    val topApps: List<AppTraffic>,
)

data class TrafficUsage(
    val bytesIn: Long,
    val bytesOut: Long,
    val byPackage: Map<String, Pair<Long, Long>>,
    val byNetwork: Map<NetworkKey, Pair<Long, Long>> = emptyMap(),
) {
    companion object {
        val Empty = TrafficUsage(0, 0, emptyMap())
    }
}

data class NetworkKey(
    val kind: NetworkKind,
    val subscriptionId: Int? = null,
)

data class QuietPeriodSummary(
    val startedAt: Long,
    val endedAt: Long,
    val blockedCount: Int,
    val topApps: List<Pair<String, Int>>,
)

data class RuleImportPreview(
    val total: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    val unchanged: Int,
    val ineffective: Int,
)

data class RuleImportPlan(
    val toAdd: List<Rule>,
    val toUpdate: List<Rule>,
    val toRemove: List<Rule>,
    val unchanged: Int,
)

data class RuleGroup(
    val key: String,
    val label: String,
    val packageName: String?,
    val rules: List<Rule>,
)

data class NetworkSummary(
    val kind: NetworkKind,
    val metered: Boolean,
    val validated: Boolean,
)
