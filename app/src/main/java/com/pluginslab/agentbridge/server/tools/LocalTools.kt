package com.pluginslab.agentbridge.server.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

object LocalTools {
    data class Entry(val name: String, val description: String, val schema: Tool.Input,
                     val handler: suspend (CallToolRequest) -> CallToolResult)
    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var ready = false
    fun register(name: String, description: String, schema: Tool.Input,
                 handler: suspend (CallToolRequest) -> CallToolResult) {
        entries[name] = Entry(name, description, schema, handler)
    }
    @Synchronized fun prepare() {
        if (ready) return
        val server = Server(Implementation(name = "AgentBridge local", version = "0.5.0"),
            ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))))
        ToolRegistry.registerAll(server)
        ready = true
    }
    fun declarations(allowed: Set<String>): JsonArray {
        prepare()
        return JsonArray(entries.values.filter { it.name in allowed }.sortedBy { it.name }.map {
            buildJsonObject {
                put("name", it.name); put("description", it.description)
                put("parametersJsonSchema", buildJsonObject {
                    Json.encodeToJsonElement(it.schema).jsonObject.forEach { (key,value) -> put(key,value) }
                    put("type", "object")
                })
            }
        })
    }
    suspend fun call(name: String, args: JsonObject): JsonObject {
        prepare()
        val entry = entries[name] ?: error("Неизвестный инструмент: $name")
        val result = entry.handler(CallToolRequest(name = name, arguments = args))
        return Json.encodeToJsonElement(result).jsonObject
    }
}
