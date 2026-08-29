package com.botglobal.lamma.app.realtime

import android.util.Log
import com.botglobal.lamma.app.data.FamilyGamesEnvironment
import com.botglobal.lamma.app.data.GameSessionSnapshot
import com.botglobal.mobile.platform.voice.IceServer
import com.botglobal.mobile.platform.voice.VoiceIceConfiguration
import com.botglobal.mobile.platform.voice.VoiceIcePolicy
import com.botglobal.mobile.platform.voice.VoiceJoinResult
import com.botglobal.mobile.platform.voice.VoiceSignal
import com.botglobal.mobile.platform.voice.VoiceConsentResult
import com.botglobal.mobile.platform.voice.VoiceConsentSignal
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.Subscription
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private class SignalRGameRealtimeTransport(
    environment: FamilyGamesEnvironment,
    private val configuration: GameRealtimeTransportConfiguration,
    private val listener: GameRealtimeTransportListener,
) : GameRealtimeTransport {
    private val subscriptions = mutableListOf<Subscription>()
    private val hub: HubConnection = HubConnectionBuilder
        .create(environment.gamesHubUrl)
        .withAccessTokenProvider(
            Single.defer {
                val token = kotlinx.coroutines.runBlocking { configuration.accessToken() }
                if (token.isNullOrBlank()) {
                    Single.error(IllegalStateException("Mobile session is unavailable."))
                } else {
                    Single.just(token)
                }
            },
        )
        .build()
        .also { connection ->
            connection.setKeepAliveInterval(KeepAliveIntervalMillis)
            connection.setServerTimeout(ServerTimeoutMillis)
            register(connection)
        }
    @Volatile private var voiceTopology: VoiceTopology? = null

    override suspend fun connectAndRejoin() = withContext(Dispatchers.IO) {
        hub.start()
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingAwait()
        hub.invoke("Rejoin", configuration.sessionId)
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingAwait()
    }

    override suspend fun rejoin() = withContext(Dispatchers.IO) {
        hub.invoke("Rejoin", configuration.sessionId)
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingAwait()
    }

    override suspend fun voiceIceConfiguration(roomId: String): VoiceIceConfiguration = withContext(Dispatchers.IO) {
        hub.invoke(VoiceIceConfigurationDto::class.java, "GetVoiceIceConfiguration", roomId)
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingGet()
            .let { dto ->
                VoiceIceConfiguration(
                    servers = dto.servers.map { IceServer(it.urls, it.username, it.credential) },
                    expiresAtUtc = dto.expiresAtUtc,
                    policy = environmentVoiceIcePolicy(),
                )
            }
    }

    override suspend fun joinVoice(roomId: String, generation: Long): VoiceJoinResult = withContext(Dispatchers.IO) {
        hub.invoke(VoiceJoinResultDto::class.java, "JoinVoiceRoom", VoiceJoinRequestDto(roomId, generation))
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingGet()
            .let {
                voiceTopology = VoiceTopology(it.participantId, it.connectionId, it.peerParticipantId, it.peerConnectionId)
                Log.i(VoiceLogTag, "voice topology localMembership=${it.participantId} localConnection=${it.connectionId} -> remoteMembership=${it.peerParticipantId ?: "pending"} remoteConnection=${it.peerConnectionId ?: "pending"} initiator=${it.isInitiator}")
                VoiceJoinResult(
                    roomId = it.sessionId,
                    generation = it.generation,
                    participantId = it.participantId,
                    isInitiator = it.isInitiator,
                    peerPresent = it.peerPresent,
                    connectionId = it.connectionId,
                    peerParticipantId = it.peerParticipantId,
                    peerConnectionId = it.peerConnectionId,
                )
            }
    }

    override suspend fun leaveVoice(roomId: String, generation: Long) = invokeVoice("LeaveVoiceRoom", roomId, generation)
    override suspend fun voiceOffer(roomId: String, generation: Long, sessionDescription: String) =
        invokeVoice("VoiceOffer", VoiceDescriptionRequestDto(roomId, generation, sessionDescription))
    override suspend fun voiceAnswer(roomId: String, generation: Long, sessionDescription: String) =
        invokeVoice("VoiceAnswer", VoiceDescriptionRequestDto(roomId, generation, sessionDescription))
    override suspend fun voiceIceCandidate(roomId: String, generation: Long, candidate: String, sdpMid: String?, sdpMLineIndex: Int) =
        invokeVoice("VoiceIceCandidate", VoiceIceCandidateRequestDto(roomId, generation, candidate, sdpMid, sdpMLineIndex))
    override suspend fun voiceMuted(roomId: String, generation: Long, muted: Boolean) =
        invokeVoice("VoiceMuteState", VoiceMuteRequestDto(roomId, generation, muted))
    override suspend fun requestVoice(roomId: String, matchNumber: Int): VoiceConsentResult = withContext(Dispatchers.IO) {
        hub.invoke(VoiceConsentResultDto::class.java, "RequestVoice", VoiceConsentRequestDto(roomId, matchNumber))
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingGet()
            .let { VoiceConsentResult(it.sessionId, it.matchNumber, it.requestId, it.requesterMembershipId, it.recipientMembershipId, it.expiresAtUtc, it.created) }
    }
    override suspend fun acceptVoice(roomId: String, matchNumber: Int, requestId: String) =
        invokeVoice("AcceptVoice", VoiceConsentActionDto(roomId, matchNumber, requestId))
    override suspend fun declineVoice(roomId: String, matchNumber: Int, requestId: String) =
        invokeVoice("DeclineVoice", VoiceConsentActionDto(roomId, matchNumber, requestId))
    override suspend fun cancelVoiceRequest(roomId: String, matchNumber: Int, requestId: String) =
        invokeVoice("CancelVoiceRequest", VoiceConsentActionDto(roomId, matchNumber, requestId))
    override suspend fun voiceUnavailable(roomId: String, matchNumber: Int, requestId: String, reason: String) =
        invokeVoice("VoiceUnavailable", VoiceUnavailableRequestDto(roomId, matchNumber, requestId, reason))
    override suspend fun endVoice(roomId: String, matchNumber: Int, requestId: String) =
        invokeVoice("EndVoice", VoiceConsentActionDto(roomId, matchNumber, requestId))

    private suspend fun invokeVoice(method: String, vararg arguments: Any?) = withContext(Dispatchers.IO) {
        voiceTopology?.let {
            Log.i(VoiceLogTag, "voice signal send type=$method localMembership=${it.localMembershipId} localConnection=${it.localConnectionId} -> remoteMembership=${it.remoteMembershipId ?: "pending"} remoteConnection=${it.remoteConnectionId ?: "pending"}")
        }
        hub.invoke(method, *arguments)
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingAwait()
    }

    override suspend fun dispose() {
        subscriptions.forEach(Subscription::unsubscribe)
        subscriptions.clear()
        runCatching {
            withContext(Dispatchers.IO) {
                hub.stop()
                    .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
                    .blockingAwait()
            }
        }
    }

    private fun register(connection: HubConnection) {
        EventNames.forEach { eventName ->
            subscriptions += connection.on(
                eventName,
                { snapshot ->
                    if (snapshot.sessionId == configuration.sessionId) {
                        listener.onEvent(eventName, snapshot)
                    }
                },
                GameSessionSnapshot::class.java,
            )
        }
        subscriptions += connection.on("VoicePeerJoined", { value ->
            voiceTopology = voiceTopology?.copy(remoteMembershipId = value.participantId, remoteConnectionId = value.participantConnectionId)
            logReceived("VoicePeerJoined", value)
            listener.onVoiceSignal(value.toSignal(joined = true))
        }, VoicePeerEventDto::class.java)
        subscriptions += connection.on("VoicePeerLeft", { value ->
            logReceived("VoicePeerLeft", value)
            voiceTopology = voiceTopology?.copy(remoteMembershipId = null, remoteConnectionId = null)
            listener.onVoiceSignal(value.toSignal(joined = false))
        }, VoicePeerEventDto::class.java)
        subscriptions += connection.on("VoiceOffer", { value -> logReceived("VoiceOffer", value); listener.onVoiceSignal(value.toOffer()) }, VoiceDescriptionEventDto::class.java)
        subscriptions += connection.on("VoiceAnswer", { value -> logReceived("VoiceAnswer", value); listener.onVoiceSignal(value.toAnswer()) }, VoiceDescriptionEventDto::class.java)
        subscriptions += connection.on("VoiceIceCandidate", { value -> logReceived("VoiceIceCandidate", value); listener.onVoiceSignal(value.toSignal()) }, VoiceIceCandidateEventDto::class.java)
        subscriptions += connection.on("VoiceMuteState", { value -> logReceived("VoiceMuteState", value); listener.onVoiceSignal(value.toSignal()) }, VoiceMuteEventDto::class.java)
        subscriptions += connection.on("VoiceRequested", { value -> logConsent("VoiceRequested", value); listener.onVoiceConsentSignal(value.toRequested()) }, VoiceConsentEventDto::class.java)
        subscriptions += connection.on("VoiceAccepted", { value -> logConsent("VoiceAccepted", value); listener.onVoiceConsentSignal(value.toAccepted()) }, VoiceConsentEventDto::class.java)
        subscriptions += connection.on("VoiceDeclined", { value -> logConsent("VoiceDeclined", value); listener.onVoiceConsentSignal(value.toDeclined()) }, VoiceConsentEventDto::class.java)
        subscriptions += connection.on("VoiceRequestCancelled", { value -> logConsent("VoiceRequestCancelled", value); listener.onVoiceConsentSignal(value.toCancelled()) }, VoiceConsentEventDto::class.java)
        subscriptions += connection.on("VoiceRequestTimedOut", { value -> logConsent("VoiceRequestTimedOut", value); listener.onVoiceConsentSignal(value.toTimedOut()) }, VoiceConsentEventDto::class.java)
        subscriptions += connection.on("VoiceUnavailable", { value -> logConsent("VoiceUnavailable", value); listener.onVoiceConsentSignal(value.toUnavailable()) }, VoiceConsentEventDto::class.java)
        subscriptions += connection.on("VoiceEnded", { value -> logConsent("VoiceEnded", value); listener.onVoiceConsentSignal(value.toEnded()) }, VoiceConsentEventDto::class.java)
        connection.onClosed { listener.onClosed() }
    }

    private fun logReceived(type: String, value: VoiceEventIdentity) {
        Log.i(VoiceLogTag, "voice signal receive type=$type senderMembership=${value.participantId} senderConnection=${value.participantConnectionId} -> localConnection=${value.receiverConnectionId}")
    }

    private fun logConsent(type: String, value: VoiceConsentEventDto) {
        Log.i(VoiceLogTag, "voice consent type=$type requesterMembership=${value.requesterMembershipId} requesterConnection=${value.requesterConnectionId} -> recipientMembership=${value.recipientMembershipId} recipientConnection=${value.recipientConnectionId} request=${value.requestId} match=${value.matchNumber}")
    }

    private companion object {
        const val VoiceLogTag = "LammaVoiceIdentity"
        const val OperationTimeoutSeconds = 4L
        const val KeepAliveIntervalMillis = 5_000L
        // SignalR's server heartbeat defaults to 15 seconds; retain its 30-second client timeout.
        const val ServerTimeoutMillis = 30_000L
        val EventNames = listOf(
            "SessionCreated",
            "PlayerJoined",
            "PlayerReady",
            "GameStarted",
            "GameStateUpdated",
            "MoveAccepted",
            "PlayerConnectionChanged",
            "GameCompleted",
            "RematchRequested",
            "RematchAccepted",
        )
    }
}

private data class VoiceTopology(
    val localMembershipId: String,
    val localConnectionId: String,
    val remoteMembershipId: String?,
    val remoteConnectionId: String?,
)

private interface VoiceEventIdentity {
    val participantId: String
    val participantConnectionId: String
    val receiverConnectionId: String
}

private fun environmentVoiceIcePolicy(): VoiceIcePolicy =
    if (System.getProperty("lamma.voice.icePolicy") == "relay") VoiceIcePolicy.Relay else VoiceIcePolicy.All

private data class VoiceJoinRequestDto(val sessionId: String, val generation: Long)
private data class VoiceDescriptionRequestDto(val sessionId: String, val generation: Long, val sessionDescription: String)
private data class VoiceIceCandidateRequestDto(val sessionId: String, val generation: Long, val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)
private data class VoiceMuteRequestDto(val sessionId: String, val generation: Long, val muted: Boolean)
private data class VoiceConsentRequestDto(val sessionId: String, val matchNumber: Int)
private data class VoiceConsentActionDto(val sessionId: String, val matchNumber: Int, val requestId: String)
private data class VoiceUnavailableRequestDto(val sessionId: String, val matchNumber: Int, val requestId: String, val reason: String)
private data class VoiceConsentResultDto(
    val sessionId: String = "", val matchNumber: Int = 0, val requestId: String = "",
    val requesterMembershipId: String = "", val recipientMembershipId: String = "",
    val expiresAtUtc: String = "", val created: Boolean = false,
)
private data class VoiceConsentEventDto(
    val sessionId: String = "", val matchNumber: Int = 0, val requestId: String = "",
    val requesterMembershipId: String = "", val requesterConnectionId: String = "",
    val recipientMembershipId: String = "", val recipientConnectionId: String = "",
    val expiresAtUtc: String = "", val state: String = "", val reason: String? = null,
) {
    fun toRequested() = VoiceConsentSignal.Requested(sessionId, matchNumber, requestId, requesterMembershipId, requesterConnectionId, recipientMembershipId, recipientConnectionId, expiresAtUtc, reason)
    fun toAccepted() = VoiceConsentSignal.Accepted(sessionId, matchNumber, requestId, requesterMembershipId, requesterConnectionId, recipientMembershipId, recipientConnectionId, expiresAtUtc, reason)
    fun toDeclined() = VoiceConsentSignal.Declined(sessionId, matchNumber, requestId, requesterMembershipId, requesterConnectionId, recipientMembershipId, recipientConnectionId, expiresAtUtc, reason)
    fun toCancelled() = VoiceConsentSignal.Cancelled(sessionId, matchNumber, requestId, requesterMembershipId, requesterConnectionId, recipientMembershipId, recipientConnectionId, expiresAtUtc, reason)
    fun toTimedOut() = VoiceConsentSignal.TimedOut(sessionId, matchNumber, requestId, requesterMembershipId, requesterConnectionId, recipientMembershipId, recipientConnectionId, expiresAtUtc, reason)
    fun toUnavailable() = VoiceConsentSignal.Unavailable(sessionId, matchNumber, requestId, requesterMembershipId, requesterConnectionId, recipientMembershipId, recipientConnectionId, expiresAtUtc, reason)
    fun toEnded() = VoiceConsentSignal.Ended(sessionId, matchNumber, requestId, requesterMembershipId, requesterConnectionId, recipientMembershipId, recipientConnectionId, expiresAtUtc, reason)
}
private data class VoiceJoinResultDto(
    val sessionId: String = "", val generation: Long = 0, val participantId: String = "",
    val connectionId: String = "", val isInitiator: Boolean = false, val peerPresent: Boolean = false,
    val peerParticipantId: String? = null, val peerConnectionId: String? = null,
)
private data class VoiceIceServerDto(val urls: List<String> = emptyList(), val username: String? = null, val credential: String? = null)
private data class VoiceIceConfigurationDto(val servers: List<VoiceIceServerDto> = emptyList(), val expiresAtUtc: String = "")
private data class VoicePeerEventDto(
    val sessionId: String = "", val receiverGeneration: Long = 0, override val participantId: String = "",
    override val participantConnectionId: String = "", override val receiverConnectionId: String = "",
    val participantGeneration: Long = 0, val isInitiator: Boolean = false,
) : VoiceEventIdentity {
    fun toSignal(joined: Boolean): VoiceSignal = if (joined) {
        VoiceSignal.PeerJoined(sessionId, receiverGeneration, participantId, participantGeneration, isInitiator, participantConnectionId, receiverConnectionId)
    } else VoiceSignal.PeerLeft(sessionId, receiverGeneration, participantId, participantGeneration, participantConnectionId, receiverConnectionId)
}
private data class VoiceDescriptionEventDto(
    val sessionId: String = "", val receiverGeneration: Long = 0, override val participantId: String = "",
    override val participantConnectionId: String = "", override val receiverConnectionId: String = "",
    val participantGeneration: Long = 0, val sessionDescription: String = "",
) : VoiceEventIdentity {
    fun toOffer() = VoiceSignal.Offer(sessionId, receiverGeneration, participantId, participantGeneration, sessionDescription, participantConnectionId, receiverConnectionId)
    fun toAnswer() = VoiceSignal.Answer(sessionId, receiverGeneration, participantId, participantGeneration, sessionDescription, participantConnectionId, receiverConnectionId)
}
private data class VoiceIceCandidateEventDto(
    val sessionId: String = "", val receiverGeneration: Long = 0, override val participantId: String = "",
    override val participantConnectionId: String = "", override val receiverConnectionId: String = "",
    val participantGeneration: Long = 0, val candidate: String = "", val sdpMid: String? = null, val sdpMLineIndex: Int = 0,
) : VoiceEventIdentity {
    fun toSignal() = VoiceSignal.IceCandidate(sessionId, receiverGeneration, participantId, participantGeneration, candidate, sdpMid, sdpMLineIndex, participantConnectionId, receiverConnectionId)
}
private data class VoiceMuteEventDto(
    val sessionId: String = "", val receiverGeneration: Long = 0, override val participantId: String = "",
    override val participantConnectionId: String = "", override val receiverConnectionId: String = "",
    val participantGeneration: Long = 0, val muted: Boolean = false,
) : VoiceEventIdentity {
    fun toSignal() = VoiceSignal.MuteState(sessionId, receiverGeneration, participantId, participantGeneration, muted, participantConnectionId, receiverConnectionId)
}

private object AndroidGameRealtimeClients {
    private val lock = Any()
    private val clients = mutableMapOf<String, GameRealtimeClient>()

    fun get(environment: FamilyGamesEnvironment): GameRealtimeClient = synchronized(lock) {
        clients.getOrPut(environment.gamesHubUrl) {
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ManagedGameRealtimeClient(
                ownerScope = ownerScope,
                transportFactory = GameRealtimeTransportFactory { configuration, listener ->
                    SignalRGameRealtimeTransport(environment, configuration, listener)
                },
                logger = RealtimeLifecycleLogger { message -> Log.i(LogTag, message) },
            )
        }
    }

    private const val LogTag = "LammaRealtime"
}

actual fun createGameRealtimeClient(environment: FamilyGamesEnvironment): GameRealtimeClient =
    AndroidGameRealtimeClients.get(environment)
