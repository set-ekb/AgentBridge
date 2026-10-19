package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class CheckpointRulesTest {
    private val task=TaskCheckpoint(goal="Изменить время",provider="gemini",model="model")
    @Test fun checkpointBeforeWriteRemainsUncertainEvenIfProcessDies() {
        val before=CheckpointRules.before(task,"tap_node",true,"example.app")
        assertEquals("ACT",before.status);assertTrue(before.pendingAction);assertEquals("tap_node",before.lastAction)
    }
    @Test fun acceptedClickDoesNotClearNeedForVerification() {
        val before=CheckpointRules.before(task,"tap_node",true,"example.app")
        val after=CheckpointRules.after(before,"tap_node","example.app",false,false)
        assertTrue(after.pendingAction)
    }
    @Test fun failedOrUnrelatedReadCannotErasePendingEffect() {
        val before=CheckpointRules.before(task,"tap_node",true,"example.app")
        val read=CheckpointRules.before(before,"get_ui_tree",false,"example.app")
        assertTrue(read.pendingAction);assertEquals("tap_node",read.lastAction)
        assertTrue(CheckpointRules.after(read,"get_ui_tree","example.app",true,true).pendingAction)
        assertTrue(CheckpointRules.after(read,"list_apps","example.app",false,false).pendingAction)
    }
    @Test fun freshUiProvidesEvidenceWithoutClaimingSemanticSuccess() {
        val before=CheckpointRules.before(task,"tap_node",true,"example.app")
        val after=CheckpointRules.after(before,"get_ui_tree","example.app",false,true)
        assertFalse(after.pendingAction);assertTrue(after.observation.contains("оценивает модель"))
    }
}
