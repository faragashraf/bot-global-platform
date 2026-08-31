package com.ashraffarag.sentricam.live.android

import androidx.camera.core.ImageProxy
import com.ashraffarag.sentricam.live.domain.CameraFrame
import java.nio.ByteBuffer

object Yuv420FrameConverter {
    fun convert(image: ImageProxy): CameraFrame? {
        if (image.planes.size < 3) return null
        val crop = image.cropRect
        val width = crop.width() and -2
        val height = crop.height() and -2
        if (width <= 0 || height <= 0) return null
        val y = copyPlane(image.planes[0], crop.left, crop.top, width, height) ?: return null
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaLeft = crop.left / 2
        val chromaTop = crop.top / 2
        val u = copyPlane(image.planes[1], chromaLeft, chromaTop, chromaWidth, chromaHeight) ?: return null
        val v = copyPlane(image.planes[2], chromaLeft, chromaTop, chromaWidth, chromaHeight) ?: return null
        return packedFrame(
            width,
            height,
            image.imageInfo.rotationDegrees,
            image.imageInfo.timestamp,
            y,
            u,
            v,
        )
    }

    internal fun packedFrame(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        timestampNanos: Long,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ) = CameraFrame(width, height, rotationDegrees, timestampNanos, y, u, v)

    internal fun copyPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): ByteArray? {
        if (rowStride <= 0 || pixelStride <= 0 || left < 0 || top < 0 || width <= 0 || height <= 0) return null
        val source = buffer.duplicate()
        val result = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val offset = (top + row) * rowStride + (left + column) * pixelStride
                if (offset !in 0 until source.limit()) return null
                result[row * width + column] = source.get(offset)
            }
        }
        return result
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) = copyPlane(plane.buffer, plane.rowStride, plane.pixelStride, left, top, width, height)
}
