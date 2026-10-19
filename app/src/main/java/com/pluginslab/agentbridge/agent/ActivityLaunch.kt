package com.pluginslab.agentbridge.agent

import com.pluginslab.agentbridge.agent.ActionDispatch
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.pluginslab.agentbridge.accessibility.BridgeAccessibilityService
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

/** Use a resumed Activity when available; never create a hidden trampoline Activity. */
object ActivityLaunch : Application.ActivityLifecycleCallbacks {
    private var resumed = WeakReference<Activity>(null)
    override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) resumed.clear()
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (resumed.get() === activity) resumed.clear()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

    suspend fun dispatch(fallback: Context, intent: Intent): String = withContext(Dispatchers.Main) {
        val activity = resumed.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (activity != null) {
            ActionDispatch.run { activity.startActivity(intent) }
            "foreground_activity"
        } else {
            ActionDispatch.run { fallback.startActivity(intent) }
            "accessibility_service"
        }
    }

    suspend fun activePackage(): String? = withContext(Dispatchers.Main) {
        val root = BridgeAccessibilityService.instance?.rootInActiveWindow
        try { root?.packageName?.toString() } finally { root?.recycle() }
    }

    suspend fun awaitPackage(expected: String, timeoutMs: Long = 8000): String? {
        val started = android.os.SystemClock.elapsedRealtime()
        var active: String?
        do {
            currentCoroutineContext().ensureActive()
            active = activePackage()
            if (active == expected) return active
            if (android.os.SystemClock.elapsedRealtime() - started >= timeoutMs) return active
            delay(200)
        } while (true)
    }
}
