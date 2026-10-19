package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class ActionProgressGuardTest {
    @Test fun acceptedClickCannotBeRepeatedWithoutObservedChange() {
        val guard=ActionProgressGuard()
        guard.record("daily time button","unchanged screen")
        assertTrue(guard.repeatedUnchanged("daily time button","unchanged screen"))
        assertFalse(guard.repeatedUnchanged("daily time button","dialog opened"))
        assertFalse(guard.repeatedUnchanged("back button","unchanged screen"))
    }
    @Test fun navigationBetweenClicksAllowsReturningToTheSameButton() {
        val guard=ActionProgressGuard();guard.record("menu","home")
        guard.clear()
        assertFalse(guard.repeatedUnchanged("menu","home"))
        assertFalse(guard.repeatedUnchanged(null,null))
    }
}
