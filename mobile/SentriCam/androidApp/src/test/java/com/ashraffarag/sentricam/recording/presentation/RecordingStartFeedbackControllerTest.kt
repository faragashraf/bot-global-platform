package com.ashraffarag.sentricam.recording.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStartFeedbackControllerTest {
    @Test
    fun successfulManualStartConfirmsExactlyOnce() {
        val vibrator = CountingVibrator()
        val controller = RecordingStartFeedbackController(vibrator)

        controller.onEvent(RecordingStartFeedbackEvent.MANUAL_STARTED)

        assertEquals(1, vibrator.count)
    }

    @Test
    fun automaticMotionStartDoesNotConfirm() {
        val vibrator = CountingVibrator()
        val controller = RecordingStartFeedbackController(vibrator)

        controller.onEvent(RecordingStartFeedbackEvent.MOTION_STARTED)

        assertEquals(0, vibrator.count)
    }

    @Test
    fun rejectedManualStartDoesNotConfirm() {
        val vibrator = CountingVibrator()
        val controller = RecordingStartFeedbackController(vibrator)

        controller.onEvent(RecordingStartFeedbackEvent.MANUAL_REJECTED)

        assertEquals(0, vibrator.count)
    }

    @Test
    fun motionPromotionDoesNotConfirm() {
        val vibrator = CountingVibrator()
        val controller = RecordingStartFeedbackController(vibrator)

        controller.onEvent(RecordingStartFeedbackEvent.MOTION_PROMOTED_TO_MANUAL)

        assertEquals(0, vibrator.count)
    }

    @Test
    fun stopDoesNotVibrate() {
        val vibrator = CountingVibrator()
        val controller = RecordingStartFeedbackController(vibrator)

        controller.onEvent(RecordingStartFeedbackEvent.STOPPED)

        assertEquals(0, vibrator.count)
    }

    @Test
    fun stateRenderDoesNotVibrate() {
        val vibrator = CountingVibrator()
        val controller = RecordingStartFeedbackController(vibrator)

        controller.onEvent(RecordingStartFeedbackEvent.STATE_RENDERED)

        assertEquals(0, vibrator.count)
    }

    private class CountingVibrator : RecordingConfirmationVibrator {
        var count = 0
            private set

        override fun vibrate() {
            count++
        }
    }
}
