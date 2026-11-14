package com.networkcontrol.android.kotlin.core.profiles

import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.AutoActivateCondition
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.RuleEndReason
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.rules.RuleService
import com.networkcontrol.android.kotlin.core.settings.RuntimeStateService
import com.networkcontrol.android.kotlin.core.storage.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProfileService(
    private val store: ProfileStore,
    private val runtime: RuntimeStateService,
    private val rules: RuleService,
) {
    private val gate = Mutex()
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    val all: List<Profile> get() = _profiles.value
    val active: Profile
        get() = all.firstOrNull { it.id == runtime.activeProfileId }
            ?: all.firstOrNull { it.id == Profile.DEFAULT_ID }
            ?: all.firstOrNull()
            ?: Profile.Default

    suspend fun initialize() {
        val loaded = store.loadAll()
        val profiles = if (loaded.isEmpty()) {
            val fallback = Profile.Default
            store.upsert(fallback)
            listOf(fallback)
        } else {
            loaded
        }
        _profiles.value = sort(profiles)
        if (_profiles.value.none { it.id == runtime.activeProfileId }) {
            val first = active
            runtime.activateProfile(first.id, first.mode)
        }
    }

    fun find(id: String): Profile? = all.firstOrNull { it.id == id }

    suspend fun create(
        name: String,
        mode: FilterMode,
        autoActivate: AutoActivateCondition? = null,
        copyRulesFrom: String? = null,
    ): Profile {
        val normalized = normalizeName(name)
        val validatedMode = validateMode(mode)
        lateinit var profile: Profile
        gate.withLock {
            ensureUniqueName(normalized, except = null)
            val sortOrder = all.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
            profile = Profile(
                id = java.util.UUID.randomUUID().toString(),
                name = normalized,
                mode = validatedMode,
                sortOrder = sortOrder,
                autoActivate = autoActivate,
            )
            store.upsert(profile)
            _profiles.value = sort(all + profile)
        }

        if (copyRulesFrom != null) {
            val now = Time.now()
            val copies = rules.all
                .filter {
                    it.profileId == copyRulesFrom &&
                        (it.lifetime == RuleLifetime.Permanent ||
                            (it.lifetime == RuleLifetime.Timed && (it.expiresAt ?: 0) > now))
                }
                .map {
                    it.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        profileId = profile.id,
                        boundProcess = null,
                        createdAt = now,
                        updatedAt = now,
                        hitCount = 0,
                        lastHitAt = null,
                    )
                }
            rules.addRange(copies)
        }

        notifyChanged()
        return profile
    }

    suspend fun update(
        id: String,
        name: String,
        mode: FilterMode,
        autoActivate: AutoActivateCondition? = null,
    ): Profile {
        val normalized = normalizeName(name)
        val validatedMode = validateMode(mode)
        lateinit var updated: Profile
        var modeChanged = false
        gate.withLock {
            val existing = find(id) ?: throw NoSuchElementException("配置文件 $id 不存在。")
            ensureUniqueName(normalized, existing.id)
            updated = existing.copy(name = normalized, mode = validatedMode, autoActivate = autoActivate)
            modeChanged = existing.mode != validatedMode
            store.upsert(updated)
            _profiles.value = sort(all.map { if (it.id == id) updated else it })
            if (modeChanged && id == runtime.activeProfileId) {
                if (runtime.mode == FilterMode.BlockAll) {
                    runtime.activateProfile(id, updated.mode)
                } else if (runtime.mode != FilterMode.Disabled) {
                    runtime.setMode(updated.mode)
                }
            }
        }
        notifyChanged()
        return updated
    }

    suspend fun delete(id: String) {
        require(id != Profile.DEFAULT_ID) { "默認配置文件不能刪除。" }
        gate.withLock {
            require(id != runtime.activeProfileId) { "不能刪除當前配置文件，請先切換到其他配置文件。" }
            find(id) ?: throw NoSuchElementException("配置文件 $id 不存在。")
            val ruleIds = rules.all.filter { it.profileId == id }.map { it.id }
            if (ruleIds.isNotEmpty()) {
                rules.remove(ruleIds, RuleEndReason.Deleted)
            }
            store.delete(id)
            _profiles.value = sort(all.filterNot { it.id == id })
        }
        notifyChanged()
    }

    suspend fun activate(id: String) {
        gate.withLock {
            val profile = find(id) ?: throw NoSuchElementException("配置文件 $id 不存在。")
            runtime.activateProfile(profile.id, profile.mode)
        }
        notifyChanged()
    }

    suspend fun setActiveMode(mode: FilterMode) {
        val temporary = mode == FilterMode.BlockAll || mode == FilterMode.Disabled
        var profileChanged = false
        gate.withLock {
            val activeProfile = find(runtime.activeProfileId)
            if (activeProfile != null && !temporary && activeProfile.mode != mode) {
                val updated = activeProfile.copy(mode = mode)
                store.upsert(updated)
                _profiles.value = sort(all.map { if (it.id == updated.id) updated else it })
                profileChanged = true
            }
            runtime.setMode(mode)
        }
        if (profileChanged) notifyChanged()
    }

    fun getRuleCount(profileId: String): Int = rules.all.count { it.profileId == profileId }

    private fun notifyChanged() {
        _revision.value = _revision.value + 1
    }

    private fun sort(items: List<Profile>): List<Profile> =
        items.sortedWith(compareBy({ it.sortOrder }, { it.name }))

    private fun ensureUniqueName(name: String, except: String?) {
        require(all.none { it.id != except && it.name.equals(name, ignoreCase = true) }) {
            "配置文件「$name」已存在。"
        }
    }

    private fun normalizeName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.length in 1..40) { "配置文件名稱長度為 1–40 個字元。" }
        return trimmed
    }

    private fun validateMode(mode: FilterMode): FilterMode {
        require(mode == FilterMode.Ask || mode == FilterMode.Whitelist || mode == FilterMode.Blacklist) {
            "配置文件模式只能是詢問、白名單或黑名單。"
        }
        return mode
    }
}
