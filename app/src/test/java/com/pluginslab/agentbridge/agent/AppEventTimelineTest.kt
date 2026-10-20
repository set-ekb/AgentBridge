package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class AppEventTimelineTest {
    @Test fun acceptsOnlyAllowlistedMetadata() {
        val before = AppEventTimeline.latestId()
        AppEventTimeline.append("vector_hint", "resumed", "test.example.app", "test.example.app.Home")
        val events = AppEventTimeline.recent(before, "test.example.app")
        assertEquals(1, events.size)
        assertEquals("resumed", events.single().type)
        assertEquals("vector_hint", events.single().source)
        AppEventTimeline.append("vector_hint", "resumed", "test.example.app;evil", "Home")
        AppEventTimeline.append("vector_hint", "read_secret", "test.example.app", "Home")
        assertEquals(1, AppEventTimeline.recent(before, "test.example.app").size)
    }

    @Test fun cursorAndBoundsDoNotExposeOldEvents() {
        val cursor = AppEventTimeline.latestId()
        AppEventTimeline.append("accessibility", "window", "test.example.window", "android.app.Activity")
        assertTrue(AppEventTimeline.recent(cursor, "test.example.window", 1).isNotEmpty())
        val latest = AppEventTimeline.latestId()
        assertTrue(AppEventTimeline.recent(latest).isEmpty())
    }
}
