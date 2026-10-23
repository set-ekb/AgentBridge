package com.pluginslab.agentbridge.agent

import android.content.Context
import android.content.Intent

/** Fixed user-invoked command. No prompt, token, password or model-provided shell text. */
internal object OpenCodeLauncher {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    fun start(context: Context) {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(
                "/data/data/com.termux/files/home/projects/agentbridge-opencode/opencode_start.py",
                "--enable-tasks", "--supervise"
            ))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "AgentBridge · OpenCode startup")
        }
        check(context.startService(intent) != null) { "Termux не найден. Установите и откройте Termux." }
    }
}
