package com.networkcontrol.android.kotlin.platform.prompt

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/** 追蹤本應用是否有 Activity 在前台，用來避免懸浮面板與 Compose 卡片重複顯示。 */
class AppUiVisibility(context: Context) : Application.ActivityLifecycleCallbacks {
    private val app = context.applicationContext as Application
    @Volatile
    var visible: Boolean = false
        private set

    var onChange: ((Boolean) -> Unit)? = null

    init {
        app.registerActivityLifecycleCallbacks(this)
    }

    fun dispose() {
        app.unregisterActivityLifecycleCallbacks(this)
    }

    private fun update(value: Boolean) {
        if (visible == value) return
        visible = value
        onChange?.invoke(value)
    }

    override fun onActivityResumed(activity: Activity) = update(true)

    override fun onActivityPaused(activity: Activity) = update(false)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
