package com.networkcontrol.android.kotlin.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.core.model.ProtectionStatus
import com.networkcontrol.android.kotlin.platform.quick.QuickAction
import com.networkcontrol.android.kotlin.platform.quick.QuickActions
import com.networkcontrol.android.kotlin.platform.quick.WidgetData
import com.networkcontrol.android.kotlin.platform.quick.modeLabel
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings
import com.networkcontrol.android.kotlin.ui.common.formatBytes

/**
 * 桌面掛件（仿 vivo 原子組件：圓角卡片、狀態一眼可見、常用操作一點即用）。
 * 資料來自 [com.networkcontrol.android.kotlin.platform.quick.QuickSurfaces]，所有掛件共用；
 * 點擊操作走 [QuickActions]，需要驗證身分或 VPN 同意時改開透明的 QuickActionActivity。
 */
object NcWidgets {
    val receivers: List<Class<out GlanceAppWidgetReceiver>> = listOf(
        StatusWidgetReceiver::class.java,
        OverviewWidgetReceiver::class.java,
        ControlsWidgetReceiver::class.java,
        TrafficWidgetReceiver::class.java,
    )

    suspend fun updateAll(context: Context) {
        listOf(StatusWidget(), OverviewWidget(), ControlsWidget(), TrafficWidget()).forEach { widget ->
            runCatching { widget.updateAll(context) }
        }
    }
}

private val ActionKey = ActionParameters.Key<String>("quick_action")

/** 不需要畫面的操作直接在背景執行，桌面不會閃一下。 */
class QuickActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val action = QuickAction.fromId(parameters[ActionKey]) ?: return
        val container = App.container(context)
        if (QuickActions.needsActivity(container, action)) {
            // 繪製後狀態才改變（例如剛開啟應用鎖）：改走 Activity 驗證。
            runCatching { context.startActivity(QuickActions.activityIntent(context, action)) }
            return
        }
        QuickActions.execute(container, action)
    }
}

private fun quickClick(context: Context, action: QuickAction): Action {
    val container = App.container(context)
    return if (QuickActions.needsActivity(container, action)) {
        actionStartActivity(QuickActions.activityIntent(context, action))
    } else {
        actionRunCallback<QuickActionCallback>(actionParametersOf(ActionKey to action.id))
    }
}

private fun openApp(context: Context): Action = actionStartActivity(QuickActions.activityIntent(context, QuickAction.OpenApp))

abstract class NcWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val surfaces = App.container(context).quickSurfaces
        surfaces.ensureFresh()
        provideContent {
            val data by surfaces.data.collectAsState()
            val strings = UiStrings(context.resources.configuration.locales[0]?.toLanguageTag().orEmpty())
            GlanceTheme {
                Content(data, strings)
            }
        }
    }

    @Composable
    protected abstract fun Content(data: WidgetData, strings: UiStrings)
}

// ---------- 共用元件 ----------

@Composable
private fun cardModifier(padding: Dp = 14.dp): GlanceModifier =
    GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(22.dp)
        .padding(padding)

@Composable
private fun shieldBadge(active: Boolean, boxSize: Dp = 32.dp, iconSize: Dp = 18.dp) {
    Box(
        modifier = GlanceModifier
            .size(boxSize)
            .background(if (active) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant)
            .cornerRadius(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        icon(R.drawable.ic_shield, if (active) GlanceTheme.colors.primary else GlanceTheme.colors.outline, iconSize)
    }
}

@Composable
private fun label(text: String, size: TextUnit = 12.sp, color: ColorProvider = GlanceTheme.colors.onSurfaceVariant, bold: Boolean = false, maxLines: Int = 1) {
    Text(
        text,
        maxLines = maxLines,
        style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    )
}

@Composable
private fun icon(res: Int, tint: ColorProvider, size: Dp) {
    Image(
        provider = ImageProvider(res),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = GlanceModifier.size(size),
    )
}

private fun protectionActive(data: WidgetData): Boolean {
    val state = data.state ?: return false
    return state.protectionOn && state.status != ProtectionStatus.Off
}

private fun statusText(data: WidgetData, strings: UiStrings): String {
    val state = data.state ?: return "…"
    return when {
        !state.protectionOn -> strings[S.WidgetProtectionOff]
        state.status == ProtectionStatus.Starting -> strings[S.WidgetStarting]
        state.status == ProtectionStatus.On -> strings[S.WidgetProtecting]
        state.status == ProtectionStatus.NeedsConsent || state.status == ProtectionStatus.Revoked ||
            state.status == ProtectionStatus.Failed -> strings[S.WidgetNeedsAttention]
        else -> strings[S.WidgetProtectionOff]
    }
}

/** 膠囊按鈕：開啟保護時是醒目的主色，關閉保護時是低調的次要色。 */
@Composable
private fun protectionPill(data: WidgetData, strings: UiStrings, height: Dp, fill: Boolean) {
    val context = LocalContext.current
    val on = data.state?.protectionOn == true
    val background = if (on) GlanceTheme.colors.secondaryContainer else GlanceTheme.colors.primary
    val foreground = if (on) GlanceTheme.colors.onSecondaryContainer else GlanceTheme.colors.onPrimary
    var modifier = GlanceModifier.height(height).background(background).cornerRadius(height / 2)
        .clickable(quickClick(context, QuickAction.ToggleProtection))
    modifier = if (fill) modifier.fillMaxWidth() else modifier.padding(horizontal = 14.dp)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            if (on) strings[S.WidgetTurnOff] else strings[S.WidgetTurnOn],
            maxLines = 1,
            style = TextStyle(color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
    }
}

// ---------- 1. 防護狀態（2×2） ----------

class StatusWidget : NcWidget() {
    @Composable
    override fun Content(data: WidgetData, strings: UiStrings) {
        val context = LocalContext.current
        val active = protectionActive(data)
        Column(modifier = cardModifier().clickable(openApp(context))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                shieldBadge(active, boxSize = 28.dp, iconSize = 16.dp)
                Spacer(GlanceModifier.width(8.dp))
                label(strings[S.WidgetBrand], 12.sp, bold = true)
            }
            Spacer(GlanceModifier.defaultWeight())
            label(statusText(data, strings), 20.sp, GlanceTheme.colors.onSurface, bold = true)
            data.state?.let { label(modeLabel(strings, it.mode), 13.sp) }
            label("${strings[S.BlockedToday]} ${data.blockedToday}", 12.sp)
            Spacer(GlanceModifier.height(8.dp))
            protectionPill(data, strings, 36.dp, fill = true)
        }
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}

// ---------- 2. 今日概覽（4×2） ----------

class OverviewWidget : NcWidget() {
    @Composable
    override fun Content(data: WidgetData, strings: UiStrings) {
        val context = LocalContext.current
        val active = protectionActive(data)
        Column(modifier = cardModifier()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().clickable(openApp(context)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shieldBadge(active, boxSize = 36.dp, iconSize = 20.dp)
                Spacer(GlanceModifier.width(10.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    label(statusText(data, strings), 17.sp, GlanceTheme.colors.onSurface, bold = true)
                    data.state?.let { label(modeLabel(strings, it.mode), 12.sp) }
                }
                protectionPill(data, strings, 34.dp, fill = false)
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                statTile(data.blockedToday.toString(), strings[S.BlockedToday], openApp(context))
                Spacer(GlanceModifier.width(8.dp))
                statTile(
                    if (data.trafficAvailable) formatBytes(data.bytesIn + data.bytesOut) else "—",
                    strings[S.TrafficToday],
                    openApp(context),
                )
                Spacer(GlanceModifier.width(8.dp))
                statTile((data.state?.pendingPrompts ?: 0).toString(), strings[S.PendingPrompts], openApp(context))
            }
        }
    }

    @Composable
    private fun androidx.glance.layout.RowScope.statTile(value: String, caption: String, action: Action) {
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(16.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clickable(action),
        ) {
            label(value, 18.sp, GlanceTheme.colors.onSecondaryContainer, bold = true)
            label(caption, 11.sp, GlanceTheme.colors.onSecondaryContainer)
        }
    }
}

class OverviewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OverviewWidget()
}

// ---------- 3. 快捷開關（4×1） ----------

class ControlsWidget : NcWidget() {
    @Composable
    override fun Content(data: WidgetData, strings: UiStrings) {
        val state = data.state
        val now = System.currentTimeMillis()
        Row(
            modifier = cardModifier(padding = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            control(R.drawable.ic_shield, strings[S.QuickProtection], state?.protectionOn == true, QuickAction.ToggleProtection)
            control(
                R.drawable.ic_tune,
                // 顯示基礎模式；全部阻止由旁邊的按鈕表示，避免兩個按鈕顯示同一個字。
                state?.let { modeLabel(strings, it.baseMode) } ?: strings[S.Mode],
                state?.protectionOn == true && !state.blockAll,
                QuickAction.CycleMode,
            )
            control(R.drawable.ic_block, strings[S.QuickBlockAll], state?.blockAll == true, QuickAction.ToggleBlockAll)
            control(R.drawable.ic_dnd, strings[S.QuickDnd], state?.dndActive(now) == true, QuickAction.ToggleDnd)
            control(R.drawable.ic_focus, strings[S.QuickFocus], state?.focusActive(now) == true, QuickAction.ToggleFocus)
        }
    }

    @Composable
    private fun androidx.glance.layout.RowScope.control(res: Int, caption: String, active: Boolean, action: QuickAction) {
        val context = LocalContext.current
        val size = LocalSize.current
        val bubble = if (size.height < 90.dp) 34.dp else if (size.height < 110.dp) 40.dp else 46.dp
        Column(
            modifier = GlanceModifier.defaultWeight().clickable(quickClick(context, action)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(bubble)
                    .background(if (active) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(bubble / 2),
                contentAlignment = Alignment.Center,
            ) {
                icon(res, if (active) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant, 22.dp)
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(
                caption,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

class ControlsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ControlsWidget()
}

// ---------- 4. 流量排行（2×2，可拉大） ----------

class TrafficWidget : NcWidget() {
    @Composable
    override fun Content(data: WidgetData, strings: UiStrings) {
        val context = LocalContext.current
        val size = LocalSize.current
        Column(modifier = cardModifier().clickable(openApp(context))) {
            label(strings[S.TrafficToday], 12.sp)
            if (!data.trafficAvailable) {
                Spacer(GlanceModifier.height(6.dp))
                label(strings[S.WidgetNeedsUsageAccess], 12.sp, GlanceTheme.colors.onSurface, maxLines = 4)
                return@Column
            }
            label(formatBytes(data.bytesIn + data.bytesOut), 20.sp, GlanceTheme.colors.onSurface, bold = true)
            label("↓ ${formatBytes(data.bytesIn)}  ↑ ${formatBytes(data.bytesOut)}", 11.sp)
            Spacer(GlanceModifier.height(6.dp))
            if (data.topApps.isEmpty()) {
                label(strings[S.WidgetNoTraffic], 12.sp)
                return@Column
            }
            // 依掛件高度決定顯示幾個應用（2×2 約 3 個，拉高後最多 5 個）。
            val rows = ((size.height.value - 96f) / 26f).toInt().coerceIn(1, data.topApps.size)
            data.topApps.take(rows).forEach { app ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (app.icon != null) {
                        Image(ImageProvider(app.icon), contentDescription = null, modifier = GlanceModifier.size(20.dp))
                    } else {
                        icon(R.drawable.ic_shield, GlanceTheme.colors.outline, 20.dp)
                    }
                    Spacer(GlanceModifier.width(6.dp))
                    Box(modifier = GlanceModifier.defaultWeight()) {
                        label(app.label, 12.sp, GlanceTheme.colors.onSurface)
                    }
                    label(formatBytes(app.bytes), 11.sp)
                }
            }
        }
    }
}

class TrafficWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrafficWidget()
}
