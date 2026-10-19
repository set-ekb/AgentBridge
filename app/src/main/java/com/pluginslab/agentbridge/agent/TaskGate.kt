package com.pluginslab.agentbridge.agent

/** A revision change and Android dispatch share this monitor. Never hold it while awaiting a model. */
class StaleTaskException: IllegalStateException("STALE_TASK: план изменён, приостановлен или отменён")

class TaskGate {
    data class Version(val id: String,val revision: Int,val paused: Boolean=false,val cancelled: Boolean=false)
    private var current: Version?=null
    @Synchronized fun begin(id: String,revision: Int) { current=Version(id,revision) }
    @Synchronized fun snapshot(): Version?=current
    @Synchronized fun revise(id: String): Version {
        val v=current;check(v!=null && v.id==id && !v.cancelled) { "Задача больше не активна" }
        return v.copy(revision=v.revision+1,paused=false).also { current=it }
    }
    @Synchronized fun freeze() { current=current?.let { it.copy(paused=true,revision=it.revision+1) } }
    @Synchronized fun release() { current=current?.let { it.copy(paused=false,revision=it.revision+1) } }
    @Synchronized fun pause() { current=current?.let { it.copy(paused=true,revision=it.revision+1) } }
    @Synchronized fun cancel() { current=current?.let { it.copy(cancelled=true,revision=it.revision+1) } }
    @Synchronized fun valid(id: String,revision: Int): Boolean = current?.let { it.id==id && it.revision==revision && !it.paused && !it.cancelled }==true
    @Synchronized fun <T> dispatch(id: String,revision: Int,checkUi: ()->Boolean={true},block: ()->T): T {
        if(!valid(id,revision)) throw StaleTaskException()
        check(checkUi()) { "STALE_UI: состояние интерфейса изменилось" }
        return block()
    }
}
