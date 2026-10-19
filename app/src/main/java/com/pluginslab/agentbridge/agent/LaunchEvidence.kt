package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*

/** startActivity returning normally is evidence of a request, not foreground success. */
object LaunchEvidence {
    fun result(expected: String, active: String?, route: String): JsonObject = buildJsonObject {
        val verified = active == expected
        put("requested",true)
        put("success",verified)
        put("verified",verified)
        put("package",expected)
        put("current_package",active ?: "unknown")
        put("launch_context",route)
        if (!verified) put("error","Запрос запуска отправлен, но нужное приложение не появилось на экране за 8 секунд. Причина отказа Android по этому результату не установлена.")
    }
}
