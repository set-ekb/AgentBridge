package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class AgentPolicyTest {
    @Test fun privateThoughtsAreNotShownAsAnswer() {
        val content=Json.parseToJsonElement("""{"parts":[{"text":"internal", "thought":true},{"text":"Gmail открыт"},{"functionCall":{"name":"list_apps"}}]}""").jsonObject
        assertEquals("Gmail открыт",AgentPolicy.publicAnswer(content))
    }
    @Test fun credentialsAreRedactedBeforeTruncation() {
        val key="key-\"secret\\value"
        val raw=JsonPrimitive("$key " + "x".repeat(30000)).toString()
        val safe=AgentPolicy.safeLog(raw,key,limit=300)
        assertFalse(safe.contains("secret"))
        assertTrue(safe.contains("[секрет скрыт]"))
        assertTrue(safe.contains("сокращён"))
    }
    @Test fun quotaDetailsAreShownWithoutProviderUrlsOrKeys() {
        val data=Json.parseToJsonElement("""{"error":{"message":"https://example.com/?key=secret","details":[{"violations":[{"quotaId":"GenerateRequestsPerMinutePerProject"}]}]}}""").jsonObject
        val hint=AgentPolicy.quotaHint(data)
        assertTrue(hint.contains("GenerateRequestsPerMinutePerProject"))
        assertFalse(hint.contains("secret"));assertFalse(hint.contains("https"))
    }
    @Test fun malformedQuotaIdentifierIsIgnored() {
        val data=Json.parseToJsonElement("""{"error":{"details":[{"violations":[{"quotaId":"https://secret.example"}]}]}}""").jsonObject
        assertFalse(AgentPolicy.quotaHint(data).contains("secret"))
    }
    @Test fun unrestrictedScriptingAndCookiesAreUnavailableToModel() {
        for(name in listOf("run_script","send_intent","browser_eval","browser_set_cookie","get_clipboard","tap_coords")) assertFalse(name in AgentPolicy.allowed)
        assertTrue("launch_app" in AgentPolicy.actions)
        assertTrue("tap_node" in AgentPolicy.actions)
        assertFalse("tap_node" in AgentPolicy.reads)
    }
}
