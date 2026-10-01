package com.ashraffarag.sentricam.recording.settings.domain

data class RecordingSettings(
    val videoQuality: RecordingVideoQuality = RecordingVideoQuality.HD,
    val audioEnabled: Boolean = true,
    val camera: RecordingCamera = RecordingCamera.REAR,
    val overlay: RecordingOverlayConfiguration = RecordingOverlayConfiguration(),
)

enum class RecordingVideoQuality {
    HD,
    FULL_HD,
}

enum class RecordingCamera {
    REAR,
    FRONT,
}

data class RecordingOverlayConfiguration(
    val enabled: Boolean = false,
    val dateEnabled: Boolean = true,
    val timeEnabled: Boolean = true,
    val use24HourTime: Boolean = true,
    val position: String = RecordingOverlayPositions.BOTTOM_LEFT,
) {
    val showDateTime: Boolean get() = enabled
}

object RecordingOverlayPositions {
    const val TOP_LEFT = "topLeft"
    const val TOP_RIGHT = "topRight"
    const val BOTTOM_LEFT = "bottomLeft"
    const val BOTTOM_RIGHT = "bottomRight"
    val ALL = listOf(TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT)
}
