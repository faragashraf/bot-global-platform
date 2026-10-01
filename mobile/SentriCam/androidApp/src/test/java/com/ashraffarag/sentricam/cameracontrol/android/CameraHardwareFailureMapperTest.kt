package com.ashraffarag.sentricam.cameracontrol.android

import androidx.camera.core.CameraControl
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraHardwareFailureMapperTest {
    @Test
    fun supersededCoroutineCancellationIsPropagatedInsteadOfBecomingHardwareFailure() {
        val cancellation = CancellationException("superseded zoom request")

        val thrown = runCatching { CameraHardwareFailureMapper.map(cancellation) }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun wrappedFutureCancellationObservedDuringCameraReplacementIsNotAUserFailure() {
        val failure = ExecutionException(CancellationException("Camera is closed"))

        val thrown = runCatching { CameraHardwareFailureMapper.map(failure) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertSame(failure, thrown?.cause)
    }

    @Test
    fun wrappedCameraXOperationCancellationObservedOnSamsungIsNotAUserFailure() {
        val failure = ExecutionException(
            CameraControl.OperationCanceledException("There is a new zoomRatio being set"),
        )

        val thrown = runCatching { CameraHardwareFailureMapper.map(failure) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertSame(failure, thrown?.cause)
    }

    @Test
    fun genuineCameraFailureStillMapsToAUserActionableFailureResult() {
        val result = CameraHardwareFailureMapper.map(IllegalStateException("camera closed"))

        assertFalse(result.succeeded)
        assertTrue(result.transientFailure)
        assertEquals("camerax_transient_failure", result.code)
    }

    @Test
    fun wrappedGenuineCameraFailureStillSurfaces() {
        val result = CameraHardwareFailureMapper.map(
            ExecutionException(IllegalStateException("camera device error")),
        )

        assertFalse(result.succeeded)
        assertTrue(result.transientFailure)
        assertEquals("camerax_transient_failure", result.code)
    }
}
