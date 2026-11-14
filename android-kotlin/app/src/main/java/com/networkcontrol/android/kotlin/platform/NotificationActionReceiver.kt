package com.networkcontrol.android.kotlin.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.core.model.PromptDecision
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val promptId = intent.getStringExtra(EXTRA_PROMPT_ID) ?: return
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        val pending = goAsync()
        val container = (context.applicationContext as App).container
        CoroutineScope(Dispatchers.Default).launch {
            try {
                container.prompts.answer(
                    promptId,
                    PromptDecision(
                        action = if (allow) VerdictAction.Allow else VerdictAction.Deny,
                        lifetime = RuleLifetime.Permanent,
                    ),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.networkcontrol.android.kotlin.action.ANSWER_PROMPT"
        const val EXTRA_PROMPT_ID = "prompt_id"
        const val EXTRA_ALLOW = "allow"
    }
}
