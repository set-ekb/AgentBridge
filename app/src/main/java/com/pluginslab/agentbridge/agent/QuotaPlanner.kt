package com.pluginslab.agentbridge.agent

import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable data class QuotaLimits(val rpm: Int=0,val tpm: Int=0,val rpd: Int=0,val dialogueReserve: Int=1)
@Serializable data class QuotaUse(val id: String,val at: Long,val tokens: Int)
@Serializable data class QuotaLedger(val uses: List<QuotaUse> = emptyList(),val blockedUntil: Long=0,val reason: String="")

/** Conservative input token estimation and rolling 24h day budget. Server cooldown always wins. */
object QuotaPlanner {
    const val MINUTE=60000L
    const val DAY=86400000L
    fun prune(ledger: QuotaLedger,now: Long)=ledger.copy(uses=ledger.uses.filter { it.at>now-DAY })
    fun readyAt(ledger: QuotaLedger,limits: QuotaLimits,tokens: Int,dialogue: Boolean,now: Long): Long {
        require(tokens>=0 && (limits.tpm<=0 || tokens<=limits.tpm)) { "Контекст больше заданного TPM. Сократите историю или увеличьте лимит профиля." }
        val recent=ledger.uses.filter { it.at>now-MINUTE }.sortedBy { it.at }
        var ready=maxOf(now,ledger.blockedUntil)
        if(limits.rpm>0) {
            val gap=ceil(MINUTE.toDouble()/limits.rpm*1.05).toLong()
            ledger.uses.maxOfOrNull { it.at }?.let { ready=maxOf(ready,it+gap) }
            val reserve=if(!dialogue && limits.rpm>1) limits.dialogueReserve.coerceIn(0,limits.rpm-1) else 0
            val capacity=limits.rpm-reserve
            if(recent.size>=capacity) ready=maxOf(ready,recent[recent.size-capacity].at+MINUTE+100)
        }
        if(limits.tpm>0) {
            var sum=recent.sumOf { it.tokens.toLong() }+tokens
            for(use in recent) { if(sum<=limits.tpm) break;sum-=use.tokens;ready=maxOf(ready,use.at+MINUTE+100) }
        }
        if(limits.rpd>0) {
            val day=ledger.uses.filter { it.at>now-DAY }.sortedBy { it.at }
            if(day.size>=limits.rpd) ready=maxOf(ready,day[day.size-limits.rpd].at+DAY+100)
        }
        return ready
    }
    fun admit(ledger: QuotaLedger,id: String,tokens: Int,now: Long)=prune(ledger,now).copy(uses=prune(ledger,now).uses+QuotaUse(id,now,tokens))
    fun block(ledger: QuotaLedger,until: Long,reason: String)=if(until>ledger.blockedUntil) ledger.copy(blockedUntil=until,reason=reason) else ledger
    fun measured(ledger: QuotaLedger,id: String,tokens: Int)=ledger.copy(uses=ledger.uses.map { if(it.id==id) it.copy(tokens=tokens.coerceAtLeast(0)) else it })
}
