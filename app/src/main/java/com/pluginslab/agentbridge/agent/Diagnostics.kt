package com.pluginslab.agentbridge.agent

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified, persistent, bounded diagnostic journal for AgentBridge.
 *
 * Strict allowlist; NEVER persist task text, UI content, API payloads, URLs,
 * secrets, exception messages, or tool arguments. Vector signals are untrusted.
 * The same JSONL is displayed in the Journal and exported with a schema header.
 */
object Diagnostics {
    private const val FILE_NAME = "diagnostics.jsonl"
    private const val MAX_BYTES = 768 * 1024L
    private const val KEEP_BYTES = 384 * 1024
    private val allowed = setOf(
        "app_start", "task_start", "task_finish", "task_error", "phase",
        "api_request", "api_response", "api_error",
        "tool_start", "tool_finish", "tool_error", "tool_result",
        "ui_event", "hook_status", "hook_event", "hook_error", "app_error"
    )
    private val sources = setOf("agent", "provider", "accessibility", "vector_hint", "bridge")
    private var app: Context? = null

    @Synchronized fun init(context: Context) {
        app = context.applicationContext
    }

    private fun safe(raw: String, max: Int, pattern: Regex): String =
        raw.takeIf { it.length <= max && pattern.matches(it) }.orEmpty()

    private val labelPattern = Regex("[a-zA-Z0-9_.:/-]{1,64}")
    private val packagePattern = Regex("[a-zA-Z0-9_.]{1,180}")
    private val activityPattern = Regex("[a-zA-Z0-9_.$]{1,220}")

    /** Pure field validation for caller-side use and JVM tests. */
    internal fun safeLabel(value: String) = safe(value, 64, labelPattern)
    internal fun safePackage(value: String) = safe(value, 180, packagePattern)
    internal fun safeActivity(value: String) = safe(value, 220, activityPattern)

    @Synchronized fun record(
        type: String,
        status: String = "",
        durationMs: Long = -1L,
        source: String = "agent",
        packageName: String = "",
        activity: String = "",
        detail: String = "",
        step: Int = -1,
        attemptId: String = "",
        actionId: String = ""
    ) {
        if (type !in allowed || source !in sources) return
        val context = app ?: return
        val event = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("elapsed_ms", SystemClock.elapsedRealtime())
            put("event", type)
            put("source", source)
            safeLabel(status).takeIf { it.isNotBlank() }?.let { put("status", it) }
            safePackage(packageName).takeIf { it.isNotBlank() }?.let { put("package_name", it) }
            safeActivity(activity).takeIf { it.isNotBlank() }?.let { put("activity", it) }
            safeLabel(detail).takeIf { it.isNotBlank() }?.let { put("detail", it) }
            if (durationMs >= 0) put("duration_ms", durationMs)
            if (step >= 0) put("step", step)
            // Random opaque correlation IDs; never store user's goal, UI or tool arguments.
            attemptId.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) }
                ?.let { put("attempt_id", it) }
            actionId.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) }
                ?.let { put("action_id", it) }
        }.toString() + "\n"
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val bytes = event.toByteArray(Charsets.UTF_8)
            if (file.length() + bytes.size > MAX_BYTES) {
                // Retain complete JSON lines only, never fragments after rotation.
                val tail = file.readBytes().takeLast(KEEP_BYTES).toByteArray().toString(Charsets.UTF_8)
                file.writeText(tail.substringAfter('\n', ""))
            }
            file.appendText(event)
        }
    }

    /**
     * On process restart, mark any tool_start without tool_finish as interrupted.
     * A process kill bypasses coroutine finally; this is recovery of a trace,
     * not an attempt to repeat the action. Never parse or record tool arguments.
     */
    @Synchronized fun closeAbandonedToolCalls() {
        val context = app ?: return
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.isFile) return@runCatching
            val pending = linkedMapOf<String, Triple<String, String, Int>>()
            file.readLines(Charsets.UTF_8).takeLast(2000).forEach { line ->
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                val id = obj.optString("action_id")
                    .takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: return@forEach
                when (obj.optString("event")) {
                    "tool_start" -> pending[id] = Triple(
                        safeLabel(obj.optString("detail")),
                        obj.optString("attempt_id").takeIf {
                            it.matches(Regex("[A-Za-z0-9_-]{1,64}"))
                        }.orEmpty(),
                        obj.optInt("step", -1)
                    )
                    "tool_finish" -> pending.remove(id)
                }
            }
            pending.entries.takeLast(32).forEach { (id, fields) ->
                val (tool, attempt, step) = fields
                record("tool_error", "orphaned", detail = tool,
                    step = step, attemptId = attempt, actionId = id)
                record("tool_finish", "interrupted", detail = tool,
                    step = step, attemptId = attempt, actionId = id)
            }
        }
    }

    /** Recent persisted events (unlike the in-memory UI timeline, survive process restarts). */
    @Synchronized fun recent(limit: Int = 30): List<String> {
        val context = app ?: return emptyList()
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readLines().takeLast(limit.coerceIn(1, 100)) else emptyList()
        }.getOrDefault(emptyList())
    }

    /** Bounded raw input for the local analyzer; never return these lines to a model. */
    @Synchronized fun snapshotLines(maxLines: Int = 1800): List<String> {
        val context = app ?: return emptyList()
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.isFile) file.readLines(Charsets.UTF_8)
                .takeLast(maxLines.coerceIn(1, 2400)) else emptyList()
        }.getOrDefault(emptyList())
    }

    /** Highlight hook states even if recent Accessibility events filled the UI timeline. */
    @Synchronized fun recentHooks(limit: Int = 20): List<String> {
        val context = app ?: return emptyList()
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) emptyList() else file.readLines()
                .filter { line ->
                    runCatching { JSONObject(line).optString("event").startsWith("hook_") }.getOrDefault(false)
                }.takeLast(limit.coerceIn(1, 50))
        }.getOrDefault(emptyList())
    }

    /** Local device-time stamp; fixed-width so logs sort correctly by filename. */
    fun exportFileName(at: Date = Date()): String =
        "AgentBridge-log-" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(at) + ".jsonl"

    @Synchronized fun export(context: Context): String {
        init(context)
        val header = JSONObject().apply {
            put("format", "agentbridge-diagnostics-v2")
            put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown")
            put("sdk", Build.VERSION.SDK_INT)
            put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            put("privacy", "allowlisted metadata only; no prompts, UI text, arguments, keys or payloads")
            put("vector_hints_trusted", false)
        }
        val file = File(context.filesDir, FILE_NAME)
        return header.toString() + "\n" + (if (file.exists()) file.readText() else "")
    }
}
