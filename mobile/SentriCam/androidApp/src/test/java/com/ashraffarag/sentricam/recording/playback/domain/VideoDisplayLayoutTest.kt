package com.ashraffarag.sentricam.recording.playback.domain

import com.ashraffarag.sentricam.camera.domain.CameraOutputGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDisplayLayoutTest {
    @Test
    fun portraitVideoUsesUsefulHeightInPortraitEmbeddedMode() {
        val frame = VideoDisplayLayout.embeddedFrame(
            availableWidthPx = 1_080,
            viewportHeightPx = 1_920,
            videoWidthPx = 720,
            videoHeightPx = 1_280,
            density = 3f,
        )
        val displayedVideo = VideoDisplayLayout.fullScreenContent(
            frame.widthPx,
            frame.heightPx,
            720,
            1_280,
        )

        assertTrue(frame.heightPx >= 1_000)
        assertTrue(frame.heightPx <= (1_920 * 0.55f).toInt() + 1)
        assertEquals(1_080, frame.widthPx)
        assertEquals(720f / 1_280f, displayedVideo.aspectRatio, 0.01f)
    }

    @Test
    fun landscapeVideoUsesWidthWithoutDistortionInPortraitEmbeddedMode() {
        val frame = VideoDisplayLayout.embeddedFrame(
            availableWidthPx = 1_080,
            viewportHeightPx = 1_920,
            videoWidthPx = 1_280,
            videoHeightPx = 720,
            density = 3f,
        )

        assertEquals(1_080, frame.widthPx)
        assertEquals(1_280f / 720f, frame.aspectRatio, 0.01f)
        assertTrue(frame.heightPx <= (1_920 * 0.42f).toInt() + 1)
    }

    @Test
    fun embeddedFrameReactsToLandscapeScreenConstraints() {
        val portraitScreen = VideoDisplayLayout.embeddedFrame(1_080, 1_920, 720, 1_280, 3f)
        val landscapeScreen = VideoDisplayLayout.embeddedFrame(1_920, 1_080, 720, 1_280, 3f)
        val landscapeVideo = VideoDisplayLayout.fullScreenContent(
            landscapeScreen.widthPx,
            landscapeScreen.heightPx,
            720,
            1_280,
        )

        assertTrue(portraitScreen != landscapeScreen)
        assertTrue(landscapeScreen.heightPx <= (1_080 * 0.62f).toInt() + 1)
        assertEquals(720f / 1_280f, landscapeVideo.aspectRatio, 0.01f)
    }

    @Test
    fun rotationMetadataIsAppliedBeforeDisplayPolicy() {
        val rotation90 = CameraOutputGeometry.effectiveDimensions(1_280, 720, 90)
        val rotation270 = CameraOutputGeometry.effectiveDimensions(1_280, 720, 270)
        val frame = VideoDisplayLayout.embeddedFrame(
            1_080,
            1_920,
            rotation90.width,
            rotation90.height,
            3f,
        )
        val displayedVideo = VideoDisplayLayout.fullScreenContent(
            frame.widthPx,
            frame.heightPx,
            rotation90.width,
            rotation90.height,
        )

        assertTrue(rotation90.isPortrait)
        assertEquals(rotation90, rotation270)
        assertEquals(720f / 1_280f, displayedVideo.aspectRatio, 0.01f)
    }

    @Test
    fun fullScreenUsesIndependentUnboundedPolicy() {
        val embedded = VideoDisplayLayout.embeddedFrame(1_080, 1_920, 720, 1_280, 3f)
        val fullScreen = VideoDisplayLayout.fullScreenContent(1_080, 1_920, 720, 1_280)

        assertTrue(fullScreen.heightPx > embedded.heightPx)
        assertEquals(720f / 1_280f, fullScreen.aspectRatio, 0.01f)
    }

    @Test
    fun fittedPortraitContentPreservesAspectRatio() {
        val content = VideoDisplayLayout.fittedContent(1_000, 560, 720, 1_280)

        assertEquals(720f / 1_280f, content.aspectRatio, 0.01f)
        assertTrue(content.widthPx <= 1_000)
        assertTrue(content.heightPx <= 560)
    }

    @Test
    fun fittedLandscapeContentPreservesAspectRatio() {
        val content = VideoDisplayLayout.fittedContent(560, 1_000, 1_280, 720)

        assertEquals(1_280f / 720f, content.aspectRatio, 0.01f)
        assertTrue(content.widthPx <= 560)
        assertTrue(content.heightPx <= 1_000)
    }

    @Test
    fun invalidMetadataUsesSafeAspectRatioFallback() {
        assertEquals(16f / 9f, VideoDisplayLayout.safeAspectRatio(0, 0), 0f)
        assertEquals(16f / 9f, VideoDisplayLayout.safeAspectRatio(-1, 720), 0f)
    }

    @Test
    fun zeroSizedInputsNeverCauseInvalidFrame() {
        val frame = VideoDisplayLayout.fullScreenContent(0, 0, 0, 0)

        assertTrue(frame.widthPx > 0)
        assertTrue(frame.heightPx > 0)
        assertTrue(frame.aspectRatio.isFinite())
    }
}
