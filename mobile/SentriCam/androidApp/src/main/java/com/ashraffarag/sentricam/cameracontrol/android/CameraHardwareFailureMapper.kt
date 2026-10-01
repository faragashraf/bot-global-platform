package com.ashraffarag.sentricam.cameracontrol.android

import androidx.camera.core.CameraControl
import com.ashraffarag.sentricam.cameracontrol.capability.CameraHardwareResult
import kotlinx.coroutines.CancellationException

internal object CameraHardwareFailureMapper {
    fun map(failure: Exception): CameraHardwareResult {
        val cancellation = generateSequence(failure as Throwable?) { it.cause }
            .firstOrNull {
                it is CancellationException || it is CameraControl.OperationCanceledException
            }
        if (cancellation != null) {
            if (failure is CancellationException) throw failure
            throw CancellationException("Camera control request was superseded").apply {
                initCause(failure)
            }
        }
        return when (failure) {
            is IllegalArgumentException -> CameraHardwareResult(false, code = "value_out_of_range")
            is SecurityException -> CameraHardwareResult(false, code = "camera_permission_missing")
            else -> CameraHardwareResult(false, transientFailure = true, code = "camerax_transient_failure")
        }
    }
}
