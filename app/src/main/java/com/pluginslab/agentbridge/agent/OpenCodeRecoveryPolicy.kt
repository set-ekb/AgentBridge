package com.pluginslab.agentbridge.agent

/**
 * Recovery is read-only: a stored task UUID is observed, never submitted again.
 * This policy has no Android or HTTP dependencies and uses no model tokens.
 */
internal object OpenCodeRecoveryPolicy {
    private val autoObserve = setOf("DISPATCHING", "RUNNING", "UNKNOWN", "STOP_REQUESTED", "STOP_UNCONFIRMED")

    /** Explicitly paused observers remain paused across process restarts. */
    fun shouldObserveAfterRestart(provider: String, state: String): Boolean =
        provider == "opencode" && state in autoObserve

    /** The gateway may restart; retry GET with capped backoff, never POST. */
    fun reconnectDelayMs(consecutiveFailures: Int): Long {
        require(consecutiveFailures > 0)
        val shift = (consecutiveFailures - 1).coerceIn(0, 3)
        return (5_000L shl shift).coerceAtMost(30_000L)
    }
}
