package com.ashraffarag.sentricam.live.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachableCameraStreamControllerTest {
    @Test
    fun plannedCameraRebindResumesExistingWebRtcConsumerWithoutTrackRestart() {
        val wrapper = AttachableCameraStreamController()
        val first = FakeStream()
        val replacement = FakeStream()
        val consumer = CameraFrameConsumer { }
        wrapper.attach(first)
        assertTrue(wrapper.start(consumer))

        wrapper.preserveConsumerForReplacement()
        wrapper.detach()
        wrapper.attach(replacement)

        assertEquals(1, first.stopCalls)
        assertEquals(1, replacement.startCalls)
        assertTrue(wrapper.isActive())
    }

    @Test
    fun ordinaryDetachEndsStreamAndDoesNotRestartConsumerOnNextCamera() {
        val wrapper = AttachableCameraStreamController()
        val first = FakeStream()
        val replacement = FakeStream()
        wrapper.attach(first)
        assertTrue(wrapper.start(CameraFrameConsumer { }))

        wrapper.detach()
        wrapper.attach(replacement)

        assertFalse(wrapper.isActive())
        assertEquals(0, replacement.startCalls)
    }

    private class FakeStream : CameraStreamController {
        var startCalls = 0
        var stopCalls = 0
        override fun start(consumer: CameraFrameConsumer): Boolean { startCalls++; return true }
        override fun stop() { stopCalls++ }
    }
}
