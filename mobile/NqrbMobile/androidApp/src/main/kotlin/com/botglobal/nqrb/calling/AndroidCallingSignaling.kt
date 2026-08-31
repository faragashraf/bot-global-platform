package com.botglobal.nqrb.calling

import android.util.Log
import com.botglobal.mobile.platform.calling.CallId
import com.botglobal.mobile.platform.calling.CallParticipant
import com.botglobal.mobile.platform.calling.CallSignaling
import com.botglobal.mobile.platform.calling.CallSignalingEvent
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.calling.OutgoingCallRequest
import com.botglobal.mobile.platform.calling.StartedCall
import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.voice.IceServer
import com.botglobal.mobile.platform.voice.VoiceIceConfiguration
import com.botglobal.mobile.platform.voice.VoiceJoinResult
import com.botglobal.mobile.platform.voice.VoiceSignal
import com.botglobal.mobile.platform.voice.VoiceSignalingTransport
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import com.microsoft.signalr.Subscription
import io.reactivex.rxjava3.core.Single
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidCallingSignaling(
    apiBaseUrl: String,
    private val sessionVault: SessionVault,
    private val restoreSession: suspend () -> Boolean = { sessionVault.restore() != null },
) : CallSignaling, VoiceSignalingTransport {
    private val connectionMutex = Mutex()
    private val mutableSignals = MutableSharedFlow<VoiceSignal>(extraBufferCapacity = 64)
    private val mutableEvents = MutableSharedFlow<CallSignalingEvent>(extraBufferCapacity = 8)
    override val events = mutableEvents.asSharedFlow()
    override val signals = mutableSignals.asSharedFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var disconnectRequested = false
    private val subscriptions = mutableListOf<Subscription>()
    private val hub: HubConnection = HubConnectionBuilder
        .create("${apiBaseUrl.trimEnd('/')}/hubs/calling")
        .withAccessTokenProvider(Single.defer {
            val token = runBlocking { sessionVault.restore()?.accessToken }
            if (token.isNullOrBlank()) Single.error(IllegalStateException("Mobile session is unavailable."))
            else Single.just(token)
        })
        .build()
        .also { connection ->
            connection.setKeepAliveInterval(5_000)
            connection.setServerTimeout(30_000)
            register(connection)
        }

    override suspend fun connect() {
        disconnectRequested = false
        ensureConnected()
    }

    override suspend fun disconnect() = connectionMutex.withLock {
        disconnectRequested = true
        if (hub.connectionState != HubConnectionState.DISCONNECTED) io {
            hub.stop().timeout(OperationTimeoutSeconds, TimeUnit.SECONDS).blockingAwait()
        }
    }

    override suspend fun startOutgoing(request: OutgoingCallRequest): StartedCall {
        ensureConnected()
        return io {
            hub.invoke(StartedCallDto::class.java, "StartOutgoingCall", StartCallDto(request.callee.membershipId))
                .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
                .blockingGet()
        }.let { StartedCall(CallId(it.callId), CallParticipant(it.calleeMembershipId, it.calleeDisplayName)) }
    }

    override suspend fun receiveIncoming(callId: CallId) {
        ensureConnected()
        val value = io { hub.invoke(IncomingCallDto::class.java, "GetIncomingCall", CallIdDto(callId.value)).timeout(OperationTimeoutSeconds, TimeUnit.SECONDS).blockingGet() }
        mutableEvents.emit(CallSignalingEvent.IncomingOffered(CallId(value.callId), value.applicationContext,
            CallParticipant(value.callerMembershipId, value.callerDisplayName)))
    }

    override suspend fun answer(callId: CallId) = invoke("AnswerIncomingCall", CallIdDto(callId.value))
    override suspend fun reject(callId: CallId) = invoke("RejectIncomingCall", CallIdDto(callId.value))

    override suspend fun end(callId: CallId, reason: CallTerminationReason) {
        if (hub.connectionState == HubConnectionState.CONNECTED) invoke(
            "EndCall", EndCallDto(callId.value, reason.name.lowercase()),
        )
    }

    override suspend fun iceConfiguration(roomId: String): VoiceIceConfiguration {
        ensureConnected()
        return io {
            hub.invoke(IceConfigurationDto::class.java, "GetCallIceConfiguration", roomId)
                .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
                .blockingGet()
        }.let { configuration ->
            VoiceIceConfiguration(
                configuration.servers.map { IceServer(it.urls, it.username, it.credential) },
                configuration.expiresAtUtc,
            )
        }
    }

    override suspend fun join(roomId: String, generation: Long): VoiceJoinResult {
        ensureConnected()
        return io {
            hub.invoke(JoinCallResultDto::class.java, "JoinCall", JoinCallDto(roomId, generation))
                .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
                .blockingGet()
        }.let {
            VoiceJoinResult(
                it.callId, it.generation, it.participantId, it.isInitiator, it.peerPresent,
                it.connectionId, it.peerParticipantId, it.peerConnectionId,
            )
        }
    }

    override suspend fun leave(roomId: String, generation: Long) = Unit
    override suspend fun offer(roomId: String, generation: Long, sessionDescription: String) =
        invoke("CallOffer", DescriptionDto(roomId, generation, sessionDescription))
    override suspend fun answer(roomId: String, generation: Long, sessionDescription: String) =
        invoke("CallAnswer", DescriptionDto(roomId, generation, sessionDescription))
    override suspend fun iceCandidate(roomId: String, generation: Long, candidate: String, sdpMid: String?, sdpMLineIndex: Int) =
        invoke("CallIceCandidate", IceCandidateDto(roomId, generation, candidate, sdpMid, sdpMLineIndex))
    override suspend fun muted(roomId: String, generation: Long, muted: Boolean) =
        invoke("CallMuteState", MuteDto(roomId, generation, muted))

    private suspend fun ensureConnected() {
        connectionMutex.withLock {
            if (hub.connectionState == HubConnectionState.CONNECTED) return@withLock
            if (!restoreSession()) throw IllegalStateException("Mobile session is unavailable.")
            io { hub.start().timeout(OperationTimeoutSeconds, TimeUnit.SECONDS).blockingAwait() }
            Log.i(LogTag, "calling realtime connected")
        }
    }

    private suspend fun invoke(method: String, argument: Any) {
        ensureConnected()
        io { hub.invoke(method, argument).timeout(OperationTimeoutSeconds, TimeUnit.SECONDS).blockingAwait() }
    }

    private fun register(connection: HubConnection) {
        subscriptions += connection.on("CallPeerJoined", { value -> mutableSignals.tryEmit(value.toJoined()) }, PeerEventDto::class.java)
        subscriptions += connection.on("CallOffered", { value ->
            mutableEvents.tryEmit(
                CallSignalingEvent.IncomingOffered(
                    CallId(value.callId),
                    value.applicationContext,
                    CallParticipant(value.callerMembershipId, value.callerDisplayName),
                ),
            )
        }, CallOfferedDto::class.java)
        subscriptions += connection.on("CallPeerLeft", { value -> mutableSignals.tryEmit(value.toLeft()) }, PeerEventDto::class.java)
        subscriptions += connection.on("CallOffer", { value -> mutableSignals.tryEmit(value.toOffer()) }, DescriptionEventDto::class.java)
        subscriptions += connection.on("CallAnswer", { value -> mutableSignals.tryEmit(value.toAnswer()) }, DescriptionEventDto::class.java)
        subscriptions += connection.on("CallIceCandidate", { value -> mutableSignals.tryEmit(value.toSignal()) }, IceCandidateEventDto::class.java)
        subscriptions += connection.on("CallMuteState", { value -> mutableSignals.tryEmit(value.toSignal()) }, MuteEventDto::class.java)
        subscriptions += connection.on("CallEnded", { value ->
            Log.i(LogTag, "call ended by peer reason=${value.reason}")
            val callId = CallId(value.callId)
            mutableEvents.tryEmit(when (value.reason) {
                "cancelled" -> CallSignalingEvent.Cancelled(callId)
                "rejected" -> CallSignalingEvent.Rejected(callId)
                "expired" -> CallSignalingEvent.Expired(callId)
                else -> CallSignalingEvent.RemoteEnded(callId)
            })
        }, CallEndedDto::class.java)
        subscriptions += connection.on("CallRejected", { value -> mutableEvents.tryEmit(CallSignalingEvent.Rejected(CallId(value.callId))) }, CallStateDto::class.java)
        connection.onClosed { error ->
            Log.i(LogTag, "calling realtime closed error=${error?.javaClass?.simpleName ?: "none"}")
            if (!disconnectRequested) {
                mutableEvents.tryEmit(CallSignalingEvent.Interrupted)
                scope.launch {
                    for (attempt in 1..5) {
                        delay(attempt * 1_000L)
                        if (runCatching { ensureConnected() }.isSuccess) {
                            mutableEvents.emit(CallSignalingEvent.Recovered)
                            break
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val LogTag = "NqrbCalling"
        const val OperationTimeoutSeconds = 5L
    }
}

private data class StartCallDto(val calleeMembershipId: String)
private data class CallIdDto(val callId: String)
private data class IncomingCallDto(
    val callId: String = "", val applicationContext: String = "", val callerMembershipId: String = "",
    val callerDisplayName: String = "", val expiresAtUtc: String = "",
)
private data class CallStateDto(val callId: String = "", val state: String = "")
private data class StartedCallDto(val callId: String = "", val calleeMembershipId: String = "", val calleeDisplayName: String = "")
private data class CallOfferedDto(
    val callId: String = "", val applicationContext: String = "",
    val callerMembershipId: String = "", val callerDisplayName: String = "",
)
private data class JoinCallDto(val callId: String, val generation: Long)
private data class JoinCallResultDto(
    val callId: String = "", val generation: Long = 0, val participantId: String = "",
    val connectionId: String = "", val isInitiator: Boolean = false, val peerPresent: Boolean = false,
    val peerParticipantId: String? = null, val peerConnectionId: String? = null,
)
private data class DescriptionDto(val callId: String, val generation: Long, val sessionDescription: String)
private data class IceCandidateDto(val callId: String, val generation: Long, val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)
private data class MuteDto(val callId: String, val generation: Long, val muted: Boolean)
private data class EndCallDto(val callId: String, val reason: String)
private data class CallEndedDto(val callId: String = "", val reason: String = "")
private data class IceServerDto(val urls: List<String> = emptyList(), val username: String? = null, val credential: String? = null)
private data class IceConfigurationDto(val servers: List<IceServerDto> = emptyList(), val expiresAtUtc: String = "")
private data class PeerEventDto(
    val callId: String = "", val receiverGeneration: Long = 0, val participantId: String = "",
    val participantConnectionId: String = "", val receiverConnectionId: String = "",
    val participantGeneration: Long = 0, val isInitiator: Boolean = false,
) {
    fun toJoined() = VoiceSignal.PeerJoined(callId, receiverGeneration, participantId, participantGeneration, isInitiator, participantConnectionId, receiverConnectionId)
    fun toLeft() = VoiceSignal.PeerLeft(callId, receiverGeneration, participantId, participantGeneration, participantConnectionId, receiverConnectionId)
}
private data class DescriptionEventDto(
    val callId: String = "", val receiverGeneration: Long = 0, val participantId: String = "",
    val participantConnectionId: String = "", val receiverConnectionId: String = "",
    val participantGeneration: Long = 0, val sessionDescription: String = "",
) {
    fun toOffer() = VoiceSignal.Offer(callId, receiverGeneration, participantId, participantGeneration, sessionDescription, participantConnectionId, receiverConnectionId)
    fun toAnswer() = VoiceSignal.Answer(callId, receiverGeneration, participantId, participantGeneration, sessionDescription, participantConnectionId, receiverConnectionId)
}
private data class IceCandidateEventDto(
    val callId: String = "", val receiverGeneration: Long = 0, val participantId: String = "",
    val participantConnectionId: String = "", val receiverConnectionId: String = "",
    val participantGeneration: Long = 0, val candidate: String = "", val sdpMid: String? = null, val sdpMLineIndex: Int = 0,
) {
    fun toSignal() = VoiceSignal.IceCandidate(callId, receiverGeneration, participantId, participantGeneration, candidate, sdpMid, sdpMLineIndex, participantConnectionId, receiverConnectionId)
}
private data class MuteEventDto(
    val callId: String = "", val receiverGeneration: Long = 0, val participantId: String = "",
    val participantConnectionId: String = "", val receiverConnectionId: String = "",
    val participantGeneration: Long = 0, val muted: Boolean = false,
) {
    fun toSignal() = VoiceSignal.MuteState(callId, receiverGeneration, participantId, participantGeneration, muted, participantConnectionId, receiverConnectionId)
}
