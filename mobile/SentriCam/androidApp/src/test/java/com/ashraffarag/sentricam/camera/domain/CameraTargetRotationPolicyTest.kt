package com.ashraffarag.sentricam.camera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraTargetRotationPolicyTest {
    @Test
    fun coldStartPortraitFallsBackToDisplay() {
        assertDisplayFallback(CameraRotationQuadrant.ROTATION_0)
    }

    @Test
    fun coldStartLandscapeClockwiseFallsBackToDisplay() {
        assertDisplayFallback(CameraRotationQuadrant.ROTATION_90)
    }

    @Test
    fun coldStartLandscapeCounterClockwiseFallsBackToDisplay() {
        assertDisplayFallback(CameraRotationQuadrant.ROTATION_270)
    }

    @Test
    fun physicalOrientationWinsEvenWhenOemReportsAutoRotateEnabled() {
        assertEquals(
            CameraRotationQuadrant.ROTATION_180,
            CameraTargetRotationPolicy.select(
                CameraRotationQuadrant.ROTATION_0,
                CameraRotationQuadrant.ROTATION_180,
            ),
        )
    }

    @Test
    fun portraitLockUsesPhysicalOrientationInsteadOfPortraitDisplay() {
        assertEquals(
            CameraRotationQuadrant.ROTATION_90,
            CameraTargetRotationPolicy.select(
                CameraRotationQuadrant.ROTATION_0,
                CameraRotationQuadrant.ROTATION_90,
            ),
        )
    }

    @Test
    fun landscapeLockUsesPhysicalOrientationInsteadOfLandscapeDisplay() {
        assertEquals(
            CameraRotationQuadrant.ROTATION_180,
            CameraTargetRotationPolicy.select(
                CameraRotationQuadrant.ROTATION_90,
                CameraRotationQuadrant.ROTATION_180,
            ),
        )
    }

    @Test
    fun physicalRotationUpdatesWhileUiRemainsLocked() {
        val quantizer = PhysicalOrientationQuantizer()
        val display = CameraRotationQuadrant.ROTATION_0

        val targets = listOf(0, 100, 180, 280).map { physicalDegrees ->
            CameraTargetRotationPolicy.select(
                display,
                quantizer.update(physicalDegrees),
            )
        }

        assertEquals(
            listOf(
                CameraRotationQuadrant.ROTATION_0,
                CameraRotationQuadrant.ROTATION_270,
                CameraRotationQuadrant.ROTATION_180,
                CameraRotationQuadrant.ROTATION_90,
            ),
            targets,
        )
    }

    @Test
    fun sensorUnavailableFallsBackToLockedDisplay() {
        assertEquals(
            CameraRotationQuadrant.ROTATION_270,
            CameraTargetRotationPolicy.select(
                CameraRotationQuadrant.ROTATION_270,
                physicalRotation = null,
            ),
        )
    }

    @Test
    fun lensFacingDoesNotAddAnotherTargetRotation() {
        val physical = PhysicalOrientationQuantizer().update(90)

        val backCamera = CameraTargetRotationPolicy.select(
            CameraRotationQuadrant.ROTATION_0,
            physical,
        )
        val frontCamera = CameraTargetRotationPolicy.select(
            CameraRotationQuadrant.ROTATION_0,
            physical,
        )

        assertEquals(CameraRotationQuadrant.ROTATION_270, backCamera)
        assertEquals(backCamera, frontCamera)
    }

    @Test
    fun initialPhysicalAnglesMapToAllSurfaceQuadrants() {
        assertEquals(CameraRotationQuadrant.ROTATION_0, quantize(0))
        assertEquals(CameraRotationQuadrant.ROTATION_270, quantize(90))
        assertEquals(CameraRotationQuadrant.ROTATION_180, quantize(180))
        assertEquals(CameraRotationQuadrant.ROTATION_90, quantize(270))
    }

    @Test
    fun invalidPhysicalOrientationDoesNotInventAQuadrant() {
        assertNull(PhysicalOrientationQuantizer().update(-1))
    }

    @Test
    fun hysteresisPreventsJitterNearZeroAndLandscapeBoundary() {
        val quantizer = PhysicalOrientationQuantizer()

        assertEquals(CameraRotationQuadrant.ROTATION_0, quantizer.update(0))
        listOf(43, 46, 49, 45, 42).forEach { degrees ->
            assertEquals(CameraRotationQuadrant.ROTATION_0, quantizer.update(degrees))
        }
        assertEquals(CameraRotationQuadrant.ROTATION_270, quantizer.update(50))
        listOf(48, 45, 41).forEach { degrees ->
            assertEquals(CameraRotationQuadrant.ROTATION_270, quantizer.update(degrees))
        }
        assertEquals(CameraRotationQuadrant.ROTATION_0, quantizer.update(39))
    }

    @Test
    fun resetRemovesPreviousHysteresisState() {
        val quantizer = PhysicalOrientationQuantizer()
        assertEquals(CameraRotationQuadrant.ROTATION_270, quantizer.update(90))

        quantizer.reset()

        assertEquals(CameraRotationQuadrant.ROTATION_0, quantizer.update(44))
    }

    private fun assertDisplayFallback(display: CameraRotationQuadrant) {
        assertEquals(
            display,
            CameraTargetRotationPolicy.select(
                display,
                physicalRotation = null,
            ),
        )
    }

    private fun quantize(degrees: Int) = PhysicalOrientationQuantizer().update(degrees)
}
