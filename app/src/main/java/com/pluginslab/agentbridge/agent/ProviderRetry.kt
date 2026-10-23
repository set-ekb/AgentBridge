package com.pluginslab.agentbridge.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class ProviderHttpFailure(val code: Int,val retryAfter: String?,val permanent: Boolean=false,message: String): IllegalStateException(message)

/** Retry only the same model request, never replay a phone command. */
class ProviderRetry(
    private val status: (String)->Unit={},
    private val exchange: suspend (String,Map<String,String>,JsonObject)->JsonObject=ProviderHttp::post,
    private val pause: suspend (Long)->Unit={ delay(it) }
) {
    suspend fun post(url: String,headers: Map<String,String>,data: JsonObject): JsonObject {
        var attempt=0;var waited=0L
        while(true) {
            currentCoroutineContext().ensureActive()
            try { return ApiQueue.transmit(data) {
                ProviderAttemptTrace.exchange { exchange(url,headers,data) }
            }.also { currentCoroutineContext().ensureActive() } }
            catch(e:ProviderHttpFailure) {
                val serverWait=GeminiRetryPolicy.serverDelayMs(buildJsonObject {},e.retryAfter,System.currentTimeMillis())
                if(e.code in setOf(429,500,502,503,504) && serverWait!=null) ApiQueue.block(serverWait+1000,"HTTP ${e.code} · срок сервера")
                if(e.permanent) { ApiQueue.block(QuotaPlanner.DAY,"Квота/биллинг; повтор не выполняется");throw e }
                val code=if(e.code in setOf(500,502,504)) 503 else e.code
                val wait=GeminiRetryPolicy.delayMs(code,buildJsonObject {},e.retryAfter,attempt,waited,System.currentTimeMillis()) ?: throw e
                ApiQueue.block(wait,"HTTP ${e.code} · серверная задержка / backoff")
                var remaining=wait
                while(remaining>0) {
                    status("HTTP ${e.code} · продолжение через ${(remaining+999)/1000} с · повтор ${attempt+1}")
                    val part=minOf(remaining,1000L);pause(part);currentCoroutineContext().ensureActive();remaining-=part
                }
                waited+=wait;attempt++
            }
        }
    }
}
