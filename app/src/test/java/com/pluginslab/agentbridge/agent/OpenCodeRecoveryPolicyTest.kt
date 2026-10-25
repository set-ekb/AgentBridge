package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class OpenCodeRecoveryPolicyTest {
    @Test fun onlyPossiblyRunningOpenCodeTasksAreAutoObserved() {
        for (state in listOf("DISPATCHING","RUNNING","UNKNOWN","STOP_REQUESTED","STOP_UNCONFIRMED")) {
            assertTrue(state,OpenCodeRecoveryPolicy.shouldObserveAfterRestart("opencode",state))
        }
        for (state in listOf("QUEUED","OBSERVATION_PAUSED","COMPLETE","FAILED","CANCELLED","WAIT_USER")) {
            assertFalse(state,OpenCodeRecoveryPolicy.shouldObserveAfterRestart("opencode",state))
        }
        assertFalse(OpenCodeRecoveryPolicy.shouldObserveAfterRestart("gemini","RUNNING"))
    }

    @Test fun reconnectIsBoundedAndDoesNotRequireModelCalls() {
        assertEquals(5_000L,OpenCodeRecoveryPolicy.reconnectDelayMs(1))
        assertEquals(10_000L,OpenCodeRecoveryPolicy.reconnectDelayMs(2))
        assertEquals(20_000L,OpenCodeRecoveryPolicy.reconnectDelayMs(3))
        assertEquals(30_000L,OpenCodeRecoveryPolicy.reconnectDelayMs(4))
        assertEquals(30_000L,OpenCodeRecoveryPolicy.reconnectDelayMs(100))
        assertThrows(IllegalArgumentException::class.java) { OpenCodeRecoveryPolicy.reconnectDelayMs(0) }
    }

    @Test fun explicitlyPausedSessionsAreOnlyResumedByOwner() {
        assertEquals("OBSERVATION_PAUSED",OpenCodeLifecyclePolicy.afterPause("RUNNING"))
        assertFalse(OpenCodeRecoveryPolicy.shouldObserveAfterRestart("opencode","OBSERVATION_PAUSED"))
        assertFalse(OpenCodeLifecyclePolicy.mayDispatch("OBSERVATION_PAUSED"))
        assertEquals("UNKNOWN",OpenCodeLifecyclePolicy.afterObserverClosed("RUNNING"))
    }
}
