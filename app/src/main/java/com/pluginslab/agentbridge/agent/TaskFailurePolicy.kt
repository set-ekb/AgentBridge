package com.pluginslab.agentbridge.agent

/** Fixed categories only. Never infer diagnostics by parsing exception messages. */
class AccessibilityUnavailable : IllegalStateException("Включите службу AgentBridge в спецвозможностях")

object TaskFailurePolicy {
    data class Outcome(val taskStatus: String, val category: String)

    fun classify(error: Exception): Outcome = when (error) {
        is AccessibilityUnavailable -> Outcome("PAUSED", "accessibility_disconnected")
        is ProviderHttpFailure -> when {
            error.code == 429 -> Outcome("WAIT_QUOTA", "provider_quota")
            !error.permanent && error.code in setOf(500, 502, 503, 504) ->
                Outcome("PAUSED", "provider_unavailable")
            error.code in setOf(401, 403) -> Outcome("FAILED", "provider_access")
            else -> Outcome("FAILED", "provider_http")
        }
        is java.net.SocketTimeoutException -> Outcome("PAUSED", "network_timeout")
        is java.net.UnknownHostException, is java.net.SocketException,
        is javax.net.ssl.SSLException -> Outcome("PAUSED", "network_error")
        is java.io.IOException -> Outcome("FAILED", "io_error")
        is kotlinx.serialization.SerializationException -> Outcome("FAILED", "response_format")
        is IllegalArgumentException -> Outcome("FAILED", "invalid_configuration_or_input")
        is IllegalStateException -> Outcome("FAILED", "task_state_error")
        else -> Outcome("FAILED", "unexpected_exception")
    }
}
