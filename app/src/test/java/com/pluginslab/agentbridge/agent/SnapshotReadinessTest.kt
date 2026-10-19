package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class SnapshotReadinessTest {
    @Test fun emptyApplicationDoesNotCountAsLoadedAndWaitIsBounded() {
        val wait = SnapshotReadiness()
        assertFalse(wait.ready(0,""))
        assertFalse(wait.ready(3000,""))
        assertTrue(wait.expired(3000))
    }
    @Test fun changingContentMustSettleAfterItAppears() {
        val wait = SnapshotReadiness()
        assertFalse(wait.ready(0,"header"))
        assertFalse(wait.ready(500,"header"))
        assertFalse(wait.ready(750,"header and settings"))
        assertFalse(wait.ready(1000,"header and settings"))
        assertTrue(wait.ready(1250,"header and settings"))
        assertFalse(wait.ready(1500,"new page"))
    }
}
