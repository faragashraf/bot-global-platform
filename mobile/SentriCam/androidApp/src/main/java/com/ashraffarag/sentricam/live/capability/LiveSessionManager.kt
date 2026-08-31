package com.ashraffarag.sentricam.live.capability

import com.ashraffarag.sentricam.live.domain.LiveDeviceCapabilities
import com.ashraffarag.sentricam.live.domain.LiveIceCandidate
import com.ashraffarag.sentricam.live.domain.LiveSessionAssignment
import com.ashraffarag.sentricam.live.domain.LiveSessionDescription
import com.ashraffarag.sentricam.live.domain.LiveSessionState
import com.ashraffarag.sentricam.live.domain.LiveSessionStates
import com.ashraffarag.sentricam.live.domain.LiveSessionStatusUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveSessionManager(
    private val camera: CameraStreamController,
    private val previewController: PreviewController,
    private val publisher: WebRtcPublisher,
    private val signaling: LiveSignalingSender,
    private val capabilityResolver: (String) -> LiveDeviceCapabilities,
    private val scope: CoroutineScope,
    private val cameraLease: LiveCameraLease = NoOpLiveCameraLease,
    private val diagnostics: LiveSessionDiagnostics = NoOpLiveSessionDiagnostics,
) : LiveSignalReceiver, WebRtcPublisherListener, AutoCloseable {
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<LiveSessionState>(LiveSessionState.Idle)
    private var activeSessionId: String? = null
    private var activeDeviceId: String? = null
    private var lastBrowserBytesReceived: Long? = null
    private var statisticsJob: Job? = null
    private val eventJob = scope.launch {
        for (event in events) event()
    }
    val state = mutableState.asStateFlow()

    override fun capabilities(deviceId: String): LiveDeviceCapabilities = capabilityResolver(deviceId)

    override fun begin(assignment: LiveSessionAssignment) = enqueue {
        closeActive(report = false)
        val sessionId = assignment.session.sessionId
        if (assignment.protocolVersion != SUPPORTED_PROTOCOL) {
            fail(sessionId, "unsupported_live_protocol")
            return@enqueue
        }
        if (assignment.session.quality != IMPLEMENTED_QUALITY) {
            fail(sessionId, "unsupported_live_quality")
            return@enqueue
        }
        activeSessionId = sessionId
        activeDeviceId = assignment.session.deviceId
        lastBrowserBytesReceived = null
        previewController.setVisible(assignment.previewVisible)
        mutableState.value = LiveSessionState.Connecting(sessionId)
        diagnostics.event("begin", sessionId, "state=connecting")
        try {
            when (val lease = cameraLease.acquire()) {
                LiveCameraLeaseResult.Acquired -> Unit
                is LiveCameraLeaseResult.Rejected -> throw IllegalStateException(lease.code)
            }
            publisher.start(sessionId, camera, this)
            diagnostics.event("publisher_acknowledged", sessionId, "state=started")
            signaling.reportState(LiveSessionStatusUpdate(sessionId, LiveSessionStates.CONNECTING))
            startStatistics(sessionId)
        } catch (failure: Throwable) {
            fail(sessionId, stableFailureCode(failure, "publisher_start_failed"), failure)
        }
    }

    override fun offer(offer: LiveSessionDescription) = enqueue {
        if (!isActive(offer.sessionId)) return@enqueue
        mutableState.value = LiveSessionState.Negotiating(offer.sessionId)
        diagnostics.event("offer", offer.sessionId, "state=connecting")
        try {
            signaling.submitAnswer(publisher.acceptOffer(offer))
            signaling.reportState(LiveSessionStatusUpdate(offer.sessionId, LiveSessionStates.NEGOTIATING))
        } catch (failure: Throwable) {
            fail(offer.sessionId, stableFailureCode(failure, "webrtc_session_creation_failed"), failure)
        }
    }

    override fun candidate(candidate: LiveIceCandidate) = enqueue {
        if (!isActive(candidate.sessionId)) return@enqueue
        try {
            publisher.addIceCandidate(candidate)
        } catch (failure: Throwable) {
            fail(candidate.sessionId, stableFailureCode(failure, "ice_candidate_failed"), failure)
        }
    }

    override fun preview(visible: Boolean) = enqueue {
        if (activeSessionId != null) previewController.setVisible(visible)
    }

    override fun browserStatistics(statistics: com.ashraffarag.sentricam.live.domain.LiveSessionStatistics) = enqueue {
        if (!isActive(statistics.sessionId) || statistics.source != "browser") {
            diagnostics.event(
                "browser_statistics_ignored",
                statistics.sessionId,
                "active=${activeSessionId ?: "none"} source=${statistics.source}",
            )
            return@enqueue
        }
        val currentBytes = statistics.bytesReceived
        val receivingFrames = (statistics.framesPerSecond ?: 0.0) > 0.0 ||
            (currentBytes != null && currentBytes > (lastBrowserBytesReceived ?: -1L))
        val nextState = if (receivingFrames) {
            LiveSessionState.Connected(statistics.sessionId)
        } else {
            LiveSessionState.Buffering(statistics.sessionId)
        }
        val changed = mutableState.value::class != nextState::class
        mutableState.value = nextState
        lastBrowserBytesReceived = currentBytes
        if (changed) {
            diagnostics.event(
                "browser_statistics",
                statistics.sessionId,
                "state=${if (receivingFrames) "streaming" else "reconnecting"} " +
                    "fps=${statistics.framesPerSecond ?: "unknown"} bytes=${currentBytes ?: "unknown"}",
            )
        }
    }

    override fun end(update: LiveSessionStatusUpdate) = enqueue {
        if (isActive(update.sessionId)) {
            diagnostics.event("end", update.sessionId, "state=${update.state}")
            closeActive(report = false)
        }
    }

    override fun connectionLost() = enqueue {
        diagnostics.event("connection_lost", activeSessionId, "state=recovering")
        closeActive(report = false)
    }

    override fun localCandidate(candidate: LiveIceCandidate) {
        enqueue {
            if (isActive(candidate.sessionId)) signaling.submitCandidate(candidate)
        }
    }

    override fun state(state: String, errorCode: String?) = enqueue {
        val sessionId = activeSessionId ?: return@enqueue
        val failureState = LiveSessionState.Failed(sessionId, errorCode ?: "webrtc_failed")
        when (state) {
            // A connected peer is necessary but not sufficient for the user-facing Streaming
            // state. Browser receive statistics promote the session once frames actually arrive.
            LiveSessionStates.CONNECTED -> if (mutableState.value !is LiveSessionState.Connected) {
                mutableState.value = LiveSessionState.Negotiating(sessionId)
            }
            LiveSessionStates.BUFFERING -> mutableState.value = LiveSessionState.Buffering(sessionId)
            LiveSessionStates.FAILED -> mutableState.value = failureState
        }
        signaling.reportState(LiveSessionStatusUpdate(sessionId, state, errorCode))
        if (state == LiveSessionStates.FAILED || state == LiveSessionStates.DISCONNECTED) {
            val deviceId = activeDeviceId
            closeActive(
                report = false,
                finalState = if (state == LiveSessionStates.FAILED) failureState else LiveSessionState.Idle,
            )
            if (state == LiveSessionStates.FAILED && deviceId != null) {
                runCatching { signaling.reportCapabilities(capabilityResolver(deviceId)) }
            }
        }
    }

    override fun close() {
        statisticsJob?.cancel()
        camera.stop()
        previewController.setVisible(true)
        activeSessionId = null
        activeDeviceId = null
        lastBrowserBytesReceived = null
        mutableState.value = LiveSessionState.Idle
        publisher.close()
        events.close()
        eventJob.cancel()
    }

    private fun enqueue(event: suspend () -> Unit) {
        events.trySend(event)
    }

    private suspend fun fail(sessionId: String, code: String, failure: Throwable? = null) {
        val deviceId = activeDeviceId
        diagnostics.event(
            "failed",
            sessionId,
            "code=$code failureType=${failure?.javaClass?.simpleName ?: "reported"}",
        )
        val failureState = LiveSessionState.Failed(sessionId, code)
        mutableState.value = failureState
        runCatching {
            signaling.reportState(LiveSessionStatusUpdate(sessionId, LiveSessionStates.FAILED, code))
        }
        closeActive(report = false, finalState = failureState)
        if (deviceId != null) {
            runCatching { signaling.reportCapabilities(capabilityResolver(deviceId)) }
        }
    }

    private suspend fun closeActive(
        report: Boolean,
        finalState: LiveSessionState = LiveSessionState.Idle,
    ) {
        val sessionId = activeSessionId
        statisticsJob?.cancel()
        statisticsJob = null
        if (sessionId != null) {
            publisher.stop()
        }
        camera.stop()
        cameraLease.release()
        previewController.setVisible(true)
        activeSessionId = null
        activeDeviceId = null
        lastBrowserBytesReceived = null
        mutableState.value = finalState
        if (report && sessionId != null) {
            signaling.reportState(LiveSessionStatusUpdate(sessionId, LiveSessionStates.DISCONNECTED))
        }
    }

    private fun startStatistics(sessionId: String) {
        statisticsJob?.cancel()
        statisticsJob = scope.launch {
            while (isActive(sessionId)) {
                delay(STATISTICS_INTERVAL_MILLIS)
                publisher.statistics()?.let { signaling.reportStatistics(it) }
            }
        }
    }

    private fun isActive(sessionId: String) = activeSessionId == sessionId

    private fun stableFailureCode(failure: Throwable, fallback: String): String {
        val message = failure.message.orEmpty()
        return if (message in STABLE_FAILURE_CODES) message else fallback
    }

    private companion object {
        const val SUPPORTED_PROTOCOL = "1"
        const val IMPLEMENTED_QUALITY = "medium"
        const val STATISTICS_INTERVAL_MILLIS = 5_000L
        val STABLE_FAILURE_CODES = setOf(
            "camera_permission_missing",
            "foreground_camera_start_failed",
            "camera_busy",
            "camera_initializing",
            "selected_camera_unavailable",
            "camera_publisher_initialization_failed",
            "publisher_start_failed",
            "webrtc_session_creation_failed",
            "ice_candidate_failed",
            "unsupported_live_protocol",
            "unsupported_live_quality",
        )
    }
}
