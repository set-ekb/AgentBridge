package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.io.ByteArrayInputStream

class OpenCodeTaskClientTest {
    private class FakeConnection(private val code: Int = 200, private val failure: Exception? = null) :
        HttpURLConnection(URL("http://127.0.0.1/")) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int { failure?.let { throw it }; return code }
        override fun getInputStream() = ByteArrayInputStream("""{"state":"running"}""".toByteArray())
    }

    @Test fun oldGateway404FallsBackToGetSameUuid() = runBlocking {
        val paths = mutableListOf<String>()
        val id = "12345678-1234-1234-1234-123456789abc"
        val old = FakeConnection(404)
        val poll = FakeConnection()
        val update = try {
            OpenCodeTaskClient.request("/tasks/$id/wait?timeout=15", "test", longWait=true,
                connect={ paths.add(it); old })
        } catch (_: GatewayWaitUnsupported) {
            OpenCodeTaskClient.request("/tasks/$id", "test", connect={ paths.add(it); poll })
        }
        assertEquals("running", update.state)
        assertEquals(2, paths.size)
        assertTrue(paths.all { it.contains("/tasks/$id") })
        assertEquals("GET", old.requestMethod)
        assertEquals("GET", poll.requestMethod)
        assertTrue(old.disconnected && poll.disconnected)
        assertTrue(old.readTimeout > 15000 + 5000)
    }

    @Test fun connectionLossAndReadTimeoutNeverPostOrRetry() = runBlocking {
        for (failure in listOf(ConnectException("offline"), SocketTimeoutException("timeout"))) {
            val connection = FakeConnection(failure=failure)
            var calls = 0
            try {
                OpenCodeTaskClient.request("/tasks/id/wait?timeout=15", "test", longWait=true,
                    connect={ calls++; connection })
                fail("Expected network failure")
            } catch (e: java.io.IOException) {
                assertSame(failure, e)
            }
            assertEquals(1, calls)
            assertEquals("GET", connection.requestMethod)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun server503MeansTemporaryOutageNotTaskFailure() = runBlocking {
        val connection=FakeConnection(503)
        try {
            OpenCodeTaskClient.request("/tasks/example", "test",connect={ connection })
            fail("Expected retryable gateway error")
        } catch (_: GatewayTemporarilyUnavailable) {
            assertEquals("GET",connection.requestMethod)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun missingTask404DoesNotImplyRetryOrSuccess() = runBlocking {
        try {
            OpenCodeTaskClient.request("/tasks/id", "test", connect={ FakeConnection(404) })
            fail("Expected missing task")
        } catch (e: IllegalStateException) {
            assertFalse(e is GatewayWaitUnsupported)
        }
    }

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
