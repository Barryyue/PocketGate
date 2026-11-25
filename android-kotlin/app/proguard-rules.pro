# Keep rules are added as the implementation grows.
-keep class com.networkcontrol.android.kotlin.engine.vpn.NcVpnService { *; }
-keep class com.networkcontrol.android.automation.service.GkdAccessibilityService { *; }
-keep class hev.htproxy.TProxyService { *; }
-keepclasseswithmembernames class * { native <methods>; }


# Glance 以類別區分各款桌面掛件；R8 會把同父類的掛件類別水平合併成一個，
# 結果每個掛件都畫成同一款（0.11.0 開發時實際發生）。保留類別本身並禁止合併。
-keep class com.networkcontrol.android.kotlin.widget.** extends androidx.glance.appwidget.GlanceAppWidget { *; }
# ActionCallback 由 Glance 以類別名稱反射建立。
-keep class com.networkcontrol.android.kotlin.widget.** implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
