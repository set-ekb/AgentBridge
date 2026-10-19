package com.pluginslab.agentbridge.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReadProgressGuardTest {
    @Test fun changingRenderDurationDoesNotHideIdenticalReads() {
        val guard=ReadProgressGuard()
        for(index in 1..5) {
            val outcome=buildJsonObject { put("text","same tree");put("render_wait_ms",800+index) }
            assertEquals(index,guard.record("get_ui_tree",buildJsonObject {},outcome,"same screen"))
        }
    }
    private val args=buildJsonObject { put("text_contains","Поиск в почте") }
    private val empty=buildJsonObject { put("count",0) }
    @Test fun sameReadAndScreenAreCountedAsNoProgress() {
        val guard=ReadProgressGuard()
        assertEquals(1,guard.record("find_nodes",args,empty,"screen-1"))
        assertEquals(2,guard.record("find_nodes",args,empty,"screen-1"))
    }
    @Test fun returningFromLetterOrRevealingToolbarResetsTheReadCount() {
        val guard=ReadProgressGuard()
        repeat(4) { guard.record("get_ui_tree",buildJsonObject {},empty,"letter") }
        assertEquals(1,guard.record("get_ui_tree",buildJsonObject {},empty,"list-toolbar-hidden"))
        assertEquals(1,guard.record("get_ui_tree",buildJsonObject {},empty,"list-toolbar-visible"))
    }
    @Test fun changedToolResultIsProgressEvenInTheSameWindow() {
        val guard=ReadProgressGuard();guard.record("find_nodes",args,empty,"same-window")
        val found=buildJsonObject { put("count",1) }
        assertEquals(1,guard.record("find_nodes",args,found,"same-window"))
    }
    @Test fun argumentReorderingDoesNotBypassRepeatDetection() {
        val guard=ReadProgressGuard()
        val a=buildJsonObject { put("editable",true);put("class_name","EditText") }
        val b=buildJsonObject { put("class_name","EditText");put("editable",true) }
        assertEquals(1,guard.record("find_nodes",a,empty,"screen"))
        assertEquals(2,guard.record("find_nodes",b,empty,"screen"))
    }
}
