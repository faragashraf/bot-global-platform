package com.ashraffarag.sentricam.recording.presentation

fun interface RecordingConfirmationVibrator {
    fun vibrate()
}

enum class RecordingStartFeedbackEvent {
    MANUAL_STARTED,
    MANUAL_REJECTED,
    MOTION_STARTED,
    MOTION_PROMOTED_TO_MANUAL,
    STOPPED,
    STATE_RENDERED,
}

class RecordingStartFeedbackController(
    private val vibrator: RecordingConfirmationVibrator,
) {
    fun onEvent(event: RecordingStartFeedbackEvent) {
        if (event == RecordingStartFeedbackEvent.MANUAL_STARTED) vibrator.vibrate()
    }
}
