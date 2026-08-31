package com.botglobal.mobile.platform.calling

import com.botglobal.mobile.platform.voice.VoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot
import com.botglobal.mobile.platform.voice.VoiceRoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CallId(val value: String)

data class CallParticipant(val membershipId: String, val displayName: String)

enum class CallDirection { Outgoing, Incoming }

enum class CallState { Idle, Preparing, Connecting, Ringing, Answering, Active, Reconnecting, Ending, Rejected, Cancelled, Missed, Expired, Ended, Failed }

enum class CallAudioRoute { System, Earpiece, Speaker, WiredHeadset, Bluetooth }

enum class CallTerminationReason { Local, Remote, Rejected, Busy, Cancelled, Missed, Expired, Failed }

data class CallNetworkUsage(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val measurementAvailable: Boolean = false,
    val isFinal: Boolean = false,
    val connectedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val connectedDurationSeconds: Long? = null,
) {
    val totalBytes: Long get() = bytesSent + bytesReceived

    /** Average WebRTC media bytes per connected minute; unavailable without measured media or duration. */
    val bytesPerConnectedMinute: Double?
        get() {
            val durationSeconds = connectedDurationSeconds ?: return null
            if (!measurementAvailable || durationSeconds <= 0) return null
            return totalBytes.toDouble() * 60.0 / durationSeconds.toDouble()
        }
}

data class CallMediaState(
    val muted: Boolean = false,
    val route: CallAudioRoute = CallAudioRoute.System,
    val availableRoutes: Set<CallAudioRoute> = emptySet(),
)

fun CallMediaState.speakerControlTarget(): CallAudioRoute? = when {
    route != CallAudioRoute.Speaker && CallAudioRoute.Speaker in availableRoutes -> CallAudioRoute.Speaker
    route == CallAudioRoute.Speaker -> listOf(
        CallAudioRoute.Earpiece,
        CallAudioRoute.WiredHeadset,
        CallAudioRoute.Bluetooth,
        CallAudioRoute.System,
    ).firstOrNull(availableRoutes::contains)
    else -> null
}

data class CallSessionSnapshot(
    val callId: CallId? = null,
    val applicationContext: String? = null,
    val participant: CallParticipant? = null,
    val direction: CallDirection? = null,
    val state: CallState = CallState.Idle,
    val media: CallMediaState = CallMediaState(),
    val activeSinceEpochMillis: Long? = null,
    val elapsedSeconds: Long = 0,
    val terminationReason: CallTerminationReason? = null,
    val networkUsage: CallNetworkUsage = CallNetworkUsage(),
    val error: String? = null,
)

data class OutgoingCallRequest(
    val applicationContext: String,
    val callee: CallParticipant,
)

data class StartedCall(val callId: CallId, val participant: CallParticipant)

sealed interface CallSignalingEvent {
    data object Interrupted : CallSignalingEvent
    data object Recovered : CallSignalingEvent
    data class RemoteEnded(val callId: CallId) : CallSignalingEvent
    data class Cancelled(val callId: CallId) : CallSignalingEvent
    data class Rejected(val callId: CallId) : CallSignalingEvent
    data class Expired(val callId: CallId) : CallSignalingEvent
    data class IncomingOffered(
        val callId: CallId,
        val applicationContext: String,
        val caller: CallParticipant,
    ) : CallSignalingEvent
}

sealed interface StartCallResult {
    data class Started(val callId: CallId) : StartCallResult
    data object ActiveCallExists : StartCallResult
    data class Failed(val reason: String) : StartCallResult
}

interface CallSignaling {
    val events: Flow<CallSignalingEvent> get() = emptyFlow()
    suspend fun connect() = Unit
    suspend fun disconnect() = Unit
    suspend fun startOutgoing(request: OutgoingCallRequest): StartedCall
    suspend fun receiveIncoming(callId: CallId) = Unit
    suspend fun answer(callId: CallId) = Unit
    suspend fun reject(callId: CallId) = Unit
    suspend fun end(callId: CallId, reason: CallTerminationReason)
}

sealed interface CallPlatformAction {
    data object Answer : CallPlatformAction
    data object Reject : CallPlatformAction
    data object End : CallPlatformAction
    data class RouteChanged(val route: CallAudioRoute) : CallPlatformAction
    data class AvailableRoutesChanged(val routes: Set<CallAudioRoute>) : CallPlatformAction
}

interface CallPlatformLifecycle {
    val actions: Flow<CallPlatformAction> get() = emptyFlow()
    suspend fun start(callId: CallId, participant: CallParticipant, direction: CallDirection)
    suspend fun markActive()
    suspend fun requestRoute(route: CallAudioRoute): CallAudioRoute
    suspend fun end(reason: CallTerminationReason)
}

object UnavailableCallPlatformLifecycle : CallPlatformLifecycle {
    override suspend fun start(callId: CallId, participant: CallParticipant, direction: CallDirection) = Unit
    override suspend fun markActive() = Unit
    override suspend fun requestRoute(route: CallAudioRoute) = CallAudioRoute.System
    override suspend fun end(reason: CallTerminationReason) = Unit
}

class CallSessionController(
    private val scope: CoroutineScope,
    private val signaling: CallSignaling,
    private val voice: VoiceRoomController,
    private val platform: CallPlatformLifecycle = UnavailableCallPlatformLifecycle,
    private val nowEpochMillis: () -> Long = { 0L },
    private val logger: (String) -> Unit = {},
) {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(CallSessionSnapshot())
    private var durationJob: Job? = null
    private val usage = CallNetworkUsageAccumulator()
    val state: StateFlow<CallSessionSnapshot> = mutableState.asStateFlow()

    init {
        scope.launch { voice.snapshot.collect(::onVoiceChanged) }
        scope.launch {
            platform.actions.collect { action ->
                logger("call platform action=${action::class.simpleName}")
                when (action) {
                    CallPlatformAction.Answer -> acceptIncoming()
                    CallPlatformAction.Reject -> rejectIncoming()
                    CallPlatformAction.End -> end(CallTerminationReason.Local)
                    is CallPlatformAction.RouteChanged -> if (mutableState.value.state in ActiveStates) {
                        updateMedia(route = action.route)
                    }
                    is CallPlatformAction.AvailableRoutesChanged -> if (mutableState.value.state in ActiveStates) {
                        updateMedia(availableRoutes = action.routes)
                    }
                }
            }
        }
        scope.launch {
            signaling.events.collect { event ->
                when (event) {
                    CallSignalingEvent.Interrupted -> signalingInterrupted()
                    CallSignalingEvent.Recovered -> signalingRecovered()
                    is CallSignalingEvent.RemoteEnded -> if (event.callId == state.value.callId) {
                        end(CallTerminationReason.Remote)
                    }
                    is CallSignalingEvent.Cancelled -> terminalFromRemote(event.callId, CallState.Cancelled, CallTerminationReason.Cancelled)
                    is CallSignalingEvent.Rejected -> terminalFromRemote(event.callId, CallState.Rejected, CallTerminationReason.Rejected)
                    is CallSignalingEvent.Expired -> terminalFromRemote(event.callId, CallState.Expired, CallTerminationReason.Expired)
                    is CallSignalingEvent.IncomingOffered -> onIncomingOffered(event)
                }
            }
        }
    }

    suspend fun connectSignaling() = signaling.connect()
    suspend fun receiveIncoming(callId: CallId) = signaling.receiveIncoming(callId)
    suspend fun dismissIncoming(callId: CallId, reason: CallTerminationReason) {
        val terminal = when (reason) {
            CallTerminationReason.Cancelled -> CallState.Cancelled
            CallTerminationReason.Expired, CallTerminationReason.Missed -> CallState.Expired
            else -> CallState.Ended
        }
        terminalFromRemote(callId, terminal, reason)
    }

    suspend fun disconnectSignaling() {
        if (mutableState.value.state in ActiveStates) end(CallTerminationReason.Local)
        signaling.disconnect()
    }

    suspend fun start(request: OutgoingCallRequest): StartCallResult = operation.withLock {
        if (mutableState.value.state in ActiveStates) return StartCallResult.ActiveCallExists
        var startedCallId: CallId? = null
        usage.reset()
        mutableState.value = CallSessionSnapshot(
            applicationContext = request.applicationContext,
            participant = request.callee,
            direction = CallDirection.Outgoing,
            state = CallState.Preparing,
        )
        try {
            val started = signaling.startOutgoing(request)
            startedCallId = started.callId
            mutableState.value = mutableState.value.copy(
                callId = started.callId,
                participant = started.participant,
                state = CallState.Connecting,
            )
            platform.start(started.callId, started.participant, CallDirection.Outgoing)
            voice.join(started.callId.value)
            logger("call state=connecting")
            StartCallResult.Started(started.callId)
        } catch (error: Throwable) {
            startedCallId?.let { callId ->
                runCatching { signaling.end(callId, CallTerminationReason.Failed) }
            }
            runCatching { platform.end(CallTerminationReason.Failed) }
            mutableState.value = mutableState.value.copy(
                state = CallState.Failed,
                terminationReason = CallTerminationReason.Failed,
                media = CallMediaState(),
                networkUsage = usage.finish(nowEpochMillis()),
                error = "call_start_failed",
            )
            logger("call state=failed phase=start type=${error::class.simpleName}")
            StartCallResult.Failed("call_start_failed")
        }
    }

    suspend fun acceptIncoming(): StartCallResult = operation.withLock {
        val current = mutableState.value
        val callId = current.callId
        val participant = current.participant
        if (current.direction != CallDirection.Incoming || current.state != CallState.Ringing ||
            callId == null || participant == null) return StartCallResult.Failed("call_offer_unavailable")
        return try {
            mutableState.value = current.copy(state = CallState.Answering)
            logger("call state=answering")
            signaling.answer(callId)
            mutableState.value = mutableState.value.copy(state = CallState.Connecting)
            voice.join(callId.value)
            StartCallResult.Started(callId)
        } catch (error: Throwable) {
            runCatching { signaling.end(callId, CallTerminationReason.Failed) }
            runCatching { platform.end(CallTerminationReason.Failed) }
            mutableState.value = current.copy(
                state = CallState.Failed,
                terminationReason = CallTerminationReason.Failed,
                media = CallMediaState(),
                networkUsage = usage.finish(nowEpochMillis()),
                error = "call_accept_failed",
            )
            StartCallResult.Failed("call_accept_failed")
        }
    }

    suspend fun rejectIncoming(): Unit = operation.withLock {
        val current = mutableState.value
        val callId = current.callId ?: return
        if (current.direction != CallDirection.Incoming || current.state != CallState.Ringing) return
        runCatching { signaling.reject(callId) }
        runCatching { platform.end(CallTerminationReason.Rejected) }
        mutableState.value = current.copy(
            state = CallState.Rejected,
            terminationReason = CallTerminationReason.Rejected,
            media = CallMediaState(),
            networkUsage = usage.finish(nowEpochMillis()),
        )
    }

    suspend fun setMuted(muted: Boolean): Unit = operation.withLock {
        if (mutableState.value.state !in ActiveStates) return
        voice.setMuted(muted)
        updateMedia(muted = muted)
        logger("call muted=$muted")
    }

    suspend fun requestRoute(route: CallAudioRoute): Unit = operation.withLock {
        if (mutableState.value.state !in ActiveStates) return
        val applied = platform.requestRoute(route)
        updateMedia(route = applied)
        logger("call route=${applied.name.lowercase()}")
    }

    suspend fun signalingInterrupted(): Unit = operation.withLock {
        if (mutableState.value.state !in ActiveStates) return
        voice.signalingInterrupted()
        mutableState.value = mutableState.value.copy(state = CallState.Reconnecting)
    }

    suspend fun signalingRecovered(): Unit = operation.withLock {
        if (mutableState.value.state != CallState.Reconnecting) return
        voice.signalingRecovered()
    }

    suspend fun end(reason: CallTerminationReason = CallTerminationReason.Local): Unit = operation.withLock {
        val current = mutableState.value
        if (current.state !in ActiveStates) return
        val endedAtEpochMillis = nowEpochMillis()
        mutableState.value = current.copy(state = CallState.Ending)
        runCatching { voice.leave() }
        if (reason != CallTerminationReason.Remote) {
            current.callId?.let { callId -> runCatching { signaling.end(callId, reason) } }
        }
        runCatching { platform.end(reason) }
        durationJob?.cancel()
        durationJob = null
        mutableState.value = current.copy(
            state = CallState.Ended,
            terminationReason = reason,
            media = CallMediaState(),
            networkUsage = usage.finish(endedAtEpochMillis),
        )
        logger("call state=ended reason=${reason.name.lowercase()}")
    }

    private fun onVoiceChanged(voice: VoiceRoomSnapshot) {
        val current = mutableState.value
        if (current.state !in ActiveStates) return
        val next = when (voice.state) {
            VoiceRoomState.Idle, VoiceRoomState.PermissionRequired -> current.state
            VoiceRoomState.Joining, VoiceRoomState.Negotiating -> CallState.Connecting
            VoiceRoomState.WaitingForPeer -> CallState.Ringing
            VoiceRoomState.Connected -> CallState.Active
            VoiceRoomState.Reconnecting -> CallState.Reconnecting
            VoiceRoomState.Failed, VoiceRoomState.Unavailable -> CallState.Failed
        }
        val becameActive = next == CallState.Active && current.state != CallState.Active
        val activeTransitionAtEpochMillis = if (becameActive) nowEpochMillis() else null
        val activeSinceEpochMillis = if (becameActive) {
            current.activeSinceEpochMillis ?: activeTransitionAtEpochMillis
        } else {
            current.activeSinceEpochMillis
        }
        if (activeTransitionAtEpochMillis != null) {
            usage.markConnected(activeTransitionAtEpochMillis)
        } else if (current.state == CallState.Active && next != CallState.Active) {
            usage.markMediaInactive(nowEpochMillis())
        }
        mutableState.value = current.copy(
            state = next,
            media = current.media.copy(muted = voice.muted),
            activeSinceEpochMillis = activeSinceEpochMillis,
            terminationReason = if (next == CallState.Failed) CallTerminationReason.Failed else current.terminationReason,
            error = if (next == CallState.Failed) voice.error ?: "call_media_failed" else current.error,
            networkUsage = usage.update(voice),
        )
        if (becameActive) {
            logger("call state=active")
            durationJob?.cancel()
            durationJob = scope.launch {
                while (true) {
                    delay(1_000)
                    val activeSince = mutableState.value.activeSinceEpochMillis ?: break
                    mutableState.value = mutableState.value.copy(
                        elapsedSeconds = ((nowEpochMillis() - activeSince) / 1_000).coerceAtLeast(0),
                    )
                }
            }
            // Telecom state changes are serialized by its own transaction scope.
            scope.launch { platform.markActive() }
        }
        if (next == CallState.Failed && current.state != CallState.Failed) {
            scope.launch { releaseFailedCall() }
        }
    }

    private suspend fun releaseFailedCall() = operation.withLock {
        val failed = mutableState.value
        runCatching { voice.leave() }
        failed.callId?.let { callId -> runCatching { signaling.end(callId, CallTerminationReason.Failed) } }
        runCatching { platform.end(CallTerminationReason.Failed) }
        durationJob?.cancel()
        durationJob = null
        mutableState.value = failed.copy(
            media = CallMediaState(),
            networkUsage = usage.finish(nowEpochMillis()),
        )
    }

    private fun onIncomingOffered(event: CallSignalingEvent.IncomingOffered) {
        val current = mutableState.value
        if (current.callId == event.callId) {
            logger("duplicate incoming call ignored")
            return
        }
        if (current.state in ActiveStates) {
            scope.launch { runCatching { signaling.end(event.callId, CallTerminationReason.Busy) } }
            return
        }
        mutableState.value = CallSessionSnapshot(
            callId = event.callId,
            applicationContext = event.applicationContext,
            participant = event.caller,
            direction = CallDirection.Incoming,
            state = CallState.Ringing,
        )
        usage.reset()
        scope.launch {
            runCatching { platform.start(event.callId, event.caller, CallDirection.Incoming) }
                .onFailure { terminalFromRemote(event.callId, CallState.Failed, CallTerminationReason.Failed) }
        }
    }

    private suspend fun terminalFromRemote(callId: CallId, state: CallState, reason: CallTerminationReason): Unit = operation.withLock {
        val current = mutableState.value
        if (current.callId != callId || current.state !in ActiveStates) return
        runCatching { voice.leave() }
        runCatching { platform.end(reason) }
        durationJob?.cancel()
        durationJob = null
        mutableState.value = current.copy(
            state = state,
            terminationReason = reason,
            media = CallMediaState(),
            networkUsage = usage.finish(nowEpochMillis()),
        )
    }

    private fun updateMedia(
        muted: Boolean = mutableState.value.media.muted,
        route: CallAudioRoute = mutableState.value.media.route,
        availableRoutes: Set<CallAudioRoute> = mutableState.value.media.availableRoutes,
    ) {
        mutableState.value = mutableState.value.copy(media = CallMediaState(muted, route, availableRoutes))
    }

    private companion object {
        val ActiveStates = setOf(
            CallState.Preparing,
            CallState.Connecting,
            CallState.Ringing,
            CallState.Answering,
            CallState.Active,
            CallState.Reconnecting,
            CallState.Ending,
        )
    }
}
