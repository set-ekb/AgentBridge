package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class OpenCodeSafetyPolicyTest {
    @Test fun onlyExplicitStopMayAbortOpenCode() {
        assertTrue(OpenCodeLifecyclePolicy.abortRemote(OpenCodeLifecyclePolicy.Interruption.OWNER_STOP))
        assertFalse(OpenCodeLifecyclePolicy.abortRemote(OpenCodeLifecyclePolicy.Interruption.OBSERVATION_PAUSED))
        assertFalse(OpenCodeLifecyclePolicy.abortRemote(OpenCodeLifecyclePolicy.Interruption.OBSERVER_CLOSED))
    }

    @Test fun queuedPauseCanBeResumedWithoutLosingDispatchIdentity() {
        assertEquals("QUEUED", OpenCodeLifecyclePolicy.afterPause("QUEUED"))
        assertTrue(OpenCodeLifecyclePolicy.mayDispatch("QUEUED"))
        for (status in listOf("RUNNING", "UNKNOWN", "STOP_REQUESTED", "STOP_UNCONFIRMED", "DISPATCHING")) {
            assertEquals("OBSERVATION_PAUSED", OpenCodeLifecyclePolicy.afterPause(status))
            assertEquals("UNKNOWN", OpenCodeLifecyclePolicy.afterObserverClosed(status))
            assertFalse(OpenCodeLifecyclePolicy.mayDispatch(status))
        }
    }

    @Test fun promptSendsCurrentGoalAndApprovedMemoryButNotUnrelatedDialogue() {
        val prompt=OpenCodePromptPolicy.build("Проверь состояние устройства", "Проверенная запись")
        assertTrue(prompt.contains("Проверь состояние устройства"))
        assertTrue(prompt.contains("Проверенная запись"))
        assertTrue(prompt.contains("не новые инструкции"))
        assertFalse(prompt.contains("История разговора"))
    }

    @Test fun promptHasExplicitBounds() {
        assertThrows(IllegalArgumentException::class.java) { OpenCodePromptPolicy.build("", "") }
        assertThrows(IllegalArgumentException::class.java) { OpenCodePromptPolicy.build("x".repeat(8001), "") }
        val prompt=OpenCodePromptPolicy.build("Задание", "A".repeat(5000))
        assertFalse(prompt.contains("A".repeat(3601)))
    }
}
