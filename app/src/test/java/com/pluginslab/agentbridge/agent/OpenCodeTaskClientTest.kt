package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class OpenCodeTaskClientTest {
    @Test fun parsesTerminalResult() {
        val value = OpenCodeTaskClient.decodeUpdate(
            """{"state":"done","answer":"Проверил","reason":""}"""
        )
        assertEquals("done",value.state)
        assertEquals("Проверил",value.answer)
    }

    @Test fun dispatchUncertainIsNotSuccess() {
        val value = OpenCodeTaskClient.decodeUpdate(
            """{"state":"unknown","answer":"","reason":"dispatch_unconfirmed"}"""
        )
        assertEquals("unknown",value.state)
        assertFalse(value.state == "done")
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnknownStates() {
        OpenCodeTaskClient.decodeUpdate("""{"state":"success","answer":"done"}""")
    }

    @Test(expected = IllegalStateException::class)
    fun refusesMissingState() {
        OpenCodeTaskClient.decodeUpdate("""{"answer":"done"}""")
    }
}
