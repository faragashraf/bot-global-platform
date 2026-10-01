package com.ashraffarag.sentricam.camera.presentation

import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings

enum class LiveFlashMode {
    OFF,
    ON,
    AUTO,
}

object LiveCameraControlPolicy {
    const val AUTO_FLASH_AVAILABLE = false

    fun flashMode(settings: CameraControlSettings): LiveFlashMode =
        if (settings.torch) LiveFlashMode.ON else LiveFlashMode.OFF

    fun settingsForFlashMode(
        current: CameraControlSettings,
        mode: LiveFlashMode,
    ): CameraControlSettings? = when (mode) {
        LiveFlashMode.OFF -> current.copy(torch = false)
        LiveFlashMode.ON -> current.copy(torch = true)
        LiveFlashMode.AUTO -> null
    }

    fun normalizedZoom(value: Float, minimum: Double, maximum: Double): Double =
        value.toDouble().coerceIn(minimum, maximum)
}
