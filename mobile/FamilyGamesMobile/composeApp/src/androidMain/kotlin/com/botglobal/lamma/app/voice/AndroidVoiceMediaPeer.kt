package com.botglobal.lamma.app.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.botglobal.mobile.platform.voice.IceServer
import com.botglobal.mobile.platform.voice.VoiceIceConfiguration
import com.botglobal.mobile.platform.voice.VoiceIcePolicy
import com.botglobal.mobile.platform.voice.VoiceMediaPath
import com.botglobal.mobile.platform.voice.VoiceMediaPeer
import com.botglobal.mobile.platform.voice.VoiceMediaPeerFactory
import com.botglobal.mobile.platform.voice.VoiceMediaPeerListener
import com.botglobal.mobile.platform.voice.VoiceMediaStats
import com.botglobal.mobile.platform.voice.VoicePeerConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidVoiceMediaPeerFactory(
    private val context: Context,
    private val icePolicy: VoiceIcePolicy,
) : VoiceMediaPeerFactory {
    override fun create(
        configuration: VoiceIceConfiguration,
        generation: Long,
        listener: VoiceMediaPeerListener,
    ): VoiceMediaPeer = AndroidVoiceMediaPeer(
        context.applicationContext,
        configuration.copy(policy = icePolicy),
        generation,
        listener,
    )
}

private class AndroidVoiceMediaPeer(
    context: Context,
    configuration: VoiceIceConfiguration,
    private val generation: Long,
    private val listener: VoiceMediaPeerListener,
) : VoiceMediaPeer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val routing = CommunicationAudioRouting(context)
    private val audioDeviceModule: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private val peer: PeerConnection
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionReady = false
    private var statsJob: Job? = null
    private var closed = false

    init {
        initializeWebRtc(context)
        routing.start()
        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        audioSource = factory.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        })
        audioTrack = factory.createAudioTrack("lamma-audio-$generation", audioSource).apply { setEnabled(true) }
        val rtcConfiguration = PeerConnection.RTCConfiguration(configuration.servers.map(::toWebRtcServer)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = if (configuration.policy == VoiceIcePolicy.Relay) {
                PeerConnection.IceTransportsType.RELAY
            } else PeerConnection.IceTransportsType.ALL
        }
        peer = requireNotNull(factory.createPeerConnection(rtcConfiguration, observer())) {
            "WebRTC PeerConnection creation failed."
        }
        peer.addTrack(audioTrack, listOf("lamma-voice"))
        Log.i(LogTag, "voice media created generation=$generation icePolicy=${configuration.policy.name.lowercase()}")
    }

    override suspend fun createOffer(): String {
        Log.i(LogTag, "voice offer creating generation=$generation")
        val description = createDescription(isOffer = true)
        setLocalDescription(description)
        Log.i(LogTag, "voice offer local description set generation=$generation")
        return description.description
    }

    override suspend fun acceptOfferAndCreateAnswer(sessionDescription: String): String {
        Log.i(LogTag, "voice offer received generation=$generation")
        setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sessionDescription))
        val answer = createDescription(isOffer = false)
        setLocalDescription(answer)
        Log.i(LogTag, "voice answer local description set generation=$generation")
        return answer.description
    }

    override suspend fun acceptAnswer(sessionDescription: String) {
        Log.i(LogTag, "voice answer received generation=$generation")
        setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sessionDescription))
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val value = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (remoteDescriptionReady) peer.addIceCandidate(value) else pendingCandidates += value
    }

    override fun setMuted(muted: Boolean) {
        audioTrack.setEnabled(!muted)
        Log.i(LogTag, "voice microphone ${if (muted) "muted" else "unmuted"} generation=$generation")
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        statsJob?.cancel()
        statsJob = null
        audioTrack.setEnabled(false)
        peer.close()
        peer.dispose()
        audioTrack.dispose()
        audioSource.dispose()
        factory.dispose()
        audioDeviceModule.release()
        routing.stop()
        Log.i(LogTag, "voice media disposed generation=$generation")
    }

    private suspend fun createDescription(isOffer: Boolean): SessionDescription = suspendCancellableCoroutine { continuation ->
        val observer = object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) { if (continuation.isActive) continuation.resume(description) }
            override fun onCreateFailure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        if (isOffer) peer.createOffer(observer, constraints) else peer.createAnswer(observer, constraints)
    }

    private suspend fun setLocalDescription(description: SessionDescription) = suspendCancellableCoroutine { continuation ->
        peer.setLocalDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
            override fun onSetFailure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
        }, description)
    }

    private suspend fun setRemoteDescription(description: SessionDescription) = suspendCancellableCoroutine { continuation ->
        peer.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteDescriptionReady = true
                pendingCandidates.forEach(peer::addIceCandidate)
                pendingCandidates.clear()
                if (continuation.isActive) continuation.resume(Unit)
            }
            override fun onSetFailure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
        }, description)
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(LogTag, "voice ice state=${state.name.lowercase()} generation=$generation")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            val type = candidate.sdp.substringAfter(" typ ", "unknown").substringBefore(' ')
            Log.i(LogTag, "voice local candidate type=$type generation=$generation")
            listener.onIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            receiver.track()?.setEnabled(true)
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(LogTag, "voice peer state=${newState.name.lowercase()} generation=$generation")
            val state = when (newState) {
                PeerConnection.PeerConnectionState.NEW -> VoicePeerConnectionState.New
                PeerConnection.PeerConnectionState.CONNECTING -> VoicePeerConnectionState.Connecting
                PeerConnection.PeerConnectionState.CONNECTED -> VoicePeerConnectionState.Connected
                PeerConnection.PeerConnectionState.DISCONNECTED -> VoicePeerConnectionState.Disconnected
                PeerConnection.PeerConnectionState.FAILED -> VoicePeerConnectionState.Failed
                PeerConnection.PeerConnectionState.CLOSED -> VoicePeerConnectionState.Closed
            }
            listener.onConnectionState(state)
            if (state == VoicePeerConnectionState.Connected && statsJob == null) startStats()
        }
    }

    private fun startStats() {
        statsJob = scope.launch {
            while (isActive && !closed) {
                peer.getStats { report -> listener.onStats(report.toVoiceStats(generation)) }
                delay(1_000)
            }
        }
    }

    private companion object {
        const val LogTag = "LammaVoice"
        @Volatile private var initialized = false
        private fun initializeWebRtc(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                    initialized = true
                }
            }
        }
        private fun toWebRtcServer(server: IceServer): PeerConnection.IceServer =
            PeerConnection.IceServer.builder(server.urls).apply {
                server.username?.let(::setUsername)
                server.credential?.let(::setPassword)
            }.createIceServer()
    }
}

private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(message: String) = Unit
    override fun onSetFailure(message: String) = Unit
}

private fun org.webrtc.RTCStatsReport.toVoiceStats(generation: Long): VoiceMediaStats {
    var outboundPackets = 0L
    var outboundBytes = 0L
    var inboundPackets = 0L
    var inboundBytes = 0L
    var audioLevel: Double? = null
    var selectedPair: org.webrtc.RTCStats? = null
    statsMap.values.forEach { stat ->
        val kind = stat.members["kind"] ?: stat.members["mediaType"]
        when {
            stat.type == "outbound-rtp" && kind == "audio" -> {
                outboundPackets += stat.long("packetsSent")
                outboundBytes += stat.long("bytesSent")
            }
            stat.type == "inbound-rtp" && kind == "audio" -> {
                inboundPackets += stat.long("packetsReceived")
                inboundBytes += stat.long("bytesReceived")
                audioLevel = (stat.members["audioLevel"] as? Number)?.toDouble()
            }
            stat.type == "candidate-pair" && stat.members["state"] == "succeeded" && stat.members["nominated"] == true -> selectedPair = stat
        }
    }
    val local = selectedPair?.members?.get("localCandidateId")?.toString()?.let(statsMap::get)
    val remote = selectedPair?.members?.get("remoteCandidateId")?.toString()?.let(statsMap::get)
    val localType = local?.members?.get("candidateType")?.toString()
    val remoteType = remote?.members?.get("candidateType")?.toString()
    val path = when {
        localType == "relay" || remoteType == "relay" -> VoiceMediaPath.Relay
        localType == "srflx" || remoteType == "srflx" -> VoiceMediaPath.ServerReflexive
        localType == "host" || remoteType == "host" -> VoiceMediaPath.Host
        else -> VoiceMediaPath.Unknown
    }
    Log.i("LammaVoiceStats", "generation=$generation mediaPath=${path.name.lowercase()} local=$localType remote=$remoteType outPackets=$outboundPackets outBytes=$outboundBytes inPackets=$inboundPackets inBytes=$inboundBytes")
    return VoiceMediaStats(outboundPackets, outboundBytes, inboundPackets, inboundBytes, audioLevel, path, localType, remoteType)
}

private fun org.webrtc.RTCStats.long(name: String): Long = (members[name] as? Number)?.toLong() ?: 0

private class CommunicationAudioRouting(context: Context) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    fun start() {
        previousMode = manager.mode
        @Suppress("DEPRECATION")
        previousSpeaker = manager.isSpeakerphoneOn
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previousCommunicationDevice = manager.communicationDevice
            val activeType = previousCommunicationDevice?.type
            if (activeType !in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
                manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let(manager::setCommunicationDevice)
            }
        } else {
            @Suppress("DEPRECATION")
            run { if (!manager.isBluetoothScoOn) manager.isSpeakerphoneOn = true }
        }
    }
    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val previous = previousCommunicationDevice
            if (previous != null && manager.availableCommunicationDevices.any { it.id == previous.id }) {
                manager.setCommunicationDevice(previous)
            } else manager.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        run { manager.isSpeakerphoneOn = previousSpeaker }
        manager.mode = previousMode
    }
}
