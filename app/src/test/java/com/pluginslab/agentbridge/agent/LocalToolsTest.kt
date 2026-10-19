package com.pluginslab.agentbridge.agent

import com.pluginslab.agentbridge.server.tools.LocalTools
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalToolsTest {
    @Test fun localDeclarationsUseTheSameSchemasAsBridge() {
        val tools=LocalTools.declarations(AgentPolicy.allowed).map { it.jsonObject }
        val names=tools.map { it["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue(names.containsAll(AgentPolicy.allowed - setOf("agent_delay","agent_ask_user","agent_apply_revision")))
        assertFalse("run_script" in names)
        assertFalse("agent_ask_user" in names) // controller-local, not an external Bridge capability
        for(tool in tools) assertEquals("object",tool["parametersJsonSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val launcher=tools.first { it["name"]!!.jsonPrimitive.content=="launch_app" }
        assertTrue("package" in launcher["parametersJsonSchema"]!!.jsonObject["properties"]!!.jsonObject)
    }
}
