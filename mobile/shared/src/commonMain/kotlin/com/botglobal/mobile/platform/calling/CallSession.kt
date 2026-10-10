package com.botglobal.mobile.platform.calling

import com.botglobal.mobile.platform.voice.VoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot
import com.botglobal.mobile.platform.voice.VoiceRoomState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CallId(val value: String)

data class CallParticipant(val membershipId: String, val displayName: String)

enum class CallDirection { Outgoing, Incoming }

enum class CallState { Idle, Preparing, Connecting, Ringing, Answering, Active, Reconnecting, Ending, Rejected, Cancelled, Missed, Expired, Ended, Failed }

enum class CallAudioRoute { System, Earpiece, Speaker, WiredHeadset, Bluetooth }

enum class CallTerminationReason { Local, Remote, Rejected, Busy, Cancelled, Missed, Expired, Failed }

enum class CallDeliveryState { Attempting, Presented, Answered, Terminal, Unknown }

data class CallDeliveryStatus(
    val callId: CallId,
    val state: CallDeliveryState = CallDeliveryState.Unknown,
    val revision: Long = 0,
    val updatedAtEpochMillis: Long? = null,
    val terminal: Boolean = false,
    val wasPresented: Boolean = false,
    val terminalReason: String? = null,
)

private fun CallDeliveryStatus?.permitsWaitingRingback(): Boolean =
    this?.state == CallDeliveryState.Presented

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
    val delivery: CallDeliveryStatus? = null,
)

data class OutgoingCallRequest(
    val applicationContext: String,
    val callee: CallParticipant,
)

data class StartedCall(
    val callId: CallId,
    val participant: CallParticipant,
    val delivery: CallDeliveryStatus? = null,
)

sealed interface CallSignalingEvent {
    data object Interrupted : CallSignalingEvent
    data object Recovered : CallSignalingEvent
    data class RemoteEnded(val callId: CallId) : CallSignalingEvent
    data class Cancelled(val callId: CallId) : CallSignalingEvent
    data class Rejected(val callId: CallId) : CallSignalingEvent
    data class Expired(val callId: CallId) : CallSignalingEvent
    data class DeliveryUpdated(val status: CallDeliveryStatus) : CallSignalingEvent
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
    suspend fun confirmIncomingReceipt(callId: CallId) = Unit
    suspend fun deliveryStatus(callId: CallId): CallDeliveryStatus? = null
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
    suspend fun presentIncoming(callId: CallId, participant: CallParticipant) {
        start(callId, participant, CallDirection.Incoming)
    }
    suspend fun start(callId: CallId, participant: CallParticipant, direction: CallDirection)
    fun setRingback(active: Boolean) = Unit
    suspend fun markActive()
    suspend fun requestRoute(route: CallAudioRoute): CallAudioRoute
    suspend fun end(reason: CallTerminationReason)
}

object UnavailableCallPlatformLifecycle : CallPlatformLifecycle {
    override suspend fun presentIncoming(callId: CallId, participant: CallParticipant) = Unit
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
    private val requirePresentationForRingback: Boolean = false,
) {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(CallSessionSnapshot())
    private val presentedIncomingCallId = MutableStateFlow<CallId?>(null)
    private var durationJob: Job? = null
    private val usage = CallNetworkUsageAccumulator()
    private var transitionGeneration = 0L
    private var voiceGeneration: Long? = null
    private var failedCleanupJob: Job? = null
    private var receiptInFlight: CallId? = null
    private var confirmedPresentation: CallId? = null
    val state: StateFlow<CallSessionSnapshot> = mutableState.asStateFlow()

    init {
        scope.launch { voice.snapshot.collect { snapshot -> operation.withLock { onVoiceChangedLocked(snapshot) } } }
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
                    is CallSignalingEvent.DeliveryUpdated -> mergeDelivery(event.status)
                    is CallSignalingEvent.IncomingOffered -> onIncomingOffered(event)
                }
            }
        }
    }

    suspend fun connectSignaling() = signaling.connect()
    suspend fun receiveIncoming(callId: CallId) {
        if (presentedIncomingCallId.value != callId) {
            signaling.receiveIncoming(callId)
            // Keep the FCM callback alive until the system notification and Telecom
            // presentation have completed, rather than only queueing the event.
            withTimeout(6_000) { presentedIncomingCallId.first { it == callId } }
        }
    }
    suspend fun dismissIncoming(callId: CallId, reason: CallTerminationReason) {
        // A terminal push is sent to every device on the callee account. The device
        // that answered must keep its connected call while sibling devices clear ringing.
        val current = mutableState.value
        if (current.callId != callId || current.direction != CallDirection.Incoming ||
            current.state != CallState.Ringing) return
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

    suspend fun start(request: OutgoingCallRequest): StartCallResult {
        failedCleanupJob?.join()
        val attempt = operation.withLock {
            if (mutableState.value.state in ActiveStates) return StartCallResult.ActiveCallExists
            transitionGeneration += 1
            voiceGeneration = Long.MIN_VALUE
            usage.reset()
            mutableState.value = CallSessionSnapshot(
                applicationContext = request.applicationContext,
                participant = request.callee,
                direction = CallDirection.Outgoing,
                state = CallState.Preparing,
            )
            transitionGeneration
        }
        var startedCallId: CallId? = null
        val startResult = CompletableDeferred<StartedCall>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { signaling.startOutgoing(request) }
                .onSuccess { startResult.complete(it) }
                .onFailure { startResult.completeExceptionally(it) }
        }
        try {
            // Do not let cancellation discard a successfully-created server call id.
            // Once the bounded start returns, the cancelled attempt is fenced below and
            // explicitly ended before the original cancellation is rethrown.
            val started = withContext(NonCancellable) {
                startResult.await().also { startedCallId = it.callId }
            }
            currentCoroutineContext().ensureActive()
            val adopted = operation.withLock {
                if (attempt != transitionGeneration || mutableState.value.state != CallState.Preparing) false
                else {
                mutableState.value = mutableState.value.copy(
                    callId = started.callId,
                    participant = started.participant,
                    state = CallState.Connecting,
                    delivery = started.delivery,
                )
                    true
                }
            }
            if (!adopted) {
                withContext(NonCancellable) { runCatching { signaling.end(started.callId, CallTerminationReason.Cancelled) } }
                return StartCallResult.Failed("call_start_cancelled")
            }

            platform.start(started.callId, started.participant, CallDirection.Outgoing)
            if (!owns(attempt, started.callId)) {
                withContext(NonCancellable) { cleanupDetachedStart(started.callId, CallTerminationReason.Cancelled) }
                return StartCallResult.Failed("call_start_cancelled")
            }
            voice.join(started.callId.value)
            operation.withLock {
                if (attempt == transitionGeneration && mutableState.value.callId == started.callId) {
                    voiceGeneration = voice.snapshot.value.generation
                }
            }
            if (!owns(attempt, started.callId)) {
                withContext(NonCancellable) { cleanupDetachedStart(started.callId, CallTerminationReason.Cancelled) }
                return StartCallResult.Failed("call_start_cancelled")
            }
            // Delivery status is an additive protocol. An older server or a
            // transient query failure must not tear down an otherwise valid call.
            runCatching { signaling.deliveryStatus(started.callId) }.getOrNull()?.let { mergeDelivery(it) }
            logger("call state=connecting")
            return if (owns(attempt, started.callId)) StartCallResult.Started(started.callId)
            else StartCallResult.Failed("call_start_cancelled")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val callId = startedCallId ?: runCatching { startResult.await().callId }.getOrNull()
                callId?.let { runCatching { signaling.end(it, CallTerminationReason.Cancelled) } }
                runCatching { voice.leave() }
                runCatching { platform.end(CallTerminationReason.Cancelled) }
                operation.withLock {
                    if (attempt == transitionGeneration) {
                        transitionGeneration += 1
                        mutableState.value = mutableState.value.copy(
                            state = CallState.Cancelled,
                            terminationReason = CallTerminationReason.Cancelled,
                            media = CallMediaState(),
                            networkUsage = usage.finish(nowEpochMillis()),
                            error = "call_start_cancelled",
                        )
                    }
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                val callId = startedCallId ?: runCatching { startResult.await().callId }.getOrNull()
                callId?.let { runCatching { signaling.end(it, CallTerminationReason.Failed) } }
                runCatching { voice.leave() }
                runCatching { platform.end(CallTerminationReason.Failed) }
                operation.withLock {
                    if (attempt == transitionGeneration) {
                        transitionGeneration += 1
                        mutableState.value = mutableState.value.copy(
                            state = CallState.Failed,
                            terminationReason = CallTerminationReason.Failed,
                            media = CallMediaState(),
                            networkUsage = usage.finish(nowEpochMillis()),
                            error = safeCallError(error),
                        )
                    }
                }
            }
            logger("call state=failed phase=start type=${error::class.simpleName}")
            return StartCallResult.Failed(safeCallError(error))
        }
    }

    private suspend fun owns(attempt: Long, callId: CallId): Boolean = operation.withLock {
        attempt == transitionGeneration && mutableState.value.callId == callId &&
            mutableState.value.state in ActiveStates
    }

    private suspend fun cleanupDetachedStart(callId: CallId, reason: CallTerminationReason) {
        runCatching { voice.leave() }
        runCatching { signaling.end(callId, reason) }
        runCatching { platform.end(reason) }
    }

    private data class IncomingPlan(
        val snapshot: CallSessionSnapshot,
        val callId: CallId,
        val participant: CallParticipant,
        val generation: Long,
    )

    private data class TerminalPlan(
        val snapshot: CallSessionSnapshot,
        val callId: CallId?,
        val generation: Long,
    )

    private data class FailurePlan(
        val callId: CallId?,
        val generation: Long,
        val voiceGeneration: Long,
        val snapshot: CallSessionSnapshot,
    )

    suspend fun acceptIncoming(): StartCallResult {
        val plan = operation.withLock {
            val current = mutableState.value
            val callId = current.callId
            val participant = current.participant
            if (current.direction != CallDirection.Incoming || current.state != CallState.Ringing ||
                callId == null || participant == null) return StartCallResult.Failed("call_offer_unavailable")
            mutableState.value = current.copy(state = CallState.Answering)
            logger("call state=answering")
            IncomingPlan(current, callId, participant, transitionGeneration)
        }
        return try {
            signaling.answer(plan.callId)
            if (!owns(plan.generation, plan.callId)) return StartCallResult.Failed("call_accept_cancelled")
            platform.start(plan.callId, plan.participant, CallDirection.Incoming)
            val connecting = operation.withLock {
                if (plan.generation != transitionGeneration || mutableState.value.callId != plan.callId ||
                    mutableState.value.state != CallState.Answering) false
                else {
                    mutableState.value = mutableState.value.copy(state = CallState.Connecting)
                    true
                }
            }
            if (!connecting) return StartCallResult.Failed("call_accept_cancelled")
            voice.join(plan.callId.value)
            operation.withLock {
                if (plan.generation == transitionGeneration && mutableState.value.callId == plan.callId) {
                    voiceGeneration = voice.snapshot.value.generation
                }
            }
            StartCallResult.Started(plan.callId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { signaling.end(plan.callId, CallTerminationReason.Cancelled) }
                runCatching { voice.leave() }
                runCatching { platform.end(CallTerminationReason.Cancelled) }
                operation.withLock {
                    if (plan.generation == transitionGeneration && mutableState.value.callId == plan.callId) {
                        transitionGeneration += 1
                        mutableState.value = plan.snapshot.copy(
                            state = CallState.Cancelled,
                            terminationReason = CallTerminationReason.Cancelled,
                            media = CallMediaState(),
                            networkUsage = usage.finish(nowEpochMillis()),
                            error = "call_accept_cancelled",
                        )
                    }
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            runCatching { signaling.end(plan.callId, CallTerminationReason.Failed) }
            runCatching { voice.leave() }
            runCatching { platform.end(CallTerminationReason.Failed) }
            operation.withLock {
                if (plan.generation == transitionGeneration && mutableState.value.callId == plan.callId) {
                    transitionGeneration += 1
                    mutableState.value = plan.snapshot.copy(
                        state = CallState.Failed,
                        terminationReason = CallTerminationReason.Failed,
                        media = CallMediaState(),
                        networkUsage = usage.finish(nowEpochMillis()),
                        error = "call_accept_failed",
                    )
                }
            }
            StartCallResult.Failed("call_accept_failed")
        }
    }

    suspend fun rejectIncoming() {
        val plan = operation.withLock {
            val current = mutableState.value
            val callId = current.callId ?: return
            if (current.direction != CallDirection.Incoming || current.state != CallState.Ringing) return
            transitionGeneration += 1
            mutableState.value = current.copy(state = CallState.Ending)
            TerminalPlan(current, callId, transitionGeneration)
        }
        runCatching { signaling.reject(plan.callId!!) }
        runCatching { platform.end(CallTerminationReason.Rejected) }
        operation.withLock {
            if (transitionGeneration == plan.generation && mutableState.value.callId == plan.callId) {
                mutableState.value = plan.snapshot.copy(
                    state = CallState.Rejected,
                    terminationReason = CallTerminationReason.Rejected,
                    media = CallMediaState(),
                    networkUsage = usage.finish(nowEpochMillis()),
                )
            }
        }
    }

    suspend fun setMuted(muted: Boolean) {
        val plan = operation.withLock {
            val current = mutableState.value
            if (current.state !in ActiveStates || current.callId == null) return
            current.callId to transitionGeneration
        }
        voice.setMuted(muted)
        operation.withLock {
            if (mutableState.value.callId == plan.first && transitionGeneration == plan.second) updateMedia(muted = muted)
        }
        logger("call muted=$muted")
    }

    suspend fun requestRoute(route: CallAudioRoute) {
        val plan = operation.withLock {
            val current = mutableState.value
            if (current.state !in ActiveStates || current.callId == null) return
            current.callId to transitionGeneration
        }
        val applied = platform.requestRoute(route)
        operation.withLock {
            if (mutableState.value.callId == plan.first && transitionGeneration == plan.second) updateMedia(route = applied)
        }
        logger("call route=${applied.name.lowercase()}")
    }

    suspend fun signalingInterrupted() {
        operation.withLock {
            val current = mutableState.value
            if (current.state !in ActiveStates || current.callId == null) return
            mutableState.value = current.copy(state = CallState.Reconnecting)
        }
        voice.signalingInterrupted()
    }

    suspend fun signalingRecovered() {
        val plan = operation.withLock {
            val current = mutableState.value
            if (current.state != CallState.Reconnecting || current.callId == null) return
            current.callId to transitionGeneration
        }
        voice.signalingRecovered()
        if (!owns(plan.second, plan.first)) return
        runCatching { signaling.deliveryStatus(plan.first) }.getOrNull()?.let { mergeDelivery(it) }
    }

    suspend fun end(reason: CallTerminationReason = CallTerminationReason.Local) {
        val plan = operation.withLock {
            val current = mutableState.value
            if (current.state !in ActiveStates) return
            transitionGeneration += 1
            mutableState.value = current.copy(state = CallState.Ending)
            TerminalPlan(current, current.callId, transitionGeneration)
        }
        runCatching { voice.leave() }
        if (reason != CallTerminationReason.Remote) {
            plan.callId?.let { callId -> runCatching { signaling.end(callId, reason) } }
        }
        runCatching { platform.end(reason) }
        operation.withLock {
            if (transitionGeneration != plan.generation || mutableState.value.callId != plan.callId) return@withLock
            durationJob?.cancel()
            durationJob = null
            voiceGeneration = null
            mutableState.value = plan.snapshot.copy(
                state = CallState.Ended,
                terminationReason = reason,
                media = CallMediaState(),
                networkUsage = usage.finish(nowEpochMillis()),
            )
        }
        logger("call state=ended reason=${reason.name.lowercase()}")
    }

    private fun onVoiceChangedLocked(voice: VoiceRoomSnapshot) {
        val current = mutableState.value
        if (current.state !in ActiveStates) return
        if (voiceGeneration?.let { it != voice.generation } == true) return
        val next = when (voice.state) {
            VoiceRoomState.Idle, VoiceRoomState.PermissionRequired -> current.state
            VoiceRoomState.Joining, VoiceRoomState.Negotiating -> CallState.Connecting
            VoiceRoomState.WaitingForPeer -> {
                if (current.direction == CallDirection.Outgoing && requirePresentationForRingback &&
                    !current.delivery.permitsWaitingRingback()) {
                    CallState.Connecting
                } else {
                    CallState.Ringing
                }
            }
            VoiceRoomState.Connected -> CallState.Active
            VoiceRoomState.Reconnecting -> CallState.Reconnecting
            VoiceRoomState.Failed, VoiceRoomState.Unavailable -> CallState.Failed
        }
        if (next != current.state) {
            platform.setRingback(
                current.direction == CallDirection.Outgoing && next == CallState.Ringing &&
                    (!requirePresentationForRingback || current.delivery.permitsWaitingRingback()),
            )
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
            val activeGeneration = transitionGeneration
            val activeCallId = current.callId
            durationJob = scope.launch {
                while (true) {
                    delay(1_000)
                    val keepRunning = operation.withLock {
                        val snapshot = mutableState.value
                        if (transitionGeneration != activeGeneration || snapshot.callId != activeCallId ||
                            snapshot.state != CallState.Active) return@withLock false
                        val activeSince = snapshot.activeSinceEpochMillis ?: return@withLock false
                        mutableState.value = snapshot.copy(
                            elapsedSeconds = ((nowEpochMillis() - activeSince) / 1_000).coerceAtLeast(0),
                        )
                        true
                    }
                    if (!keepRunning) break
                }
            }
            scope.launch {
                if (activeCallId != null && owns(activeGeneration, activeCallId)) platform.markActive()
            }
        }
        if (next == CallState.Failed && current.state != CallState.Failed) {
            val failed = FailurePlan(current.callId, transitionGeneration, voice.generation, mutableState.value)
            val cleanup = scope.launch(start = CoroutineStart.LAZY) { releaseFailedCall(failed) }
            failedCleanupJob = cleanup
            cleanup.start()
        }
    }

    private suspend fun releaseFailedCall(failed: FailurePlan) {
        try {
            if (!matchesFailure(failed)) return
            runCatching { voice.leave() }
            if (!matchesFailure(failed)) return
            failed.callId?.let { callId -> runCatching { signaling.end(callId, CallTerminationReason.Failed) } }
            if (!matchesFailure(failed)) return
            runCatching { platform.end(CallTerminationReason.Failed) }
            operation.withLock {
                if (!matchesFailureLocked(failed)) return@withLock
                durationJob?.cancel()
                durationJob = null
                voiceGeneration = null
                mutableState.value = failed.snapshot.copy(
                    media = CallMediaState(),
                    networkUsage = usage.finish(nowEpochMillis()),
                )
            }
        } finally {
            operation.withLock { failedCleanupJob = null }
        }
    }

    private suspend fun matchesFailure(failed: FailurePlan): Boolean = operation.withLock { matchesFailureLocked(failed) }

    private fun matchesFailureLocked(failed: FailurePlan): Boolean =
        transitionGeneration == failed.generation && voiceGeneration == failed.voiceGeneration &&
            mutableState.value.callId == failed.callId && mutableState.value.state == CallState.Failed

    private suspend fun onIncomingOffered(event: CallSignalingEvent.IncomingOffered) {
        val plan = operation.withLock {
            val current = mutableState.value
            if (current.callId == event.callId) {
                logger("duplicate incoming call ignored")
                return
            }
            if (current.state in ActiveStates) return@withLock null
            transitionGeneration += 1
            mutableState.value = CallSessionSnapshot(
                callId = event.callId,
                applicationContext = event.applicationContext,
                participant = event.caller,
                direction = CallDirection.Incoming,
                state = CallState.Ringing,
            )
            voiceGeneration = Long.MIN_VALUE
            receiptInFlight = null
            confirmedPresentation = null
            usage.reset()
            event.callId to transitionGeneration
        }
        if (plan == null) {
            runCatching { signaling.end(event.callId, CallTerminationReason.Busy) }
            return
        }
        try {
            platform.presentIncoming(event.callId, event.caller)
            operation.withLock {
                if (transitionGeneration == plan.second && mutableState.value.callId == plan.first &&
                    mutableState.value.state in setOf(CallState.Ringing, CallState.Answering, CallState.Connecting)) {
                    presentedIncomingCallId.value = event.callId
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching { voice.leave() }
            runCatching { platform.end(CallTerminationReason.Failed) }
            operation.withLock {
                if (transitionGeneration == plan.second && mutableState.value.callId == plan.first) {
                    mutableState.value = mutableState.value.copy(
                        state = CallState.Failed,
                        terminationReason = CallTerminationReason.Failed,
                        media = CallMediaState(),
                        networkUsage = usage.finish(nowEpochMillis()),
                        error = "call_presentation_failed",
                    )
                }
            }
        }
    }

    suspend fun confirmIncomingPresented(callId: CallId) {
        val generation = operation.withLock {
            val current = mutableState.value
            if (current.callId != callId || current.direction != CallDirection.Incoming ||
                current.state !in setOf(CallState.Ringing, CallState.Answering, CallState.Connecting) ||
                confirmedPresentation == callId || receiptInFlight == callId) return
            receiptInFlight = callId
            transitionGeneration
        }
        try {
            signaling.confirmIncomingReceipt(callId)
            operation.withLock {
                if (transitionGeneration == generation && mutableState.value.callId == callId) {
                    confirmedPresentation = callId
                }
                if (receiptInFlight == callId) receiptInFlight = null
            }
        } catch (cancelled: CancellationException) {
            operation.withLock { if (receiptInFlight == callId) receiptInFlight = null }
            throw cancelled
        } catch (error: Exception) {
            operation.withLock { if (receiptInFlight == callId) receiptInFlight = null }
            logger("call receipt confirmation failed type=${error::class.simpleName}")
        }
    }

    suspend fun clearForAccountChange() {
        val (current, cleanup) = operation.withLock {
            transitionGeneration += 1
            val snapshot = mutableState.value
            durationJob?.cancel()
            durationJob = null
            voiceGeneration = null
            receiptInFlight = null
            confirmedPresentation = null
            presentedIncomingCallId.value = null
            mutableState.value = CallSessionSnapshot()
            snapshot to failedCleanupJob
        }
        cleanup?.cancelAndJoin()
        operation.withLock { if (failedCleanupJob === cleanup) failedCleanupJob = null }
        runCatching { voice.leave() }
        current.callId?.let { runCatching { signaling.end(it, CallTerminationReason.Cancelled) } }
        runCatching { platform.end(CallTerminationReason.Cancelled) }
    }

    suspend fun dismissTerminal(): Unit = operation.withLock {
        if (mutableState.value.state in ActiveStates) return
        transitionGeneration += 1
        mutableState.value = CallSessionSnapshot()
    }

    private suspend fun mergeDelivery(status: CallDeliveryStatus) {
        val cleanupReason = operation.withLock {
            val current = mutableState.value
            if (current.callId != status.callId || status.revision <= (current.delivery?.revision ?: -1)) return
            if (!status.terminal) {
                val nextState = if (
                    current.direction == CallDirection.Outgoing &&
                    current.state == CallState.Connecting &&
                    voice.snapshot.value.state == VoiceRoomState.WaitingForPeer &&
                    (!requirePresentationForRingback || status.permitsWaitingRingback())
                ) {
                    CallState.Ringing
                } else {
                    current.state
                }
                mutableState.value = current.copy(delivery = status, state = nextState)
                if (current.direction == CallDirection.Outgoing && nextState == CallState.Ringing) {
                    platform.setRingback(
                        status.state != CallDeliveryState.Answered &&
                            (!requirePresentationForRingback || status.permitsWaitingRingback()),
                    )
                }
                return
            }
            val (terminalState, reason) = terminalProjection(status.terminalReason)
            transitionGeneration += 1
            durationJob?.cancel()
            durationJob = null
            voiceGeneration = null
            platform.setRingback(false)
            mutableState.value = current.copy(
                state = terminalState,
                terminationReason = reason,
                media = CallMediaState(),
                networkUsage = usage.finish(nowEpochMillis()),
                delivery = status,
            )
            reason
        }
        runCatching { voice.leave() }
        runCatching { platform.end(cleanupReason) }
    }

    private fun terminalProjection(value: String?): Pair<CallState, CallTerminationReason> = when (value?.lowercase()) {
        "expired" -> CallState.Expired to CallTerminationReason.Expired
        "missed" -> CallState.Missed to CallTerminationReason.Missed
        "rejected" -> CallState.Rejected to CallTerminationReason.Rejected
        "cancelled" -> CallState.Cancelled to CallTerminationReason.Cancelled
        "busy" -> CallState.Ended to CallTerminationReason.Busy
        "failed" -> CallState.Failed to CallTerminationReason.Failed
        else -> CallState.Ended to CallTerminationReason.Remote
    }

    private fun safeCallError(error: Throwable): String = when {
        error.message?.contains("call_peer_unavailable", ignoreCase = true) == true -> "call_peer_unavailable"
        error.message?.contains("call_active_exists", ignoreCase = true) == true -> "call_active_exists"
        else -> "call_start_failed"
    }

    private suspend fun terminalFromRemote(callId: CallId, state: CallState, reason: CallTerminationReason) {
        val plan = operation.withLock {
            val current = mutableState.value
            if (current.callId != callId || current.state !in ActiveStates) return
            transitionGeneration += 1
            mutableState.value = current.copy(state = CallState.Ending)
            TerminalPlan(current, callId, transitionGeneration)
        }
        runCatching { voice.leave() }
        runCatching { platform.end(reason) }
        operation.withLock {
            if (transitionGeneration != plan.generation || mutableState.value.callId != plan.callId) return@withLock
            durationJob?.cancel()
            durationJob = null
            voiceGeneration = null
            mutableState.value = plan.snapshot.copy(
                state = state,
                terminationReason = reason,
                media = CallMediaState(),
                networkUsage = usage.finish(nowEpochMillis()),
            )
        }
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
