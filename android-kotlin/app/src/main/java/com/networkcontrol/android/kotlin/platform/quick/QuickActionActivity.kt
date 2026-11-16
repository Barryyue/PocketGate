package com.networkcontrol.android.kotlin.platform.quick

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings

/**
 * 快捷操作的透明跳板：桌面掛件、磁貼、捷徑需要「驗證身分」或「VPN 同意」時經過這裡。
 * 不匯出（exported=false），只能由本應用的 PendingIntent / 捷徑啟動，避免其他應用藉它關閉防護。
 */
class QuickActionActivity : ComponentActivity() {
    private var action: QuickAction? = null

    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) run(authorized = true) else finish()
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val container = App.container(this)
            container.backend.setProtection(true)
            container.quickSurfaces.refreshSoon()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        action = QuickActions.fromIntent(intent)
        // 重建時（例如旋轉）等待原本的驗證 / 同意結果，不重複執行。
        if (savedInstanceState != null) return
        run(authorized = false)
    }

    private fun run(authorized: Boolean) {
        val current = action ?: return finish()
        val container = App.container(this)
        if (current == QuickAction.OpenApp) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val now = System.currentTimeMillis()
        if (!authorized && QuickLogic.requiresAuth(current, QuickActions.state(container), now)) {
            val strings = UiStrings(resources.configuration.locales[0]?.toLanguageTag().orEmpty())
            @Suppress("DEPRECATION")
            val confirm = getSystemService(KeyguardManager::class.java)
                ?.createConfirmDeviceCredentialIntent(strings[S.AppLockedTitle], strings[S.AppLockConfirmAction])
            if (confirm != null) {
                confirmCredential.launch(confirm)
                return
            }
            // 裝置沒有鎖屏憑證：與應用內的行為一致，照常執行。
        }
        when (val result = QuickActions.execute(container, current)) {
            is QuickResult.NeedsConsent -> vpnConsent.launch(result.intent)
            QuickResult.Done -> finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
