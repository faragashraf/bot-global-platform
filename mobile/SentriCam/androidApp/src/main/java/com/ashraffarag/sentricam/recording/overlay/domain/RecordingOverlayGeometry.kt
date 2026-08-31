package com.ashraffarag.sentricam.recording.overlay.domain

import com.ashraffarag.sentricam.camera.domain.OutputRotation
import kotlin.math.max
import kotlin.math.min
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayPositions

data class OverlayBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun isInside(width: Float, height: Float): Boolean =
        left >= 0f && top >= 0f && right <= width && bottom <= height &&
            right > left && bottom > top
}

data class OverlayRenderMetrics(
    val textSize: Float,
    val margin: Float,
    val horizontalPadding: Float,
    val verticalPadding: Float,
    val cornerRadius: Float,
)

object RecordingOverlayGeometry {
    fun renderMetrics(outputWidth: Int, outputHeight: Int): OverlayRenderMetrics {
        val shortEdge = min(outputWidth, outputHeight).coerceAtLeast(1).toFloat()
        val textSize = (shortEdge * TEXT_SIZE_FRACTION).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        return OverlayRenderMetrics(
            textSize = textSize,
            margin = max(MIN_MARGIN, shortEdge * MARGIN_FRACTION),
            horizontalPadding = textSize * HORIZONTAL_PADDING_FACTOR,
            verticalPadding = textSize * VERTICAL_PADDING_FACTOR,
            cornerRadius = textSize * CORNER_RADIUS_FACTOR,
        )
    }

    fun uprightOutputCorners(
        outputWidth: Float,
        outputHeight: Float,
        rotationDegrees: Int,
    ): FloatArray = when (OutputRotation.fromDegrees(rotationDegrees)) {
        OutputRotation.ROTATION_90 -> floatArrayOf(
            outputWidth, 0f,
            outputWidth, outputHeight,
            0f, outputHeight,
            0f, 0f,
        )

        OutputRotation.ROTATION_180 -> floatArrayOf(
            outputWidth, outputHeight,
            0f, outputHeight,
            0f, 0f,
            outputWidth, 0f,
        )

        OutputRotation.ROTATION_270 -> floatArrayOf(
            0f, outputHeight,
            0f, 0f,
            outputWidth, 0f,
            outputWidth, outputHeight,
        )

        OutputRotation.ROTATION_0 -> floatArrayOf(
            0f, 0f,
            outputWidth, 0f,
            outputWidth, outputHeight,
            0f, outputHeight,
        )
    }

    fun timestampBounds(
        outputWidth: Float,
        outputHeight: Float,
        textWidth: Float,
        textHeight: Float,
        margin: Float,
        horizontalPadding: Float,
        verticalPadding: Float,
        position: String = RecordingOverlayPositions.BOTTOM_LEFT,
    ): OverlayBounds {
        val safeMargin = max(0f, margin)
        val boxWidth = textWidth + (horizontalPadding * 2f)
        val boxHeight = textHeight + (verticalPadding * 2f)
        val rightAligned = position == RecordingOverlayPositions.TOP_RIGHT ||
            position == RecordingOverlayPositions.BOTTOM_RIGHT
        val topAligned = position == RecordingOverlayPositions.TOP_LEFT ||
            position == RecordingOverlayPositions.TOP_RIGHT
        val left = if (rightAligned) max(0f, outputWidth - safeMargin - boxWidth) else min(safeMargin, outputWidth)
        val top = if (topAligned) min(safeMargin, outputHeight) else max(0f, outputHeight - safeMargin - boxHeight)
        return OverlayBounds(
            left = left,
            top = top,
            right = min(outputWidth, left + boxWidth),
            bottom = min(outputHeight, top + boxHeight),
        )
    }

    private const val TEXT_SIZE_FRACTION = 0.035f
    private const val MARGIN_FRACTION = 0.025f
    private const val MIN_TEXT_SIZE = 24f
    private const val MAX_TEXT_SIZE = 52f
    private const val MIN_MARGIN = 16f
    private const val HORIZONTAL_PADDING_FACTOR = 0.45f
    private const val VERTICAL_PADDING_FACTOR = 0.22f
    private const val CORNER_RADIUS_FACTOR = 0.22f
}
