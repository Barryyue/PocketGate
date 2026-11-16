package com.networkcontrol.android.kotlin.ui.security

import android.app.Activity
import android.app.KeyguardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.networkcontrol.android.kotlin.AppContainer
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.rememberUiStrings

private const val RELOCK_AFTER_MILLIS = 60_000L

/** 敏感操作驗證後的寬限期：這段時間內的連續操作不再重複要求驗證。 */
private const val SENSITIVE_GRACE_MILLIS = 30_000L

/**
 * 系統鎖屏憑證確認畫面。`createConfirmDeviceCredentialIntent` 自 API 29 標為棄用（建議 BiometricPrompt），
 * 但它在所有版本上都可用，且不需要額外的 androidx.biometric 依賴；只用於本地確認身分，不涉及金鑰。
 */
@Suppress("DEPRECATION")
private fun confirmCredentialIntent(context: android.content.Context, title: String, description: String?): android.content.Intent? =
    context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, description)

/**
 * 敏感操作的再驗證（step-up authentication）：開啟應用鎖時，關閉保護、切換模式、修改規則等操作前
 * 先要求系統鎖屏憑證，避免已解鎖的應用被別人拿去改設定。未開啟應用鎖時直接執行。
 */
fun interface SensitiveActionGuard {
    fun run(action: () -> Unit)
}

val LocalSensitiveAction = androidx.compose.runtime.staticCompositionLocalOf<SensitiveActionGuard> {
    SensitiveActionGuard { it() }
}

/**
 * 應用鎖：開啟時用系統鎖屏憑證解鎖；離開超過 1 分鐘後重新鎖定。
 * 不在應用內保存密碼，驗證完全交給系統 Keyguard。
 */
@Composable
fun AppLockGate(container: AppContainer, content: @Composable () -> Unit) {
    val s = rememberUiStrings()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = container.preferences
    var unlocked by remember { mutableStateOf(!prefs.appLockEnabled) }
    var lastStoppedAt by remember { mutableLongStateOf(0L) }
    var lastAuthAt by remember { mutableLongStateOf(0L) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun confirmIntent() = runCatching { confirmCredentialIntent(context, s[S.AppLockedTitle], s[S.AppLockConfirmAction]) }.getOrNull()

    val confirmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val action = pendingAction
        pendingAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            lastAuthAt = System.currentTimeMillis()
            action?.invoke()
        }
    }
    val guard = remember(prefs) {
        SensitiveActionGuard { action ->
            val now = System.currentTimeMillis()
            if (!prefs.appLockEnabled || now - lastAuthAt < SENSITIVE_GRACE_MILLIS) {
                action()
                return@SensitiveActionGuard
            }
            val intent = confirmIntent()
            if (intent == null) {
                // 裝置沒有鎖屏憑證（使用者後來移除了）：無法驗證，照常執行，避免功能卡死。
                action()
            } else {
                pendingAction = action
                confirmLauncher.launch(intent)
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        lastStoppedAt = System.currentTimeMillis()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (prefs.appLockEnabled && lastStoppedAt > 0 &&
            System.currentTimeMillis() - lastStoppedAt > RELOCK_AFTER_MILLIS
        ) {
            unlocked = false
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            unlocked = true
            // 剛解鎖：寬限期內的操作不必再驗證一次。
            lastAuthAt = System.currentTimeMillis()
        }
    }

    if (!prefs.appLockEnabled) {
        unlocked = true
        androidx.compose.runtime.CompositionLocalProvider(LocalSensitiveAction provides guard) { content() }
        return
    }
    if (unlocked) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSensitiveAction provides guard) { content() }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            s[S.AppLockedTitle],
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Button(
            onClick = {
                val intent = runCatching { confirmCredentialIntent(context, s[S.AppLockedTitle], null) }.getOrNull()
                if (intent == null) {
                    Toast.makeText(context, s[S.AppLockNoCredential], Toast.LENGTH_LONG).show()
                } else {
                    launcher.launch(intent)
                }
            },
        ) { Text(s[S.Unlock]) }
    }
}
