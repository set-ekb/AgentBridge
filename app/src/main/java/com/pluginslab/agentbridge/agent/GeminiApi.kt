package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GeminiApi(private val key: String, private val status: (String) -> Unit = {},
                private val exchange: suspend (String, JsonObject?) -> Reply = { path,payload -> http(key,path,payload) },
                private val pause: suspend (Long) -> Unit = { delay(it) },
                private val now: () -> Long = System::currentTimeMillis) {
    data class Reply(val code: Int, val body: JsonObject, val retryAfter: String? = null)
    companion object {
        private suspend fun http(key: String, path: String, payload: JsonObject?): Reply = withContext(Dispatchers.IO) {
            val connection = URL("https://generativelanguage.googleapis.com/v1beta$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10000; connection.readTimeout = 45000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("x-goog-api-key", key)
                if (payload != null) {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
                Reply(code,runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(buildJsonObject {}),connection.getHeaderField("Retry-After"))
            } finally { connection.disconnect() }
        }
    }
    suspend fun request(path: String, payload: JsonObject? = null): JsonObject {
        var attempt = 0
        var waitedMs = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val (code, body, retryAfter) = ApiQueue.transmit(payload) {
                ProviderAttemptTrace.exchange(code = { it.code }) { exchange(path,payload) }
            }
            currentCoroutineContext().ensureActive()
            if (code in 200..299) return body
            val serverWait=GeminiRetryPolicy.serverDelayMs(body,retryAfter,now())
            if(code in setOf(429,503) && serverWait!=null) ApiQueue.block(serverWait+1000,"Gemini HTTP $code · срок сервера")
            val wait = GeminiRetryPolicy.delayMs(code,body,retryAfter,attempt,waitedMs,now())
            if (wait != null) {
                ApiQueue.block(wait+if(currentCoroutineContext()[QuotaContext]!=null && GeminiRetryPolicy.serverDelayMs(body,retryAfter,now())==null) kotlin.random.Random.nextLong(0,1500) else 0L,"Gemini HTTP $code · серверная задержка / backoff")
                val reason = if(code == 429 && GeminiRetryPolicy.minuteLimited(body)) "минутная квота" else "ожидание API"
                var remaining = wait
                while (remaining > 0) {
                    status("Gemini HTTP $code · $reason · продолжение через ${(remaining+999)/1000} с · повтор ${attempt+1}")
                    val part = minOf(remaining,1_000L)
                    pause(part); currentCoroutineContext().ensureActive(); remaining -= part
                }
                waitedMs += wait; attempt++
            } else {
                val permanentQuota=code==429 && GeminiRetryPolicy.permanentQuota(body)
                if(permanentQuota) ApiQueue.block(QuotaPlanner.DAY,"Подтверждённый дневной предел; задача сохранена")
                val hint = when (code) {
                    429 -> AgentPolicy.quotaHint(body)
                    401, 403 -> "Проверьте API-ключ, доступ к модели и регион."
                    400 -> "Модель не приняла формат запроса. Попробуйте другую модель с инструментами."
                    404 -> "Модель не найдена; обновите список моделей."
                    503 -> "Сервис временно недоступен."
                    else -> "Ошибка API."
                }
                throw ProviderHttpFailure(code,retryAfter,permanentQuota,"Gemini HTTP $code: $hint")
            }
        }
    }
    suspend fun models(): List<String> {
        val result = mutableSetOf<String>(); var page = ""
        repeat(10) {
            val response = request("/models?pageSize=100&pageToken=" + URLEncoder.encode(page, "UTF-8"))
            response["models"]?.jsonArray.orEmpty().forEach { item ->
                val obj = item.jsonObject; val name = obj["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") ?: return@forEach
                if (name.startsWith("gemini-") && obj["supportedGenerationMethods"]?.jsonArray.orEmpty().any { it.jsonPrimitive.content == "generateContent" }) result.add(name)
            }
            page = response["nextPageToken"]?.jsonPrimitive?.contentOrNull ?: ""
            if (page.isEmpty()) return result.sorted()
        }
        return result.sorted()
    }
}
