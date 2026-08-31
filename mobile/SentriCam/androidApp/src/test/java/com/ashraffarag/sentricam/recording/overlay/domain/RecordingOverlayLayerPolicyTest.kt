package com.ashraffarag.sentricam.recording.overlay.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingOverlayLayerPolicyTest {
    @Test
    fun frontPreviewMirrorsCameraImageButNeverOverlayLayer() {
        val policy = RecordingOverlayLayerPolicy.resolve(
            frontCamera = true,
            target = OverlayOutputTarget.PREVIEW,
        )

        assertTrue(policy.cameraImageMirrored)
        assertFalse(policy.overlayMirrored)
    }

    @Test
    fun frontRecordingLeavesTimestampAndEncodedImageUnmirrored() {
        val policy = RecordingOverlayLayerPolicy.resolve(
            frontCamera = true,
            target = OverlayOutputTarget.RECORDED_VIDEO,
        )

        assertFalse(policy.cameraImageMirrored)
        assertFalse(policy.overlayMirrored)
    }

    @Test
    fun rearCameraNeverAddsMirrorTransformation() {
        OverlayOutputTarget.entries.forEach { target ->
            val policy = RecordingOverlayLayerPolicy.resolve(false, target)
            assertFalse(policy.cameraImageMirrored)
            assertFalse(policy.overlayMirrored)
        }
    }

    @Test
    fun rotationTransformContainsNoCameraMirrorStep() {
        assertArrayEquals(
            floatArrayOf(720f, 0f, 720f, 1_280f, 0f, 1_280f, 0f, 0f),
            RecordingOverlayGeometry.uprightOutputCorners(720f, 1_280f, 90),
            0f,
        )
    }
}
