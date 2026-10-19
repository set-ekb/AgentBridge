package com.pluginslab.agentbridge.agent

/** A successful dispatch is not proof that the target screen changed. */
class ActionProgressGuard {
    private var target: String? = null
    private var screen: String? = null
    fun repeatedUnchanged(nextTarget: String?, currentScreen: String?): Boolean =
        nextTarget != null && currentScreen != null && target == nextTarget && screen == currentScreen
    fun record(nextTarget: String?, beforeScreen: String?) { target = nextTarget; screen = beforeScreen }
    fun clear() { target = null; screen = null }
}
