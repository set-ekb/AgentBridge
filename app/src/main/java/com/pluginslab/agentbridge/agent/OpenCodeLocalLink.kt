package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** Separate pairing token; the OpenCode server password remains in Termux. */
internal object OpenCodeLocalLink {
    internal data class Status(
        val healthy: Boolean,
        val version: String,
        val activeSessions: Int,
        val readOnly: Boolean
    )

    internal fun decodeStatus(text: String): Status {
        val body = Json.parseToJsonElement(text).jsonObject
        require(body["gateway"]?.jsonPrimitive?.content == "agentbridge-opencode")
        require(body["mode"]?.jsonPrimitive?.content == "read_only")
        val healthy = body["healthy"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: error("Неизвестный статус OpenCode")
        val version = body["version"]?.jsonPrimitive?.content.orEmpty().take(40)
        val active = body["active_sessions"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("Некорректное количество сессий")
        require(active in 0..100000) { "Некорректное количество сессий" }
        return Status(healthy, version, active, readOnly = true)
    }

    suspend fun check(pairingToken: String): Status = withContext(Dispatchers.IO) {
        require(pairingToken.matches(Regex("[A-Za-z0-9_-]{20,128}"))) {
            "Введите ключ сопряжения OpenCode из Termux"
        }
        val connection = URL("http://127.0.0.1:4097/status").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $pairingToken")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            when (code) {
                401, 403 -> error("Неверный ключ сопряжения OpenCode")
                200 -> Unit
                else -> error("Локальный шлюз OpenCode ответил HTTP $code")
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readText().take(16385)
            }
            require(response.length <= 16384) { "Слишком большой ответ OpenCode" }
            decodeStatus(response)
        } catch (e: java.net.ConnectException) {
            error("Шлюз OpenCode не запущен в Termux (127.0.0.1:4097)")
        } catch (e: java.net.SocketTimeoutException) {
            error("Тайм-аут локального соединения OpenCode")
        } finally {
            connection.disconnect()
        }
    }
}
