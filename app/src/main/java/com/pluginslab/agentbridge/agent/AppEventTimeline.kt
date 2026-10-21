package com.pluginslab.agentbridge.agent

/** Read-only timeline. Xposed notifications are hints, never authorization to act. */
object AppEventTimeline {
    data class Event(
        val id: Long, val timestamp: Long, val source: String,
        val type: String, val packageName: String, val activity: String = ""
    )
    private const val CAPACITY = 120
    private val ring = ArrayDeque<Event>()
    private var nextId = 0L

    @Synchronized fun append(source: String, type: String, packageName: String, activity: String = "") {
        if (source !in setOf("accessibility", "vector_hint")) return
        if (type !in setOf("window", "windows", "resumed", "paused")) return
        if (!packageName.matches(Regex("[A-Za-z0-9_.]{1,180}"))) return
        if (activity.isNotEmpty() && !activity.matches(Regex("[A-Za-z0-9_.$]{1,220}"))) return
        val now = System.currentTimeMillis()
        val last = ring.lastOrNull()
        // Collapse only consecutive *identical* Accessibility notifications.
        // A different window/package always remains a separate transition.
        val duplicateWindowMs = if (source == "accessibility") 1500L else 500L
        if (last != null && last.source == source && last.type == type &&
            last.packageName == packageName && last.activity == activity &&
            now - last.timestamp in 0 until duplicateWindowMs) return
        ring.addLast(Event(++nextId, now, source, type, packageName, activity))
        // One logical event: bounded UI ring plus the persistent JSONL export.
        Diagnostics.record(
            type = if (source == "vector_hint") "hook_event" else "ui_event",
            status = type,
            source = source,
            packageName = packageName,
            activity = activity
        )
        while (ring.size > CAPACITY) ring.removeFirst()
    }

    @Synchronized fun recent(afterId: Long = 0, packageName: String? = null, limit: Int = 20): List<Event> =
        ring.filter { it.id > afterId && (packageName == null || it.packageName == packageName) }
            .takeLast(limit.coerceIn(1, 100))

    @Synchronized fun latestId(): Long = nextId
}
