package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** MCP callers have no permit and retain their existing behavior. Built-in actions must have a valid permit. */
object ActionDispatch {
    data class Permit(val gate: TaskGate,val id: String,val revision: Int,val checkUi: ()->Boolean)
    private val local=ThreadLocal<Permit?>()
    suspend fun <T> withPermit(permit: Permit,block: suspend ()->T): T = withContext(local.asContextElement(permit)) { block() }
    fun <T> run(block: ()->T): T {
        val permit=local.get() ?: return block()
        return permit.gate.dispatch(permit.id,permit.revision,permit.checkUi,block)
    }
}
