package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TaskGateTest {
    @Test fun lateModelResponseCannotExecuteAfterConditionsChange() {
        val gate=TaskGate();gate.begin("task",1)
        gate.revise("task")
        var acted=false
        assertThrows(IllegalStateException::class.java) { gate.dispatch("task",1) { acted=true } }
        assertFalse(acted)
        gate.dispatch("task",2) { acted=true };assertTrue(acted)
    }
    @Test fun stopInvalidatesPreviouslyApprovedPendingCall() {
        val gate=TaskGate();gate.begin("task",3);assertTrue(gate.valid("task",3));gate.cancel()
        assertFalse(gate.valid("task",3));assertTrue(gate.snapshot()!!.cancelled)
    }
    @Test fun routingHoldAndResumeInvalidateBothOldPlans() {
        val gate=TaskGate();gate.begin("task",1);gate.freeze()
        assertFalse(gate.valid("task",1));val held=gate.snapshot()!!.revision
        assertFalse(gate.valid("task",held));gate.release()
        assertFalse(gate.valid("task",held));assertTrue(gate.valid("task",gate.snapshot()!!.revision))
    }
    @Test fun dispatchAndAmendmentHaveAnAtomicOrder() {
        val gate=TaskGate();gate.begin("task",1)
        val executor=Executors.newFixedThreadPool(2)
        val entered=CountDownLatch(1);val attempted=CountDownLatch(1);val revised=AtomicBoolean(false)
        try {
            val action=executor.submit {
                gate.dispatch("task",1) {
                    entered.countDown();assertTrue(attempted.await(2,TimeUnit.SECONDS));assertFalse(revised.get())
                }
            }
            val update=executor.submit {
                assertTrue(entered.await(2,TimeUnit.SECONDS));attempted.countDown();gate.revise("task");revised.set(true)
            }
            action.get(3,TimeUnit.SECONDS);update.get(3,TimeUnit.SECONDS)
            assertTrue(revised.get());assertFalse(gate.valid("task",1))
        } finally { executor.shutdownNow() }
    }
    @Test fun changedUiFailsInsideSameAtomicDispatchGate() {
        val gate=TaskGate();gate.begin("task",1);var acted=false
        assertThrows(IllegalStateException::class.java) { gate.dispatch("task",1,{false}) { acted=true } }
        assertFalse(acted)
    }
    @Test fun newExplicitTaskDoesNotReviveOldTaskPermit() {
        val gate=TaskGate();gate.begin("old",1);gate.cancel();gate.begin("new",1)
        assertFalse(gate.valid("old",1));assertTrue(gate.valid("new",1))
    }
}
