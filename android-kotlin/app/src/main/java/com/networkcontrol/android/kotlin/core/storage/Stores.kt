package com.networkcontrol.android.kotlin.core.storage

import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.ActivitySummary
import com.networkcontrol.android.kotlin.core.model.ActivitySummaryQuery
import com.networkcontrol.android.kotlin.core.model.AppActivity
import com.networkcontrol.android.kotlin.core.model.AppTraffic
import com.networkcontrol.android.kotlin.core.model.ConnectionEvent
import com.networkcontrol.android.kotlin.core.model.LogEntry
import com.networkcontrol.android.kotlin.core.model.LogQuery
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleEndReason
import com.networkcontrol.android.kotlin.core.model.RuntimeState
import com.networkcontrol.android.kotlin.core.model.TrafficSummary
import com.networkcontrol.android.kotlin.core.model.UserSettings

interface SettingsStore {
    suspend fun loadUserSettings(): UserSettings?
    suspend fun saveUserSettings(settings: UserSettings)
    suspend fun loadRuntimeState(): RuntimeState?
    suspend fun saveRuntimeState(state: RuntimeState)
}

interface RuleStore {
    suspend fun loadAll(): List<Rule>
    suspend fun upsert(rule: Rule)
    suspend fun delete(rules: List<Rule>, reason: RuleEndReason, endedAt: Long)
    suspend fun recordHits(hits: List<RuleHitDelta>)
}

data class RuleHitDelta(val ruleId: String, val count: Long, val lastHitAt: Long)

interface ProfileStore {
    suspend fun loadAll(): List<Profile>
    suspend fun upsert(profile: Profile)
    suspend fun delete(id: String)
}

interface AppStore {
    suspend fun upsert(identity: AppIdentity): AppIdentity
    suspend fun get(appId: Long): AppIdentity?
    suspend fun find(path: String): AppIdentity?
}

interface ActivityStore {
    suspend fun write(event: ConnectionEvent)
    suspend fun query(query: LogQuery): List<LogEntry>
    suspend fun summarize(query: ActivitySummaryQuery): ActivitySummary
    suspend fun listAppActivity(from: Long, to: Long, limit: Int): List<AppActivity>
    suspend fun countBlockedSince(since: Long): Long
    suspend fun applyRetention(cutoff: Long)
}

interface TrafficStore {
    suspend fun add(appId: Long, minute: Long, bytesIn: Long, bytesOut: Long)
    suspend fun summarize(from: Long, to: Long, bucketMillis: Long, topCount: Int): TrafficSummary
    suspend fun byApp(from: Long, to: Long): Map<Long, AppTraffic>
}
