package com.pluginslab.agentbridge.agent

/** Bounded rendering wait; an empty or changing tree is not evidence of readiness. */
class SnapshotReadiness {
    private var previous: String? = null
    private var lastChange = 0L
    fun ready(elapsedMs: Long, semantics: String): Boolean {
        if (semantics != previous) { previous = semantics; lastChange = elapsedMs }
        return semantics.isNotBlank() && elapsedMs >= 750 && elapsedMs - lastChange >= 500
    }
    fun expired(elapsedMs: Long): Boolean = elapsedMs >= 3000
}
