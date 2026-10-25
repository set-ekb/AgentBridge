package com.pluginslab.agentbridge.agent

/** One user action must never be confused with the lifetime of a UI observer. */
internal object OpenCodeLifecyclePolicy {
    enum class Interruption { OWNER_STOP, OBSERVATION_PAUSED, OBSERVER_CLOSED }

    /** Only an explicit stop issued by the owner may abort the remote session. */
    fun abortRemote(reason: Interruption): Boolean = reason == Interruption.OWNER_STOP

    /** A queued task has not been dispatched, so it remains eligible for explicit start. */
    fun afterPause(previousStatus: String): String =
        if (previousStatus == "QUEUED") "QUEUED" else "OBSERVATION_PAUSED"

    /** Service shutdown is not an owner pause; it should be reconciled after restart. */
    fun afterObserverClosed(previousStatus: String): String =
        if (previousStatus in setOf("QUEUED", "OBSERVATION_PAUSED")) previousStatus else "UNKNOWN"

    /** Resuming a remote task always reconciles its UUID; never sends a second prompt. */
    fun mayDispatch(previousStatus: String): Boolean = previousStatus == "QUEUED"
}
