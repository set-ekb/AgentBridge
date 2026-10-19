package com.pluginslab.agentbridge.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** Titles, content, checkpoints, goals and events are encrypted, not only API keys. */
class MemoryStore private constructor(private val app: Context): SQLiteOpenHelper(app,"agent_memory.db",null,2) {
    companion object {
        @Volatile private var instance: MemoryStore?=null
        fun get(context: Context): MemoryStore = instance ?: synchronized(this) {
            instance ?: MemoryStore(context.applicationContext).also { it.migrate();instance=it }
        }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memory(id TEXT PRIMARY KEY,payload TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY,payload TEXT NOT NULL,updated INTEGER NOT NULL)")
        createConversationTables(db)
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,payload TEXT NOT NULL)")
    }
    private fun createConversationTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,conversation TEXT NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS quotas(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase,old: Int,new: Int) { if(old<2) createConversationTables(db) }
    private fun migrate() {
        val prefs=app.getSharedPreferences("native_agent",Context.MODE_PRIVATE)
        if(prefs.getBoolean("memory_db_v1",false)) return
        val notes=prefs.getString("owner_notes_encrypted",null)?.let { AgentCrypto.decrypt(it) }
            ?: prefs.getString("owner_notes",null) ?: AgentContext.defaults
        val db=writableDatabase;db.beginTransaction()
        try {
            if(notes.isNotBlank()) put(MemoryEntry(id="migrated-owner-context",title="Рабочий контекст владельца",content=notes.take(12000),pinned=true))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        check(prefs.edit().putBoolean("memory_db_v1",true).remove("owner_notes").remove("owner_notes_encrypted").commit())
    }
    @Synchronized fun entries(): List<MemoryEntry> = readableDatabase.rawQuery("SELECT payload FROM memory ORDER BY updated DESC,rowid DESC LIMIT 1000",null).use { c ->
        buildList { while(c.moveToNext()) add(MemoryRules.json.decodeFromString<MemoryEntry>(AgentCrypto.decrypt(c.getString(0)))) }
    }
    @Synchronized fun put(entry: MemoryEntry) {
        MemoryRules.validate(entry)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM memory",null).use { c ->
            c.moveToFirst()
            if(c.getInt(0)>=1000) readableDatabase.rawQuery("SELECT id FROM memory WHERE id=?",arrayOf(entry.id)).use { existing ->
                require(existing.moveToFirst()) { "Память: не более 1000 записей. Удалите ненужные." }
            }
        }
        writableDatabase.insertWithOnConflict("memory",null,ContentValues().apply {
            put("id",entry.id);put("payload",AgentCrypto.encrypt(MemoryRules.json.encodeToString(entry)));put("updated",entry.updated)
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
    }
    @Synchronized fun import(entries: List<MemoryEntry>) {
        val db=writableDatabase;db.beginTransaction()
        try { entries.forEach { put(it) };db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    @Synchronized fun delete(id: String) { writableDatabase.delete("memory","id=?",arrayOf(id)) }
    @Synchronized fun clearMemory() { writableDatabase.delete("memory",null,null) }
    @Synchronized fun checkpoint(task: TaskCheckpoint) {
        writableDatabase.insertWithOnConflict("tasks",null,ContentValues().apply {
            put("id",task.id);put("payload",AgentCrypto.encrypt(MemoryRules.json.encodeToString(task)));put("updated",task.updated)
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
        writableDatabase.execSQL("DELETE FROM tasks WHERE id NOT IN (SELECT id FROM tasks ORDER BY updated DESC,rowid DESC LIMIT 30)")
    }
    @Synchronized fun tasks(): List<TaskCheckpoint> = readableDatabase.rawQuery("SELECT payload FROM tasks ORDER BY updated DESC,rowid DESC LIMIT 30",null).use { c ->
        buildList { while(c.moveToNext()) add(MemoryRules.json.decodeFromString<TaskCheckpoint>(AgentCrypto.decrypt(c.getString(0)))) }
    }
    @Synchronized fun event(text: String) {
        writableDatabase.insertOrThrow("events",null,ContentValues().apply { put("payload",AgentCrypto.encrypt("${java.time.Instant.now()} · ${text.take(1000)}")) })
        writableDatabase.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 300)")
    }
    @Synchronized fun events(): String = readableDatabase.rawQuery("SELECT payload FROM events ORDER BY id DESC LIMIT 100",null).use { c ->
        buildList { while(c.moveToNext()) add(AgentCrypto.decrypt(c.getString(0))) }.joinToString("\n")
    }
    @Synchronized fun clearConversation() { writableDatabase.delete("messages",null,null) }
    @Synchronized fun clearHistory() { writableDatabase.delete("tasks",null,null);writableDatabase.delete("events",null,null) }
    @Synchronized fun addMessage(message: ConversationMessage): Boolean {
        require(message.text.length<=16000 && message.id.length<=200 && message.conversationId=="main") { "Сообщение слишком большое или неверный диалог" }
        val result=writableDatabase.insertWithOnConflict("messages",null,ContentValues().apply {
            put("id",message.id);put("conversation",message.conversationId);put("state",message.status)
            put("payload",AgentCrypto.encrypt(MemoryRules.json.encodeToString(message)));put("updated",message.created)
        },SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.execSQL("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY updated DESC,rowid DESC LIMIT 1000)")
        return result!=-1L
    }
    @Synchronized fun messages(): List<ConversationMessage> = readableDatabase.rawQuery("SELECT payload,state FROM messages WHERE conversation=? ORDER BY updated DESC,rowid DESC LIMIT 120",arrayOf("main")).use { c ->
        buildList { while(c.moveToNext()) add(MemoryRules.json.decodeFromString<ConversationMessage>(AgentCrypto.decrypt(c.getString(0))).copy(status=c.getString(1))) }.reversed()
    }
    @Synchronized fun message(id: String): ConversationMessage? = readableDatabase.rawQuery("SELECT payload,state FROM messages WHERE id=?",arrayOf(id)).use { c ->
        if(c.moveToFirst()) MemoryRules.json.decodeFromString<ConversationMessage>(AgentCrypto.decrypt(c.getString(0))).copy(status=c.getString(1)) else null
    }
    @Synchronized fun claimMessage(id: String): Boolean = writableDatabase.update("messages",ContentValues().apply { put("state","PROCESSING") },"id=? AND state=?",arrayOf(id,"RECEIVED"))==1
    @Synchronized fun messageStatus(id: String,status: String) { writableDatabase.update("messages",ContentValues().apply { put("state",status) },"id=?",arrayOf(id)) }
    @Synchronized fun quota(group: String): QuotaLedger = readableDatabase.rawQuery("SELECT payload FROM quotas WHERE id=?",arrayOf(group)).use { c ->
        if(c.moveToFirst()) MemoryRules.json.decodeFromString<QuotaLedger>(AgentCrypto.decrypt(c.getString(0))) else QuotaLedger()
    }
    @Synchronized fun quota(group: String,ledger: QuotaLedger) {
        writableDatabase.insertWithOnConflict("quotas",null,ContentValues().apply { put("id",group);put("payload",AgentCrypto.encrypt(MemoryRules.json.encodeToString(ledger))) },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
    }
    @Synchronized fun answerQuestion(taskId: String,questionId: String,text: String): TaskCheckpoint? {
        val task=tasks().firstOrNull { it.id==taskId && it.questionId==questionId && it.status=="WAIT_USER" } ?: return null
        val next=QuestionRules.answer(task,questionId,text) ?: return null
        checkpoint(next);return next
    }
    fun observeApp(pkg: String) {
        if(!pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) return
        val existing=readableDatabase.rawQuery("SELECT payload FROM memory WHERE id=?",arrayOf("app:$pkg")).use { c ->
            if(c.moveToFirst()) MemoryRules.json.decodeFromString<MemoryEntry>(AgentCrypto.decrypt(c.getString(0))) else null
        }
        if(existing!=null) {
            if(existing.source=="verified_tool_result") put(existing.copy(updated=System.currentTimeMillis(),revision=existing.revision+1))
            return
        }
        put(MemoryEntry(id="app:$pkg",kind="app_fact",title="Наблюдался пакет $pkg",content="Пакет был активен во время работы Bridge. Это не подтверждает текущий экран или доступность прежних элементов.",scope="app:$pkg",source="verified_tool_result"))
    }
}
