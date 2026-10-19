package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UiContextPolicyTest {
    private val bridge="com.pluginslab.agentbridge"
    private val gmail="com.google.android.gm"
    private fun errorCode(result: JsonObject?): String {
        assertNotNull(result)
        val text=result!!["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject["error"]!!.jsonPrimitive.content
    }
    @Test fun taskTextInBridgeIsNotAGmailSearchField() {
        val policy=UiContextPolicy(bridge)
        val args=buildJsonObject { put("text_contains","Поиск в почте");put("class_name","EditText") }
        assertEquals("agent_panel_is_active",errorCode(policy.reject("find_nodes",args,bridge)))
    }
    @Test fun guessedGmailIdIsRejectedWithoutWaitingForTimeout() {
        val policy=UiContextPolicy(bridge);policy.acknowledge(gmail)
        val args=buildJsonObject { put("resource_id","com.google.android.gm:id/search_view_text");put("timeout_ms",3000) }
        assertEquals("unobserved_resource_id",errorCode(policy.reject("wait_for_node",args,gmail)))
    }
    @Test fun discoveryByEditableThenObservedIdCanBeUsed() {
        val policy=UiContextPolicy(bridge);policy.acknowledge(gmail)
        assertNull(policy.reject("find_nodes",buildJsonObject { put("editable",true) },gmail))
        val actualId="com.google.android.gm:id/open_search_view_edit_text"
        policy.observe(gmail,setOf(actualId))
        assertNull(policy.reject("wait_for_node",buildJsonObject { put("resource_id",actualId) },gmail))
    }
    @Test fun observedIdCannotBeReusedInADifferentApplication() {
        val policy=UiContextPolicy(bridge)
        val id="android:id/search_src_text"
        policy.acknowledge(gmail);policy.observe(gmail,setOf(id))
        policy.acknowledge("com.example.browser")
        assertEquals("unobserved_resource_id",errorCode(policy.reject("find_nodes",buildJsonObject { put("resource_id",id) },"com.example.browser")))
    }
    @Test fun unexpectedAppSwitchRequiresAcknowledgingTheNewContext() {
        val policy=UiContextPolicy(bridge);policy.acknowledge(gmail)
        assertEquals("application_changed",errorCode(policy.reject("get_ui_tree",buildJsonObject {},"com.example.browser")))
        policy.acknowledge("com.example.browser")
        assertNull(policy.reject("get_ui_tree",buildJsonObject {},"com.example.browser"))
    }
    @Test fun metadataInspectionAndAppLaunchRemainAvailableFromPanel() {
        val policy=UiContextPolicy(bridge)
        assertNull(policy.reject("get_active_window_info",buildJsonObject {},bridge))
        assertNull(policy.reject("launch_app",buildJsonObject { put("package",gmail) },bridge))
        assertEquals("no_active_window",errorCode(policy.reject("find_nodes",buildJsonObject {},null)))
    }
}
