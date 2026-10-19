package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class UiTargetRulesTest {
    @Test fun flutterLoadingPlaceholderCannotBeTappedEvenWhenItHasBounds() {
        assertTrue(UiTargetRules.actionable(true,1080,53))
        assertFalse(UiTargetRules.namedTapTarget(false,false,false,false,true))
        assertFalse(UiTargetRules.namedTapTarget(false,true,false,false,true))
    }
    @Test fun namedFlutterButtonAndCompositeButtonRemainUsable() {
        assertTrue(UiTargetRules.namedTapTarget(true,false,true,false,true))
        assertTrue(UiTargetRules.namedTapTarget(false,true,true,false,true))
        assertTrue(UiTargetRules.namedTapTarget(false,false,true,true,true))
        assertFalse(UiTargetRules.namedTapTarget(true,true,true,false,false))
    }
    @Test fun collapsedGmailSearchWithNegativeHeightCannotBeClicked() {
        assertFalse(UiTargetRules.actionable(true,750,-165))
        assertEquals("above_or_collapsed",UiTargetRules.position(0,750,-165))
    }
    @Test fun invisibleNodesWithPositiveBoundsCannotBeClicked() {
        assertFalse(UiTargetRules.actionable(false,750,154))
    }
    @Test fun visibleNodeWithPositiveBoundsCanBeClicked() {
        assertTrue(UiTargetRules.actionable(true,750,154))
        assertFalse(UiTargetRules.actionable(true,0,154))
    }
}
