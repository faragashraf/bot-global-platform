package com.ashraffarag.sentricam.camera.presentation

import com.ashraffarag.sentricam.cameracontrol.capability.CameraHardwareResult

enum class ZoomRequestPresentation {
    APPLIED,
    FAILURE,
    STALE,
}

class ZoomRequestPresentationGuard {
    private var latestRequestId = 0L

    fun beginRequest(): Long = ++latestRequestId

    fun resolve(requestId: Long, result: CameraHardwareResult): ZoomRequestPresentation = when {
        requestId != latestRequestId -> ZoomRequestPresentation.STALE
        result.succeeded -> ZoomRequestPresentation.APPLIED
        else -> ZoomRequestPresentation.FAILURE
    }
}
