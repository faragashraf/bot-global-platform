package com.ashraffarag.sentricam.live.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.ashraffarag.sentricam.camera.domain.CameraOutputGeometry
import com.ashraffarag.sentricam.live.domain.CameraFrame
import com.ashraffarag.sentricam.recording.overlay.domain.RecordingOverlayGeometry
import com.ashraffarag.sentricam.recording.overlay.domain.RecordingOverlayTimestampSource
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration

/** Burns the shared overlay into the outgoing WebRTC I420 frame; correlation data never enters pixels. */
class I420DateTimeOverlayRenderer(
    private val timestampSource: RecordingOverlayTimestampSource = RecordingOverlayTimestampSource(),
) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OVERLAY_BACKGROUND }
    private var cached: CachedOverlay? = null

    fun apply(frame: CameraFrame, configuration: RecordingOverlayConfiguration) {
        if (!configuration.showDateTime) return
        val timestamp = timestampSource.current(configuration)
        val signature = Signature(
            frame.width,
            frame.height,
            frame.rotationDegrees,
            timestamp.epochSecond,
            timestamp.zoneId.id,
            configuration,
        )
        val overlay = cached?.takeIf { it.signature == signature }
            ?: render(signature, timestamp.text).also { cached = it }
        overlay.lumaIndices.forEachIndexed { index, target -> frame.y[target] = overlay.luma[index] }
        overlay.chromaIndices.forEach { target ->
            frame.u[target] = NEUTRAL_CHROMA
            frame.v[target] = NEUTRAL_CHROMA
        }
    }

    private fun render(signature: Signature, text: String): CachedOverlay {
        val output = CameraOutputGeometry.effectiveDimensions(
            signature.width,
            signature.height,
            signature.rotationDegrees,
        )
        val metrics = RecordingOverlayGeometry.renderMetrics(output.width, output.height)
        textPaint.textSize = metrics.textSize
        val font = textPaint.fontMetrics
        val bounds = RecordingOverlayGeometry.timestampBounds(
            output.width.toFloat(),
            output.height.toFloat(),
            textPaint.measureText(text),
            font.descent - font.ascent,
            metrics.margin,
            metrics.horizontalPadding,
            metrics.verticalPadding,
            signature.configuration.position,
        )
        val left = bounds.left.toInt().coerceAtLeast(0)
        val top = bounds.top.toInt().coerceAtLeast(0)
        val width = kotlin.math.ceil(bounds.right - bounds.left).toInt().coerceAtLeast(1)
        val height = kotlin.math.ceil(bounds.bottom - bounds.top).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            metrics.cornerRadius,
            metrics.cornerRadius,
            backgroundPaint,
        )
        canvas.drawText(text, metrics.horizontalPadding, metrics.verticalPadding - font.ascent, textPaint)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val lumaIndices = ArrayList<Int>(pixels.size)
        val luma = ArrayList<Byte>(pixels.size)
        val chroma = LinkedHashSet<Int>()
        pixels.forEachIndexed { index, color ->
            if (Color.alpha(color) == 0) return@forEachIndexed
            val displayX = left + (index % width)
            val displayY = top + (index / width)
            val (rawX, rawY) = rawCoordinates(
                displayX,
                displayY,
                signature.width,
                signature.height,
                signature.rotationDegrees,
            )
            if (rawX !in 0 until signature.width || rawY !in 0 until signature.height) return@forEachIndexed
            lumaIndices += rawY * signature.width + rawX
            val brightness = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
            luma += if (brightness >= TEXT_BRIGHTNESS_THRESHOLD) TEXT_LUMA else BACKGROUND_LUMA
            chroma += (rawY / 2) * (signature.width / 2) + (rawX / 2)
        }
        return CachedOverlay(
            signature,
            lumaIndices.toIntArray(),
            ByteArray(luma.size) { luma[it] },
            chroma.toIntArray(),
        )
    }

    private fun rawCoordinates(
        displayX: Int,
        displayY: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
    ): Pair<Int, Int> = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> displayY to rawHeight - 1 - displayX
        180 -> rawWidth - 1 - displayX to rawHeight - 1 - displayY
        270 -> rawWidth - 1 - displayY to displayX
        else -> displayX to displayY
    }

    private data class Signature(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val epochSecond: Long,
        val zoneId: String,
        val configuration: RecordingOverlayConfiguration,
    )

    private data class CachedOverlay(
        val signature: Signature,
        val lumaIndices: IntArray,
        val luma: ByteArray,
        val chromaIndices: IntArray,
    )

    private companion object {
        const val OVERLAY_BACKGROUND = 0x99000000.toInt()
        const val TEXT_BRIGHTNESS_THRESHOLD = 160
        val TEXT_LUMA = 235.toByte()
        val BACKGROUND_LUMA = 32.toByte()
        val NEUTRAL_CHROMA = 128.toByte()
    }
}
