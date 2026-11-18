package com.networkcontrol.android.kotlin.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** 懸浮工具欄覆蓋在內容上方時，清單底部需要額外留出的空間。 */
val LocalBottomContentPadding = staticCompositionLocalOf { 0.dp }
