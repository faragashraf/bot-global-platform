package com.ashraffarag.sentricam.motion.presentation

import com.ashraffarag.sentricam.motion.domain.MotionDebugMetrics
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionEventSummary
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionStatusUiTest {
    private val metrics = MotionDebugMetrics(score = 0.2)
    private val event = MotionEventSummary(
        eventId = "event-1",
        detectedAtMillis = 1L,
        lastMotionAtMillis = 2L,
        sensitivity = MotionSensitivity.MEDIUM,
        peakScore = 0.4,
        averageScore = 0.2,
        sampleCount = 2,
        burstCount = 1,
    )

    @Test
    fun toolbarKindsFollowMotionLifecycle() {
        assertEquals(
            MotionStatusKind.OFF,
            MotionStatusUiFactory.from(MotionDetectionState.Disabled, false).kind,
        )
        assertEquals(
            MotionStatusKind.MONITORING,
            MotionStatusUiFactory.from(MotionDetectionState.NoMotion(metrics), false).kind,
        )
        assertEquals(
            MotionStatusKind.DETECTED,
            MotionStatusUiFactory.from(MotionDetectionState.MotionConfirmed(event, metrics), false).kind,
        )
        assertEquals(
            MotionStatusKind.RECORDING,
            MotionStatusUiFactory.from(MotionDetectionState.MotionConfirmed(event, metrics), true).kind,
        )
        assertEquals(
            MotionStatusKind.HOLDING,
            MotionStatusUiFactory.from(MotionDetectionState.Holding(event, 10L, metrics), true).kind,
        )
        assertEquals(
            MotionStatusKind.COOLDOWN,
            MotionStatusUiFactory.from(MotionDetectionState.Cooldown(event, 20L, metrics), false).kind,
        )
    }
}
