package com.networkcontrol.android.kotlin.engine.protection

import android.content.Context
import com.networkcontrol.android.kotlin.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProtectionController(
    private val preferences: AppPreferences,
) {
    private val _enabled = MutableStateFlow(preferences.protectionEnabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isEnabled: Boolean get() = _enabled.value

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        preferences.protectionEnabled = value
        _enabled.value = value
    }
}
