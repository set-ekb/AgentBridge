package com.pluginslab.agentbridge.agent

object ForegroundWaitRules {
    /** Keep foreground checks responsive initially, then reduce battery drain. */
    fun nextCheckDelayMs(elapsedMs: Long): Long = when {
        elapsedMs < 3_000L -> 200L
        elapsedMs < 15_000L -> 750L
        else -> 2_000L
    }

    fun shouldWait(task: TaskCheckpoint,active: String?,own: String): Boolean {
        if(task.lastAction.isBlank()) return false
        return active==own || (active=="com.android.systemui" && task.activePackage!="com.android.systemui")
    }
}
