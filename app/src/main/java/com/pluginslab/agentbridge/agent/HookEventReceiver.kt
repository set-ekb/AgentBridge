package com.pluginslab.agentbridge.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The sender runs inside another app UID; signals are UNTRUSTED and cannot
 * authorize actions. Only allowlisted package/activity/stage metadata persists.
 * A manually sent test broadcast is intentionally indistinguishable from a hint.
 */
class HookEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Diagnostics.init(context)
        if (intent.action != ACTION) return
        val pkg = intent.getStringExtra("package").orEmpty()
        val activity = intent.getStringExtra("activity").orEmpty()
        val state = intent.getStringExtra("state").orEmpty()
        val stage = intent.getStringExtra("stage").orEmpty()
        if (Diagnostics.safePackage(pkg).isBlank() ||
            (activity.isNotEmpty() && Diagnostics.safeActivity(activity).isBlank()) ||
            pkg == context.packageName || pkg == "android" ||
            pkg.startsWith("com.android.systemui")) {
            Diagnostics.record("hook_error", "invalid_payload", source = "bridge")
            return
        }
        if (stage == "hook_active") {
            Diagnostics.record("hook_status", "callback_active", source = "vector_hint",
                packageName = pkg, activity = activity)
            return
        }
        if (state !in setOf("resumed", "paused")) {
            Diagnostics.record("hook_error", "unknown_stage", source = "bridge")
            return
        }
        AppEventTimeline.append("vector_hint", state, pkg, activity)
    }

    companion object {
        const val ACTION = "com.pluginslab.agentbridge.action.HOOK_LIFECYCLE"
    }
}
