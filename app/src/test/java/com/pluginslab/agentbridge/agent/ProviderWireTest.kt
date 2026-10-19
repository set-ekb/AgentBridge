package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class ProviderWireTest {
    private fun obj(s: String)=Json.parseToJsonElement(s).jsonObject
    @Test fun openAiReplaysCallsEncryptedReasoningAndMatchingOutputs() = runBlocking {
        val requests=mutableListOf<JsonObject>()
        val engine=OpenAiEngine("fake","model","Задание") { _,_,request ->
            requests.add(request)
            if(requests.size==1) obj("""{"output":[{"type":"reasoning","id":"r1","encrypted_content":"opaque"},{"type":"function_call","name":"get_ui_tree","arguments":"{}","call_id":"call1"}]}""")
            else obj("""{"output":[{"type":"message","content":[{"type":"output_text","text":"Готово"}]}]}""")
        }
        val turn=engine.next("policy",emptyList())
        engine.accept(listOf(AgentReply(turn.calls.single(),obj("""{"isError":false}"""))))
        assertEquals("Готово",engine.next("policy",emptyList()).answer)
        val input=requests.last()["input"]!!.jsonArray
        assertTrue(input.any { it.jsonObject["encrypted_content"]?.jsonPrimitive?.contentOrNull=="opaque" })
        assertTrue(input.any { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull=="function_call_output" && it.jsonObject["call_id"]?.jsonPrimitive?.contentOrNull=="call1" })
        assertFalse(requests.first().containsKey("tools"))
        assertEquals(false,requests.first()["store"]!!.jsonPrimitive.boolean)
    }
    @Test fun claudeReturnsToolErrorImmediatelyAfterAssistantUse() = runBlocking {
        val requests=mutableListOf<JsonObject>()
        val engine=AnthropicEngine("fake","model","Task") { _,headers,request ->
            assertEquals("2023-06-01",headers["anthropic-version"]);requests.add(request)
            if(requests.size==1) obj("""{"content":[{"type":"tool_use","id":"tool1","name":"get_ui_tree","input":{}}]}""")
            else obj("""{"content":[{"type":"text","text":"Недоступно"}],"stop_reason":"end_turn"}""")
        }
        val turn=engine.next("policy",emptyList())
        engine.accept(listOf(AgentReply(turn.calls.single(),obj("""{"isError":true}"""))))
        engine.next("policy",emptyList())
        val messages=requests.last()["messages"]!!.jsonArray
        val reply=messages.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool1",reply["tool_use_id"]!!.jsonPrimitive.content)
        assertTrue(reply["is_error"]!!.jsonPrimitive.boolean)
        assertEquals("assistant",messages[messages.lastIndex-1].jsonObject["role"]!!.jsonPrimitive.content)
    }
    @Test fun compatibleReplaysToolId() = runBlocking {
        val requests=mutableListOf<JsonObject>()
        val engine=CompatibleEngine("","local:model","http://localhost:8000/v1/chat/completions","Task") { _,headers,request ->
            assertTrue(headers.isEmpty());requests.add(request)
            if(requests.size==1) obj("""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_ui_tree","arguments":"{}"}}]}}]}""")
            else obj("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}""")
        }
        val turn=engine.next("policy",emptyList());engine.accept(listOf(AgentReply(turn.calls.single(),obj("{}"))))
        assertEquals("OK",engine.next("policy",emptyList()).answer)
        assertEquals("c1",requests.last()["messages"]!!.jsonArray.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }
    @Test fun malformedFunctionArgumentsCannotBecomeAnEmptyAction() = runBlocking {
        val engine=OpenAiEngine("fake","model","Task") { _,_,_ -> obj("""{"output":[{"type":"function_call","name":"tap_node","arguments":"broken","call_id":"c"}]}""") }
        var failed=false
        try { engine.next("policy",emptyList()) } catch(_:Exception) { failed=true }
        assertTrue(failed)
    }
    @Test fun truncatedToolResponseIsNotExecuted() = runBlocking {
        val engine=AnthropicEngine("fake","model","Task") { _,_,_ -> obj("""{"stop_reason":"max_tokens","content":[{"type":"tool_use","name":"tap_node","id":"c","input":{}}]}""") }
        var failed=false;try { engine.next("policy",emptyList()) } catch(_:IllegalStateException) { failed=true }
        assertTrue(failed)
    }
    @Test fun endpointsRejectCredentialsAndRemotePlainHttp() {
        ProviderAddress.validate("http://127.0.0.1:8000/v1/chat/completions")
        assertThrows(IllegalArgumentException::class.java) { ProviderAddress.validate("http://example.com/api") }
        assertThrows(IllegalArgumentException::class.java) { ProviderAddress.validate("https://user:secret@example.com/api") }
        assertThrows(IllegalArgumentException::class.java) { ProviderAddress.validate("https://example.com/api?key=secret") }
    }
}
