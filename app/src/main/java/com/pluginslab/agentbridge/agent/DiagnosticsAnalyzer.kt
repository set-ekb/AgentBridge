package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*
import kotlin.math.ceil

/**
 * Deterministic, read-only analysis of the privacy-filtered diagnostics JSONL.
 * This class never reads raw task text, tool arguments, UI content or provider payloads.
 * It can be tested on the JVM without Android and used by any LLM provider.
 */
object DiagnosticsAnalyzer {
    private val json = Json { ignoreUnknownKeys = true }
    private val validEvents = setOf(
        "app_start", "task_start", "task_finish", "task_error", "phase",
        "api_request", "api_response", "api_error", "api_cancelled", "tool_start", "tool_finish",
        "tool_error", "tool_result", "ui_event", "hook_status", "hook_event",
        "hook_error", "app_error"
    )
    private val identifier = Regex("[A-Za-z0-9_.:/-]{1,64}")
    private val providers = setOf("gemini", "openai", "anthropic", "compatible")
    private val errors = setOf("api_error", "tool_error", "task_error", "hook_error", "app_error")
    private const val MAX_EVENTS = 1800

    data class Event(
        val time: Long,
        val kind: String,
        val source: String,
        val status: String,
        val detail: String,
        val durationMs: Long?,
        val step: Int?,
        val requestId: String = ""
    )

    data class Window(
        val events: List<Event>,
        val linesSeen: Int,
        val badLines: Int,
        val lookbackHours: Int,
        val truncated: Boolean
    ) {
        val firstTime: Long? get() = events.minOfOrNull { it.time }
        val lastTime: Long? get() = events.maxOfOrNull { it.time }
    }

    private fun JsonObject.string(name: String): String =
        (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.long(name: String): Long? =
        (get(name) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(name: String): Int? =
        (get(name) as? JsonPrimitive)?.intOrNull
    private fun safe(raw: String): String = raw.takeIf { identifier.matches(it) }.orEmpty()

    /**
     * Read at most the newest MAX_EVENTS input lines in a caller-provided window.
     * The source journal rotates; absence of an event is NEVER proof that it did not happen.
     */
    fun parse(lines: List<String>, hours: Int = 24, now: Long = System.currentTimeMillis()): Window {
        val h = hours.coerceIn(1, 72)
        val recent = lines.takeLast(MAX_EVENTS)
        val start = now - h * 60L * 60L * 1000L
        var malformed = 0
        val events = recent.mapNotNull { line ->
            try {
                val obj = json.parseToJsonElement(line) as? JsonObject
                if (obj == null) {
                    malformed++
                    return@mapNotNull null
                }
                val kind = obj.string("event")
                val time = obj.long("time") ?: 0L
                if (kind !in validEvents || time <= 0L) {
                    malformed++
                    return@mapNotNull null
                }
                if (time < start || time > now + 60_000L) return@mapNotNull null
                val duration = obj.long("duration_ms")?.takeIf { it in 0L..86_400_000L }
                Event(
                    time = time,
                    kind = kind,
                    source = safe(obj.string("source")),
                    status = safe(obj.string("status")),
                    detail = safe(obj.string("detail")),
                    durationMs = duration,
                    step = obj.int("step")?.takeIf { it in 0..10000 },
                    requestId = obj.string("request_id").takeIf {
                        it.matches(Regex("[A-Za-z0-9_-]{1,64}"))
                    }.orEmpty()
                )
            } catch (_: Exception) {
                malformed++
                null
            }
        }
        return Window(events, recent.size, malformed, h, lines.size >= MAX_EVENTS)
    }

    private fun coverage(w: Window): JsonObject = buildJsonObject {
        put("lookback_hours", w.lookbackHours)
        put("event_count", w.events.size)
        put("bad_lines", w.badLines)
        put("journal_may_be_truncated", w.truncated)
        put("historical_completeness", "unknown")
        w.firstTime?.let { put("first_observed_at", it) }
        w.lastTime?.let { put("last_observed_at", it) }
    }

    private fun counts(items: Iterable<String>): JsonObject = buildJsonObject {
        items.groupingBy { it }.eachCount().toSortedMap().forEach { (name, n) ->
            put(name, n)
        }
    }
    private fun isError(e: Event) = e.kind in errors ||
        (e.kind == "tool_finish" && e.status in setOf("error", "rejected", "interrupted"))

    fun summary(w: Window): JsonObject = buildJsonObject {
        put("coverage", coverage(w))
        put("events_by_type", counts(w.events.map { it.kind }))
        val finished = w.events.filter { it.kind == "task_finish" }
        put("task_outcomes_observed", counts(finished.map { it.status.ifBlank { "unknown" } }))
        put("task_starts_observed", w.events.count { it.kind == "task_start" })
        put("errors_observed", w.events.count(::isError))
        put("note", "Observed events only; legacy api_error may include countdown updates. Error events are not unique failures. Missing or rotated entries are not proof of success.")
    }

    fun recentErrors(w: Window, limit: Int = 20): JsonObject = buildJsonObject {
        val filtered = w.events.filter(::isError)
        put("coverage", coverage(w))
        put("count_in_window", filtered.size)
        put("groups", buildJsonArray {
            filtered.groupBy { listOf(it.kind, it.status, it.detail) }
                .entries.sortedByDescending { (_, list) -> list.maxOf { it.time } }
                .take(limit.coerceIn(1, 30))
                .forEach { (key, group) ->
                    add(buildJsonObject {
                        put("event", key[0]);put("status", key[1]);put("category", key[2])
                        put("count", group.size)
                        put("last_seen_at", group.maxOf { it.time })
                    })
                }
        })
        put("note", "Category event counts only; legacy API countdown updates can repeat one failure. Exception texts, request bodies and tokens are unavailable.")
    }

    fun apiFailures(w: Window): JsonObject = buildJsonObject {
        val api = w.events.filter { it.kind.startsWith("api_") }
        val failed = api.filter { it.kind == "api_error" }
        val durations = api.filter { it.kind == "api_response" }.mapNotNull { it.durationMs }.sorted()
        put("coverage", coverage(w))
        put("api_requests_observed", api.count { it.kind == "api_request" })
        put("api_responses_observed", api.count { it.kind == "api_response" })
        put("api_errors_observed", failed.size)
        put("http_attempts_observed", api.count { it.kind == "api_request" && it.requestId.isNotBlank() })
        put("http_failures_observed", failed.count { it.requestId.isNotBlank() })
        put("legacy_api_error_events", failed.count { it.requestId.isBlank() })
        put("api_cancellations_observed", api.count { it.kind == "api_cancelled" })
        put("error_categories", counts(failed.map { it.status.ifBlank { "unknown" } }))
        put("errors_by_provider", counts(failed.map {
            it.detail.takeIf { provider -> provider in providers } ?: "unattributed"
        }))
        put("latency_ms", latency(durations))
        put("note", "Only request_id-tagged events count actual HTTP attempts. Legacy api_error events may include repeated countdown updates; their count is not a failed-request count. Latency may mix legacy logical calls and actual attempts. No live network request is performed.")
    }

    private fun percentile(values: List<Long>, fraction: Double): Long? =
        values.getOrNull((ceil(values.size * fraction).toInt() - 1).coerceAtLeast(0))
    private fun latency(sorted: List<Long>): JsonObject = buildJsonObject {
        put("samples", sorted.size)
        percentile(sorted, 0.5)?.let { put("p50", it) }
        percentile(sorted, 0.95)?.let { put("p95", it) }
    }

    fun toolPerformance(w: Window): JsonObject = buildJsonObject {
        val tools = w.events.filter { it.kind == "tool_finish" || it.kind == "tool_error" }
        put("coverage", coverage(w))
        put("tools", buildJsonArray {
            tools.groupBy { it.detail.ifBlank { "unknown" } }
                .entries.sortedByDescending { (_, entries) -> entries.size }.take(25)
                .forEach { (name, entries) ->
                    val times = entries.mapNotNull { it.durationMs }.sorted()
                    add(buildJsonObject {
                        put("tool", name)
                        put("completed_or_errored", entries.size)
                        put("reported_error_or_rejected", entries.count(::isError))
                        put("reported_ok", entries.count { it.kind == "tool_finish" && it.status == "ok" })
                        put("latency_ms", latency(times))
                    })
                }
        })
        put("note", "tool_finish=ok records tool response only, never verifies user's intended result")
    }

    fun hookPipeline(w: Window): JsonObject = buildJsonObject {
        val events = w.events
        val stages = events.filter { it.kind == "hook_event" || it.kind == "hook_status" }
        val accessible = events.filter { it.kind == "ui_event" && it.source == "accessibility" }
        put("coverage", coverage(w))
        put("accessibility_events_observed", accessible.size)
        put("vector_hints_observed", events.count { it.kind == "hook_event" })
        put("hook_callback_status_observed", events.count {
            it.kind == "hook_status" && it.status == "callback_active"
        })
        put("receiver_rejections_observed", events.count { it.kind == "hook_error" })
        put("vector_signal", if (stages.isEmpty()) "not_observed" else "untrusted_hints_observed")
        put("module_loaded_in_vector", "unknown")
        put("hooks_installed_in_vector", "unknown")
        put("next_step", if (stages.isEmpty()) "inspect_vector_logs_and_scope" else "verify_real_ui_state")
        put("note", "Test broadcasts can forge vector_hint. No Vector system log access is available here.")
    }

    fun health(w: Window, accessibilityConnected: Boolean, agentRunning: Boolean): JsonObject =
        buildJsonObject {
            put("coverage", coverage(w))
            put("accessibility", if (accessibilityConnected) "connected" else "disconnected")
            put("journal", if (w.events.isEmpty()) "insufficient_evidence" else "events_observed")
            put("agent", if (agentRunning) "running" else "idle_or_not_running")
            put("provider_api", "not_checked")
            put("vector", if (w.events.any { it.kind == "hook_event" || it.kind == "hook_status" })
                "hints_observed_unverified" else "unknown_optional")
            put("overall", when {
                !accessibilityConnected -> "degraded"
                w.events.isEmpty() -> "unknown"
                else -> "operational_observations"
            })
            put("note", "Health is based on local observations, not an API or Vector liveness probe")
        }
}
