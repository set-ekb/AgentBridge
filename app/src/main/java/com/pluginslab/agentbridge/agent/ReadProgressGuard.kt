package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Repeats are tied to observed screen + result, not the lifetime of the task. */
class ReadProgressGuard {
    private data class Seen(val evidence: String, val count: Int)
    private val seen = mutableMapOf<String,Seen>()
    private fun canonical(value: JsonElement): JsonElement = when(value) {
        is JsonObject -> JsonObject(value.filterKeys { it != "render_wait_ms" }.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map { canonical(it) })
        else -> value
    }
    fun record(name: String, args: JsonObject, result: JsonObject, screen: String?): Int {
        val key = name + canonical(args).toString()
        val bytes = ((screen ?: "no_window") + canonical(result).toString()).toByteArray(Charsets.UTF_8)
        val evidence = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val previous = seen[key]
        val count = if(previous?.evidence == evidence) previous.count + 1 else 1
        seen[key] = Seen(evidence,count)
        return count
    }
}
