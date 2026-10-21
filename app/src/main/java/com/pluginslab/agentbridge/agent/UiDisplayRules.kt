package com.pluginslab.agentbridge.agent

/** Pure presentation rules; keep narrow-phone labels and memory pagination stable. */
object UiDisplayRules {
    data class Tab(val short: String, val accessible: String)
    val tabs = listOf(
        Tab("Агент", "Агент"),
        Tab("Память", "Память"),
        Tab("Настр.", "Настройки"),
        Tab("Журнал", "Журнал"),
        Tab("Статус", "Состояние")
    )
    const val MEMORY_PAGE_SIZE = 60

    fun activityName(fullClass: String, showFull: Boolean): String =
        if (showFull) fullClass else fullClass.substringAfterLast('.')

    fun nextMemoryLimit(current: Int, total: Int): Int =
        (current.coerceAtLeast(0) + MEMORY_PAGE_SIZE).coerceAtMost(total.coerceAtLeast(0))
}
