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
        if (result.resultCode == Activity.RESULT_OK) unlocked = true
    }

    if (!prefs.appLockEnabled) {
        unlocked = true
        content()
        return
    }
    if (unlocked) {
        content()
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
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                val intent = runCatching {
                    keyguard?.createConfirmDeviceCredentialIntent(s[S.AppLockedTitle], null)
                }.getOrNull()
                if (intent == null) {
                    Toast.makeText(context, s[S.AppLockNoCredential], Toast.LENGTH_LONG).show()
                } else {
                    launcher.launch(intent)
                }
            },
        ) { Text(s[S.Unlock]) }
    }
}
