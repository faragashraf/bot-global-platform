package com.botglobal.mobile.platform.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)
enum class VoiceIcePolicy { All, Relay }
data class VoiceIceConfiguration(val servers: List<IceServer>, val expiresAtUtc: String, val policy: VoiceIcePolicy = VoiceIcePolicy.All)

enum class VoiceRoomState { Idle, PermissionRequired, Joining, WaitingForPeer, Negotiating, Connected, Reconnecting, Failed, Unavailable }
enum class VoiceMediaPath { Unknown, Host, ServerReflexive, Relay }
data class VoiceMediaStats(
    val outboundPackets: Long = 0,
    val outboundBytes: Long = 0,
    val inboundPackets: Long = 0,
    val inboundBytes: Long = 0,
    val audioLevel: Double? = null,
    val path: VoiceMediaPath = VoiceMediaPath.Unknown,
    val localCandidateType: String? = null,
    val remoteCandidateType: String? = null,
    val available: Boolean = false,
)
data class VoiceRoomSnapshot(
    val state: VoiceRoomState = VoiceRoomState.Idle,
    val muted: Boolean = false,
    val peerMuted: Boolean = false,
    val generation: Long = 0,
    val stats: VoiceMediaStats = VoiceMediaStats(),
    val error: String? = null,
)
data class VoiceJoinResult(
    val roomId: String,
    val generation: Long,
    val participantId: String,
    val isInitiator: Boolean,
    val peerPresent: Boolean,
    val connectionId: String = "",
    val peerParticipantId: String? = null,
    val peerConnectionId: String? = null,
)

sealed interface VoiceSignal {
    val roomId: String
    val receiverGeneration: Long
    val participantGeneration: Long
    val participantId: String
    val participantConnectionId: String
    val receiverConnectionId: String
    data class PeerJoined(override val roomId: String, override val receiverGeneration: Long, override val participantId: String, override val participantGeneration: Long, val participantIsInitiator: Boolean, override val participantConnectionId: String = "", override val receiverConnectionId: String = "") : VoiceSignal
    data class PeerLeft(override val roomId: String, override val receiverGeneration: Long, override val participantId: String, override val participantGeneration: Long, override val participantConnectionId: String = "", override val receiverConnectionId: String = "") : VoiceSignal
    data class Offer(override val roomId: String, override val receiverGeneration: Long, override val participantId: String, override val participantGeneration: Long, val sessionDescription: String, override val participantConnectionId: String = "", override val receiverConnectionId: String = "") : VoiceSignal
    data class Answer(override val roomId: String, override val receiverGeneration: Long, override val participantId: String, override val participantGeneration: Long, val sessionDescription: String, override val participantConnectionId: String = "", override val receiverConnectionId: String = "") : VoiceSignal
    data class IceCandidate(override val roomId: String, override val receiverGeneration: Long, override val participantId: String, override val participantGeneration: Long, val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int, override val participantConnectionId: String = "", override val receiverConnectionId: String = "") : VoiceSignal
    data class MuteState(override val roomId: String, override val receiverGeneration: Long, override val participantId: String, override val participantGeneration: Long, val muted: Boolean, override val participantConnectionId: String = "", override val receiverConnectionId: String = "") : VoiceSignal
}

interface VoiceSignalingTransport {
    val signals: Flow<VoiceSignal>
    suspend fun iceConfiguration(roomId: String): VoiceIceConfiguration
    suspend fun join(roomId: String, generation: Long): VoiceJoinResult
    suspend fun leave(roomId: String, generation: Long)
    suspend fun offer(roomId: String, generation: Long, sessionDescription: String)
    suspend fun answer(roomId: String, generation: Long, sessionDescription: String)
    suspend fun iceCandidate(roomId: String, generation: Long, candidate: String, sdpMid: String?, sdpMLineIndex: Int)
    suspend fun muted(roomId: String, generation: Long, muted: Boolean)
}

enum class VoicePeerConnectionState { New, Connecting, Connected, Disconnected, Failed, Closed }
interface VoiceMediaPeerListener {
    fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
    fun onConnectionState(state: VoicePeerConnectionState)
    fun onStats(stats: VoiceMediaStats)
}
interface VoiceMediaPeer {
    suspend fun createOffer(): String
    suspend fun acceptOfferAndCreateAnswer(sessionDescription: String): String
    suspend fun acceptAnswer(sessionDescription: String)
    suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
    fun setMuted(muted: Boolean)
    suspend fun close()
}
fun interface VoiceMediaPeerFactory {
    fun create(configuration: VoiceIceConfiguration, generation: Long, listener: VoiceMediaPeerListener): VoiceMediaPeer
}
interface VoiceRoomController {
    val snapshot: StateFlow<VoiceRoomSnapshot>
    suspend fun join(roomId: String)
    suspend fun leave()
    suspend fun setMuted(muted: Boolean)
    suspend fun signalingInterrupted()
    suspend fun signalingRecovered()
}

class ManagedVoiceRoomController(
    private val scope: CoroutineScope,
    private val signaling: VoiceSignalingTransport,
    private val mediaFactory: VoiceMediaPeerFactory,
    private val logger: (String) -> Unit = {},
    private val logTopologyIdentifiers: Boolean = true,
) : VoiceRoomController {
    private val mutableSnapshot = MutableStateFlow(VoiceRoomSnapshot())
    override val snapshot: StateFlow<VoiceRoomSnapshot> = mutableSnapshot.asStateFlow()
    private var roomId: String? = null
    private var generation = 0L
    private var media: VoiceMediaPeer? = null
    private var isInitiator = false
    private var currentIceConfiguration: VoiceIceConfiguration? = null
    private var offerSentGeneration = Long.MIN_VALUE
    private var localParticipantId: String? = null
    private var localConnectionId: String? = null
    private var peerParticipantId: String? = null
    private var peerConnectionId: String? = null

    init { scope.launch { signaling.signals.collect(::onSignal) } }

    override suspend fun join(roomId: String) {
        if (this.roomId == roomId && snapshot.value.state !in setOf(VoiceRoomState.Idle, VoiceRoomState.Failed, VoiceRoomState.Unavailable)) return
        disposeCurrent(notifyServer = this.roomId != null)
        generation++
        this.roomId = roomId
        update(state = VoiceRoomState.Joining, generation = generation, error = null)
        try {
            val expected = generation
            currentIceConfiguration = signaling.iceConfiguration(roomId)
            media = mediaFactory.create(requireNotNull(currentIceConfiguration), expected, listener(expected))
            val joined = signaling.join(roomId, expected)
            if (!isCurrent(roomId, expected)) return
            localParticipantId = joined.participantId
            localConnectionId = joined.connectionId
            peerParticipantId = joined.peerParticipantId
            peerConnectionId = joined.peerConnectionId
            isInitiator = joined.isInitiator
            logger(if (logTopologyIdentifiers) {
                "voice topology localMembership=${joined.participantId} localConnection=${joined.connectionId} -> remoteMembership=${joined.peerParticipantId ?: "pending"} remoteConnection=${joined.peerConnectionId ?: "pending"} initiator=${joined.isInitiator}"
            } else "voice topology established peerPresent=${joined.peerPresent} initiator=${joined.isInitiator}")
            update(state = if (joined.peerPresent) VoiceRoomState.Negotiating else VoiceRoomState.WaitingForPeer)
            if (joined.peerPresent) createOfferIfInitiator(roomId, expected)
        } catch (error: Throwable) {
            logger("voice join failed generation=$generation type=${error::class.simpleName}")
            media?.close()
            media = null
            update(state = VoiceRoomState.Failed, error = "voice_join_failed")
        }
    }

    override suspend fun leave() {
        disposeCurrent(notifyServer = true)
        generation++
        mutableSnapshot.value = VoiceRoomSnapshot(generation = generation)
    }

    override suspend fun setMuted(muted: Boolean) {
        val room = roomId ?: return
        media?.setMuted(muted)
        update(muted = muted)
        runCatching { signaling.muted(room, generation, muted) }
    }

    override suspend fun signalingInterrupted() {
        if (roomId == null || snapshot.value.state == VoiceRoomState.Reconnecting) return
        generation++
        media?.close()
        media = null
        update(state = VoiceRoomState.Reconnecting, generation = generation)
    }

    override suspend fun signalingRecovered() {
        val room = roomId ?: return
        val preservedMute = snapshot.value.muted
        roomId = null
        join(room)
        if (preservedMute) setMuted(true)
    }

    private suspend fun onSignal(signal: VoiceSignal) {
        val room = roomId ?: return
        if (signal.roomId != room || signal.receiverGeneration != generation) {
            logger("voice stale signal ignored generation=${signal.receiverGeneration} current=$generation type=${signal::class.simpleName}")
            return
        }
        if (signal.participantId == localParticipantId ||
            signal.participantConnectionId.isNotBlank() && signal.participantConnectionId == localConnectionId ||
            signal.receiverConnectionId.isNotBlank() && signal.receiverConnectionId != localConnectionId ||
            peerParticipantId != null && signal.participantId != peerParticipantId) {
            logger(if (logTopologyIdentifiers) {
                "voice identity violation ignored localMembership=$localParticipantId localConnection=$localConnectionId senderMembership=${signal.participantId} senderConnection=${signal.participantConnectionId} intendedReceiver=${signal.receiverConnectionId} type=${signal::class.simpleName}"
            } else "voice identity violation ignored type=${signal::class.simpleName}")
            return
        }
        logger(if (logTopologyIdentifiers) {
            "voice signal received senderMembership=${signal.participantId} senderConnection=${signal.participantConnectionId} -> localMembership=$localParticipantId localConnection=$localConnectionId type=${signal::class.simpleName}"
        } else "voice signal received type=${signal::class.simpleName}")
        when (signal) {
            is VoiceSignal.PeerJoined -> {
                peerParticipantId = signal.participantId
                peerConnectionId = signal.participantConnectionId
                update(state = VoiceRoomState.Negotiating)
                if (media == null) currentIceConfiguration?.let { media = mediaFactory.create(it, generation, listener(generation)) }
                createOfferIfInitiator(room, signal.receiverGeneration)
            }
            is VoiceSignal.PeerLeft -> {
                media?.close()
                media = null
                update(state = VoiceRoomState.WaitingForPeer, peerMuted = false)
            }
            is VoiceSignal.Offer -> if (!isInitiator) {
                update(state = VoiceRoomState.Negotiating)
                media?.acceptOfferAndCreateAnswer(signal.sessionDescription)?.let { if (isCurrent(room, signal.receiverGeneration)) signaling.answer(room, generation, it) }
            }
            is VoiceSignal.Answer -> if (isInitiator) media?.acceptAnswer(signal.sessionDescription)
            is VoiceSignal.IceCandidate -> media?.addIceCandidate(signal.candidate, signal.sdpMid, signal.sdpMLineIndex)
            is VoiceSignal.MuteState -> update(peerMuted = signal.muted)
        }
    }

    private fun listener(expectedGeneration: Long) = object : VoiceMediaPeerListener {
        override fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
            val room = roomId ?: return
            scope.launch { if (generation == expectedGeneration) signaling.iceCandidate(room, expectedGeneration, candidate, sdpMid, sdpMLineIndex) }
        }
        override fun onConnectionState(state: VoicePeerConnectionState) {
            if (generation != expectedGeneration) return
            update(state = when (state) {
                VoicePeerConnectionState.New, VoicePeerConnectionState.Connecting -> VoiceRoomState.Negotiating
                VoicePeerConnectionState.Connected -> VoiceRoomState.Connected
                VoicePeerConnectionState.Disconnected -> VoiceRoomState.Reconnecting
                VoicePeerConnectionState.Failed -> VoiceRoomState.Failed
                VoicePeerConnectionState.Closed -> VoiceRoomState.Idle
            })
            logger("voice peer state=${state.name.lowercase()} generation=$expectedGeneration")
        }
        override fun onStats(stats: VoiceMediaStats) { if (generation == expectedGeneration) update(stats = stats) }
    }

    private suspend fun disposeCurrent(notifyServer: Boolean) {
        val oldRoom = roomId
        val oldGeneration = generation
        roomId = null
        media?.close()
        media = null
        currentIceConfiguration = null
        offerSentGeneration = Long.MIN_VALUE
        localParticipantId = null
        localConnectionId = null
        peerParticipantId = null
        peerConnectionId = null
        if (notifyServer && oldRoom != null) runCatching { signaling.leave(oldRoom, oldGeneration) }
    }
    private suspend fun createOfferIfInitiator(room: String, expectedGeneration: Long) {
        if (!isInitiator || offerSentGeneration == expectedGeneration) return
        offerSentGeneration = expectedGeneration
        media?.createOffer()?.let { if (isCurrent(room, expectedGeneration)) signaling.offer(room, expectedGeneration, it) }
    }
    private fun isCurrent(room: String, expectedGeneration: Long) = roomId == room && generation == expectedGeneration
    private fun update(
        state: VoiceRoomState = mutableSnapshot.value.state,
        muted: Boolean = mutableSnapshot.value.muted,
        peerMuted: Boolean = mutableSnapshot.value.peerMuted,
        generation: Long = mutableSnapshot.value.generation,
        stats: VoiceMediaStats = mutableSnapshot.value.stats,
        error: String? = mutableSnapshot.value.error,
    ) { mutableSnapshot.value = VoiceRoomSnapshot(state, muted, peerMuted, generation, stats, error) }
}
