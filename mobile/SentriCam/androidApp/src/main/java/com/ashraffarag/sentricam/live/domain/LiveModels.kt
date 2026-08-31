package com.ashraffarag.sentricam.live.domain

data class LiveSessionView(
    val sessionId: String,
    val deviceId: String,
    val state: String,
    val quality: String,
    val createdAtUtc: String,
    val lastActivityAtUtc: String,
    val expiresAtUtc: String,
    val errorCode: String? = null,
)

data class LiveSessionAssignment(
    val session: LiveSessionView,
    val previewVisible: Boolean,
    val protocolVersion: String,
)

data class LiveSessionDescription(
    val sessionId: String,
    val type: String,
    val sdp: String,
)

data class LiveIceCandidate(
    val sessionId: String,
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?,
    val usernameFragment: String? = null,
)

data class LiveSessionStatusUpdate(
    val sessionId: String,
    val state: String,
    val errorCode: String? = null,
)

data class LiveSessionStatistics(
    val sessionId: String,
    val source: String = "device",
    val bytesReceived: Long? = null,
    val bytesSent: Long? = null,
    val framesPerSecond: Double? = null,
    val roundTripTimeMilliseconds: Double? = null,
    val jitterMilliseconds: Double? = null,
    val packetsLost: Long? = null,
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
    val sampledAtUtc: String,
)

data class LivePreviewVisibility(
    val sessionId: String,
    val visible: Boolean,
)

data class LiveCameraCapability(
    val lens: String,
    val available: Boolean,
    val torch: Boolean,
    val zoom: Boolean,
    val resolutions: List<String>,
    val frameRates: List<Int>,
)

data class LiveDeviceCapabilities(
    val deviceId: String,
    val cameras: List<LiveCameraCapability>,
    val previewVisibilitySupported: Boolean,
    val reportedAtUtc: String,
    val readiness: String = LiveReadinessStates.READY,
    val unavailableReason: String? = null,
)

data class LiveCapabilityReadiness(
    val state: String,
    val reason: String? = null,
)

object LiveReadinessStates {
    const val UNAVAILABLE = "unavailable"
    const val INITIALIZING = "initializing"
    const val READY = "ready"
}

data class CameraFrame(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
)

sealed interface LiveSessionState {
    data object Idle : LiveSessionState
    data class Connecting(val sessionId: String) : LiveSessionState
    data class Negotiating(val sessionId: String) : LiveSessionState
    data class Connected(val sessionId: String) : LiveSessionState
    data class Buffering(val sessionId: String) : LiveSessionState
    data class Failed(val sessionId: String, val code: String) : LiveSessionState
}

object LiveSessionStates {
    const val CONNECTING = "connecting"
    const val NEGOTIATING = "negotiating"
    const val CONNECTED = "connected"
    const val BUFFERING = "buffering"
    const val DISCONNECTED = "disconnected"
    const val FAILED = "failed"
    const val CLOSED = "closed"
}
