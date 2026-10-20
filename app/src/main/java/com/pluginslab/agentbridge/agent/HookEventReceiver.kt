package com.pluginslab.agentbridge.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Optional Vector/Xposed adapter. Broadcasts are untrusted observations,
 * not commands: never accept arguments, UI text, secrets, or task instructions.
 */
class HookEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pkg = intent.getStringExtra("package").orEmpty()
        val activity = intent.getStringExtra("activity").orEmpty()
        val state = intent.getStringExtra("state").orEmpty()
        if (state !in setOf("resumed", "paused")) return
        if (pkg == context.packageName || pkg == "android" || pkg.startsWith("com.android.systemui")) return
        AppEventTimeline.append("vector_hint", state, pkg, activity)
    }

    companion object {
        const val ACTION = "com.pluginslab.agentbridge.action.HOOK_LIFECYCLE"
    }
}
