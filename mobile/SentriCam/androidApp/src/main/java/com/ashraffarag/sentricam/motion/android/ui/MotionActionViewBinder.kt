package com.ashraffarag.sentricam.motion.android.ui

import android.view.HapticFeedbackConstants
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.ashraffarag.sentricam.motion.presentation.MotionActionBindingController
import com.ashraffarag.sentricam.motion.presentation.MotionActionUiPolicy
import com.ashraffarag.sentricam.motion.presentation.MotionFallbackActionTarget
import com.ashraffarag.sentricam.motion.presentation.MotionPrimaryActionTarget
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

class MotionActionViewBinder(
    private val primaryButton: MaterialButton,
    private val settingsFallbackButton: MaterialButton?,
    private val bindingController: MotionActionBindingController,
    private val longPressHint: String,
    private val accessibilitySettingsLabel: String,
) {
    fun bind() {
        ensureMinimumTouchTarget(primaryButton)
        settingsFallbackButton?.let(::ensureMinimumTouchTarget)

        // On API 23–25 TooltipCompat installs its own long-click listener. Install it first,
        // then bind the application action so the standard long press always reaches Settings.
        TooltipCompat.setTooltipText(primaryButton, longPressHint)
        bindingController.bindPrimary(AndroidPrimaryTarget(primaryButton, accessibilitySettingsLabel))
        settingsFallbackButton?.let { fallback ->
            bindingController.bindFallback(AndroidFallbackTarget(fallback))
        }
    }

    private fun ensureMinimumTouchTarget(button: MaterialButton) {
        val pixels = (MotionActionUiPolicy.MINIMUM_TOUCH_TARGET_DP *
            button.resources.displayMetrics.density).roundToInt()
        button.minWidth = maxOf(button.minWidth, pixels)
        button.minHeight = maxOf(button.minHeight, pixels)
    }

    private class AndroidPrimaryTarget(
        private val button: MaterialButton,
        private val settingsActionLabel: String,
    ) : MotionPrimaryActionTarget {
        override var isLongClickable: Boolean
            get() = button.isLongClickable
            set(value) { button.isLongClickable = value }

        override fun setClickAction(action: () -> Unit) {
            button.setOnClickListener { action() }
        }

        override fun setLongClickAction(action: () -> Boolean) {
            button.setOnLongClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                action()
            }
        }

        override fun setOpenSettingsAccessibilityAction(action: () -> Boolean) {
            ViewCompat.replaceAccessibilityAction(
                button,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                settingsActionLabel,
            ) { _, _ -> action() }
        }
    }

    private class AndroidFallbackTarget(
        private val button: MaterialButton,
    ) : MotionFallbackActionTarget {
        override fun setClickAction(action: () -> Unit) {
            button.setOnClickListener { action() }
        }
    }
}
