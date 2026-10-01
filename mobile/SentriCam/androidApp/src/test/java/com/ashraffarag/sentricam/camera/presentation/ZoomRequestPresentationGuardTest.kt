package com.ashraffarag.sentricam.camera.presentation

import com.ashraffarag.sentricam.cameracontrol.capability.CameraHardwareResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomRequestPresentationGuardTest {
    private val guard = ZoomRequestPresentationGuard()

    @Test
    fun staleFailureIsNotPresentedAfterANewerRequestStarts() {
        val stale = guard.beginRequest()
        guard.beginRequest()

        assertEquals(
            ZoomRequestPresentation.STALE,
            guard.resolve(stale, CameraHardwareResult(false, true, "camerax_transient_failure")),
        )
    }

    @Test
    fun genuineFailureForCurrentRequestIsPresented() {
        val current = guard.beginRequest()

        assertEquals(
            ZoomRequestPresentation.FAILURE,
            guard.resolve(current, CameraHardwareResult(false, true, "camera_closed")),
        )
    }

    @Test
    fun onlyLatestSuccessfulValueIsAcceptedForRendering() {
        val older = guard.beginRequest()
        val latest = guard.beginRequest()

        assertEquals(
            ZoomRequestPresentation.STALE,
            guard.resolve(older, CameraHardwareResult(true)),
        )
        assertEquals(
            ZoomRequestPresentation.APPLIED,
            guard.resolve(latest, CameraHardwareResult(true)),
        )
    }
}
