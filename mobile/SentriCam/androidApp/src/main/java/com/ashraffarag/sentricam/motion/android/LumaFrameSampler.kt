package com.ashraffarag.sentricam.motion.android

import java.nio.ByteBuffer

class LumaFrameSampler(
    private val targetWidth: Int = DEFAULT_SAMPLE_WIDTH,
    private val targetHeight: Int = DEFAULT_SAMPLE_HEIGHT,
) {
    fun sample(
        buffer: ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rowStride: Int,
        pixelStride: Int,
    ): SampledLuma? {
        if (imageWidth <= 0 || imageHeight <= 0 || cropWidth <= 0 || cropHeight <= 0 ||
            rowStride <= 0 || pixelStride <= 0 || targetWidth <= 0 || targetHeight <= 0
        ) return null
        val safeLeft = cropLeft.coerceIn(0, imageWidth - 1)
        val safeTop = cropTop.coerceIn(0, imageHeight - 1)
        val safeRight = (cropLeft + cropWidth).coerceIn(safeLeft + 1, imageWidth)
        val safeBottom = (cropTop + cropHeight).coerceIn(safeTop + 1, imageHeight)
        val sampled = ByteArray(targetWidth * targetHeight)
        val duplicate = buffer.duplicate()
        for (sampleY in 0 until targetHeight) {
            val sourceY = safeTop + ((sampleY + 0.5) * (safeBottom - safeTop) / targetHeight)
                .toInt()
                .coerceAtMost(safeBottom - 1)
            for (sampleX in 0 until targetWidth) {
                val sourceX = safeLeft + ((sampleX + 0.5) * (safeRight - safeLeft) / targetWidth)
                    .toInt()
                    .coerceAtMost(safeRight - 1)
                val offset = sourceY * rowStride + sourceX * pixelStride
                if (offset !in 0 until duplicate.limit()) return null
                sampled[sampleY * targetWidth + sampleX] = duplicate.get(offset)
            }
        }
        return SampledLuma(sampled, targetWidth, targetHeight)
    }

    data class SampledLuma(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val DEFAULT_SAMPLE_WIDTH = 32
        const val DEFAULT_SAMPLE_HEIGHT = 24
    }
}

class CloseableFrameProcessor<T : AutoCloseable> {
    inline fun process(frame: T, block: (T) -> Unit) {
        try {
            block(frame)
        } finally {
            frame.close()
        }
    }
}
