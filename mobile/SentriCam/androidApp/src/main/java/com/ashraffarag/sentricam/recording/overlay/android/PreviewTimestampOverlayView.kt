package com.ashraffarag.sentricam.recording.overlay.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.ashraffarag.sentricam.recording.overlay.domain.RecordingOverlayGeometry
import com.ashraffarag.sentricam.recording.overlay.domain.RecordingOverlayTimestampSource
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration

/** Drawn above PreviewView, so front-camera image mirroring never transforms overlay text. */
class PreviewTimestampOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OVERLAY_BACKGROUND
    }
    private var timestampVisible = false
    private var formattedSecond = Long.MIN_VALUE
    private var formattedZoneId = ""
    private var formattedTimestamp = ""
    private var configuration = RecordingOverlayConfiguration()
    private var formattedConfiguration: RecordingOverlayConfiguration? = null
    private val timestampSource = RecordingOverlayTimestampSource()
    private val nextFrame = Runnable {
        invalidate()
        scheduleNextFrame()
    }

    fun setTimestampVisible(visible: Boolean) {
        if (timestampVisible == visible) return
        timestampVisible = visible
        visibility = if (visible) VISIBLE else GONE
        if (visible && isAttachedToWindow) scheduleNextFrame() else removeCallbacks(nextFrame)
        invalidate()
    }

    fun setConfiguration(configuration: RecordingOverlayConfiguration) {
        this.configuration = configuration
        setTimestampVisible(configuration.showDateTime)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (timestampVisible) scheduleNextFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(nextFrame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!timestampVisible || width <= 0 || height <= 0) return

        val timestamp = currentTimestamp()
        val renderMetrics = RecordingOverlayGeometry.renderMetrics(width, height)
        textPaint.textSize = renderMetrics.textSize
        val metrics = textPaint.fontMetrics
        val bounds = RecordingOverlayGeometry.timestampBounds(
            outputWidth = width.toFloat(),
            outputHeight = height.toFloat(),
            textWidth = textPaint.measureText(timestamp),
            textHeight = metrics.descent - metrics.ascent,
            margin = renderMetrics.margin,
            horizontalPadding = renderMetrics.horizontalPadding,
            verticalPadding = renderMetrics.verticalPadding,
            position = configuration.position,
        )
        canvas.drawRoundRect(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            renderMetrics.cornerRadius,
            renderMetrics.cornerRadius,
            backgroundPaint,
        )
        canvas.drawText(
            timestamp,
            bounds.left + renderMetrics.horizontalPadding,
            bounds.top + renderMetrics.verticalPadding - metrics.ascent,
            textPaint,
        )
    }

    private fun currentTimestamp(): String {
        val current = timestampSource.current(configuration)
        val zoneId = current.zoneId.id
        if (current.epochSecond != formattedSecond || zoneId != formattedZoneId || configuration != formattedConfiguration) {
            formattedSecond = current.epochSecond
            formattedZoneId = zoneId
            formattedTimestamp = current.text
            formattedConfiguration = configuration
        }
        return formattedTimestamp
    }

    private fun scheduleNextFrame() {
        removeCallbacks(nextFrame)
        if (!timestampVisible || !isAttachedToWindow) return
        val nowMillis = System.currentTimeMillis()
        postDelayed(nextFrame, MILLIS_PER_SECOND - (nowMillis % MILLIS_PER_SECOND))
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val OVERLAY_BACKGROUND = 0x99000000.toInt()
    }
}
