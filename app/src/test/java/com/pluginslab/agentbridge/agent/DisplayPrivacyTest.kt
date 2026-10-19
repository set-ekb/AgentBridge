package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class DisplayPrivacyTest {
    @Test fun secretsAreNotEchoedOnWidgetOrNotificationSurfaces() {
        val text="API_KEY=confidential-value Bearer abcdefghijklmnop sk-abcdefghijklmnop"
        val safe=DisplayPrivacy.safe(text)
        assertFalse(safe.contains("confidential-value"));assertFalse(safe.contains("abcdefghijklmnop"))
        assertTrue(safe.contains("[секрет скрыт]"))
    }
    @Test fun ordinaryDeviceIdentifiersAndRussianRepliesRemainReadable() {
        val text="Телефон 23124RA7EO · осталось 3 часа"
        assertEquals(text,DisplayPrivacy.safe(text))
    }
}
