package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class ForegroundWaitRulesTest {
    @Test fun openingChatWaitsForUiWithoutCancellingTask() {
        val task=TaskCheckpoint(goal="Read mail",provider="gemini",model="model",lastAction="launch_app",activePackage="example.mail")
        assertTrue(ForegroundWaitRules.shouldWait(task,"own.agent","own.agent"))
        assertFalse(ForegroundWaitRules.shouldWait(task,"example.mail","own.agent"))
    }
    @Test fun firstLaunchMayLeaveAgentPanelToPerformExplicitTask() {
        val task=TaskCheckpoint(goal="Open calculator",provider="gemini",model="model")
        assertFalse(ForegroundWaitRules.shouldWait(task,"own.agent","own.agent"))
    }
    @Test fun notificationReplyDoesNotMakeOldAppControlsActionable() {
        val task=TaskCheckpoint(goal="Read mail",provider="gemini",model="model",lastAction="tap_node",activePackage="example.mail")
        assertTrue(ForegroundWaitRules.shouldWait(task,"com.android.systemui","own.agent"))
        assertFalse(ForegroundWaitRules.shouldWait(task.copy(activePackage="com.android.systemui"),"com.android.systemui","own.agent"))
    }
}
