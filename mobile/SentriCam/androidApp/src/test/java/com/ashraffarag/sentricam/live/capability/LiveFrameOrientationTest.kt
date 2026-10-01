package com.ashraffarag.sentricam.live.capability

import com.ashraffarag.sentricam.camera.domain.CameraLensFacing
import com.ashraffarag.sentricam.camera.domain.PixelDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.webrtc.VideoFrame

class LiveFrameOrientationTest {
    @Test
    fun zeroDegreeFrameKeepsEncodedDimensions() {
        assertOrientation(0, PixelDimensions(1280, 720))
    }

    @Test
    fun ninetyDegreeFrameSwapsEffectiveDimensions() {
        assertOrientation(90, PixelDimensions(720, 1280))
    }

    @Test
    fun oneHundredEightyDegreeFrameKeepsEncodedDimensions() {
        assertOrientation(180, PixelDimensions(1280, 720))
    }

    @Test
    fun twoHundredSeventyDegreeFrameSwapsEffectiveDimensions() {
        assertOrientation(270, PixelDimensions(720, 1280))
    }

    @Test
    fun backCameraPortraitUsesCameraXRotationWithoutMirroring() {
        val orientation = LiveFrameOrientationResolver.resolve(1280, 720, 90)

        assertEquals(PixelDimensions(720, 1280), orientation.displayDimensions)
        assertFalse(LiveRemoteMirrorPolicy.isMirrored(CameraLensFacing.REAR))
    }

    @Test
    fun backCameraLandscapeRightStaysUpright() {
        assertOrientation(0, PixelDimensions(1280, 720))
    }

    @Test
    fun backCameraLandscapeLeftStaysUpright() {
        assertOrientation(180, PixelDimensions(1280, 720))
    }

    @Test
    fun frontCameraPortraitIsUprightAndRemoteViewIsNotMirrored() {
        val orientation = LiveFrameOrientationResolver.resolve(1280, 720, 270)

        assertEquals(PixelDimensions(720, 1280), orientation.displayDimensions)
        assertFalse(LiveRemoteMirrorPolicy.isMirrored(CameraLensFacing.FRONT))
    }

    @Test
    fun frontCameraLandscapeIsUprightAndRemoteViewIsNotMirrored() {
        val orientation = LiveFrameOrientationResolver.resolve(1280, 720, 0)

        assertEquals(PixelDimensions(1280, 720), orientation.displayDimensions)
        assertFalse(LiveRemoteMirrorPolicy.isMirrored(CameraLensFacing.FRONT))
    }

    @Test
    fun resolvedRotationIsPreservedByWebRtcVideoFrame() {
        val orientation = LiveFrameOrientationResolver.resolve(1280, 720, 90)
        val frame = VideoFrame(TestBuffer(1280, 720), orientation.rotationDegrees, 1L)

        assertEquals(90, frame.rotation)
        assertEquals(720, frame.rotatedWidth)
        assertEquals(1280, frame.rotatedHeight)
    }

    @Test
    fun encodedDimensionsRemainUnrotatedSoMetadataIsNotAppliedTwice() {
        val orientation = LiveFrameOrientationResolver.resolve(1280, 720, 270)

        assertEquals(PixelDimensions(1280, 720), orientation.encodedDimensions)
        assertEquals(PixelDimensions(720, 1280), orientation.displayDimensions)
    }

    private fun assertOrientation(rotation: Int, expectedDisplay: PixelDimensions) {
        val orientation = LiveFrameOrientationResolver.resolve(1280, 720, rotation)

        assertEquals(rotation, orientation.rotationDegrees)
        assertEquals(PixelDimensions(1280, 720), orientation.encodedDimensions)
        assertEquals(expectedDisplay, orientation.displayDimensions)
    }

    private class TestBuffer(
        private val frameWidth: Int,
        private val frameHeight: Int,
    ) : VideoFrame.Buffer {
        override fun getWidth() = frameWidth
        override fun getHeight() = frameHeight
        override fun toI420(): VideoFrame.I420Buffer = throw UnsupportedOperationException()
        override fun retain() = Unit
        override fun release() = Unit
        override fun cropAndScale(
            cropX: Int,
            cropY: Int,
            cropWidth: Int,
            cropHeight: Int,
            scaleWidth: Int,
            scaleHeight: Int,
        ): VideoFrame.Buffer = this
    }
}
