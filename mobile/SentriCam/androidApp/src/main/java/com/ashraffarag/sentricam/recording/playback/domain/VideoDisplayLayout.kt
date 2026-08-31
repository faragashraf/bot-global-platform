package com.ashraffarag.sentricam.recording.playback.domain

import kotlin.math.min
import kotlin.math.roundToInt

data class VideoFrameSize(
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(widthPx > 0)
        require(heightPx > 0)
    }

    val aspectRatio: Float
        get() = widthPx.toFloat() / heightPx.toFloat()
}

object VideoDisplayLayout {
    private const val FALLBACK_ASPECT_RATIO = 16f / 9f
    private const val PORTRAIT_VIDEO_MAX_VIEWPORT_FRACTION = 0.55f
    private const val LANDSCAPE_VIDEO_MAX_VIEWPORT_FRACTION = 0.42f
    private const val LANDSCAPE_SCREEN_MAX_VIEWPORT_FRACTION = 0.62f
    private const val PORTRAIT_VIDEO_MAX_HEIGHT_DP = 480f
    private const val LANDSCAPE_VIDEO_MAX_HEIGHT_DP = 360f
    private const val MIN_CONTROL_FRAME_WIDTH_DP = 280f

    fun embeddedFrame(
        availableWidthPx: Int,
        viewportHeightPx: Int,
        videoWidthPx: Int,
        videoHeightPx: Int,
        density: Float,
    ): VideoFrameSize {
        val width = availableWidthPx.coerceAtLeast(1)
        val viewportHeight = viewportHeightPx.coerceAtLeast(1)
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val videoRatio = safeAspectRatio(videoWidthPx, videoHeightPx)
        val portraitVideo = videoRatio < 1f
        val portraitScreen = viewportHeight > width
        val viewportFraction = when {
            !portraitScreen -> LANDSCAPE_SCREEN_MAX_VIEWPORT_FRACTION
            portraitVideo -> PORTRAIT_VIDEO_MAX_VIEWPORT_FRACTION
            else -> LANDSCAPE_VIDEO_MAX_VIEWPORT_FRACTION
        }
        val maximumHeightDp = if (portraitVideo) {
            PORTRAIT_VIDEO_MAX_HEIGHT_DP
        } else {
            LANDSCAPE_VIDEO_MAX_HEIGHT_DP
        }
        val maximumHeight = min(
            (maximumHeightDp * safeDensity).roundToInt(),
            (viewportHeight * viewportFraction).roundToInt(),
        ).coerceAtLeast(1)

        val fittedVideo = fittedContent(
            frameWidthPx = width,
            frameHeightPx = maximumHeight,
            videoWidthPx = videoWidthPx,
            videoHeightPx = videoHeightPx,
        )
        if (!portraitVideo) return fittedVideo

        val minimumControlWidth = min(
            width,
            (MIN_CONTROL_FRAME_WIDTH_DP * safeDensity).roundToInt().coerceAtLeast(1),
        )
        return VideoFrameSize(
            widthPx = if (portraitScreen) width else maxOf(fittedVideo.widthPx, minimumControlWidth),
            heightPx = fittedVideo.heightPx,
        )
    }

    fun fullScreenContent(
        availableWidthPx: Int,
        availableHeightPx: Int,
        videoWidthPx: Int,
        videoHeightPx: Int,
    ): VideoFrameSize = fittedContent(
        frameWidthPx = availableWidthPx,
        frameHeightPx = availableHeightPx,
        videoWidthPx = videoWidthPx,
        videoHeightPx = videoHeightPx,
    )

    fun fittedContent(
        frameWidthPx: Int,
        frameHeightPx: Int,
        videoWidthPx: Int,
        videoHeightPx: Int,
    ): VideoFrameSize {
        val frameWidth = frameWidthPx.coerceAtLeast(1)
        val frameHeight = frameHeightPx.coerceAtLeast(1)
        val videoRatio = safeAspectRatio(videoWidthPx, videoHeightPx)
        val frameRatio = frameWidth.toFloat() / frameHeight.toFloat()

        return if (videoRatio > frameRatio) {
            VideoFrameSize(frameWidth, (frameWidth / videoRatio).roundToInt().coerceAtLeast(1))
        } else {
            VideoFrameSize((frameHeight * videoRatio).roundToInt().coerceAtLeast(1), frameHeight)
        }
    }

    fun safeAspectRatio(widthPx: Int, heightPx: Int): Float {
        if (widthPx <= 0 || heightPx <= 0) return FALLBACK_ASPECT_RATIO
        val ratio = widthPx.toFloat() / heightPx.toFloat()
        return ratio.takeIf { it.isFinite() && it > 0f } ?: FALLBACK_ASPECT_RATIO
    }
}
