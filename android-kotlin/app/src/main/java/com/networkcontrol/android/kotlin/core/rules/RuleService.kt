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
        val now = Time.now()
        val loaded = store.loadAll()
        val duplicates = mutableListOf<Rule>()
        loaded.groupBy { RuleIdentity.key(it) }.values.forEach { group ->
            val keeper = group.maxWith(
                compareBy<Rule> { it.updatedAt }
                    .thenBy { it.manualPriority }
                    .thenBy { it.id },
            )
            val totalHits = group.sumOf { it.hitCount }
            val lastHitAt = group.mapNotNull { it.lastHitAt }.maxOrNull()
            rules[keeper.id] = keeper
            hits[keeper.id] = HitCounter(totalHits, lastHitAt)
            if (group.size > 1) {
                runCatching {
                    store.upsert(keeper.copy(hitCount = totalHits, lastHitAt = lastHitAt))
                }
            }
            group.filter { it.id != keeper.id }.forEach {
                duplicates += it
            }
        }
        if (duplicates.isNotEmpty()) {
            runCatching { store.delete(duplicates, RuleEndReason.Replaced, now) }
            com.networkcontrol.android.kotlin.platform.AppLog.i(
                "rules",
                "merged ${duplicates.size} duplicate rules on startup",
            )
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
        val now = Time.now()
        var result: Rule? = null
        var added = true
        gate.withLock {
            val outcome = addLocked(rule, now)
            result = outcome.first
            added = outcome.second
            publishLocked()
        }
        val saved = result ?: rule
        _changes.emit(RuleChange(if (added) RuleChangeKind.Added else RuleChangeKind.Updated, saved))
        return saved
    }

    suspend fun addRange(items: List<Rule>): List<Rule> {
        if (items.isEmpty()) return emptyList()
        items.forEach(::validate)
        val now = Time.now()
        val outcomes = mutableListOf<Pair<Rule, Boolean>>()
        gate.withLock {
            items.forEach { outcomes += addLocked(it, now) }
            publishLocked()
        }
        outcomes.forEach { (rule, added) ->
            _changes.emit(RuleChange(if (added) RuleChangeKind.Added else RuleChangeKind.Updated, rule))
        }
        return outcomes.map { it.first }
    }

    private suspend fun addLocked(rule: Rule, now: Long): Pair<Rule, Boolean> {
        val key = RuleIdentity.key(rule)
        val existing = rules.values.firstOrNull { RuleIdentity.key(it) == key }
        if (existing != null) {
            if (existing.source == RuleSource.Builtin) return existing to false
            val merged = RuleIdentity.merge(existing, rule, now)
            if (merged.isPersistent) store.upsert(merged)
            rules[merged.id] = merged
            return merged to false
        }
        if (rule.isPersistent) store.upsert(rule)
        rules[rule.id] = rule
        hits.putIfAbsent(rule.id, HitCounter(0, null))
        return rule to true
    }

    suspend fun update(rule: Rule): Rule {
        validate(rule)
        val updated: Rule
        var replaced: Rule? = null
        gate.withLock {
            val existing = rules[rule.id] ?: throw NoSuchElementException("規則 ${rule.id} 不存在。")
            val candidate = if (existing.source == RuleSource.Builtin) {
                existing.copy(enabled = rule.enabled)
            } else {
                rule
            }
            updated = candidate.copy(createdAt = existing.createdAt, updatedAt = Time.now())
            val key = RuleIdentity.key(updated)
            replaced = rules.values.firstOrNull {
                it.id != updated.id &&
                    it.source != RuleSource.Builtin &&
                    RuleIdentity.key(it) == key
            }
            replaced?.let { duplicate ->
                if (duplicate.isPersistent) {
                    store.delete(listOf(duplicate), RuleEndReason.Replaced, Time.now())
                }
                rules.remove(duplicate.id)
                hits.remove(duplicate.id)
            }
            if (updated.isPersistent) store.upsert(updated)
            rules[updated.id] = updated
            publishLocked()
        }
        replaced?.let { _changes.emit(RuleChange(RuleChangeKind.Removed, it, RuleEndReason.Replaced)) }
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
