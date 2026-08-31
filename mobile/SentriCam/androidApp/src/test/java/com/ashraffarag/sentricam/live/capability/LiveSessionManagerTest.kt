package com.ashraffarag.sentricam.live.capability

import com.ashraffarag.sentricam.live.domain.CameraFrame
import com.ashraffarag.sentricam.live.domain.LiveDeviceCapabilities
import com.ashraffarag.sentricam.live.domain.LiveIceCandidate
import com.ashraffarag.sentricam.live.domain.LiveSessionAssignment
import com.ashraffarag.sentricam.live.domain.LiveSessionDescription
import com.ashraffarag.sentricam.live.domain.LiveSessionState
import com.ashraffarag.sentricam.live.domain.LiveSessionStatistics
import com.ashraffarag.sentricam.live.domain.LiveSessionStatusUpdate
import com.ashraffarag.sentricam.live.domain.LiveSessionView
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val camera = FakeCameraStreamController()
    private val preview = FakePreviewController()
    private val publisher = FakePublisher()
    private val signaling = FakeSignaling()
    private val manager = LiveSessionManager(
        camera,
        preview,
        publisher,
        signaling,
        { deviceId -> LiveDeviceCapabilities(deviceId, emptyList(), true, Instant.EPOCH.toString()) },
        scope,
        NoOpLiveCameraLease,
    )

    @After
    fun release() {
        manager.close()
        scope.cancel()
    }

    @Test
    fun beginStartsCameraAndReportsConnecting() {
        manager.begin(assignment())

        assertTrue(camera.started)
        assertEquals("session-1", publisher.sessionId)
        assertEquals(LiveSessionState.Connecting("session-1"), manager.state.value)
        assertEquals("connecting", signaling.states.single().state)
    }

    @Test
    fun offerAnswerAndIceUseSignalingWithoutVideoPayloads() {
        manager.begin(assignment())
        manager.offer(LiveSessionDescription("session-1", "offer", "v=0 browser"))
        manager.candidate(LiveIceCandidate("session-1", "candidate:browser", "0", 0))
        publisher.emitCandidate(LiveIceCandidate("session-1", "candidate:android", "0", 0))

        assertEquals("answer", signaling.answers.single().type)
        assertEquals("candidate:browser", publisher.remoteCandidates.single().candidate)
        assertEquals("candidate:android", signaling.candidates.single().candidate)
    }

    @Test
    fun hiddenPreviewDoesNotStopCameraStream() {
        manager.begin(assignment())
        manager.preview(false)

        assertFalse(preview.visible.value)
        assertTrue(camera.started)
        assertEquals("session-1", publisher.sessionId)
    }

    @Test
    fun hiddenThenVisibleRestoresPreviewWithoutRestartingSession() {
        manager.begin(assignment())

        manager.preview(false)
        manager.preview(true)

        assertTrue(preview.visible.value)
        assertTrue(camera.started)
        assertEquals(1, publisher.startCalls)
        assertEquals(0, publisher.stopCalls)
        assertEquals("session-1", publisher.sessionId)
    }

    @Test
    fun connectionLossReleasesPublisherCameraAndPreview() {
        manager.begin(assignment(previewVisible = false))

        manager.connectionLost()

        assertEquals(LiveSessionState.Idle, manager.state.value)
        assertFalse(camera.started)
        assertTrue(preview.visible.value)
        assertEquals(1, publisher.stopCalls)
    }

    @Test
    fun replacementAssignmentCleansOldPublisherAndIgnoresStaleSignals() {
        manager.begin(assignment(sessionId = "session-1"))
        manager.begin(assignment(sessionId = "session-2"))
        manager.offer(LiveSessionDescription("session-1", "offer", "v=0 stale"))
        manager.candidate(LiveIceCandidate("session-1", "candidate:stale", "0", 0))

        assertEquals(2, publisher.startCalls)
        assertEquals(1, publisher.stopCalls)
        assertEquals("session-2", publisher.sessionId)
        assertTrue(signaling.answers.isEmpty())
        assertTrue(publisher.remoteCandidates.isEmpty())
    }

    @Test
    fun explicitStopIntentPreventsOldSessionFromRestartingPublisher() {
        manager.begin(assignment())

        manager.end(LiveSessionStatusUpdate("session-1", "closed"))
        manager.offer(LiveSessionDescription("session-1", "offer", "v=0 stale"))

        assertEquals(LiveSessionState.Idle, manager.state.value)
        assertFalse(camera.started)
        assertEquals(1, publisher.startCalls)
        assertEquals(1, publisher.stopCalls)
        assertTrue(signaling.answers.isEmpty())
    }

    @Test
    fun repeatedSessionCyclesReleaseEveryPublisherLease() {
        repeat(5) { index ->
            val sessionId = "session-${index + 1}"
            manager.begin(assignment(sessionId = sessionId))
            manager.end(LiveSessionStatusUpdate(sessionId, "closed"))
        }

        assertEquals(5, publisher.startCalls)
        assertEquals(5, publisher.stopCalls)
        assertFalse(camera.started)
        assertEquals(LiveSessionState.Idle, manager.state.value)
    }

    @Test
    fun motionDetachCannotCancelLiveStateOwnershipOrEventPublishing() {
        val source = java.io.File(
            "src/main/java/com/ashraffarag/sentricam/device/android/DeviceRuntime.kt",
        ).readText()
        val detachMotion = source.substringAfter("fun detachMotionState()")
            .substringBefore("\n    }")

        assertFalse(detachMotion.contains("liveSessionStateJob"))
        assertTrue(source.contains("state.collectLatest {"))
        assertTrue(source.contains("report.telemetry.streaming,"))
    }

    @Test
    fun unsupportedQualityFailsWithoutOpeningCamera() {
        manager.begin(assignment(quality = "high"))

        assertFalse(camera.started)
        assertEquals("unsupported_live_quality", signaling.states.single().errorCode)
        assertEquals(
            LiveSessionState.Failed("session-1", "unsupported_live_quality"),
            manager.state.value,
        )
    }

    @Test
    fun publisherLeaseFailureReportsExactCodeAndRefreshesReadiness() {
        val localSignaling = FakeSignaling()
        val localManager = LiveSessionManager(
            camera,
            preview,
            publisher,
            localSignaling,
            { deviceId -> LiveDeviceCapabilities(deviceId, emptyList(), true, Instant.EPOCH.toString()) },
            scope,
            object : LiveCameraLease {
                override suspend fun acquire() = LiveCameraLeaseResult.Rejected("camera_permission_missing")
                override suspend fun release() = Unit
            },
        )

        localManager.begin(assignment())

        assertEquals("camera_permission_missing", localSignaling.states.single().errorCode)
        assertEquals("device-1", localSignaling.capabilities.single().deviceId)
        assertEquals(LiveSessionState.Failed("session-1", "camera_permission_missing"), localManager.state.value)
        assertEquals(0, publisher.startCalls)
        localManager.close()
    }

    @Test
    fun peerFailureRemainsVisibleAfterPublisherResourcesAreReleased() {
        manager.begin(assignment())

        publisher.listener?.state("failed", "webrtc_connection_failed")

        assertEquals(
            LiveSessionState.Failed("session-1", "webrtc_connection_failed"),
            manager.state.value,
        )
        assertFalse(camera.started)
        assertEquals(1, publisher.stopCalls)
    }

    @Test
    fun frameRotationChangesDoNotRestartOrDisconnectTheActiveSession() {
        manager.begin(assignment())
        publisher.listener?.state("connected")

        camera.emit(frame(rotation = 90))
        camera.emit(frame(rotation = 0))
        camera.emit(frame(rotation = 270))
        camera.emit(frame(rotation = 90))
        manager.browserStatistics(browserStatistics(bytesReceived = 4_096, framesPerSecond = 29.0))

        assertEquals(listOf(90, 0, 270, 90), publisher.frameRotations)
        assertEquals(1, publisher.startCalls)
        assertEquals(0, publisher.stopCalls)
        assertEquals("session-1", publisher.sessionId)
        assertEquals(LiveSessionState.Connected("session-1"), manager.state.value)
    }

    @Test
    fun streamingRequiresBrowserReceiveEvidenceAndStalledFramesBecomeReconnecting() {
        manager.begin(assignment())
        publisher.listener?.state("connected")

        assertEquals(LiveSessionState.Negotiating("session-1"), manager.state.value)

        manager.browserStatistics(browserStatistics(bytesReceived = 1_024, framesPerSecond = 30.0))
        assertEquals(LiveSessionState.Connected("session-1"), manager.state.value)

        manager.browserStatistics(browserStatistics(bytesReceived = 1_024, framesPerSecond = 0.0))
        assertEquals(LiveSessionState.Buffering("session-1"), manager.state.value)
    }

    private fun assignment(
        sessionId: String = "session-1",
        quality: String = "medium",
        previewVisible: Boolean = true,
    ) = LiveSessionAssignment(
        LiveSessionView(
            sessionId,
            "device-1",
            "connecting",
            quality,
            Instant.EPOCH.toString(),
            Instant.EPOCH.toString(),
            Instant.EPOCH.toString(),
        ),
        previewVisible,
        "1",
    )

    private class FakeCameraStreamController : CameraStreamController {
        var started = false
        private var consumer: CameraFrameConsumer? = null
        override fun start(consumer: CameraFrameConsumer): Boolean {
            started = true
            this.consumer = consumer
            return true
        }
        override fun stop() { started = false; consumer = null }
        fun emit(frame: CameraFrame) { consumer?.onFrame(frame) }
    }

    private class FakePreviewController : PreviewController {
        private val mutableVisible = kotlinx.coroutines.flow.MutableStateFlow(true)
        override val visible = mutableVisible
        override fun setVisible(visible: Boolean) { mutableVisible.value = visible }
    }

    private class FakePublisher : WebRtcPublisher {
        var sessionId: String? = null
        var listener: WebRtcPublisherListener? = null
        var stopCalls = 0
        var startCalls = 0
        val remoteCandidates = mutableListOf<LiveIceCandidate>()
        val frameRotations = mutableListOf<Int>()
        override suspend fun start(sessionId: String, frames: CameraStreamController, listener: WebRtcPublisherListener) {
            startCalls++
            this.sessionId = sessionId
            this.listener = listener
            check(frames.start(CameraFrameConsumer { frame ->
                frameRotations += LiveFrameOrientationResolver.resolve(
                    frame.width,
                    frame.height,
                    frame.rotationDegrees,
                ).rotationDegrees
            }))
        }
        override suspend fun acceptOffer(offer: LiveSessionDescription) = LiveSessionDescription(offer.sessionId, "answer", "v=0 android")
        override suspend fun addIceCandidate(candidate: LiveIceCandidate) { remoteCandidates += candidate }
        override suspend fun statistics(): LiveSessionStatistics? = null
        override suspend fun stop() { stopCalls++; sessionId = null }
        override fun close() { sessionId = null }
        fun emitCandidate(candidate: LiveIceCandidate) { listener?.localCandidate(candidate) }
    }

    private class FakeSignaling : LiveSignalingSender {
        val answers = mutableListOf<LiveSessionDescription>()
        val candidates = mutableListOf<LiveIceCandidate>()
        val states = mutableListOf<LiveSessionStatusUpdate>()
        val capabilities = mutableListOf<LiveDeviceCapabilities>()
        override suspend fun submitAnswer(answer: LiveSessionDescription) { answers += answer }
        override suspend fun submitCandidate(candidate: LiveIceCandidate) { candidates += candidate }
        override suspend fun reportState(update: LiveSessionStatusUpdate) { states += update }
        override suspend fun reportStatistics(statistics: LiveSessionStatistics) = Unit
        override suspend fun reportCapabilities(capabilities: LiveDeviceCapabilities) {
            this.capabilities += capabilities
        }
    }

    private fun frame(rotation: Int) = CameraFrame(
        width = 1280,
        height = 720,
        rotationDegrees = rotation,
        timestampNanos = rotation.toLong(),
        y = byteArrayOf(),
        u = byteArrayOf(),
        v = byteArrayOf(),
    )

    private fun browserStatistics(bytesReceived: Long, framesPerSecond: Double) = LiveSessionStatistics(
        sessionId = "session-1",
        source = "browser",
        bytesReceived = bytesReceived,
        framesPerSecond = framesPerSecond,
        sampledAtUtc = Instant.EPOCH.toString(),
    )
}
