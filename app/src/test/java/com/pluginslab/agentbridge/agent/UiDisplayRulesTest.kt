package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class UiDisplayRulesTest {
    @Test fun narrowNavigationHasFiveVisibleShortLabels() {
        val tabs=UiDisplayRules.tabs
        assertEquals(5,tabs.size)
        assertEquals(5,tabs.map { it.short }.distinct().size)
        assertTrue(tabs.all { it.short.length <= 7 })
        assertEquals("Настройки",tabs[2].accessible)
        assertEquals("Состояние",tabs[4].accessible)
    }

    @Test fun memoryPagingIsBoundedAndNeverDropsMatchingRecords() {
        assertEquals(60,UiDisplayRules.MEMORY_PAGE_SIZE)
        assertEquals(60,UiDisplayRules.nextMemoryLimit(0,140))
        assertEquals(120,UiDisplayRules.nextMemoryLimit(60,140))
        assertEquals(140,UiDisplayRules.nextMemoryLimit(120,140))
        assertEquals(15,UiDisplayRules.nextMemoryLimit(0,15))
        assertEquals(0,UiDisplayRules.nextMemoryLimit(0,0))
    }

    @Test fun compactEventsPreserveUnambiguousFullClassOption() {
        val full="com.android.settings.Settings"
        assertEquals("Settings",UiDisplayRules.activityName(full,false))
        assertEquals(full,UiDisplayRules.activityName(full,true))
        assertEquals("",UiDisplayRules.activityName("",false))
    }
}
