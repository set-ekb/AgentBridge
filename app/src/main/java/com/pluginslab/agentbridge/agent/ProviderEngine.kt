package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

/** One shared bridge protocol; only the transport/format varies by LLM provider. */
data class AgentCall(val name: String, val args: JsonObject, val id: String = "")
data class AgentTurn(val answer: String, val calls: List<AgentCall>)
data class AgentReply(val call: AgentCall, val result: JsonObject)

interface ProviderEngine {
    suspend fun next(system: String, declarations: List<JsonObject>): AgentTurn
    fun accept(replies: List<AgentReply>)
}

internal object ProviderHttp {
    suspend fun post(url: String, headers: Map<String,String>, data: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        ProviderAddress.validate(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name,value) }
            connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if(code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(buildJsonObject { })
            if (code !in 200..299) {
                // Never embed raw HTTP errors: some endpoints echo sensitive user data.
                val hint = when(code) {
                    400 -> "Неверный формат запроса или модель не поддерживает вызовы инструментов"
                    401,403 -> "Проверьте API-ключ и права доступа"
                    404 -> "Проверьте модель и адрес API"
                    429 -> "Квота или лимит запросов; попробуйте другую модель или позже"
                    500,502,503,504 -> "Временная ошибка сервиса"
                    else -> "Проверьте настройки провайдера"
                }
                val permanent=((body["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull in setOf("insufficient_quota","billing_hard_limit_reached")
                throw ProviderHttpFailure(code,connection.getHeaderField("Retry-After"),permanent,"HTTP $code: $hint")
            }
            body
        } finally { connection.disconnect() }
    }
}

private fun JsonObject.string(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
private fun standardSchema(tool: JsonObject): JsonObject = tool["parametersJsonSchema"]?.jsonObject ?: buildJsonObject { put("type","object");put("properties",buildJsonObject { }) }

class GeminiEngine(key: String, private val model: String, initial: String, private val progress: (String)->Unit): ProviderEngine {
    private val api = GeminiApi(key, progress)
    private val contents = mutableListOf<JsonElement>(buildJsonObject {
        put("role","user");put("parts", buildJsonArray { add(buildJsonObject { put("text", initial) }) })
    })
    override suspend fun next(system: String, declarations: List<JsonObject>): AgentTurn {
        val response = api.request("/models/$model:generateContent",buildJsonObject {
            put("contents",JsonArray(contents.toList()))
            put("systemInstruction",buildJsonObject { put("parts",buildJsonArray { add(buildJsonObject { put("text",system) }) }) })
            if(declarations.isNotEmpty()) put("tools",buildJsonArray { add(buildJsonObject { put("functionDeclarations",JsonArray(declarations)) }) })
            put("generationConfig",buildJsonObject { put("maxOutputTokens",4096) })
        })
        check(response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.string("finishReason")!="MAX_TOKENS") { "Gemini: ответ обрезан; действия не выполнены" }
        val content = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject
            ?: error("Gemini не вернул ответ")
        // Preserve thoughtSignatures; dropping them breaks subsequent Gemini tool calls.
        contents.add(content)
        val calls = content["parts"]?.jsonArray.orEmpty().mapNotNull { part ->
            part.jsonObject["functionCall"]?.jsonObject?.let { AgentCall(it.string("name"), it["args"]?.jsonObject ?: buildJsonObject { },it.string("id")) }
        }
        return AgentTurn(AgentPolicy.publicAnswer(content), calls)
    }
    override fun accept(replies: List<AgentReply>) {
        contents.add(buildJsonObject { put("role","user");put("parts",buildJsonArray {
            replies.forEach { r -> add(buildJsonObject { put("functionResponse",buildJsonObject {
                put("name",r.call.name); if (r.call.id.isNotBlank()) put("id",r.call.id)
                put("response",buildJsonObject { put("result",r.result) })
            }) }) }
        }) })
    }
}

class OpenAiEngine(private val key: String, private val model: String, initial: String, private val post: suspend (String,Map<String,String>,JsonObject)->JsonObject=ProviderHttp::post): ProviderEngine {
    private val inputs = mutableListOf<JsonElement>(buildJsonObject { put("role","user");put("content",initial) })
    override suspend fun next(system: String, declarations: List<JsonObject>): AgentTurn {
        val response = post("https://api.openai.com/v1/responses",mapOf("Authorization" to "Bearer $key"),buildJsonObject {
            put("model",model);put("instructions",system);put("input",JsonArray(inputs.toList()));put("store",false)
            put("max_output_tokens",4096);put("include",buildJsonArray { add("reasoning.encrypted_content") });put("parallel_tool_calls",false)
            if(declarations.isNotEmpty()) put("tools",buildJsonArray { declarations.forEach { tool -> add(buildJsonObject {
                put("type","function");put("name",tool.string("name"));put("description",tool.string("description"))
                put("parameters",standardSchema(tool));put("strict",false)
            }) } })
        })
        check(response.string("status") !in setOf("incomplete","failed")) { "OpenAI: ответ обрезан; действия не выполнены" }
        val output = response["output"]?.jsonArray ?: error("OpenAI вернул ответ без output")
        // Stateless replay includes function calls and opaque reasoning items, if any.
        inputs.addAll(output)
        val calls = output.mapNotNull { item ->
            val obj = item.jsonObject
            if(obj.string("type") != "function_call") null else AgentCall(obj.string("name"),
                Json.parseToJsonElement(obj.string("arguments")).jsonObject,obj.string("call_id"))
        }
        val texts = output.filter { it.jsonObject.string("type") == "message" }.flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
            .mapNotNull { part -> part.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        return AgentTurn(texts.joinToString("\n").ifEmpty { response.string("output_text") },calls)
    }
    override fun accept(replies: List<AgentReply>) {
        replies.forEach { r -> inputs.add(buildJsonObject { put("type","function_call_output");put("call_id",r.call.id);put("output",r.result.toString()) }) }
    }
}

class AnthropicEngine(private val key: String, private val model: String, initial: String, private val post: suspend (String,Map<String,String>,JsonObject)->JsonObject=ProviderHttp::post): ProviderEngine {
    private val messages = mutableListOf<JsonElement>(buildJsonObject { put("role","user");put("content",initial) })
    override suspend fun next(system: String, declarations: List<JsonObject>): AgentTurn {
        val response = post("https://api.anthropic.com/v1/messages",mapOf(
            "x-api-key" to key,"anthropic-version" to "2023-06-01"), buildJsonObject {
            put("model",model);put("max_tokens",4096);put("system",system);put("messages",JsonArray(messages.toList()))
            if(declarations.isNotEmpty()) put("tools",buildJsonArray { declarations.forEach { tool -> add(buildJsonObject {
                put("name",tool.string("name"));put("description",tool.string("description"));put("input_schema",standardSchema(tool))
            }) } })
        })
        check(response.string("stop_reason")!="max_tokens") { "Claude: ответ обрезан; действия не выполнены" }
        val blocks = response["content"]?.jsonArray ?: error("Claude вернул ответ без content")
        // Keep full assistant blocks, including thinking and tool_use.
        messages.add(buildJsonObject { put("role","assistant");put("content",blocks) })
        val calls = blocks.mapNotNull { part -> val obj=part.jsonObject
            if(obj.string("type") != "tool_use") null else AgentCall(obj.string("name"),obj["input"]?.jsonObject ?: buildJsonObject { },obj.string("id"))
        }
        return AgentTurn(blocks.mapNotNull { part -> val obj=part.jsonObject
            if(obj.string("type") == "text") obj.string("text") else null
        }.joinToString("\n"),calls)
    }
    override fun accept(replies: List<AgentReply>) {
        messages.add(buildJsonObject { put("role","user");put("content", buildJsonArray { replies.forEach { r -> add(buildJsonObject {
            put("type","tool_result");put("tool_use_id",r.call.id);put("content",r.result.toString());put("is_error",r.result["isError"]?.jsonPrimitive?.booleanOrNull==true)
        }) } }) })
    }
}

/** OpenAI-compatible chat/completions endpoint for local gateways and other providers. */
class CompatibleEngine(private val key: String,private val model: String, private val endpoint: String,initial: String, private val post: suspend (String,Map<String,String>,JsonObject)->JsonObject=ProviderHttp::post): ProviderEngine {
    private val messages = mutableListOf<JsonElement>(buildJsonObject { put("role","user");put("content",initial) })
    override suspend fun next(system: String, declarations: List<JsonObject>): AgentTurn {
        val url = java.net.URI(endpoint)
        require(!url.host.isNullOrBlank() && url.userInfo == null && url.fragment == null) { "Укажите URL API без пароля в адресе" }
        val response = post(endpoint,if(key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap(),buildJsonObject {
            put("model",model);put("messages",buildJsonArray { add(buildJsonObject { put("role","system");put("content",system) });messages.forEach { add(it) } })
            if(declarations.isNotEmpty()) put("tools",buildJsonArray { declarations.forEach { tool -> add(buildJsonObject { put("type","function");put("function",buildJsonObject {
                put("name",tool.string("name"));put("description",tool.string("description"));put("parameters",standardSchema(tool))
            }) }) } })
        })
        check(response["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.string("finish_reason")!="length") { "Ответ обрезан; действия не выполнены" }
        val message = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: error("Нет choices.message")
        messages.add(message)
        val calls = message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { item -> val obj=item.jsonObject
            val fn=obj["function"]?.jsonObject ?: return@mapNotNull null
            AgentCall(fn.string("name"),Json.parseToJsonElement(fn.string("arguments")).jsonObject,obj.string("id"))
        }
        return AgentTurn(message.string("content"),calls)
    }
    override fun accept(replies: List<AgentReply>) {
        replies.forEach { r -> messages.add(buildJsonObject { put("role","tool");put("tool_call_id",r.call.id);put("content",r.result.toString()) }) }
    }
}

object ProviderFactory {
    fun create(provider: String,key: String,model: String,endpoint:String,initial:String,progress:(String)->Unit): ProviderEngine {
        val transport=ProviderRetry(progress)
        val post: suspend (String,Map<String,String>,JsonObject)->JsonObject = { url,headers,data -> transport.post(url,headers,data) }
        return when(provider) {
            "gemini" -> GeminiEngine(key,model,initial,progress)
            "openai" -> OpenAiEngine(key,model,initial,post)
            "anthropic" -> AnthropicEngine(key,model,initial,post)
            "compatible" -> CompatibleEngine(key,model,endpoint,initial,post)
            else -> error("Неизвестный провайдер")
        }
    }
}
