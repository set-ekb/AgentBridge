package com.pluginslab.agentbridge.agent

object UiTargetRules {
    fun namedTapTarget(ownLabel: Boolean, descendantLabel: Boolean, control: Boolean,
                       editable: Boolean, enabled: Boolean): Boolean =
        enabled && (editable || ownLabel || (control && descendantLabel))
    fun actionable(visible: Boolean, width: Int, height: Int): Boolean = visible && width > 0 && height > 0
    fun position(y: Int, width: Int, height: Int): String = when {
        y + height <= 0 -> "above_or_collapsed"
        width <= 0 || height <= 0 -> "clipped_or_collapsed"
        else -> "outside_visible_area"
    }
}
