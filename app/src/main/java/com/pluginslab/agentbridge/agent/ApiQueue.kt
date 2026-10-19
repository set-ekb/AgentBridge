package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.UUID

class QuotaWaitException(val nextAt: Long,reason: String): IllegalStateException("Ожидание API: $reason. Задача сохранена. Не раньше оценки ${java.time.Instant.ofEpochMilli(nextAt).atZone(java.time.ZoneId.systemDefault())}.")

class QuotaContext(val group: String,val limits: QuotaLimits,val dialogue: Boolean,val status: (String)->Unit): AbstractCoroutineContextElement(Key) {
    companion object Key: CoroutineContext.Key<QuotaContext>
}

/** All real LLM transports, including retries and model listing, acquire one shared admission. */
object ApiQueue {
    data class View(val waiting: Int=0,val nextAt: Long=0,val reason: String="")
    private val mutex=Any()
    private val ledgers=mutableMapOf<String,QuotaLedger>()
    private data class Ticket(val id: String,val group: String,val dialogue: Boolean,val created: Long)
    private val tickets=mutableListOf<Ticket>()
    private var dialogueStreak=0
    private var load: (String)->QuotaLedger={ QuotaLedger() }
    private var save: (String,QuotaLedger)->Unit={ _,_-> }
    private val mutable=MutableStateFlow(View())
    val state=mutable.asStateFlow()
    fun configure(read: (String)->QuotaLedger,write: (String,QuotaLedger)->Unit) { synchronized(mutex) { load=read;save=write } }
    private fun ledger(group: String)=ledgers.getOrPut(group) { load(group) }
    suspend fun <T> transmit(payload: JsonObject?,block: suspend ()->T): T {
        val profile=currentCoroutineContext()[QuotaContext] ?: return block()
        val tokenEstimate=(payload?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 100).coerceAtLeast(1)
        val ticket=Ticket(UUID.randomUUID().toString(),profile.group,profile.dialogue,System.currentTimeMillis())
        synchronized(mutex) { tickets.add(ticket) }
        try {
            while(true) {
                currentCoroutineContext().ensureActive()
                val now=System.currentTimeMillis()
                val wait=synchronized(mutex) {
                    val groupTickets=tickets.filter { it.group==profile.group }
                    val background=groupTickets.firstOrNull { !it.dialogue }
                    val chosen=if(background!=null && (dialogueStreak>=3 || now-background.created>=30000)) background else groupTickets.firstOrNull { it.dialogue } ?: groupTickets.firstOrNull()
                    val value=QuotaPlanner.prune(ledger(profile.group),now)
                    val ready=QuotaPlanner.readyAt(value,profile.limits,tokenEstimate,profile.dialogue,now)
                    if(ready>ticket.created+300000) throw QuotaWaitException(ready,value.reason.ifBlank { "лимит RPM/TPM/RPD" })
                    if(chosen?.id==ticket.id && ready<=now) {
                        val admitted=QuotaPlanner.admit(value,ticket.id,tokenEstimate,now)
                        save(profile.group,admitted);ledgers[profile.group]=admitted;tickets.remove(ticket)
                        dialogueStreak=if(profile.dialogue) dialogueStreak+1 else 0
                        mutable.value=View(tickets.size)
                        0L
                    } else {
                        val until=if(ready>now) ready else now+250
                        mutable.value=View(tickets.size,until,if(value.blockedUntil>now) value.reason else "Ограничение RPM/TPM/RPD или очередь")
                        (until-now).coerceIn(100,1000)
                    }
                }
                if(wait==0L) break
                val view=state.value
                profile.status("WAIT_QUOTA · ${view.reason} · очередь ${view.waiting} · оценка ${(view.nextAt-System.currentTimeMillis()).coerceAtLeast(0)/1000} с")
                delay(wait)
            }
            val response=block()
            currentCoroutineContext().ensureActive()
            val responseBody=when(response) { is JsonObject -> response;is GeminiApi.Reply -> response.body;else -> null }
            if(responseBody!=null) {
                val usage=(responseBody["usageMetadata"] as? JsonObject)?.get("promptTokenCount")?.jsonPrimitive?.intOrNull
                    ?: (responseBody["usage"] as? JsonObject)?.let { it["input_tokens"]?.jsonPrimitive?.intOrNull ?: it["prompt_tokens"]?.jsonPrimitive?.intOrNull }
                if(usage!=null) synchronized(mutex) {
                    val updated=QuotaPlanner.measured(ledger(profile.group),ticket.id,usage)
                    save(profile.group,updated);ledgers[profile.group]=updated
                }
            }
            return response
        } finally { synchronized(mutex) { tickets.remove(ticket);if(tickets.isEmpty()) mutable.value=View() } }
    }
    suspend fun block(wait: Long,reason: String) {
        val profile=currentCoroutineContext()[QuotaContext] ?: return
        synchronized(mutex) {
            val updated=QuotaPlanner.block(ledger(profile.group),System.currentTimeMillis()+wait,reason)
            save(profile.group,updated);ledgers[profile.group]=updated
        }
    }
}
