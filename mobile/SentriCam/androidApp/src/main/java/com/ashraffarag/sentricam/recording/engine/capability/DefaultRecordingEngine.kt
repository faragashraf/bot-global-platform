package com.ashraffarag.sentricam.recording.engine.capability

import com.ashraffarag.sentricam.recording.engine.domain.PauseResult
import com.ashraffarag.sentricam.recording.engine.domain.PrepareResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingEngineStateMachine
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailure
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailureCode
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegment
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentMetadata
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentStatus
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSession
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSessionResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStorageLevel
import com.ashraffarag.sentricam.recording.engine.domain.RecordingTriggerContext
import com.ashraffarag.sentricam.recording.engine.domain.RecoveryAction
import com.ashraffarag.sentricam.recording.engine.domain.ResumeResult
import com.ashraffarag.sentricam.recording.engine.domain.StartResult
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.engine.domain.StopResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultRecordingEngine(
    private val recorder: SegmentRecorder,
    private val storage: RecordingStorageGateway,
    private val qualityProvider: RecordingQualityProvider,
    private val metadataStore: RecordingMetadataStore,
    private val fileNameFactory: RecordingFileNameFactory,
    private val clock: RecordingClock,
    private val idFactory: RecordingIdFactory,
    private val scheduler: RecordingScheduler,
    private val scope: CoroutineScope,
) : RecordingEngine {
    private val mutex = Mutex()
    private val stateMachine = RecordingEngineStateMachine()
    override val state: StateFlow<RecordingState> = stateMachine.state

    private var preparedSession: RecordingSession? = null
    private var activeSegment: RecordingSegment? = null
    private var activeHandle: SegmentRecordingHandle? = null
    private var pendingFinalizationAction = FinalizationAction.NONE
    private var pendingStopReason = StopReason.USER
    private var terminalFailure: RecordingFailure? = null
    private var currentStorageLevel = RecordingStorageLevel.HEALTHY
    private var rotationTask: RecordingScheduledTask? = null
    private var storageTask: RecordingScheduledTask? = null
    private var timerTask: RecordingScheduledTask? = null
    private var released = false

    override suspend fun prepare(request: RecordingRequest): PrepareResult = mutex.withLock {
        if (released || state.value.isBusy()) return PrepareResult.Busy

        val requestId = idFactory.createId()
        if (!stateMachine.transitionTo(RecordingState.Preparing(requestId))) return PrepareResult.Busy

        request.profile.validationFailure()?.let { failure ->
            failPreparation(failure)
            return PrepareResult.Rejected(failure)
        }

        val storageCheck = try {
            storage.validateForStart(request.profile.storagePolicy)
        } catch (failure: Throwable) {
            val recordingFailure = unexpectedFailure("storage_prepare", failure)
            failPreparation(recordingFailure)
            return PrepareResult.Rejected(recordingFailure)
        }
        val availableStorageBytes = when (storageCheck) {
            is RecordingStorageCheck.Unavailable -> {
                failPreparation(storageCheck.failure)
                return PrepareResult.Rejected(storageCheck.failure)
            }
            is RecordingStorageCheck.Available -> {
                if (storageCheck.availableBytes < request.profile.storagePolicy.minimumStartBytes) {
                    val failure = insufficientStorageFailure("prepare")
                    failPreparation(failure)
                    return PrepareResult.Rejected(failure)
                }
                currentStorageLevel = storageCheck.level
                storageCheck.availableBytes
            }
        }

        val requestedQuality = RecordingProfileQualityPolicy.requestedQuality(request.profile.id)
        val supportedQualities = try {
            qualityProvider.supportedQualities()
        } catch (failure: Throwable) {
            val recordingFailure = unexpectedFailure("quality_discovery", failure)
            failPreparation(recordingFailure)
            return PrepareResult.Rejected(recordingFailure)
        }
        val selectedQuality = RecordingProfileQualityPolicy.select(request.profile.id, supportedQualities) ?: run {
            val failure = RecordingFailure(
                code = RecordingFailureCode.UNSUPPORTED_QUALITY,
                retryAllowed = false,
                recoveryAction = RecoveryAction.NONE,
                diagnosticTag = request.profile.id.name,
            )
            failPreparation(failure)
            return PrepareResult.Rejected(failure)
        }

        val audioFallbackApplied = request.profile.audioEnabled && !request.audioPermissionGranted
        val session = RecordingSession(
            id = idFactory.createId(),
            request = request,
            requestedQuality = requestedQuality,
            selectedQuality = selectedQuality,
            audioEnabled = request.profile.audioEnabled && request.audioPermissionGranted,
            createdAtMillis = clock.nowMillis(),
            availableStorageBytes = availableStorageBytes,
        )
        preparedSession = session
        pendingFinalizationAction = FinalizationAction.NONE
        terminalFailure = null
        stateMachine.transitionTo(RecordingState.Ready(session, audioFallbackApplied))
        PrepareResult.Prepared(session, audioFallbackApplied)
    }

    override suspend fun start(): StartResult = mutex.withLock {
        when (val current = state.value) {
            is RecordingState.Ready -> startNextSegmentLocked(current.session)
            is RecordingState.Starting,
            is RecordingState.Recording,
            is RecordingState.RotatingSegment,
            is RecordingState.Stopping,
            -> StartResult.AlreadyActive
            else -> StartResult.Rejected(
                RecordingFailure.invalidRequest("start_without_prepare"),
            )
        }
    }

    override suspend fun stop(reason: StopReason): StopResult = mutex.withLock {
        when (val current = state.value) {
            is RecordingState.Stopping -> StopResult.AlreadyStopping
            is RecordingState.Starting,
            is RecordingState.Recording,
            is RecordingState.RotatingSegment,
            -> {
                val session = current.session()
                pendingFinalizationAction = FinalizationAction.STOP
                pendingStopReason = reason
                cancelScheduledTasks()
                stateMachine.transitionTo(RecordingState.Stopping(session, reason))
                if (current !is RecordingState.RotatingSegment) {
                    stopActiveHandleLocked()
                }
                StopResult.Accepted
            }
            else -> StopResult.NotActive
        }
    }

    override suspend fun pause(): PauseResult = mutex.withLock {
        val current = state.value as? RecordingState.Recording ?: return PauseResult.NotRecording
        if (current.paused) return PauseResult.AlreadyPaused
        return try {
            activeHandle?.pause() ?: return PauseResult.NotRecording
            stateMachine.transitionTo(current.copy(paused = true))
            PauseResult.Accepted
        } catch (failure: Throwable) {
            failActiveSession(unexpectedFailure("pause", failure))
            PauseResult.NotRecording
        }
    }

    override suspend fun resume(): ResumeResult = mutex.withLock {
        val current = state.value as? RecordingState.Recording ?: return ResumeResult.NotRecording
        if (!current.paused) return ResumeResult.AlreadyRecording
        return try {
            activeHandle?.resume() ?: return ResumeResult.NotRecording
            stateMachine.transitionTo(current.copy(paused = false))
            ResumeResult.Accepted
        } catch (failure: Throwable) {
            failActiveSession(unexpectedFailure("resume", failure))
            ResumeResult.NotRecording
        }
    }

    override suspend fun updateTriggerContext(context: RecordingTriggerContext): Boolean = mutex.withLock {
        val session = preparedSession ?: return@withLock false
        val updatedSegments = session.segments.map { it.copy(triggerContext = context) }
        val updatedSession = session.copy(
            request = session.request.copy(triggerContext = context),
            segments = updatedSegments,
        )
        preparedSession = updatedSession
        updatedSegments.forEach { metadataStore.save(it) }
        val next = when (val current = state.value) {
            is RecordingState.Ready -> current.copy(session = updatedSession)
            is RecordingState.Starting -> current.copy(session = updatedSession)
            is RecordingState.Recording -> current.copy(session = updatedSession)
            is RecordingState.RotatingSegment -> current.copy(session = updatedSession)
            is RecordingState.Stopping -> current.copy(session = updatedSession)
            else -> return@withLock false
        }
        stateMachine.transitionTo(next)
        true
    }

    override suspend fun release() {
        val shouldCancelImmediately = mutex.withLock {
            released = true
            val current = state.value
            when (current) {
                is RecordingState.Starting,
                is RecordingState.Recording,
                is RecordingState.RotatingSegment,
                is RecordingState.Stopping,
                -> {
                    if (state.value !is RecordingState.Stopping) {
                        val session = state.value.session()
                        pendingFinalizationAction = FinalizationAction.STOP
                        pendingStopReason = StopReason.RELEASE
                        cancelScheduledTasks()
                        stateMachine.transitionTo(RecordingState.Stopping(session, StopReason.RELEASE))
                        if (current !is RecordingState.RotatingSegment) stopActiveHandleLocked()
                    }
                    false
                }
                else -> true
            }
        }
        if (shouldCancelImmediately) {
            recorder.release()
            scope.cancel()
        }
    }

    private suspend fun startNextSegmentLocked(session: RecordingSession): StartResult {
        val index = session.nextSegmentIndex
        val createdAt = clock.nowMillis()
        val segmentId: String
        val fileName: String
        val targetResult: RecordingTargetResult
        try {
            segmentId = idFactory.createId()
            fileName = fileNameFactory.create(createdAt, session.id, index, session.request.lens)
            targetResult = storage.createTarget(fileName)
        } catch (failure: Throwable) {
            val recordingFailure = unexpectedFailure("segment_target_$index", failure)
            failActiveSession(recordingFailure, session)
            return StartResult.Rejected(recordingFailure)
        }
        val target = when (val result = targetResult) {
            is RecordingTargetResult.Created -> result.absolutePath
            is RecordingTargetResult.Failed -> {
                failActiveSession(result.failure, session)
                return StartResult.Rejected(result.failure)
            }
        }
        val segment = RecordingSegment(
            id = segmentId,
            sessionId = session.id,
            index = index,
            absolutePath = target,
            startedAtMillis = createdAt,
        )
        activeSegment = segment
        activeHandle = null
        preparedSession = session
        pendingFinalizationAction = FinalizationAction.NONE
        stateMachine.transitionTo(RecordingState.Starting(session, index))

        return try {
            activeHandle = recorder.start(
                request = SegmentStartRequest(
                    sessionId = session.id,
                    segmentId = segment.id,
                    segmentIndex = index,
                    absolutePath = target,
                    audioEnabled = session.audioEnabled,
                    maximumFileSizeBytes = session.request.profile.maximumFileSizeBytes,
                ),
                listener = recorderListener,
            )
            StartResult.Accepted
        } catch (failure: Throwable) {
            val recordingFailure = RecordingFailure(
                code = RecordingFailureCode.RECORDING_START_FAILED,
                retryAllowed = true,
                recoveryAction = RecoveryAction.RETRY,
                diagnosticTag = "segment_$index",
                technicalCause = failure,
            )
            saveFailedStartMetadata(session, segment, recordingFailure)
            activeSegment = null
            activeHandle = null
            failActiveSession(recordingFailure, session)
            StartResult.Rejected(recordingFailure)
        }
    }

    private val recorderListener = object : SegmentRecorderListener {
        override fun onStarted(sessionId: String, segmentId: String) {
            scope.launch { handleSegmentStarted(sessionId, segmentId) }
        }

        override fun onFinalized(
            sessionId: String,
            segmentId: String,
            result: SegmentFinalizeResult,
        ) {
            scope.launch { handleSegmentFinalized(sessionId, segmentId, result) }
        }
    }

    private suspend fun handleSegmentStarted(sessionId: String, segmentId: String) = mutex.withLock {
        val segment = activeSegment ?: return
        val session = preparedSession ?: return
        if (segment.sessionId != sessionId || segment.id != segmentId || session.id != sessionId) return
        if (state.value !is RecordingState.Starting) return

        val startedSegment = segment.copy(startedAtMillis = clock.nowMillis())
        activeSegment = startedSegment
        stateMachine.transitionTo(
            RecordingState.Recording(
                session = session,
                segment = startedSegment,
                sessionDurationMillis = completedDuration(session),
                segmentDurationMillis = 0L,
                storageLevel = currentStorageLevel,
            ),
        )
        scheduleSegmentRotation(session, startedSegment)
        scheduleStorageCheck(session)
        scheduleTimerTick(session, startedSegment)
    }

    private suspend fun handleSegmentFinalized(
        sessionId: String,
        segmentId: String,
        result: SegmentFinalizeResult,
    ) {
        mutex.withLock {
        val segment = activeSegment ?: return@withLock
        val session = preparedSession ?: return@withLock
        if (segment.sessionId != sessionId || segment.id != segmentId || session.id != sessionId) {
            return@withLock
        }

        if (result is SegmentFinalizeResult.Success &&
            result.continueSession &&
            pendingFinalizationAction == FinalizationAction.NONE &&
            state.value is RecordingState.Recording
        ) {
            val elapsed = (clock.nowMillis() - segment.startedAtMillis).coerceAtLeast(0L)
            pendingFinalizationAction = FinalizationAction.ROTATE
            stateMachine.transitionTo(
                RecordingState.RotatingSegment(
                    session = session,
                    completedSegmentIndex = segment.index,
                    sessionDurationMillis = completedDuration(session) + elapsed,
                    segmentDurationMillis = elapsed,
                ),
            )
        }

        cancelScheduledTasks()
        activeHandle = null
        activeSegment = null

        val metadata = result.toMetadata(session, segment, clock.nowMillis())
        try {
            metadataStore.save(metadata)
        } catch (failure: Throwable) {
            terminalFailure = unexpectedFailure("metadata_save", failure)
        }
        val updatedSession = session.copy(
            selectedQuality = metadata.actualQuality,
            nextSegmentIndex = segment.index + 1,
            segments = session.segments + metadata,
        )
        preparedSession = updatedSession

        if (result is SegmentFinalizeResult.Failure) {
            val failure = result.failure
            terminalFailure = failure
            pendingFinalizationAction = FinalizationAction.STOP
            pendingStopReason = StopReason.SEGMENT_FAILURE
            stateMachine.transitionTo(RecordingState.Failed(failure, updatedSession))
            finishReleasedScopeIfNeeded()
            return@withLock
        }

        terminalFailure?.let { failure ->
            stateMachine.transitionTo(RecordingState.Failed(failure, updatedSession))
            finishReleasedScopeIfNeeded()
            return@withLock
        }

        when (pendingFinalizationAction) {
            FinalizationAction.ROTATE -> {
                pendingFinalizationAction = FinalizationAction.NONE
                startNextSegmentLocked(updatedSession)
            }
            FinalizationAction.STOP -> {
                val reason = pendingStopReason
                val finalizedSegments = updatedSession.segments.map { it.copy(stopReason = reason) }
                try {
                    finalizedSegments.forEach { metadataStore.save(it) }
                } catch (failure: Throwable) {
                    stateMachine.transitionTo(
                        RecordingState.Failed(unexpectedFailure("metadata_stop_reason", failure), updatedSession),
                    )
                    finishReleasedScopeIfNeeded()
                    return@withLock
                }
                val completedSession = updatedSession.copy(segments = finalizedSegments)
                preparedSession = completedSession
                stateMachine.transitionTo(
                    RecordingState.Completed(
                        RecordingSessionResult(completedSession.id, completedSession.segments, reason),
                    ),
                )
                finishReleasedScopeIfNeeded()
            }
            FinalizationAction.NONE -> {
                val failure = RecordingFailure(
                    code = RecordingFailureCode.CAMERA_UNAVAILABLE,
                    retryAllowed = true,
                    recoveryAction = RecoveryAction.RESTART_CAMERA,
                    diagnosticTag = "unexpected_finalize",
                )
                stateMachine.transitionTo(RecordingState.Failed(failure, updatedSession))
                finishReleasedScopeIfNeeded()
            }
        }
        }
    }

    private fun scheduleSegmentRotation(session: RecordingSession, segment: RecordingSegment) {
        rotationTask?.cancel()
        rotationTask = scheduler.schedule(session.request.profile.segmentDurationMillis) {
            scope.launch { rotateSegment(session.id, segment.id) }
        }
    }

    private suspend fun rotateSegment(sessionId: String, segmentId: String) = mutex.withLock {
        val current = state.value as? RecordingState.Recording ?: return
        if (current.session.id != sessionId || current.segment.id != segmentId) return
        pendingFinalizationAction = FinalizationAction.ROTATE
        cancelScheduledTasks()
        stateMachine.transitionTo(
            RecordingState.RotatingSegment(
                session = current.session,
                completedSegmentIndex = current.segment.index,
                sessionDurationMillis = current.sessionDurationMillis,
                segmentDurationMillis = current.segmentDurationMillis,
            ),
        )
        stopActiveHandleLocked()
    }

    private fun scheduleStorageCheck(session: RecordingSession) {
        storageTask?.cancel()
        storageTask = scheduler.schedule(session.request.profile.storagePolicy.checkIntervalMillis) {
            scope.launch { checkStorageDuringRecording(session.id) }
        }
    }

    private suspend fun checkStorageDuringRecording(sessionId: String) {
        val session = mutex.withLock {
            val current = state.value as? RecordingState.Recording ?: return
            if (current.session.id != sessionId) return
            current.session
        }
        val check = storage.currentStatus(session.request.profile.storagePolicy)
        mutex.withLock {
            val current = state.value as? RecordingState.Recording ?: return@withLock
            if (current.session.id != sessionId) return@withLock
            when (check) {
                is RecordingStorageCheck.Unavailable -> stopForStorageFailure(current, check.failure)
                is RecordingStorageCheck.Available -> {
                    currentStorageLevel = check.level
                    val updatedSession = current.session.copy(
                        availableStorageBytes = check.availableBytes,
                    )
                    preparedSession = updatedSession
                    val updatedState = current.copy(
                        session = updatedSession,
                        storageLevel = check.level,
                    )
                    stateMachine.transitionTo(updatedState)
                    if (check.mustStop) {
                        stopForStorageFailure(updatedState, insufficientStorageFailure("recording"))
                    } else {
                        scheduleStorageCheck(updatedSession)
                    }
                }
            }
        }
    }

    private fun stopForStorageFailure(current: RecordingState.Recording, failure: RecordingFailure) {
        terminalFailure = failure
        pendingFinalizationAction = FinalizationAction.STOP
        pendingStopReason = StopReason.STORAGE_LIMIT
        cancelScheduledTasks()
        stateMachine.transitionTo(RecordingState.Stopping(current.session, StopReason.STORAGE_LIMIT))
        stopActiveHandleLocked()
    }

    private fun scheduleTimerTick(session: RecordingSession, segment: RecordingSegment) {
        timerTask?.cancel()
        timerTask = scheduler.schedule(TIMER_INTERVAL_MILLIS) {
            scope.launch { updateDurations(session.id, segment.id) }
        }
    }

    private suspend fun updateDurations(sessionId: String, segmentId: String) = mutex.withLock {
        val current = state.value as? RecordingState.Recording ?: return
        if (current.session.id != sessionId || current.segment.id != segmentId) return
        val segmentDuration = (clock.nowMillis() - current.segment.startedAtMillis).coerceAtLeast(0L)
        stateMachine.transitionTo(
            current.copy(
                sessionDurationMillis = completedDuration(current.session) + segmentDuration,
                segmentDurationMillis = segmentDuration,
            ),
        )
        scheduleTimerTick(current.session, current.segment)
    }

    private suspend fun saveFailedStartMetadata(
        session: RecordingSession,
        segment: RecordingSegment,
        failure: RecordingFailure,
    ) {
        val now = clock.nowMillis()
        try {
            metadataStore.save(
                RecordingSegmentMetadata(
                sessionId = session.id,
                segmentId = segment.id,
                segmentIndex = segment.index,
                createdAtMillis = segment.startedAtMillis,
                startedAtMillis = segment.startedAtMillis,
                endedAtMillis = now,
                durationMillis = 0L,
                fileSizeBytes = 0L,
                width = 0,
                height = 0,
                rotationDegrees = session.request.orientationDegrees,
                lens = session.request.lens,
                requestedQuality = session.requestedQuality,
                actualQuality = session.selectedQuality,
                audioEnabled = session.audioEnabled,
                timestampOverlayEnabled = session.request.timestampOverlayEnabled,
                status = RecordingSegmentStatus.FAILED,
                failureCode = failure.code,
                finalized = false,
                absolutePath = segment.absolutePath,
                triggerContext = session.request.triggerContext,
                ),
            )
        } catch (_: Throwable) {
            // The original start failure remains the actionable session failure.
        }
    }

    private fun SegmentFinalizeResult.toMetadata(
        session: RecordingSession,
        segment: RecordingSegment,
        endedAtMillis: Long,
    ): RecordingSegmentMetadata {
        val output = when (this) {
            is SegmentFinalizeResult.Success -> output
            is SegmentFinalizeResult.Failure -> partialOutput
        }
        val failure = (this as? SegmentFinalizeResult.Failure)?.failure
        return RecordingSegmentMetadata(
            sessionId = session.id,
            segmentId = segment.id,
            segmentIndex = segment.index,
            createdAtMillis = segment.startedAtMillis,
            startedAtMillis = segment.startedAtMillis,
            endedAtMillis = endedAtMillis,
            durationMillis = output?.durationMillis?.coerceAtLeast(0L) ?: 0L,
            fileSizeBytes = output?.fileSizeBytes?.coerceAtLeast(0L) ?: 0L,
            width = output?.width?.coerceAtLeast(0) ?: 0,
            height = output?.height?.coerceAtLeast(0) ?: 0,
            rotationDegrees = output?.rotationDegrees ?: session.request.orientationDegrees,
            lens = session.request.lens,
            requestedQuality = session.requestedQuality,
            actualQuality = output?.actualQuality ?: session.selectedQuality,
            audioEnabled = output?.audioEnabled ?: session.audioEnabled,
            timestampOverlayEnabled = session.request.timestampOverlayEnabled,
            status = if (failure == null) RecordingSegmentStatus.COMPLETED else RecordingSegmentStatus.FAILED,
            failureCode = failure?.code,
            finalized = output?.finalized == true && failure == null,
            absolutePath = segment.absolutePath,
            triggerContext = session.request.triggerContext,
        )
    }

    private fun failPreparation(failure: RecordingFailure) {
        preparedSession = null
        stateMachine.transitionTo(RecordingState.Failed(failure))
    }

    private fun failActiveSession(
        failure: RecordingFailure,
        session: RecordingSession? = preparedSession,
    ) {
        cancelScheduledTasks()
        activeHandle = null
        activeSegment = null
        stateMachine.transitionTo(RecordingState.Failed(failure, session))
        finishReleasedScopeIfNeeded()
    }

    private fun stopActiveHandleLocked() {
        try {
            activeHandle?.stop()
        } catch (failure: Throwable) {
            failActiveSession(
                RecordingFailure(
                    code = RecordingFailureCode.SEGMENT_FINALIZATION_FAILED,
                    retryAllowed = true,
                    recoveryAction = RecoveryAction.RETRY,
                    diagnosticTag = "stop",
                    technicalCause = failure,
                ),
            )
        }
    }

    private fun cancelScheduledTasks() {
        rotationTask?.cancel()
        storageTask?.cancel()
        timerTask?.cancel()
        rotationTask = null
        storageTask = null
        timerTask = null
    }

    private fun completedDuration(session: RecordingSession): Long =
        session.segments.sumOf { it.durationMillis.coerceAtLeast(0L) }

    private fun insufficientStorageFailure(tag: String) = RecordingFailure(
        code = RecordingFailureCode.INSUFFICIENT_STORAGE,
        retryAllowed = true,
        recoveryAction = RecoveryAction.FREE_STORAGE,
        diagnosticTag = tag,
    )

    private fun unexpectedFailure(tag: String, cause: Throwable) = RecordingFailure(
        code = RecordingFailureCode.UNEXPECTED_FAILURE,
        retryAllowed = true,
        recoveryAction = RecoveryAction.RETRY,
        diagnosticTag = tag,
        technicalCause = cause,
    )

    private fun RecordingState.isBusy(): Boolean = when (this) {
        is RecordingState.Preparing,
        is RecordingState.Starting,
        is RecordingState.Recording,
        is RecordingState.RotatingSegment,
        is RecordingState.Stopping,
        -> true
        else -> false
    }

    private fun RecordingState.session(): RecordingSession = when (this) {
        is RecordingState.Ready -> session
        is RecordingState.Starting -> session
        is RecordingState.Recording -> session
        is RecordingState.RotatingSegment -> session
        is RecordingState.Stopping -> session
        else -> error("State does not contain an active session")
    }

    private fun finishReleasedScopeIfNeeded() {
        if (released) {
            recorder.release()
            scope.cancel()
        }
    }

    private enum class FinalizationAction {
        NONE,
        ROTATE,
        STOP,
    }

    private companion object {
        const val TIMER_INTERVAL_MILLIS = 1_000L
    }
}
