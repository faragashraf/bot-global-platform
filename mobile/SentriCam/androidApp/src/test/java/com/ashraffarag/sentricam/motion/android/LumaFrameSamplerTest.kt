package com.ashraffarag.sentricam.motion.android

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaFrameSamplerTest {
    @Test
    fun samplesSmallGridWithoutFullFrameAllocation() {
        val sampler = LumaFrameSampler(2, 2)
        val sampled = sampler.sample(ByteBuffer.wrap(ByteArray(16) { it.toByte() }), 4, 4, 0, 0, 4, 4, 4, 1)
        assertNotNull(sampled)
        assertArrayEquals(byteArrayOf(5, 7, 13, 15), sampled!!.bytes)
    }

    @Test
    fun invalidPlaneGeometryReturnsNull() {
        val sampled = LumaFrameSampler().sample(ByteBuffer.allocate(1), 0, 0, 0, 0, 0, 0, 0, 0)
        assertNull(sampled)
    }

    @Test
    fun frameIsClosedOnSuccessAndFailure() {
        val processor = CloseableFrameProcessor<FakeCloseable>()
        val success = FakeCloseable()
        processor.process(success) {}
        assertTrue(success.closed)
        val failure = FakeCloseable()
        try {
            processor.process(failure) { error("failure") }
        } catch (_: IllegalStateException) {
            // Expected.
        }
        assertTrue(failure.closed)
        assertFalse(success === failure)
    }

    private class FakeCloseable : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }
}
