package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class SupervisorPolicyTest {
    @Test fun runningUsesBoundedBackoffWithoutModelCalls() {
        val first = SupervisorPolicy.decide("running", true, 500L, 0L)
        assertEquals(SupervisorPolicy.Action.WAIT, first.action)
        assertEquals(500L, first.nextPollInMs)
        val due = SupervisorPolicy.decide("running", true, 1000L, 0L)
        assertEquals(SupervisorPolicy.Action.POLL, due.action)
        val capped = SupervisorPolicy.decide("running", true, 0L, 0L, 100)
        assertEquals(30000L, capped.nextPollInMs)
    }

    @Test fun disconnectionNeverTriggersDuplicateExecution() {
        val d = SupervisorPolicy.decide("running", false, 2000L, 1000L)
        assertEquals(SupervisorPolicy.State.DISCONNECTED, d.state)
        assertEquals(SupervisorPolicy.Action.RECONNECT, d.action)
        assertEquals(5000L, d.nextPollInMs)
    }

    @Test fun unknownRequiresReconciliation() {
        assertEquals(SupervisorPolicy.Action.REQUEST_RECONCILIATION,
            SupervisorPolicy.decide("unknown", true, 200L, 0L).action)
    }

    @Test fun terminalStatusesDoNotPollAgain() {
        val pairs = listOf("done" to SupervisorPolicy.Action.REPORT_RESULT,
            "failed" to SupervisorPolicy.Action.REPORT_FAILURE,
            "cancelled" to SupervisorPolicy.Action.REPORT_CANCELLED)
        pairs.forEach { (state, expected) ->
            val d = SupervisorPolicy.decide(state, true, 1000L, 0L)
            assertEquals(expected, d.action)
            assertNull(d.nextPollInMs)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBackwardMonotonicClock() {
        SupervisorPolicy.decide("running", true, 100L, 200L)
    }
}
