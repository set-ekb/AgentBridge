package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Metadata for actual transports, never for progress/countdown callbacks. */
data class ProviderAttemptEvent(
    val kind: String,
    val status: String,
    val provider: String,
    val requestId: String,
    val taskAttemptId: String,
    val step: Int,
    val durationMs: Long = -1
)

class ProviderAttemptTrace(
    val provider: String,
    val taskAttemptId: String = "",
    val step: Int = -1,
    val sink: (ProviderAttemptEvent) -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProviderAttemptTrace> {
        fun httpStatus(code: Int): String = when (code) {
            429 -> "quota_429"
            503 -> "service_503"
            else -> "http_$code"
        }

        suspend fun <T> exchange(code: (T) -> Int = { 200 }, block: suspend () -> T): T {
            val context = currentCoroutineContext()
            context.ensureActive()
            val trace = context[Key] ?: return block()
            val request = UUID.randomUUID().toString()
            val started = System.nanoTime()
            fun emit(kind: String, status: String, duration: Boolean = true) {
                // Journal failure must not change/replay a network request.
                runCatching { trace.sink(ProviderAttemptEvent(kind, status, trace.provider,
                    request, trace.taskAttemptId, trace.step,
                    if (duration) (System.nanoTime() - started) / 1_000_000 else -1)) }
            }
            emit("api_request", "start", false)
            try {
                val response = block()
                context.ensureActive()
                val status = code(response)
                if (status in 200..299) emit("api_response", "ok")
                else emit("api_error", httpStatus(status))
                return response
            } catch (e: CancellationException) {
                emit("api_cancelled", "cancelled")
                throw e
            } catch (e: Exception) {
                emit("api_error", when (e) {
                    is ProviderHttpFailure -> httpStatus(e.code)
                    is java.net.SocketTimeoutException -> "network_timeout"
                    is java.io.IOException -> "network_error"
                    else -> "transport_exception"
                })
                throw e
            }
        }
    }
}
