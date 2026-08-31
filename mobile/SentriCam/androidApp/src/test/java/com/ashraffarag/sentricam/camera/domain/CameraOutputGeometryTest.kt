package com.ashraffarag.sentricam.camera.domain

import com.ashraffarag.sentricam.recording.overlay.domain.RecordingOverlayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOutputGeometryTest {
    @Test
    fun rotationZeroKeepsLandscapeDimensions() {
        assertEquals(
            PixelDimensions(1280, 720),
            CameraOutputGeometry.effectiveDimensions(1280, 720, 0),
        )
    }

    @Test
    fun rotationNinetySwapsDimensionsToPortrait() {
        val dimensions = CameraOutputGeometry.effectiveDimensions(1280, 720, 90)

        assertEquals(PixelDimensions(720, 1280), dimensions)
        assertTrue(dimensions.isPortrait)
    }

    @Test
    fun rotationOneEightyKeepsPortraitDimensions() {
        val dimensions = CameraOutputGeometry.effectiveDimensions(720, 1152, 180)

        assertEquals(PixelDimensions(720, 1152), dimensions)
        assertTrue(dimensions.isPortrait)
    }

    @Test
    fun rotationTwoSeventySwapsPortraitEncodedTrackToLandscape() {
        assertEquals(
            PixelDimensions(1280, 720),
            CameraOutputGeometry.effectiveDimensions(720, 1280, 270),
        )
    }

    @Test
    fun overlayBoundsRemainInsideLandscapeOutput() {
        val bounds = RecordingOverlayGeometry.timestampBounds(
            outputWidth = 1280f,
            outputHeight = 720f,
            textWidth = 280f,
            textHeight = 36f,
            margin = 24f,
            horizontalPadding = 12f,
            verticalPadding = 8f,
        )

        assertTrue(bounds.isInside(1280f, 720f))
    }

    @Test
    fun overlayBoundsRemainInsideRotatedPortraitOutput() {
        val output = CameraOutputGeometry.effectiveDimensions(1152, 720, 90)
        val bounds = RecordingOverlayGeometry.timestampBounds(
            outputWidth = output.width.toFloat(),
            outputHeight = output.height.toFloat(),
            textWidth = 280f,
            textHeight = 36f,
            margin = 18f,
            horizontalPadding = 12f,
            verticalPadding = 8f,
        )

        assertTrue(bounds.isInside(output.width.toFloat(), output.height.toFloat()))
    }
}
