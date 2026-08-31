package com.ashraffarag.sentricam.live.android

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class Yuv420FrameConverterTest {
    @Test
    fun copiesStridedPlaneIntoPackedOutput() {
        val buffer = ByteBuffer.wrap(
            byteArrayOf(
                1, 99, 2, 99, 3, 99, 0, 0,
                4, 99, 5, 99, 6, 99, 0, 0,
            ),
        )

        val result = Yuv420FrameConverter.copyPlane(
            buffer,
            rowStride = 8,
            pixelStride = 2,
            left = 1,
            top = 0,
            width = 2,
            height = 2,
        )

        assertArrayEquals(byteArrayOf(2, 3, 5, 6), result)
    }

    @Test
    fun rejectsPlaneWhoseStrideWouldReadPastBuffer() {
        val result = Yuv420FrameConverter.copyPlane(
            ByteBuffer.wrap(byteArrayOf(1, 2)),
            rowStride = 4,
            pixelStride = 1,
            left = 0,
            top = 0,
            width = 2,
            height = 2,
        )

        assertNull(result)
    }

    @Test
    fun packedFramePreservesCameraXRotationWithoutRotatingPixels() {
        val y = byteArrayOf(1, 2, 3, 4)
        val u = byteArrayOf(5)
        val v = byteArrayOf(6)

        val frame = Yuv420FrameConverter.packedFrame(2, 2, 270, 42L, y, u, v)

        assertEquals(270, frame.rotationDegrees)
        assertSame(y, frame.y)
        assertSame(u, frame.u)
        assertSame(v, frame.v)
    }
}
