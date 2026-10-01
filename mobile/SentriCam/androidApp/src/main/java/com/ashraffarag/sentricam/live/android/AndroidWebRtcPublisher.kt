package com.ashraffarag.sentricam.live.android

import android.content.Context
import com.ashraffarag.sentricam.live.capability.CameraFrameConsumer
import com.ashraffarag.sentricam.live.capability.CameraStreamController
import com.ashraffarag.sentricam.live.capability.WebRtcPublisher
import com.ashraffarag.sentricam.live.capability.WebRtcPublisherListener
import com.ashraffarag.sentricam.live.capability.LiveFrameOrientationResolver
import com.ashraffarag.sentricam.live.domain.CameraFrame
import com.ashraffarag.sentricam.live.domain.LiveIceCandidate
import com.ashraffarag.sentricam.live.domain.LiveSessionDescription
import com.ashraffarag.sentricam.live.domain.LiveSessionStates
import com.ashraffarag.sentricam.live.domain.LiveSessionStatistics
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues

class AndroidWebRtcPublisher(
    context: Context,
    private val settingsProvider: () -> CameraControlSettings = { CameraControlSettings() },
    private val diagnostics: com.ashraffarag.sentricam.live.capability.LiveSessionDiagnostics =
        com.ashraffarag.sentricam.live.capability.NoOpLiveSessionDiagnostics,
) : WebRtcPublisher {
    private val appContext = context.applicationContext
    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var cameraStream: CameraStreamController? = null
    private var listener: WebRtcPublisherListener? = null
    private var sessionId: String? = null
    private var lastFrameSignature: String? = null
    private val dateTimeOverlay = I420DateTimeOverlayRenderer()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    override suspend fun start(
        sessionId: String,
        frames: CameraStreamController,
        listener: WebRtcPublisherListener,
    ) {
        stop()
        this.sessionId = sessionId
        this.listener = listener
        diagnostics.event("publisher_start", sessionId, "state=starting")
        LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) { "event=publisher_start session=$sessionId" }
        val settings = settingsProvider()
        val dimensions = settings.resolution.split('x', limit = 2)
        val width = dimensions.getOrNull(0)?.toIntOrNull() ?: V1_WIDTH
        val height = dimensions.getOrNull(1)?.toIntOrNull() ?: V1_HEIGHT
        val framesPerSecond = when (settings.nightProfile) {
            CameraControlValues.NIGHT -> minOf(settings.framesPerSecond, 15)
            CameraControlValues.INDOOR -> minOf(settings.framesPerSecond, 24)
            else -> settings.framesPerSecond
        }.coerceIn(1, 60)
        val source = factory.createVideoSource(false)
        source.adaptOutputFormat(width, height, framesPerSecond)
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source).apply { setEnabled(true) }
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        val peer = factory.createPeerConnection(configuration, Observer())
            ?: throw IllegalStateException("peer_connection_unavailable")
        val sender = peer.addTrack(track, listOf(MEDIA_STREAM_ID))
        val parameters = sender.parameters
        parameters.encodings.forEach { encoding -> encoding.maxBitrateBps = settings.bitrate }
        sender.parameters = parameters
        videoSource = source
        videoTrack = track
        peerConnection = peer
        cameraStream = frames
        source.capturerObserver.onCapturerStarted(true)
        if (!frames.start(CameraFrameConsumer(::publishFrame))) {
            stop()
            throw IllegalStateException("camera_stream_unavailable")
        }
    }

    override suspend fun acceptOffer(offer: LiveSessionDescription): LiveSessionDescription {
        val peer = requirePeer(offer.sessionId)
        LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
            "event=offer_received session=${offer.sessionId} remoteDescriptionReady=${peer.remoteDescription != null}"
        }
        peer.setRemote(SessionDescription(SessionDescription.Type.OFFER, offer.sdp))
        LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
            "event=remote_description_set session=${offer.sessionId}"
        }
        val answer = peer.createAnswer()
        LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
            "event=answer_created session=${offer.sessionId}"
        }
        peer.setLocal(answer)
        LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
            "event=local_description_set session=${offer.sessionId}"
        }
        return LiveSessionDescription(offer.sessionId, "answer", answer.description)
    }

    override suspend fun addIceCandidate(candidate: LiveIceCandidate) {
        val peer = requirePeer(candidate.sessionId)
        val remoteDescriptionReady = peer.remoteDescription != null
        LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
            "event=remote_candidate_received session=${candidate.sessionId} " +
                "remoteDescriptionReady=$remoteDescriptionReady"
        }
        val accepted = peer.addIceCandidate(
            IceCandidate(candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex ?: 0, candidate.candidate),
        )
        LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
            "event=remote_candidate_result session=${candidate.sessionId} accepted=$accepted"
        }
        check(accepted) { "ice_candidate_rejected" }
    }

    override suspend fun statistics(): LiveSessionStatistics? {
        val peer = peerConnection ?: return null
        val id = sessionId ?: return null
        return suspendCancellableCoroutine { continuation ->
            peer.getStats { report ->
                if (!continuation.isActive) return@getStats
                val outbound = report.statsMap.values.firstOrNull { statistic ->
                    statistic.type == "outbound-rtp" && statistic.members["kind"] == "video"
                }
                val members = outbound?.members.orEmpty()
                continuation.resume(
                    LiveSessionStatistics(
                        sessionId = id,
                        bytesSent = members.long("bytesSent"),
                        framesPerSecond = members.double("framesPerSecond"),
                        frameWidth = members.int("frameWidth"),
                        frameHeight = members.int("frameHeight"),
                        sampledAtUtc = Instant.now().toString(),
                    ),
                )
            }
        }
    }

    override suspend fun stop() {
        diagnostics.event("publisher_stop", sessionId, "state=stopping")
        LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) { "event=publisher_stop session=$sessionId" }
        cameraStream?.stop()
        cameraStream = null
        videoSource?.capturerObserver?.onCapturerStopped()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        sessionId = null
        listener = null
    }

    override fun close() {
        cameraStream?.stop()
        peerConnection?.close()
        peerConnection?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        factory.dispose()
        eglBase.release()
    }

    private fun publishFrame(frame: CameraFrame) {
        val source = videoSource ?: return
        dateTimeOverlay.apply(frame, settingsProvider().dateTimeOverlay)
        val orientation = LiveFrameOrientationResolver.resolve(
            frame.width,
            frame.height,
            frame.rotationDegrees,
        )
        val signature = "${frame.width}x${frame.height}:${frame.rotationDegrees}"
        if (signature != lastFrameSignature) {
            lastFrameSignature = signature
            LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
                "event=outgoing_frame encoded=${frame.width}x${frame.height} " +
                    "cameraXRotation=${frame.rotationDegrees} videoFrameRotation=${orientation.rotationDegrees} " +
                    "display=${orientation.displayDimensions.width}x${orientation.displayDimensions.height} " +
                    "pixelsRotated=false"
            }
        }
        val buffer = JavaI420Buffer.allocate(frame.width, frame.height)
        buffer.dataY.put(frame.y).rewind()
        buffer.dataU.put(frame.u).rewind()
        buffer.dataV.put(frame.v).rewind()
        // Pixels remain in CameraX sensor-buffer order. WebRTC applies this metadata exactly once.
        val videoFrame = VideoFrame(buffer, orientation.rotationDegrees, frame.timestampNanos)
        try {
            source.capturerObserver.onFrameCaptured(videoFrame)
        } finally {
            videoFrame.release()
        }
    }

    private fun requirePeer(expectedSessionId: String): PeerConnection {
        check(expectedSessionId == sessionId) { "live_session_mismatch" }
        return checkNotNull(peerConnection) { "peer_connection_unavailable" }
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val id = sessionId ?: return
            LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
                "event=local_candidate_emitted session=$id"
            }
            listener?.localCandidate(LiveIceCandidate(id, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
                "event=peer_connection state=$newState session=$sessionId"
            }
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> listener?.state(LiveSessionStates.CONNECTED)
                PeerConnection.PeerConnectionState.DISCONNECTED -> listener?.state(LiveSessionStates.BUFFERING)
                PeerConnection.PeerConnectionState.FAILED -> {
                    diagnostics.event("publisher_failure", sessionId, "code=webrtc_connection_failed")
                    listener?.state(LiveSessionStates.FAILED, "webrtc_connection_failed")
                }
                PeerConnection.PeerConnectionState.CLOSED -> listener?.state(LiveSessionStates.DISCONNECTED)
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
                "event=signaling_state state=$state session=$sessionId"
            }
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
                "event=ice_connection_state state=$state session=$sessionId"
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            LiveViewDiagnostics.log(LiveViewDiagnostics.WEBRTC) {
                "event=ice_gathering_state state=$state session=$sessionId"
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
    }

    private suspend fun PeerConnection.setRemote(description: SessionDescription) =
        suspendCancellableCoroutine { continuation ->
            setRemoteDescription(ContinuationSdpObserver(continuation), description)
        }

    private suspend fun PeerConnection.createAnswer(): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            createAnswer(CreationSdpObserver(continuation), MediaConstraints())
        }

    private suspend fun PeerConnection.setLocal(description: SessionDescription) =
        suspendCancellableCoroutine { continuation ->
            setLocalDescription(ContinuationSdpObserver(continuation), description)
        }

    private class ContinuationSdpObserver(
        private val continuation: kotlin.coroutines.Continuation<Unit>,
    ) : SdpObserver {
        override fun onSetSuccess() = continuation.resume(Unit)
        override fun onSetFailure(message: String) = continuation.resumeWithException(IllegalStateException(message))
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onCreateFailure(message: String) = Unit
    }

    private class CreationSdpObserver(
        private val continuation: kotlin.coroutines.Continuation<SessionDescription>,
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
        override fun onCreateFailure(message: String) = continuation.resumeWithException(IllegalStateException(message))
        override fun onSetSuccess() = Unit
        override fun onSetFailure(message: String) = Unit
    }

    private fun Map<String, Any>.long(name: String) = (this[name] as? Number)?.toLong()
    private fun Map<String, Any>.double(name: String) = (this[name] as? Number)?.toDouble()
    private fun Map<String, Any>.int(name: String) = (this[name] as? Number)?.toInt()

    private companion object {
        const val V1_WIDTH = 1280
        const val V1_HEIGHT = 720
        const val V1_FRAMES_PER_SECOND = 30
        const val VIDEO_TRACK_ID = "sentricam-video"
        const val MEDIA_STREAM_ID = "sentricam-live"
    }
}
