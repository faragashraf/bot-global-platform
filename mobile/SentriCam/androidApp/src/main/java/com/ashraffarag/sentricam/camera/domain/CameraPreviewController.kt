package com.ashraffarag.sentricam.camera.domain

interface CameraPreviewController {
    fun start(
        onPreviewReady: () -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    fun queryAvailableLenses(
        onResult: (Set<CameraLensFacing>) -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    fun switchLens(
        lens: CameraLensFacing,
        onPreviewReady: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean

    fun currentOutputRotationDegrees(): Int

    fun stop()
}
