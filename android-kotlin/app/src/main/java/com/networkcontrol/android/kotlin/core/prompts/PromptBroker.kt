package com.networkcontrol.android.kotlin.core.prompts

import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.matching.NetworkZones
import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.DomainCandidates
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.FlowOrigin
import com.networkcontrol.android.kotlin.core.model.PromptAnswerResult
import com.networkcontrol.android.kotlin.core.model.PromptBatchResult
import com.networkcontrol.android.kotlin.core.model.PromptDecision
import com.networkcontrol.android.kotlin.core.model.PromptEvent
import com.networkcontrol.android.kotlin.core.model.PromptEventKind
import com.networkcontrol.android.kotlin.core.model.PromptFlowSnapshot
import com.networkcontrol.android.kotlin.core.model.PromptScope
import com.networkcontrol.android.kotlin.core.model.PromptSnapshot
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.Verdict
import com.networkcontrol.android.kotlin.core.model.VerdictReason
import com.networkcontrol.android.kotlin.core.rules.CompiledRule
import com.networkcontrol.android.kotlin.core.rules.PromptRuleFactory
import com.networkcontrol.android.kotlin.core.rules.RuleService
import com.networkcontrol.android.kotlin.core.settings.RuntimeStateService
import com.networkcontrol.android.kotlin.core.settings.SettingsService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class PromptBroker(
    private val rules: RuleService,
    private val runtime: RuntimeStateService,
    private val settings: SettingsService,
    private val scope: CoroutineScope,
) {
    private val gate = Mutex()
    private val prompts = linkedMapOf<String, PendingPrompt>()
    private val byApp = mutableMapOf<MergeKey, PendingPrompt>()
    private var pendingFlows = 0

    private val _pending = MutableStateFlow<List<PromptSnapshot>>(emptyList())
    val pending: StateFlow<List<PromptSnapshot>> = _pending.asStateFlow()
    private val sessionSuppressed = HashSet<String>()
    @Volatile
    private var sessionSuppressedCount = 0
    private val _busy = MutableStateFlow(false)

    /** 有待決詢問或「稍後」抑制時為 true；PromptRouter 只在這時輪詢前台應用。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<PromptEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    val pendingCount: Int get() = pendingFlows
    val engineHoldsConnections: Boolean = false

    fun hasSubscriberFor(userId: String?): Boolean = true

    suspend fun request(
        flow: FlowInfo,
        app: AppIdentity?,
        domains: DomainCandidates,
        isTamperAlert: Boolean,
        holdConnection: Boolean = true,
    ): CompletableDeferred<Verdict> {
        val currentSettings = settings.state.value
        val key = MergeKey(
            userId = flow.userId,
            appPath = app?.path,
            unknownPath = if (app == null) flow.executablePath else null,
            tamper = isTamperAlert,
        )
        var event: PromptEvent? = null
        val deferred = CompletableDeferred<Verdict>()
        gate.withLock {
            val suppressKey = suppressionKey(app?.path ?: flow.executablePath)
            if (suppressKey in sessionSuppressed) {
                deferred.complete(Verdict(settings.state.value.promptDefaultAction, VerdictReason.Mode))
                return@withLock
            }
            if (holdConnection && pendingFlows >= currentSettings.maxPendingFlows) {
                deferred.complete(Verdict(currentSettings.promptDefaultAction, VerdictReason.FailSafe))
                return@withLock
            }

            val pendingFlow = PendingFlow(flow, domains, deferred)
            val existing = byApp[key]
            if (existing != null && !holdConnection && existing.isRedundant(flow, domains)) {
                // 黑洞 / 轉發模式的連線已經被丟棄，同一目標重複加入只會讓詢問與快照無限增長。
                deferred.complete(Verdict(currentSettings.promptDefaultAction, VerdictReason.Mode))
                return@withLock
            }
            if (existing != null) {
                existing.flows += pendingFlow
                event = PromptEvent(PromptEventKind.Updated, existing.snapshot())
            } else {
                val now = Time.now()
                val prompt = PendingPrompt(
                    id = UUID.randomUUID().toString(),
                    key = key,
                    app = app,
                    firstFlow = flow,
                    isTamperAlert = isTamperAlert,
                    isConnectionHeld = flow.origin == FlowOrigin.Pending,
                    createdAt = now,
                    deadline = now + currentSettings.promptTimeoutSeconds * 1000L,
                )
                prompt.flows += pendingFlow
                prompt.timer = scheduleTimeout(prompt)
                prompts[prompt.id] = prompt
                byApp[key] = prompt
                event = PromptEvent(PromptEventKind.Added, prompt.snapshot())
            }
            pendingFlows += if (holdConnection) 1 else 0
            refreshPendingLocked()
        }
        event?.let { _events.emit(it) }
        com.networkcontrol.android.kotlin.platform.AppLog.i(
            "prompt",
            "request app=${app?.path} flows=${pendingFlows} tamper=$isTamperAlert",
        )
        return deferred
    }

    suspend fun answer(promptId: String, decision: PromptDecision): PromptAnswerResult {
        var head: PendingFlow? = null
        var app: AppIdentity? = null
        gate.withLock {
            val prompt = prompts[promptId]
            if (prompt != null && prompt.flows.isNotEmpty()) {
                head = prompt.flows.first()
                app = prompt.app
            }
        }
        val first = head ?: return PromptAnswerResult(false, null, 0, 0)
        val now = Time.now()
        val effectiveApp = app ?: first.flow.executablePath
            ?.takeIf { it.startsWith("android-app://") && !it.startsWith("android-app://uid:") }
            ?.let {
                AppIdentity(
                    path = it,
                    displayName = it.removePrefix("android-app://"),
                )
            }
        val rule = PromptRuleFactory.createRule(
            decision = decision,
            flow = first.flow,
            app = effectiveApp,
            domains = first.domains,
            profileId = runtime.activeProfileId,
            now = now,
            onceFallbackSeconds = settings.state.value.onceFallbackSeconds,
        )
        val created = rule?.let { rules.add(it) }

        val coverageRule = Rule(
            id = "",
            conditions = PromptRuleFactory.createConditions(decision, first.flow, first.domains),
            action = decision.action,
            lifetime = RuleLifetime.Once,
            source = RuleSource.Prompt,
            createdAt = 0,
            updatedAt = 0,
        )
        val coverage = CompiledRule(coverageRule)

        var resolved: List<PendingFlow> = emptyList()
        var remaining = 0
        var event: PromptEvent? = null
        gate.withLock {
            val prompt = prompts[promptId] ?: return@withLock
            resolved = if (decision.applyToAllFlows) {
                prompt.flows.filter {
                    it === first || coverage.matchConditions(
                        it.flow,
                        it.domains,
                        NetworkZones.classify(it.flow.remote.address),
                    )
                }
            } else {
                listOf(first)
            }
            prompt.flows.removeAll(resolved.toSet())
            pendingFlows = (pendingFlows - resolved.count { it.flow.origin == FlowOrigin.Pending }).coerceAtLeast(0)
            remaining = prompt.flows.size
            if (remaining == 0) {
                removeLocked(prompt)
                event = PromptEvent(PromptEventKind.Removed, prompt.snapshot())
            } else {
                event = PromptEvent(PromptEventKind.Updated, prompt.snapshot())
            }
            refreshPendingLocked()
        }

        val verdict = Verdict(decision.action, VerdictReason.Prompt, created?.id)
        resolved.forEach { it.deferred.complete(verdict) }
        event?.let { _events.emit(it) }
        com.networkcontrol.android.kotlin.platform.AppLog.i(
            "prompt",
            "answer ${decision.action} ${decision.lifetime} resolved=${resolved.size} remaining=$remaining",
        )
        return PromptAnswerResult(true, created?.id, resolved.size, remaining)
    }

    /**
     * 批量回答：一次建立所有規則（只發布一次規則集），再逐個解決詢問。
     * 逾時或已被移除的詢問會被跳過。
     */
    suspend fun answerMany(
        promptIds: Collection<String>,
        decision: PromptDecision,
    ): PromptBatchResult {
        val selections = mutableListOf<BatchSelection>()
        gate.withLock {
            promptIds.distinct().forEach { id ->
                val prompt = prompts[id] ?: return@forEach
                val first = prompt.flows.firstOrNull() ?: return@forEach
                selections += BatchSelection(prompt.id, first, prompt.app, first.domains)
            }
        }
        if (selections.isEmpty()) return PromptBatchResult(0, 0, prompts.size)

        val now = Time.now()
        val toAdd = mutableListOf<Rule>()
        val ruleIndexes = mutableListOf<Int>()
        selections.forEachIndexed { index, selection ->
            PromptRuleFactory.createRule(
                decision = decision,
                flow = selection.first.flow,
                app = selection.app ?: selection.first.flow.executablePath
                    ?.takeIf { it.startsWith("android-app://") && !it.startsWith("android-app://uid:") }
                    ?.let { AppIdentity(path = it, displayName = it.removePrefix("android-app://")) },
                domains = selection.domains,
                profileId = runtime.activeProfileId,
                now = now,
                onceFallbackSeconds = settings.state.value.onceFallbackSeconds,
            )?.let {
                toAdd += it
                ruleIndexes += index
            }
        }
        val saved = if (toAdd.isEmpty()) emptyList() else rules.addRange(toAdd)
        val ruleIds = mutableMapOf<Int, String>()
        ruleIndexes.forEachIndexed { order, selectionIndex ->
            saved.getOrNull(order)?.let { ruleIds[selectionIndex] = it.id }
        }

        var resolvedFlows = 0
        val verdicts = mutableListOf<Pair<List<PendingFlow>, Verdict>>()
        val events = mutableListOf<PromptEvent>()
        gate.withLock {
            selections.forEachIndexed { index, selection ->
                val prompt = prompts[selection.promptId] ?: return@forEachIndexed
                val first = prompt.flows.firstOrNull { it === selection.first }
                    ?: prompt.flows.firstOrNull()
                    ?: return@forEachIndexed
                val coverageRule = Rule(
                    id = "",
                    conditions = PromptRuleFactory.createConditions(decision, first.flow, first.domains),
                    action = decision.action,
                    lifetime = RuleLifetime.Once,
                    source = RuleSource.Prompt,
                    createdAt = 0,
                    updatedAt = 0,
                )
                val coverage = CompiledRule(coverageRule)
                val resolved = if (decision.applyToAllFlows) {
                    prompt.flows.filter {
                        it === first || coverage.matchConditions(
                            it.flow,
                            it.domains,
                            NetworkZones.classify(it.flow.remote.address),
                        )
                    }
                } else {
                    listOf(first)
                }
                prompt.flows.removeAll(resolved.toSet())
                pendingFlows = (pendingFlows - resolved.count { it.flow.origin == FlowOrigin.Pending }).coerceAtLeast(0)
                resolvedFlows += resolved.size
                if (prompt.flows.isEmpty()) {
                    removeLocked(prompt)
                    events += PromptEvent(PromptEventKind.Removed, prompt.snapshot())
                } else {
                    events += PromptEvent(PromptEventKind.Updated, prompt.snapshot())
                }
                verdicts += resolved to Verdict(decision.action, VerdictReason.Prompt, ruleIds[index])
            }
            refreshPendingLocked()
        }
        verdicts.forEach { (flows, verdict) -> flows.forEach { it.deferred.complete(verdict) } }
        events.forEach { _events.emit(it) }
        com.networkcontrol.android.kotlin.platform.AppLog.i(
            "prompt",
            "batch ${decision.action} ${decision.lifetime} prompts=${selections.size} resolved=$resolvedFlows",
        )
        return PromptBatchResult(selections.size, resolvedFlows, prompts.size)
    }

    suspend fun pauseTimer(promptId: String): Boolean {
        var changed = false
        var event: PromptEvent? = null
        gate.withLock {
            val prompt = prompts[promptId] ?: return@withLock
            val now = Time.now()
            val limit = prompt.createdAt + settings.state.value.maxPromptLifetimeSeconds * 1000L
            val deadline = minOf(now + settings.state.value.promptTimeoutSeconds * 1000L, limit)
            if (deadline <= prompt.deadline) return@withLock
            prompt.deadline = deadline
            prompt.timer?.cancel()
            prompt.timer = scheduleTimeout(prompt)
            changed = true
            event = PromptEvent(PromptEventKind.Updated, prompt.snapshot())
        }
        event?.let { _events.emit(it) }
        return changed
    }

    suspend fun cancelAll() {
        val action = settings.state.value.promptDefaultAction
        val all: List<PendingPrompt>
        gate.withLock {
            all = prompts.values.toList()
            all.forEach { removeLocked(it) }
            pendingFlows = 0
            refreshPendingLocked()
        }
        all.forEach { prompt ->
            prompt.flows.forEach {
                it.deferred.complete(Verdict(action, VerdictReason.Cancelled))
            }
        }
    }

    /** 使用者選擇「稍後」後，同一應用在本次前台會話內不再詢問；切換出去再回來會重新詢問。 */
    suspend fun dismiss(promptId: String): Boolean {
        var prompt: PendingPrompt? = null
        gate.withLock {
            prompt = prompts[promptId] ?: return@withLock
            removeLocked(prompt!!)
            pendingFlows = (pendingFlows - prompt!!.flows.count { it.flow.origin == FlowOrigin.Pending }).coerceAtLeast(0)
            val key = suppressionKey(prompt!!.app?.path ?: prompt!!.firstFlow.executablePath)
            if (sessionSuppressed.add(key)) sessionSuppressedCount = sessionSuppressed.size
            refreshPendingLocked()
        }
        val dismissed = prompt ?: return false
        dismissed.flows.forEach {
            it.deferred.complete(Verdict(settings.state.value.promptDefaultAction, VerdictReason.Mode))
        }
        _events.emit(PromptEvent(PromptEventKind.Removed, dismissed.snapshot()))
        return true
    }

    suspend fun dismissMany(promptIds: Collection<String>): Int {
        var count = 0
        promptIds.distinct().forEach { id ->
            if (dismiss(id)) count++
        }
        return count
    }

    /** 應用回到前台時呼叫，允許它再次觸發詢問。 */
    suspend fun clearSessionSuppression(packageName: String) {
        gate.withLock {
            if (sessionSuppressed.remove(packageName)) {
                sessionSuppressedCount = sessionSuppressed.size
                refreshBusyLocked()
            }
        }
    }

    fun hasSessionSuppressions(): Boolean = sessionSuppressedCount > 0

    /** 沒有使用情況權限時的兜底：清空本次會話抑制。 */
    suspend fun clearAllSessionSuppressions() {
        gate.withLock {
            if (sessionSuppressed.isNotEmpty()) {
                sessionSuppressed.clear()
                sessionSuppressedCount = 0
                refreshBusyLocked()
            }
        }
    }

    private fun suppressionKey(path: String?): String =
        path?.removePrefix("android-app://")?.takeIf { it.isNotBlank() } ?: "unknown"

    fun getPending(): List<PromptSnapshot> = _pending.value

    private fun scheduleTimeout(prompt: PendingPrompt): Job = scope.launch {
        delay((prompt.deadline - Time.now()).coerceAtLeast(0))
        onTimeout(prompt.id)
    }

    private suspend fun onTimeout(promptId: String) {
        var prompt: PendingPrompt? = null
        gate.withLock {
            prompt = prompts[promptId] ?: return@withLock
            prompt!!.timer = null
            removeLocked(prompt!!)
            pendingFlows = (pendingFlows - prompt!!.flows.count { it.flow.origin == FlowOrigin.Pending }).coerceAtLeast(0)
            refreshPendingLocked()
        }
        val timedOut = prompt ?: return
        val verdict = Verdict(settings.state.value.promptDefaultAction, VerdictReason.PromptTimeout)
        timedOut.flows.forEach { it.deferred.complete(verdict) }
        _events.emit(PromptEvent(PromptEventKind.Removed, timedOut.snapshot()))
        com.networkcontrol.android.kotlin.platform.AppLog.i("prompt", "timeout flows=${timedOut.flows.size}")
    }

    private fun removeLocked(prompt: PendingPrompt) {
        prompts.remove(prompt.id)
        byApp.remove(prompt.key)
        prompt.timer?.cancel()
        prompt.timer = null
    }

    private fun refreshPendingLocked() {
        _pending.value = prompts.values.map { it.snapshot() }
        refreshBusyLocked()
    }

    private fun refreshBusyLocked() {
        _busy.value = prompts.isNotEmpty() || sessionSuppressed.isNotEmpty()
    }

    private companion object {
        /** 不持有連線的詢問最多保留的連線數；超過的只計入記錄，不再加入詢問。 */
        const val MAX_DETACHED_FLOWS = 50
    }

    private data class MergeKey(
        val userId: String?,
        val appPath: String?,
        val unknownPath: String?,
        val tamper: Boolean,
    )

    private class PendingFlow(
        val flow: FlowInfo,
        val domains: DomainCandidates,
        val deferred: CompletableDeferred<Verdict>,
    )

    private class BatchSelection(
        val promptId: String,
        val first: PendingFlow,
        val app: AppIdentity?,
        val domains: DomainCandidates,
    )

    private class PendingPrompt(
        val id: String,
        val key: MergeKey,
        val app: AppIdentity?,
        val firstFlow: FlowInfo,
        val isTamperAlert: Boolean,
        val isConnectionHeld: Boolean,
        val createdAt: Long,
        var deadline: Long,
        val flows: MutableList<PendingFlow> = mutableListOf(),
        var timer: Job? = null,
    ) {
        /** 已有相同協定、目標與域名的連線，或不持有連線的流已達上限。 */
        fun isRedundant(flow: FlowInfo, domains: DomainCandidates): Boolean {
            if (flows.size >= MAX_DETACHED_FLOWS) return true
            return flows.any {
                it.flow.protocol == flow.protocol &&
                    it.flow.remote == flow.remote &&
                    it.domains.names == domains.names
            }
        }

        fun snapshot(): PromptSnapshot = PromptSnapshot(
            id = id,
            app = app,
            executablePath = app?.path ?: firstFlow.executablePath,
            process = null,
            targetUserId = key.userId,
            flows = flows.map {
                PromptFlowSnapshot(
                    flowId = it.flow.flowId,
                    direction = it.flow.direction,
                    protocol = it.flow.protocol,
                    local = it.flow.local,
                    remote = it.flow.remote,
                    domains = it.domains.names,
                    timestamp = it.flow.timestamp,
                )
            },
            createdAt = createdAt,
            deadline = deadline,
            isTamperAlert = isTamperAlert,
            isConnectionHeld = isConnectionHeld && firstFlow.origin == FlowOrigin.Pending,
        )
    }
}
