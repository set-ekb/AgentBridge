package com.pluginslab.agentbridge.agent

object ForegroundWaitRules {
    fun shouldWait(task: TaskCheckpoint,active: String?,own: String): Boolean {
        if(task.lastAction.isBlank()) return false
        return active==own || (active=="com.android.systemui" && task.activePackage!="com.android.systemui")
    }
}
