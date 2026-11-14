package com.networkcontrol.android.kotlin.core.rules

import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleEndReason
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.settings.RuntimeStateService
import com.networkcontrol.android.kotlin.core.storage.RuleHitDelta
import com.networkcontrol.android.kotlin.core.storage.RuleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RuleChangeKind { Added, Updated, Removed }

data class RuleChange(
    val kind: RuleChangeKind,
    val rule: Rule,
    val endReason: RuleEndReason? = null,
)

class RuleService(
    private val store: RuleStore,
    private val runtime: RuntimeStateService,
    private val scope: CoroutineScope,
) {
    private val gate = Mutex()
    private val rules = linkedMapOf<String, Rule>()
    private val hits = mutableMapOf<String, HitCounter>()
    private var version = 0L
    private var started = false

    private val _ruleSet = MutableStateFlow(RuleSet.Empty)
    val ruleSet: StateFlow<RuleSet> = _ruleSet.asStateFlow()

    private val _changes = MutableSharedFlow<RuleChange>(extraBufferCapacity = 64)
    val changes = _changes.asSharedFlow()

    val all: List<Rule> get() = rules.values.toList()

    fun find(id: String): Rule? = rules[id]

    suspend fun initialize() {
        store.loadAll().forEach { rule ->
            rules[rule.id] = rule
            hits[rule.id] = HitCounter(rule.hitCount, rule.lastHitAt)
        }
        publish()
        started = true
        scope.launch {
            runtime.state.collect {
                if (started) publish()
            }
        }
    }

    suspend fun add(rule: Rule): Rule {
        validate(rule)
        gate.withLock {
            if (rule.isPersistent) store.upsert(rule)
            rules[rule.id] = rule
            hits.putIfAbsent(rule.id, HitCounter(0, null))
            publishLocked()
        }
        _changes.emit(RuleChange(RuleChangeKind.Added, rule))
        return rule
    }

    suspend fun addRange(items: List<Rule>) {
        if (items.isEmpty()) return
        items.forEach(::validate)
        gate.withLock {
            items.forEach { if (it.isPersistent) store.upsert(it) }
            items.forEach {
                rules[it.id] = it
                hits.putIfAbsent(it.id, HitCounter(0, null))
            }
            publishLocked()
        }
        items.forEach { _changes.emit(RuleChange(RuleChangeKind.Added, it)) }
    }

    suspend fun update(rule: Rule): Rule {
        validate(rule)
        val updated: Rule
        gate.withLock {
            val existing = rules[rule.id] ?: throw NoSuchElementException("規則 ${rule.id} 不存在。")
            val candidate = if (existing.source == RuleSource.Builtin) {
                existing.copy(enabled = rule.enabled)
            } else {
                rule
            }
            updated = candidate.copy(createdAt = existing.createdAt, updatedAt = Time.now())
            if (updated.isPersistent) store.upsert(updated)
            rules[updated.id] = updated
            publishLocked()
        }
        _changes.emit(RuleChange(RuleChangeKind.Updated, updated))
        return updated
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Rule {
        val existing = rules[id] ?: throw NoSuchElementException("規則 $id 不存在。")
        return if (existing.enabled == enabled) existing else update(existing.copy(enabled = enabled))
    }

    suspend fun remove(ids: Collection<String>, reason: RuleEndReason): Int {
        var removed: List<Rule> = emptyList()
        gate.withLock {
            removed = ids.mapNotNull { rules[it] }
                .filter { it.source != RuleSource.Builtin }
            if (removed.isEmpty()) return 0
            val persistent = removed.filter { it.isPersistent }
            if (persistent.isNotEmpty()) store.delete(persistent, reason, Time.now())
            removed.forEach {
                rules.remove(it.id)
                hits.remove(it.id)
            }
            publishLocked()
        }
        removed.forEach { _changes.emit(RuleChange(RuleChangeKind.Removed, it, reason)) }
        return removed.size
    }

    suspend fun removeExpired() {
        val now = Time.now()
        val expired = rules.values.filter { !it.isLiveAt(now) }.map { it.id }
        if (expired.isNotEmpty()) remove(expired, RuleEndReason.Expired)
    }

    fun recordHit(ruleId: String) {
        hits[ruleId]?.hit(Time.now())
    }

    fun getHitStats(ruleId: String): Pair<Long, Long?> {
        val counter = hits[ruleId] ?: return 0L to null
        return counter.total to counter.lastHitAt
    }

    suspend fun flushHits() {
        val deltas = mutableListOf<RuleHitDelta>()
        hits.forEach { (id, counter) ->
            val rule = rules[id] ?: return@forEach
            if (!rule.isPersistent) return@forEach
            counter.takeDelta()?.let { (count, last) ->
                deltas += RuleHitDelta(id, count, last)
            }
        }
        if (deltas.isNotEmpty()) store.recordHits(deltas)
    }

    fun publish() {
        publishLocked()
    }

    private fun publishLocked() {
        val now = Time.now()
        val profileId = runtime.activeProfileId
        val active = rules.values.filter {
            it.enabled &&
                it.lifetime != RuleLifetime.Once &&
                it.isLiveAt(now) &&
                (it.profileId == null || it.profileId == profileId)
        }
        version++
        _ruleSet.value = RuleSet(active, version)
    }

    private fun validate(rule: Rule) {
        require(rule.lifetime != RuleLifetime.Timed || rule.expiresAt != null) {
            "限時規則必須設定過期時間。"
        }
        require(rule.lifetime != RuleLifetime.UntilProcessExit || rule.boundProcess != null) {
            "直到進程退出的規則必須綁定進程。"
        }
        require(rule.app == null || rule.app.pathPattern.isNotBlank()) {
            "應用路徑不能為空。"
        }
    }

    private class HitCounter(total: Long, lastHitAt: Long?) {
        private var totalCount = total
        private var pending = 0L
        var lastHitAt: Long? = lastHitAt
            private set

        val total: Long get() = totalCount

        fun hit(now: Long) {
            totalCount++
            pending++
            lastHitAt = now
        }

        fun takeDelta(): Pair<Long, Long>? {
            if (pending == 0L || lastHitAt == null) return null
            val delta = pending to lastHitAt!!
            pending = 0
            return delta
        }
    }
}
