package com.ashraffarag.sentricam.motion.android.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.databinding.ViewMotionStatusBinding
import com.ashraffarag.sentricam.motion.presentation.MotionStatusKind
import com.ashraffarag.sentricam.motion.presentation.MotionStatusUi
import com.ashraffarag.sentricam.motion.domain.MotionFailureCode
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class MotionStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {
    private val binding = ViewMotionStatusBinding.inflate(LayoutInflater.from(context), this, true)

    fun render(ui: MotionStatusUi) {
        visibility = when (ui.kind) {
            MotionStatusKind.OFF -> View.GONE
            MotionStatusKind.MONITORING -> if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            else -> View.VISIBLE
        }
        val label = when (ui.kind) {
            MotionStatusKind.OFF -> R.string.motion_status_off
            MotionStatusKind.MONITORING -> R.string.motion_status_monitoring
            MotionStatusKind.DETECTED -> R.string.motion_status_detected
            MotionStatusKind.RECORDING -> R.string.motion_status_recording
            MotionStatusKind.HOLDING -> R.string.motion_status_holding
            MotionStatusKind.COOLDOWN -> R.string.motion_status_cooldown
            MotionStatusKind.ERROR -> ui.failureCode.toMessageResource()
        }
        val color = when (ui.kind) {
            MotionStatusKind.OFF -> R.color.vision_status_offline
            MotionStatusKind.MONITORING -> R.color.vision_brand_primary
            MotionStatusKind.DETECTED -> R.color.vision_status_motion
            MotionStatusKind.RECORDING -> R.color.vision_status_recording
            MotionStatusKind.HOLDING -> R.color.vision_status_motion
            MotionStatusKind.COOLDOWN -> R.color.vision_status_motion
            MotionStatusKind.ERROR -> R.color.vision_status_critical
        }
        val containerColor = when (ui.kind) {
            MotionStatusKind.OFF -> R.color.vision_status_offline_container
            MotionStatusKind.MONITORING -> R.color.vision_brand_primary_container
            MotionStatusKind.DETECTED,
            MotionStatusKind.HOLDING,
            MotionStatusKind.COOLDOWN,
            -> R.color.vision_status_motion_container
            MotionStatusKind.RECORDING -> R.color.vision_status_recording_container
            MotionStatusKind.ERROR -> R.color.vision_status_critical_container
        }
        binding.motionLabel.setText(label)
        binding.motionLabel.setTextColor(ContextCompat.getColor(context, color))
        strokeColor = ContextCompat.getColor(context, color)
        setCardBackgroundColor(ContextCompat.getColor(context, containerColor))
        contentDescription = context.getString(label)
        binding.motionDebug.visibility = if (BuildConfig.DEBUG && ui.metrics != null) View.VISIBLE else View.GONE
        ui.metrics?.let { metrics ->
            binding.motionDebug.text = context.getString(
                R.string.motion_debug_metrics,
                String.format(Locale.US, "%.3f", metrics.score),
                String.format(Locale.US, "%.3f", metrics.threshold),
                metrics.positiveFrames,
                metrics.requiredPositiveFrames,
                String.format(Locale.US, "%.1f", metrics.analyzerFps),
                String.format(Locale.US, "%.1f", metrics.averageProcessingMillis),
                metrics.droppedFrames,
                ui.eventId?.take(8) ?: "-",
            )
        }
    }

    private fun MotionFailureCode?.toMessageResource(): Int = when (this) {
        MotionFailureCode.ANALYZER_UNAVAILABLE -> R.string.motion_error_analyzer_unavailable
        MotionFailureCode.UNSUPPORTED_USE_CASE_COMBINATION -> R.string.motion_error_unsupported_combination
        MotionFailureCode.CAMERA_NOT_READY -> R.string.motion_error_camera_not_ready
        MotionFailureCode.INVALID_CONFIG -> R.string.motion_error_invalid_config
        MotionFailureCode.RECORDING_TRIGGER_REJECTED -> R.string.motion_error_recording_rejected
        MotionFailureCode.INITIALIZATION_FAILED -> R.string.motion_error_initialization
        MotionFailureCode.PROCESSING_FAILED -> R.string.motion_error_processing
        MotionFailureCode.MONITORING_ALREADY_RUNNING -> R.string.motion_error_already_running
        MotionFailureCode.MONITORING_NOT_RUNNING -> R.string.motion_error_not_running
        MotionFailureCode.UNEXPECTED_FAILURE, null -> R.string.motion_status_error
    }
}
