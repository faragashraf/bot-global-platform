package com.ashraffarag.sentricam.motion.presentation

interface MotionPrimaryActionTarget {
    var isLongClickable: Boolean
    fun setClickAction(action: () -> Unit)
    fun setLongClickAction(action: () -> Boolean)
    fun setOpenSettingsAccessibilityAction(action: () -> Boolean)
}

interface MotionFallbackActionTarget {
    fun setClickAction(action: () -> Unit)
}

class MotionActionController(
    private val toggleMonitoring: () -> Unit,
    private val openSettings: () -> Unit,
) {
    fun onPrimaryClick() = toggleMonitoring()

    fun onPrimaryLongClick(): Boolean {
        openSettings()
        return true
    }

    fun onSettingsFallbackClick() = openSettings()

    fun onOpenSettingsAccessibilityAction(): Boolean {
        openSettings()
        return true
    }
}

/** Reapplying this binding is safe when an action view is recreated. */
class MotionActionBindingController(
    private val controller: MotionActionController,
) {
    fun bindPrimary(target: MotionPrimaryActionTarget) {
        target.isLongClickable = true
        target.setClickAction(controller::onPrimaryClick)
        target.setLongClickAction(controller::onPrimaryLongClick)
        target.setOpenSettingsAccessibilityAction(controller::onOpenSettingsAccessibilityAction)
    }

    fun bindFallback(target: MotionFallbackActionTarget) {
        target.setClickAction(controller::onSettingsFallbackClick)
    }
}

object MotionActionUiPolicy {
    const val MINIMUM_TOUCH_TARGET_DP = 48
}

interface MotionSettingsHintStore {
    var hasShownMotionSettingsHint: Boolean
}

class MotionSettingsHintPolicy(
    private val store: MotionSettingsHintStore,
) {
    fun consumeHint(): Boolean {
        if (store.hasShownMotionSettingsHint) return false
        store.hasShownMotionSettingsHint = true
        return true
    }
}
