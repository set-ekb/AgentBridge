package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

object GeminiRetryPolicy {
    const val MAX_WAIT_MS = 300_000L
    private fun details(body: JsonObject): List<JsonObject> =
        ((body["error"] as? JsonObject)?.get("details") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun violations(body: JsonObject): List<JsonObject> = details(body).flatMap {
        (it["violations"] as? JsonArray).orEmpty().mapNotNull { value -> value as? JsonObject }
    }

    fun minuteLimited(body: JsonObject): Boolean = violations(body).any {
        val id = (it["quotaId"] as? JsonPrimitive)?.content.orEmpty()
        val metric = (it["quotaMetric"] as? JsonPrimitive)?.content.orEmpty()
        id.contains("PerMinute",true) || metric.contains("per_minute",true)
    }

    fun permanentQuota(body: JsonObject): Boolean = violations(body).any {
        val id = (it["quotaId"] as? JsonPrimitive)?.content.orEmpty()
        val metric = (it["quotaMetric"] as? JsonPrimitive)?.content.orEmpty()
        id.contains("PerDay",true) || metric.contains("per_day",true) ||
            (it["quotaValue"] as? JsonPrimitive)?.doubleOrNull == 0.0
    }

    private fun secondsMs(value: String?): Long? {
        val seconds = value?.toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds < 0 || seconds > 86400) return null
        return ceil(seconds*1000).toLong()
    }

    fun serverDelayMs(body: JsonObject, retryAfter: String?, nowMs: Long): Long? {
        val header = secondsMs(retryAfter) ?: runCatching {
            retryAfter?.let { (ZonedDateTime.parse(it,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-nowMs).coerceAtLeast(0) }
        }.getOrNull()
        val info = details(body).filter {
            (it["@type"] as? JsonPrimitive)?.content?.endsWith("google.rpc.RetryInfo") == true
        }.mapNotNull { (it["retryDelay"] as? JsonPrimitive)?.content?.removeSuffix("s")?.let(::secondsMs) }.maxOrNull()
        return listOfNotNull(header,info).maxOrNull()
    }

    /** retryIndex is zero for the first retry. Daily/zero-entitlement quotas must not loop. */
    fun delayMs(code: Int, body: JsonObject, retryAfter: String?, retryIndex: Int,
                waitedMs: Long, nowMs: Long): Long? {
        if (retryIndex < 0) return null
        if (code == 429 && permanentQuota(body)) return null
        val fallback = when {
            code == 429 && retryIndex < 4 -> 65_000L * (1L shl retryIndex.coerceAtMost(2))
            code == 503 && retryIndex < 3 -> 3_000L * (1L shl retryIndex)
            else -> return null
        }
        val delay = ((serverDelayMs(body,retryAfter,nowMs) ?: fallback) + 1_000L).coerceAtLeast(1_000L)
        return delay.takeIf { it <= MAX_WAIT_MS - waitedMs }
    }
}
