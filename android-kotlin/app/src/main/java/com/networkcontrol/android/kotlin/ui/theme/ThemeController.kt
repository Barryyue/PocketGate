package com.networkcontrol.android.kotlin.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.networkcontrol.android.kotlin.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeController(private val preferences: AppPreferences) {
    private val _color = MutableStateFlow(Color(preferences.themeColor))
    val color: StateFlow<Color> = _color.asStateFlow()

    val current: Color get() = _color.value

    fun set(color: Color) {
        preferences.themeColor = color.toArgb()
        _color.value = color
    }
}
