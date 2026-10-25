package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * A strictly scoped, authenticated client for the opt-in Termux task gateway.
 * No shell/proxy API is exposed to Android; OpenCode controls its own native tools.
 */
internal class GatewayWaitUnsupported : IllegalStateException("Версия шлюза не поддерживает ожидание событий")

internal object OpenCodeTaskClient {
    internal data class Update(val state: String, val answer: String = "", val reason: String = "")
    private val validStates = setOf("running", "done", "cancelled", "failed", "unknown")
    private const val base = "http://127.0.0.1:4097"

    fun decodeUpdate(text: String): Update {
        val json = Json.parseToJsonElement(text).jsonObject
        val state = json["state"]?.jsonPrimitive?.content ?: error("Нет статуса OpenCode")
        require(state in validStates) { "Неверный статус OpenCode" }
        val answer = json["answer"]?.jsonPrimitive?.content.orEmpty()
        require(answer.length <= 12288) { "Слишком большой ответ OpenCode" }
        val reason = json["reason"]?.jsonPrimitive?.content.orEmpty().take(120)
        return Update(state, answer, reason)
    }

    private fun validate(id: String, token: String) {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "Неверный идентификатор задания"
        }
        require(token.matches(Regex("[A-Za-z0-9_-]{20,128}"))) { "Сохраните ключ сопряжения OpenCode" }
    }

    internal suspend fun request(path: String, token: String, payload: JsonObject? = null, longWait: Boolean = false,
        connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
    ): Update = withContext(Dispatchers.IO) {
        val connection = connect(base + path)
        try {
            connection.connectTimeout = 3000
            connection.readTimeout = if (longWait) 25000 else 16000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (payload == null) "GET" else "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status != 200) {
                if (longWait && status == 404) throw GatewayWaitUnsupported()
                val explanation = when (status) {
                    401, 403 -> "Ключ OpenCode отклонён"
                    404 -> "Задача отсутствует в шлюзе; повторно отправлять её нельзя без проверки"
                    405 -> "Запуск задач отключён в Termux (требуется --enable-tasks)"
                    503 -> "OpenCode недоступен или не подтвердил остановку"
                    else -> "Локальный шлюз OpenCode: HTTP $status"
                }
                error(explanation)
            }
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(13000) }
            require(raw.length <= 12500) { "Слишком большой ответ шлюза OpenCode" }
            decodeUpdate(raw)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun start(id: String, prompt: String, token: String): Update {
        validate(id, token)
        require(prompt.length in 1..16000) { "Слишком длинная задача OpenCode" }
        return request("/tasks", token, buildJsonObject { put("id", id); put("prompt", prompt) })
    }

    suspend fun poll(id: String, token: String): Update {
        validate(id, token)
        return request("/tasks/$id", token)
    }

    /** A long-held HTTP read in Dispatchers.IO, not a model call or rapid UI polling. */
    suspend fun waitForChange(id: String, token: String): Update {
        validate(id, token)
        return request("/tasks/$id/wait?timeout=15", token, longWait = true)
    }

    suspend fun abort(id: String, token: String): Update {
        validate(id, token)
        return request("/tasks/$id/cancel", token, buildJsonObject {})
    }
}
