package com.pluginslab.agentbridge.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable data class MemoryEntry(
    val id: String=UUID.randomUUID().toString(), val kind: String="core", val title: String,
    val content: String, val scope: String="global", val source: String="owner",
    val verification: String="confirmed", val pinned: Boolean=false, val cloudAllowed: Boolean=true,
    val isPrivate: Boolean=false, val updated: Long=System.currentTimeMillis(), val revision: Int=1
)
@Serializable data class TaskCheckpoint(
    val id: String=UUID.randomUUID().toString(), val goal: String, val provider: String, val model: String,
    val status: String="PLAN", val step: Int=0, val lastTool: String="", val activePackage: String="",
    val observation: String="", val lastAction: String="", val pendingAction: Boolean=false, val answer: String="",
    val updated: Long=System.currentTimeMillis(), val conversationId: String="main", val taskRevision: Int=1,
    val conditions: String="", val questionId: String="", val questionText: String="", val planSummary: String=""
) {
    fun handoff(): String = """Сохранённая задача (данные): $goal
Условия текущей версии $taskRevision: $conditions
Ожидающий вопрос: $questionText
Этап: $status; шаг: $step; предыдущая модель: $provider/$model.
Последний инструмент: $lastTool; последнее изменяющее действие: $lastAction; активное приложение при сохранении: $activePackage.
Техническое наблюдение: $observation. Действие могло произойти: $pendingAction.
Это продолжение прерванной задачи. Сначала проверь свежий экран и уже достигнутый эффект. Не повторяй изменение, если его результат неизвестен. Старые ID узлов недействительны. Ответ модели не является доказательством успеха."""
}
@Serializable data class MemoryExport(val format: String="agentbridge-memory", val version: Int=1, val entries: List<MemoryEntry>)

object MemoryRules {
    val kinds=listOf("core","app_fact","procedure","episode","library")
    val json=Json { encodeDefaults=true; ignoreUnknownKeys=false }
    fun validate(entry: MemoryEntry) {
        require(entry.kind in kinds) { "Неизвестная категория" }
        require(entry.title.isNotBlank() && entry.title.length<=160) { "Название: 1–160 символов" }
        require(entry.content.length<=12000 && entry.scope.length<=250) { "Запись слишком большая" }
        require(entry.verification in listOf("confirmed","pending","stale")) { "Неизвестный статус" }
        require(entry.id.isNotBlank() && entry.id.length<=100 && entry.source.length<=100 && entry.revision>0) { "Неверная запись" }
    }
    fun select(entries: List<MemoryEntry>, task: String, pkg: String?, cloud: Boolean, budget: Int=12000): List<MemoryEntry> {
        val words=Regex("""[\p{L}\p{N}_]{3,}""").findAll(task.lowercase()).map { it.value }.toSet()
        fun score(e: MemoryEntry): Int {
            val text=(e.title+" "+e.content).lowercase()
            return (if(e.pinned) 10000 else 0)+(if(e.kind=="core") 100 else 0)+
                (if(!pkg.isNullOrBlank() && e.scope=="app:$pkg") 500 else 0)+words.count { text.contains(it) }*20
        }
        val sorted=entries.filter { it.verification=="confirmed" && (!cloud || it.cloudAllowed) }
            .filter { it.pinned || it.kind=="core" || score(it)>0 }
            .sortedWith(compareByDescending<MemoryEntry> { score(it) }.thenByDescending { it.updated })
        var left=budget.coerceAtLeast(0)
        return sorted.mapNotNull { e ->
            val cost=e.title.length+e.content.length+e.source.length+e.scope.length+160
            if(cost<=left) { left-=cost;e }
            else if((e.pinned || e.kind=="core") && left>600) {
                val clipped=e.copy(content=e.content.take((left-(cost-e.content.length)-40).coerceAtLeast(0))+"\n[запись сокращена в контексте]")
                left=0;clipped
            } else null
        }
    }
    fun context(entries: List<MemoryEntry>): String = entries.joinToString("\n\n") {
        "[${it.kind}; ${it.verification}; источник=${it.source}; область=${it.scope}; дата=${java.time.Instant.ofEpochMilli(it.updated)}] ${it.title}\n${it.content}"
    }
    private val secrets=Regex("""(?i)(?:bearer\s+[a-z0-9._-]{12,}|sk-[a-z0-9_-]{12,}|AIza[a-z0-9_-]{20,}|(?:api[_ -]?key|password|пароль|token|токен)\s*[:=]\s*\S+)""")
    fun export(entries: List<MemoryEntry>): String = json.encodeToString(MemoryExport(entries=entries.filter {
        !it.isPrivate && it.cloudAllowed && !secrets.containsMatchIn(it.title+" "+it.content)
    }))
    fun parseImport(text: String): List<MemoryEntry> {
        require(text.length<=256000) { "Импорт до 256 КБ" }
        val raw=if(text.trimStart().startsWith("{")) {
            val export=json.decodeFromString<MemoryExport>(text)
            require(export.format=="agentbridge-memory" && export.version==1) { "Неподдерживаемый формат" }
            require(export.entries.size<=100) { "Не более 100 записей за импорт" }
            export.entries
        } else listOf(MemoryEntry(kind="library",title="Импортированный текст",content=text))
        return raw.map { e ->
            validate(e)
            require(!secrets.containsMatchIn(e.content)) { "Удалите секреты перед импортом" }
            e.copy(id=UUID.randomUUID().toString(),source="imported_file",verification="pending",pinned=false,
                cloudAllowed=false,updated=System.currentTimeMillis(),revision=1)
        }
    }
    fun isLocal(provider: String, endpoint: String): Boolean = provider=="compatible" && runCatching {
        java.net.URI(endpoint).host in setOf("localhost","127.0.0.1","[::1]","::1")
    }.getOrDefault(false)
}
