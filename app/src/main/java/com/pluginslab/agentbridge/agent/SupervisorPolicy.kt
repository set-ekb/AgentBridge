package com.pluginslab.agentbridge.agent

/**
 * Deterministic, provider-independent supervisor policy.
 * No model call, Android UI inspection or side effects occur here.
 *
 * A missing heartbeat means "unknown", never "failed" or "safe to retry".
 * Commands are idempotent at the gateway using the original task UUID.
 */
internal object SupervisorPolicy {
    enum class State { RUNNING, WAITING, DONE, FAILED, CANCELLED, UNKNOWN, DISCONNECTED }
    enum class Action { WAIT, POLL, REPORT_RESULT, REPORT_FAILURE, REPORT_CANCELLED, RECONNECT, REQUEST_RECONCILIATION }

    data class Decision(val state: State, val action: Action, val nextPollInMs: Long? = null)

    private const val MIN_POLL_MS = 1_000L
    private const val MAX_POLL_MS = 30_000L
    private const val RECONNECT_MS = 5_000L

    /**
     * @param nowMs monotonic time source, not wall clock.
     * @param lastCheckedMs monotonic timestamp of the most recent gateway check.
     * @param consecutiveRunningChecks count of consecutive unchanged RUNNING polls.
     * @param gatewayReachable false on connection loss or timeout (not a task failure).
     */
    fun decide(
        remoteState: String?,
        gatewayReachable: Boolean,
        nowMs: Long,
        lastCheckedMs: Long,
        consecutiveRunningChecks: Int = 0
    ): Decision {
        require(nowMs >= lastCheckedMs)
        require(consecutiveRunningChecks >= 0)

        if (!gatewayReachable) return Decision(State.DISCONNECTED, Action.RECONNECT, RECONNECT_MS)

        return when (remoteState) {
            "done" -> Decision(State.DONE, Action.REPORT_RESULT)
            "failed" -> Decision(State.FAILED, Action.REPORT_FAILURE)
            "cancelled" -> Decision(State.CANCELLED, Action.REPORT_CANCELLED)
            "unknown", null -> Decision(State.UNKNOWN, Action.REQUEST_RECONCILIATION)
            "running" -> {
                val shift = consecutiveRunningChecks.coerceIn(0, 5)
                val interval = (MIN_POLL_MS * (1L shl shift)).coerceAtMost(MAX_POLL_MS)
                val elapsed = nowMs - lastCheckedMs
                if (elapsed >= interval) Decision(State.RUNNING, Action.POLL, 0)
                else Decision(State.WAITING, Action.WAIT, interval - elapsed)
            }
            else -> Decision(State.UNKNOWN, Action.REQUEST_RECONCILIATION)
        }
    }
}
