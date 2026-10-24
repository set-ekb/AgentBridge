package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class PortableTaskHandoffTest {
    @Test fun preservesRevisionAndPendingAction() {
        val task=TaskCheckpoint(id="task-1",goal="Diagnose ADB",provider="gemini",model="model-a",
            status="PAUSED",step=3,pendingAction=true,lastAction="tap_node",taskRevision=7,
            activePackage="com.android.settings")
        val card=PortableTaskHandoffRules.from(task)
        assertEquals(1,card.schema_version)
        assertEquals("task-1",card.task_id)
        assertEquals(7,card.task_revision)
        assertTrue(card.action_may_have_occurred)
        assertTrue(card.resume_requires_reverification)
        assertEquals("com.android.settings",card.active_package_at_checkpoint)
    }

    @Test fun runtimeHandoffContainsJsonAndSafetyGate() {
        val task=TaskCheckpoint(goal="Continue",provider="claude",model="test")
        val prompt=task.handoff()
        assertTrue(prompt.contains("\\"schema_version\\":1"))
        assertTrue(prompt.contains("\\"resume_requires_reverification\\":true"))
        assertTrue(prompt.contains("Не повторяй"))
    }
}
