package com.ashraffarag.sentricam.recording.overlay.android

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.core.graphics.withMatrix
import com.ashraffarag.sentricam.camera.domain.CameraOutputGeometry
import com.ashraffarag.sentricam.camera.domain.PixelDimensions
import com.ashraffarag.sentricam.recording.overlay.domain.RecordingOverlayEngine
import com.ashraffarag.sentricam.recording.overlay.domain.RecordingOverlayGeometry
import com.ashraffarag.sentricam.recording.overlay.domain.RecordingOverlayTimestampSource
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration
import java.util.concurrent.atomic.AtomicBoolean

/** Android adapter that burns the domain overlay into CameraX preview and video frames. */
class CameraXRecordingOverlayEngine(
    initialConfiguration: RecordingOverlayConfiguration,
    private val timestampSource: RecordingOverlayTimestampSource = RecordingOverlayTimestampSource(),
) : RecordingOverlayEngine {
    private val closed = AtomicBoolean(false)
    private val effectThread = HandlerThread(THREAD_NAME).apply { start() }
    private val effectHandler = Handler(effectThread.looper)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OVERLAY_BACKGROUND
    }
    private val outputToBuffer = Matrix()

    @Volatile
    private var configuration = initialConfiguration
    private var formattedSecond = Long.MIN_VALUE
    private var formattedZoneId = ""
    private var formattedTimestamp = ""
    private var formattedConfiguration: RecordingOverlayConfiguration? = null

    val cameraEffect: OverlayEffect = OverlayEffect(
        CameraEffect.VIDEO_CAPTURE,
        QUEUE_DEPTH,
        effectHandler,
    ) { failure ->
        Log.e(TAG, "Camera overlay processing failed", failure)
    }.also { effect ->
        effect.setOnDrawListener(::drawOverlay)
    }

    override fun update(configuration: RecordingOverlayConfiguration) {
        this.configuration = configuration
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cameraEffect.apply {
            clearOnDrawListener()
            close()
        }
        // OverlayEffect releases on the supplied handler, so quit only after that work is queued.
        effectHandler.post { effectThread.quitSafely() }
    }

    private fun drawOverlay(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (!configuration.showDateTime || closed.get()) return true

        val timestamp = currentTimestamp()
        val outputSize = configureOutputToBufferTransform(frame)
        val renderMetrics = RecordingOverlayGeometry.renderMetrics(outputSize.width, outputSize.height)

        textPaint.textSize = renderMetrics.textSize
        val metrics = textPaint.fontMetrics
        val textWidth = textPaint.measureText(timestamp)
        val bounds = RecordingOverlayGeometry.timestampBounds(
            outputWidth = outputSize.width.toFloat(),
            outputHeight = outputSize.height.toFloat(),
            textWidth = textWidth,
            textHeight = metrics.descent - metrics.ascent,
            margin = renderMetrics.margin,
            horizontalPadding = renderMetrics.horizontalPadding,
            verticalPadding = renderMetrics.verticalPadding,
            position = configuration.position,
        )
        val baseline = bounds.top + renderMetrics.verticalPadding - metrics.ascent

        canvas.withMatrix(outputToBuffer) {
            drawRoundRect(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                renderMetrics.cornerRadius,
                renderMetrics.cornerRadius,
                backgroundPaint,
            )
            drawText(timestamp, bounds.left + renderMetrics.horizontalPadding, baseline, textPaint)
        }
        return true
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

    /** Maps upright post-crop/rotation coordinates into the unmirrored recording buffer. */
    private fun configureOutputToBufferTransform(frame: Frame): PixelDimensions {
        val crop = frame.cropRect
        val cropWidth = crop.width().toFloat()
        val cropHeight = crop.height().toFloat()
        val outputSize = CameraOutputGeometry.effectiveDimensions(
            crop.width(),
            crop.height(),
            frame.rotationDegrees,
        )
        val outputWidth = outputSize.width.toFloat()
        val outputHeight = outputSize.height.toFloat()

        val bufferCorners = floatArrayOf(
            crop.left.toFloat(), crop.top.toFloat(),
            crop.right.toFloat(), crop.top.toFloat(),
            crop.right.toFloat(), crop.bottom.toFloat(),
            crop.left.toFloat(), crop.bottom.toFloat(),
        )
        val outputCorners = RecordingOverlayGeometry.uprightOutputCorners(
            outputWidth,
            outputHeight,
            frame.rotationDegrees,
        )
        check(outputToBuffer.setPolyToPoly(outputCorners, 0, bufferCorners, 0, CORNER_COUNT)) {
            "Unable to calculate overlay transform"
        }
        return outputSize
    }

    private companion object {
        const val TAG = "RecordingOverlay"
        const val THREAD_NAME = "SentriCamOverlay"
        const val QUEUE_DEPTH = 0
        const val CORNER_COUNT = 4
        const val OVERLAY_BACKGROUND = 0x99000000.toInt()
    }
}
