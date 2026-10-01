package com.ashraffarag.sentricam.live.capability

import com.ashraffarag.sentricam.live.domain.CameraFrame
import com.ashraffarag.sentricam.live.domain.LiveDeviceCapabilities
import com.ashraffarag.sentricam.live.domain.LiveIceCandidate
import com.ashraffarag.sentricam.live.domain.LiveSessionAssignment
import com.ashraffarag.sentricam.live.domain.LiveSessionDescription
import com.ashraffarag.sentricam.live.domain.LiveSessionStatistics
import com.ashraffarag.sentricam.live.domain.LiveSessionStatusUpdate
import kotlinx.coroutines.flow.StateFlow

fun interface CameraFrameConsumer {
    fun onFrame(frame: CameraFrame)
}

interface CameraStreamController {
    fun start(consumer: CameraFrameConsumer): Boolean
    fun stop()
}

interface PreviewController {
    val visible: StateFlow<Boolean>
    fun setVisible(visible: Boolean)
}

interface LiveCameraLease {
    suspend fun acquire(): LiveCameraLeaseResult
    suspend fun release()
}

sealed interface LiveCameraLeaseResult {
    data object Acquired : LiveCameraLeaseResult
    data class Rejected(val code: String) : LiveCameraLeaseResult
}

object NoOpLiveCameraLease : LiveCameraLease {
    override suspend fun acquire() = LiveCameraLeaseResult.Acquired
    override suspend fun release() = Unit
}

fun interface LiveSessionDiagnostics {
    fun event(name: String, sessionId: String?, detail: String)
}

object NoOpLiveSessionDiagnostics : LiveSessionDiagnostics {
    override fun event(name: String, sessionId: String?, detail: String) = Unit
}

interface WebRtcPublisher : AutoCloseable {
    suspend fun start(sessionId: String, frames: CameraStreamController, listener: WebRtcPublisherListener)
    suspend fun acceptOffer(offer: LiveSessionDescription): LiveSessionDescription
    suspend fun addIceCandidate(candidate: LiveIceCandidate)
    suspend fun statistics(): LiveSessionStatistics?
    suspend fun stop()
}

interface WebRtcPublisherListener {
    fun localCandidate(candidate: LiveIceCandidate)
    fun state(state: String, errorCode: String? = null)
}

interface LiveSignalingSender {
    suspend fun submitAnswer(answer: LiveSessionDescription)
    suspend fun submitCandidate(candidate: LiveIceCandidate)
    suspend fun reportState(update: LiveSessionStatusUpdate)
    suspend fun reportStatistics(statistics: LiveSessionStatistics)
    suspend fun reportCapabilities(capabilities: LiveDeviceCapabilities) = Unit
}

interface LiveSignalReceiver {
    fun capabilities(deviceId: String): LiveDeviceCapabilities
    fun begin(assignment: LiveSessionAssignment)
    fun offer(offer: LiveSessionDescription)
    fun candidate(candidate: LiveIceCandidate)
    fun preview(visible: Boolean)
    fun browserStatistics(statistics: LiveSessionStatistics)
    fun end(update: LiveSessionStatusUpdate)
    fun connectionLost()
}

object NoOpLiveSignalReceiver : LiveSignalReceiver {
    override fun capabilities(deviceId: String) = LiveDeviceCapabilities(deviceId, emptyList(), true, java.time.Instant.EPOCH.toString())
    override fun begin(assignment: LiveSessionAssignment) = Unit
    override fun offer(offer: LiveSessionDescription) = Unit
    override fun candidate(candidate: LiveIceCandidate) = Unit
    override fun preview(visible: Boolean) = Unit
    override fun browserStatistics(statistics: LiveSessionStatistics) = Unit
    override fun end(update: LiveSessionStatusUpdate) = Unit
    override fun connectionLost() = Unit
}
