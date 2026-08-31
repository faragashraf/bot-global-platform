package com.ashraffarag.sentricam.motion.capability

import com.ashraffarag.sentricam.motion.domain.MotionEvent
import com.ashraffarag.sentricam.motion.domain.MotionEventSummary
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngine
import com.ashraffarag.sentricam.recording.engine.domain.PrepareResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingMotionMetadata
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingTriggerContext
import com.ashraffarag.sentricam.recording.engine.domain.StartResult
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface MotionRecordingRequestProvider {
    suspend fun requestFor(event: MotionEventSummary): RecordingRequest?
}

sealed interface MotionRecordingCoordinatorState {
    data object Idle : MotionRecordingCoordinatorState
    data class Starting(val eventId: String) : MotionRecordingCoordinatorState
    data class Active(val eventId: String) : MotionRecordingCoordinatorState
    data class ManualPriority(val previousMotionEventId: String?) : MotionRecordingCoordinatorState
    data class TriggerRejected(val eventId: String) : MotionRecordingCoordinatorState
}

sealed interface ManualRecordingRequestResult {
    data object Started : ManualRecordingRequestResult
    data object PromotedExistingMotionRecording : ManualRecordingRequestResult
    data object Busy : ManualRecordingRequestResult
    data object Rejected : ManualRecordingRequestResult
}

class MotionRecordingCoordinator(
    private val motionEngine: MotionDetectionEngine,
    private val recordingEngine: RecordingEngine,
    private val requestProvider: MotionRecordingRequestProvider,
    private val nowMillis: () -> Long,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<MotionRecordingCoordinatorState>(
        MotionRecordingCoordinatorState.Idle,
    )
    val state: StateFlow<MotionRecordingCoordinatorState> = mutableState.asStateFlow()
    private var eventJob: Job? = null

    fun start() {
        if (eventJob != null) return
        eventJob = scope.launch {
            motionEngine.events.collect(::handleMotionEvent)
        }
    }

    fun release() {
        eventJob?.cancel()
        eventJob = null
    }

    suspend fun startManual(request: RecordingRequest): ManualRecordingRequestResult = mutex.withLock {
        val currentSession = recordingEngine.state.value.activeSession()
        if (currentSession?.request?.triggerContext?.startReason == RecordingStartReason.MOTION) {
            val existingContext = currentSession.request.triggerContext
            val eventId = existingContext.motion?.eventId
            recordingEngine.updateTriggerContext(existingContext.copy(manualControlClaimed = true))
            mutableState.value = MotionRecordingCoordinatorState.ManualPriority(eventId)
            return@withLock ManualRecordingRequestResult.PromotedExistingMotionRecording
        }
        if (recordingEngine.state.value.isBusy()) return@withLock ManualRecordingRequestResult.Busy
        when (recordingEngine.prepare(request.copy(triggerContext = RecordingTriggerContext.manual()))) {
            is PrepareResult.Prepared -> when (recordingEngine.start()) {
                StartResult.Accepted -> {
                    mutableState.value = MotionRecordingCoordinatorState.ManualPriority(null)
                    ManualRecordingRequestResult.Started
                }
                else -> ManualRecordingRequestResult.Rejected
            }
            else -> ManualRecordingRequestResult.Rejected
        }
    }

    suspend fun promoteMotionRecordingToManual(): Boolean = mutex.withLock {
        val session = recordingEngine.state.value.activeSession() ?: return@withLock false
        val context = session.request.triggerContext
        if (context.startReason != RecordingStartReason.MOTION || context.manualControlClaimed) return@withLock false
        val eventId = context.motion?.eventId
        val updated = recordingEngine.updateTriggerContext(context.copy(manualControlClaimed = true))
        if (updated) mutableState.value = MotionRecordingCoordinatorState.ManualPriority(eventId)
        updated
    }

    fun isMotionRecordingActive(): Boolean =
        recordingEngine.state.value.activeSession()?.request?.triggerContext?.let { context ->
            context.startReason == RecordingStartReason.MOTION && !context.manualControlClaimed
        } == true

    private suspend fun handleMotionEvent(event: MotionEvent) = mutex.withLock {
        when (event) {
            is MotionEvent.Confirmed -> handleConfirmedLocked(event.event)
            is MotionEvent.Activity -> {
                if (isMotionRecordingActive()) {
                    mutableState.value = MotionRecordingCoordinatorState.Active(event.event.eventId)
                }
            }
            is MotionEvent.Ended -> handleEndedLocked(event.event)
        }
    }

    private suspend fun handleConfirmedLocked(event: MotionEventSummary) {
        val existing = recordingEngine.state.value.activeSession()
        if (existing != null) {
            if (existing.request.triggerContext.startReason == RecordingStartReason.MOTION &&
                existing.request.triggerContext.motion?.eventId == event.eventId
            ) {
                updateMotionMetadataLocked(event)
            }
            return
        }
        if (recordingEngine.state.value.isBusy()) return
        val baseRequest = requestProvider.requestFor(event)
        if (baseRequest == null) {
            mutableState.value = MotionRecordingCoordinatorState.TriggerRejected(event.eventId)
            return
        }
        mutableState.value = MotionRecordingCoordinatorState.Starting(event.eventId)
        val request = baseRequest.copy(
            triggerContext = RecordingTriggerContext.motion(event.toRecordingMetadata()),
        )
        when (recordingEngine.prepare(request)) {
            is PrepareResult.Prepared -> when (recordingEngine.start()) {
                StartResult.Accepted -> {
                    recordingEngine.updateTriggerContext(
                        RecordingTriggerContext.motion(
                            event.toRecordingMetadata(recordingStartedAtMillis = nowMillis()),
                        ),
                    )
                    mutableState.value = MotionRecordingCoordinatorState.Active(event.eventId)
                }
                else -> mutableState.value = MotionRecordingCoordinatorState.TriggerRejected(event.eventId)
            }
            else -> mutableState.value = MotionRecordingCoordinatorState.TriggerRejected(event.eventId)
        }
    }

    private suspend fun updateMotionMetadataLocked(event: MotionEventSummary) {
        val session = recordingEngine.state.value.activeSession() ?: return
        val existing = session.request.triggerContext
        if (existing.startReason != RecordingStartReason.MOTION || existing.motion?.eventId != event.eventId) return
        recordingEngine.updateTriggerContext(
            existing.copy(
                motion = event.toRecordingMetadata(existing.motion.recordingStartedAtMillis),
            ),
        )
        mutableState.value = MotionRecordingCoordinatorState.Active(event.eventId)
    }

    private suspend fun handleEndedLocked(event: MotionEventSummary) {
        val session = recordingEngine.state.value.activeSession()
        val context = session?.request?.triggerContext
        if (context?.startReason != RecordingStartReason.MOTION || context.manualControlClaimed ||
            context.motion?.eventId != event.eventId
        ) return
        recordingEngine.updateTriggerContext(
            RecordingTriggerContext.motion(
                event.toRecordingMetadata(context.motion.recordingStartedAtMillis),
            ),
        )
        recordingEngine.stop(StopReason.MOTION_ENDED)
        mutableState.value = MotionRecordingCoordinatorState.Idle
    }

    private fun MotionEventSummary.toRecordingMetadata(recordingStartedAtMillis: Long? = null) =
        RecordingMotionMetadata(
            eventId = eventId,
            detectedAtMillis = detectedAtMillis,
            recordingStartedAtMillis = recordingStartedAtMillis,
            lastMotionAtMillis = lastMotionAtMillis,
            motionEndedAtMillis = endedAtMillis,
            sensitivity = sensitivity.name,
            peakScore = peakScore,
            averageScore = averageScore,
            burstCount = burstCount,
        )

    private fun RecordingState.activeSession() = when (this) {
        is RecordingState.Ready -> session
        is RecordingState.Starting -> session
        is RecordingState.Recording -> session
        is RecordingState.RotatingSegment -> session
        is RecordingState.Stopping -> session
        else -> null
    }

    private fun RecordingState.isBusy(): Boolean = when (this) {
        is RecordingState.Preparing,
        is RecordingState.Ready,
        is RecordingState.Starting,
        is RecordingState.Recording,
        is RecordingState.RotatingSegment,
        is RecordingState.Stopping,
        -> true
        else -> false
    }
}
