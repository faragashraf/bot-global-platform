package com.ashraffarag.sentricam.motion.presentation

import com.ashraffarag.sentricam.motion.domain.MotionDebugMetrics
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionFailureCode

enum class MotionStatusKind {
    OFF,
    MONITORING,
    DETECTED,
    RECORDING,
    HOLDING,
    COOLDOWN,
    ERROR,
}

data class MotionStatusUi(
    val kind: MotionStatusKind,
    val metrics: MotionDebugMetrics? = null,
    val eventId: String? = null,
    val failureCode: MotionFailureCode? = null,
)

object MotionStatusUiFactory {
    fun from(state: MotionDetectionState, motionRecordingActive: Boolean): MotionStatusUi = when (state) {
        MotionDetectionState.Disabled -> MotionStatusUi(MotionStatusKind.OFF)
        is MotionDetectionState.Initializing -> MotionStatusUi(MotionStatusKind.MONITORING, state.metrics)
        is MotionDetectionState.NoMotion -> MotionStatusUi(MotionStatusKind.MONITORING, state.metrics)
        is MotionDetectionState.SuspectedMotion -> MotionStatusUi(MotionStatusKind.DETECTED, state.metrics)
        is MotionDetectionState.MotionConfirmed -> MotionStatusUi(
            if (motionRecordingActive) MotionStatusKind.RECORDING else MotionStatusKind.DETECTED,
            state.metrics,
            state.event.eventId,
        )
        is MotionDetectionState.Holding -> MotionStatusUi(
            if (motionRecordingActive) MotionStatusKind.HOLDING else MotionStatusKind.DETECTED,
            state.metrics,
            state.event.eventId,
        )
        is MotionDetectionState.Cooldown -> MotionStatusUi(
            MotionStatusKind.COOLDOWN,
            state.metrics,
            state.event.eventId,
        )
        is MotionDetectionState.Error -> MotionStatusUi(
            kind = MotionStatusKind.ERROR,
            failureCode = state.failure.code,
        )
    }
}
