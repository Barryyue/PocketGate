package com.networkcontrol.android.kotlin.core.settings

import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.RuntimeState
import com.networkcontrol.android.kotlin.core.model.UserSettings
import com.networkcontrol.android.kotlin.core.storage.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsService(private val store: SettingsStore) {
    private val _state = MutableStateFlow(UserSettings.Default)
    val state: StateFlow<UserSettings> = _state.asStateFlow()

    suspend fun initialize() {
        _state.value = store.loadUserSettings()?.validated() ?: UserSettings.Default
    }

    suspend fun update(transform: (UserSettings) -> UserSettings): UserSettings {
        val next = transform(_state.value).validated()
        store.saveUserSettings(next)
        _state.value = next
        return next
    }
}

class RuntimeStateService(private val store: SettingsStore) {
    private val gate = Mutex()
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    val mode: FilterMode get() = _state.value.mode
    val activeProfileId: String get() = _state.value.activeProfileId
    val isDoNotDisturb: Boolean get() = _state.value.isDoNotDisturb(Time.now())

    suspend fun initialize() {
        _state.value = store.loadRuntimeState() ?: RuntimeState()
    }

    suspend fun setMode(mode: FilterMode) {
        update { current ->
            if (current.mode == mode) return@update current
            if (mode == FilterMode.BlockAll) {
                current.copy(mode = mode, modeBeforeBlockAll = current.mode)
            } else {
                current.copy(mode = mode, modeBeforeBlockAll = null)
            }
        }
    }

    suspend fun restoreFromBlockAll() {
        update { current ->
            if (current.mode == FilterMode.BlockAll) {
                current.copy(
                    mode = current.modeBeforeBlockAll ?: FilterMode.Ask,
                    modeBeforeBlockAll = null,
                )
            } else {
                current
            }
        }
    }

    suspend fun activateProfile(profileId: String, mode: FilterMode) {
        update { current ->
            when (current.mode) {
                FilterMode.BlockAll -> if (current.activeProfileId == profileId && current.modeBeforeBlockAll == mode) {
                    current
                } else {
                    current.copy(activeProfileId = profileId, modeBeforeBlockAll = mode)
                }
                FilterMode.Disabled -> if (current.activeProfileId == profileId) {
                    current
                } else {
                    current.copy(activeProfileId = profileId)
                }
                else -> if (current.activeProfileId == profileId && current.mode == mode) {
                    current
                } else {
                    current.copy(activeProfileId = profileId, mode = mode, modeBeforeBlockAll = null)
                }
            }
        }
    }

    suspend fun setDoNotDisturb(until: Long?) {
        update { it.copy(doNotDisturbUntil = until) }
    }

    private suspend fun update(transform: (RuntimeState) -> RuntimeState) {
        gate.withLock {
            val current = _state.value
            val next = transform(current)
            if (next == current) return
            store.saveRuntimeState(next)
            _state.value = next
        }
    }
}
