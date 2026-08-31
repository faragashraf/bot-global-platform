package com.ashraffarag.sentricam.motion.android.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.motion.presentation.MotionStatusKind
import com.google.android.material.button.MaterialButton

/** Keeps toolbar state styling and accessibility behavior out of the activity. */
class MotionToolbarButtonController(
    private val button: MaterialButton,
) {
    private var pulseAnimator: AnimatorSet? = null

    init {
        button.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun render(kind: MotionStatusKind) {
        val visual = MotionButtonVisual.forKind(kind)
        button.setIconResource(visual.icon)
        button.iconTint = ColorStateList.valueOf(color(visual.color))
        button.strokeColor = ColorStateList.valueOf(color(visual.color))
        button.contentDescription = button.context.getString(visual.contentDescription)
        ViewCompat.setStateDescription(button, button.context.getString(visual.contentDescription))

        if (kind == MotionStatusKind.DETECTED) startPulse() else stopPulse()
    }

    fun release() = stopPulse()

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        val scaleX = ObjectAnimator.ofFloat(button, View.SCALE_X, 1f, 1.12f, 1f)
        val scaleY = ObjectAnimator.ofFloat(button, View.SCALE_Y, 1f, 1.12f, 1f)
        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 850L
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 100L
            addListener(RepeatingAnimatorListener { start() })
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.removeAllListeners()
        pulseAnimator?.cancel()
        pulseAnimator = null
        button.scaleX = 1f
        button.scaleY = 1f
    }

    private fun color(@ColorRes resource: Int): Int = ContextCompat.getColor(button.context, resource)
}

private data class MotionButtonVisual(
    @param:DrawableRes val icon: Int,
    @param:ColorRes val color: Int,
    @param:StringRes val contentDescription: Int,
) {
    companion object {
        fun forKind(kind: MotionStatusKind): MotionButtonVisual = when (kind) {
            MotionStatusKind.OFF -> MotionButtonVisual(
                R.drawable.ic_motion_24,
                R.color.motion_off,
                R.string.motion_accessibility_disabled,
            )
            MotionStatusKind.MONITORING -> MotionButtonVisual(
                R.drawable.ic_motion_24,
                R.color.motion_monitoring,
                R.string.motion_accessibility_monitoring,
            )
            MotionStatusKind.DETECTED -> MotionButtonVisual(
                R.drawable.ic_motion_24,
                R.color.motion_detected,
                R.string.motion_accessibility_detected,
            )
            MotionStatusKind.RECORDING -> MotionButtonVisual(
                R.drawable.ic_motion_recording_24,
                R.color.motion_recording,
                R.string.motion_accessibility_recording,
            )
            MotionStatusKind.HOLDING -> MotionButtonVisual(
                R.drawable.ic_motion_recording_24,
                R.color.motion_holding,
                R.string.motion_accessibility_holding,
            )
            MotionStatusKind.COOLDOWN -> MotionButtonVisual(
                R.drawable.ic_motion_24,
                R.color.motion_cooldown,
                R.string.motion_accessibility_cooldown,
            )
            MotionStatusKind.ERROR -> MotionButtonVisual(
                R.drawable.ic_motion_24,
                R.color.motion_error,
                R.string.motion_accessibility_error,
            )
        }
    }
}

private class RepeatingAnimatorListener(
    private val onEnd: () -> Unit,
) : android.animation.Animator.AnimatorListener {
    override fun onAnimationStart(animation: android.animation.Animator) = Unit
    override fun onAnimationCancel(animation: android.animation.Animator) = Unit
    override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
    override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
}
