package com.networkcontrol.android.kotlin.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.networkcontrol.android.kotlin.AppPreferences
import com.networkcontrol.android.kotlin.core.model.AppThemeStyle
import com.networkcontrol.android.kotlin.core.model.ToolbarStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeController(private val preferences: AppPreferences) {
    private val _color = MutableStateFlow(Color(preferences.themeColor))
    val color: StateFlow<Color> = _color.asStateFlow()

    private val _themeStyle = MutableStateFlow(preferences.themeStyle)
    val themeStyle: StateFlow<AppThemeStyle> = _themeStyle.asStateFlow()

    private val _toolbarStyle = MutableStateFlow(preferences.toolbarStyle)
    val toolbarStyle: StateFlow<ToolbarStyle> = _toolbarStyle.asStateFlow()

    val current: Color get() = _color.value

    fun set(color: Color) {
        preferences.themeColor = color.toArgb()
        _color.value = color
    }

    fun setThemeStyle(style: AppThemeStyle) {
        preferences.themeStyle = style
        _themeStyle.value = style
    }

    fun setToolbarStyle(style: ToolbarStyle) {
        preferences.toolbarStyle = style
        _toolbarStyle.value = style
    }
}
