package com.pluginslab.agentbridge.agent

import android.content.Context
import android.os.Build
import com.pluginslab.agentbridge.BuildConfig
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local, bounded, privacy-first diagnostic event log.
 * Only allowlisted event types and numeric/status metadata are persisted.
 * No prompts, UI content, request bodies, responses, URLs, keys or tokens.
 */
object Diagnostics {
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP_BYTES = 256 * 1024
    private val allowed = setOf("task_start", "task_finish", "phase", "api_error", "tool_result", "app_error")
    private var app: Context? = null

    @Synchronized fun init(context: Context) { app = context.applicationContext }
    @Synchronized fun record(type: String, status: String = "", durationMs: Long = -1L) {
        if (type !in allowed) return
        val context = app ?: return
        val safeStatus = status.take(48).takeIf { it.matches(Regex("[A-Za-z0-9_./:-]*")) }.orEmpty()
        val event = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("event", type)
            put("status", safeStatus)
            if (durationMs >= 0) put("duration_ms", durationMs)
        }.toString() + "\n"
        runCatching {
            val file = File(context.filesDir, "diagnostics.jsonl")
            if (file.length() + event.toByteArray().size > MAX_BYTES) {
                val bytes = file.readBytes()
                val tail = bytes.takeLast(KEEP_BYTES).toByteArray().toString(Charsets.UTF_8)
                file.writeText(tail.substringAfter('\n', ""))
            }
            file.appendText(event)
        }
    }

    @Synchronized fun export(context: Context): String {
        init(context)
        val header = JSONObject().apply {
            put("format", "agentbridge-diagnostics-v1")
            put("version", BuildConfig.VERSION_NAME)
            put("sdk", Build.VERSION.SDK_INT)
            put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            put("privacy", "allowlisted metadata only; no prompts, API keys or tool arguments")
        }
        val file = File(context.filesDir, "diagnostics.jsonl")
        return header.toString() + "\n" + (if (file.exists()) file.readText() else "")
    }
}
