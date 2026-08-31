package com.ashraffarag.sentricam.recording.engine.capability

import com.ashraffarag.sentricam.recording.engine.domain.DefaultRecordingFileNameFactory
import com.ashraffarag.sentricam.recording.engine.domain.PauseResult
import com.ashraffarag.sentricam.recording.engine.domain.PrepareResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailure
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailureCode
import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfile
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentMetadata
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentStatus
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStorageLevel
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStoragePolicy
import com.ashraffarag.sentricam.recording.engine.domain.RecoveryAction
import com.ashraffarag.sentricam.recording.engine.domain.ResumeResult
import com.ashraffarag.sentricam.recording.engine.domain.StartResult
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.engine.domain.StopResult
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRecordingEngineTest {
    @Test
    fun prepareStartPreventsDuplicateStartAndSupportsPauseResume() = runBlocking {
        val fixture = Fixture()

        assertEquals(StartResult.Rejected(RecordingFailure.invalidRequest("start_without_prepare")), fixture.engine.start())
        val prepared = fixture.engine.prepare(fixture.request(audioPermission = true)) as PrepareResult.Prepared
        assertFalse(prepared.audioFallbackApplied)
        assertEquals(StartResult.Accepted, fixture.engine.start())
        yield()
        assertTrue(fixture.engine.state.value is RecordingState.Recording)
        assertEquals(StartResult.AlreadyActive, fixture.engine.start())
        assertEquals(PauseResult.Accepted, fixture.engine.pause())
        assertEquals(PauseResult.AlreadyPaused, fixture.engine.pause())
        assertEquals(ResumeResult.Accepted, fixture.engine.resume())
        assertEquals(ResumeResult.AlreadyRecording, fixture.engine.resume())
        assertEquals(1, fixture.recorder.starts.size)
    }

    @Test
    fun stopIsIdempotentAndSuccessfulFinalizeCompletesMetadata() = runBlocking {
        val fixture = Fixture()
        fixture.start()
        val sessionId = (fixture.engine.state.value as RecordingState.Recording).session.id

        assertEquals(StopResult.Accepted, fixture.engine.stop(StopReason.USER))
        assertEquals(StopResult.AlreadyStopping, fixture.engine.stop(StopReason.USER))
        assertEquals(1, fixture.recorder.starts.single().handle.stopCount)
        fixture.recorder.finalizeSuccess(durationMillis = 2_500L)
        yield()

        val completed = fixture.engine.state.value as RecordingState.Completed
        assertEquals(sessionId, completed.result.sessionId)
        assertEquals(1, completed.result.segments.size)
        assertEquals(RecordingSegmentStatus.COMPLETED, fixture.metadata.saved.single().status)
        assertTrue(fixture.metadata.saved.single().finalized)
        assertEquals(2_500L, fixture.metadata.saved.single().durationMillis)
        assertEquals(StopReason.USER, fixture.metadata.saved.single().stopReason)
    }

    @Test
    fun segmentRotationKeepsSessionAndIncrementsIndex() = runBlocking {
        val fixture = Fixture()
        fixture.start(lens = RecordingLens.FRONT)
        val firstState = fixture.engine.state.value as RecordingState.Recording

        fixture.scheduler.runDelay(SEGMENT_DURATION)
        yield()
        assertTrue(fixture.engine.state.value is RecordingState.RotatingSegment)
        fixture.recorder.finalizeSuccess(durationMillis = SEGMENT_DURATION)
        yield()

        val secondState = fixture.engine.state.value as RecordingState.Recording
        assertEquals(firstState.session.id, secondState.session.id)
        assertEquals(2, secondState.segment.index)
        assertNotEquals(firstState.segment.id, secondState.segment.id)
        assertEquals(RecordingLens.FRONT, fixture.metadata.saved.single().lens)
        assertEquals(1, fixture.metadata.saved.single().segmentIndex)
    }

    @Test
    fun recorderLimitFinalizationContinuesWithNextSegment() = runBlocking {
        val fixture = Fixture()
        fixture.start()

        fixture.recorder.finalizeSuccess(continueSession = true)
        yield()

        val current = fixture.engine.state.value as RecordingState.Recording
        assertEquals(2, current.segment.index)
        assertEquals(2, fixture.recorder.starts.size)
        assertEquals(RecordingSegmentStatus.COMPLETED, fixture.metadata.saved.single().status)
    }

    @Test
    fun manualStopDuringRotationDoesNotDoubleStopOrStartAnotherSegment() = runBlocking {
        val fixture = Fixture()
        fixture.start()

        fixture.scheduler.runDelay(SEGMENT_DURATION)
        yield()
        assertEquals(1, fixture.recorder.starts[0].handle.stopCount)
        assertEquals(StopResult.Accepted, fixture.engine.stop(StopReason.USER))
        assertEquals(1, fixture.recorder.starts[0].handle.stopCount)
        fixture.recorder.finalizeSuccess()
        yield()

        val completed = fixture.engine.state.value as RecordingState.Completed
        assertEquals(StopReason.USER, completed.result.stopReason)
        assertEquals(1, fixture.recorder.starts.size)
    }

    @Test
    fun segmentFailureIsPersistedAndClosesSessionWithoutLoop() = runBlocking {
        val fixture = Fixture()
        fixture.start()
        val failure = failure(RecordingFailureCode.SEGMENT_FINALIZATION_FAILED)

        fixture.recorder.finalizeFailure(failure)
        yield()

        val failed = fixture.engine.state.value as RecordingState.Failed
        assertEquals(RecordingFailureCode.SEGMENT_FINALIZATION_FAILED, failed.failure.code)
        assertEquals(RecordingSegmentStatus.FAILED, fixture.metadata.saved.single().status)
        assertFalse(fixture.metadata.saved.single().finalized)
        assertEquals(1, fixture.recorder.starts.size)
    }

    @Test
    fun startFailureReturnsSafeFailedStateAndRecordsFailedMetadata() = runBlocking {
        val fixture = Fixture()
        fixture.recorder.failOnStart = true
        fixture.engine.prepare(fixture.request())

        val result = fixture.engine.start() as StartResult.Rejected

        assertEquals(RecordingFailureCode.RECORDING_START_FAILED, result.failure.code)
        assertTrue(fixture.engine.state.value is RecordingState.Failed)
        assertEquals(RecordingSegmentStatus.FAILED, fixture.metadata.saved.single().status)
    }

    @Test
    fun insufficientStorageRejectsPrepareAndCriticalStopFinalizesSafely() = runBlocking {
        val insufficient = Fixture().apply {
            storage.startCheck = RecordingStorageCheck.Available(10L, RecordingStorageLevel.CRITICAL)
        }
        val rejected = insufficient.engine.prepare(insufficient.request()) as PrepareResult.Rejected
        assertEquals(RecordingFailureCode.INSUFFICIENT_STORAGE, rejected.failure.code)

        val fixture = Fixture()
        fixture.start()
        fixture.storage.currentCheck = RecordingStorageCheck.Available(
            availableBytes = 10L,
            level = RecordingStorageLevel.CRITICAL,
            mustStop = true,
        )
        fixture.scheduler.runDelay(STORAGE_INTERVAL)
        yield()
        assertTrue(fixture.engine.state.value is RecordingState.Stopping)
        fixture.recorder.finalizeSuccess()
        yield()
        val failed = fixture.engine.state.value as RecordingState.Failed
        assertEquals(RecordingFailureCode.INSUFFICIENT_STORAGE, failed.failure.code)
    }

    @Test
    fun missingAudioPermissionFallsBackExplicitlyToSilentRecording() = runBlocking {
        val fixture = Fixture()
        val prepared = fixture.engine.prepare(fixture.request(audioPermission = false)) as PrepareResult.Prepared

        assertTrue(prepared.audioFallbackApplied)
        assertFalse(prepared.session.audioEnabled)
        fixture.engine.start()
        yield()
        assertFalse(fixture.recorder.starts.single().request.audioEnabled)
    }

    @Test
    fun staleEventsCannotMutateNewSession() = runBlocking {
        val fixture = Fixture()
        fixture.start()
        val oldStart = fixture.recorder.starts.single()
        fixture.engine.stop()
        fixture.recorder.finalizeSuccess()
        yield()

        fixture.engine.prepare(fixture.request())
        fixture.engine.start()
        yield()
        val newState = fixture.engine.state.value as RecordingState.Recording
        oldStart.listener.onStarted(oldStart.request.sessionId, oldStart.request.segmentId)
        oldStart.listener.onFinalized(
            oldStart.request.sessionId,
            oldStart.request.segmentId,
            SegmentFinalizeResult.Failure(failure(RecordingFailureCode.UNEXPECTED_FAILURE)),
        )
        yield()

        val unchanged = fixture.engine.state.value as RecordingState.Recording
        assertEquals(newState.session.id, unchanged.session.id)
        assertEquals(newState.segment.id, unchanged.segment.id)
    }

    @Test
    fun lifecycleStopAndStateFlowTransitionsFollowTheExpectedOrder() = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.engine.state.value is RecordingState.Idle)
        assertTrue(fixture.engine.prepare(fixture.request()) is PrepareResult.Prepared)
        assertTrue(fixture.engine.state.value is RecordingState.Ready)
        assertEquals(StartResult.Accepted, fixture.engine.start())
        yield()
        assertTrue(fixture.engine.state.value is RecordingState.Recording)

        assertEquals(StopResult.Accepted, fixture.engine.stop(StopReason.LIFECYCLE))
        assertTrue(fixture.engine.state.value is RecordingState.Stopping)
        fixture.recorder.finalizeSuccess()
        yield()
        val completed = fixture.engine.state.value as RecordingState.Completed
        assertEquals(StopReason.LIFECYCLE, completed.result.stopReason)
    }

    @Test
    fun negativeOutputValuesAreClampedAndFrontBackMetadataRemainAccurate() = runBlocking {
        val fixture = Fixture()
        fixture.start(lens = RecordingLens.BACK)
        fixture.engine.stop()
        fixture.recorder.finalizeSuccess(
            output = output(durationMillis = -1L, fileSizeBytes = -1L, width = -1, height = -1),
        )
        yield()

        val metadata = fixture.metadata.saved.single()
        assertEquals(0L, metadata.durationMillis)
        assertEquals(0L, metadata.fileSizeBytes)
        assertEquals(0, metadata.width)
        assertEquals(0, metadata.height)
        assertEquals(RecordingLens.BACK, metadata.lens)
    }

    private class Fixture {
        val recorder = FakeRecorder()
        val storage = FakeStorage()
        val metadata = FakeMetadataStore()
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        private var nextId = 0
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = DefaultRecordingEngine(
            recorder = recorder,
            storage = storage,
            qualityProvider = RecordingQualityProvider {
                setOf(RecordingQuality.SD, RecordingQuality.HD, RecordingQuality.FULL_HD)
            },
            metadataStore = metadata,
            fileNameFactory = DefaultRecordingFileNameFactory(TimeZone.getTimeZone("UTC")),
            clock = clock,
            idFactory = RecordingIdFactory { "id-${++nextId}" },
            scheduler = scheduler,
            scope = scope,
        )

        fun request(
            lens: RecordingLens = RecordingLens.BACK,
            audioPermission: Boolean = true,
        ) = RecordingRequest(
            profile = RecordingProfile(
                id = RecordingProfileId.STANDARD,
                audioEnabled = true,
                segmentDurationMillis = SEGMENT_DURATION,
                storagePolicy = POLICY,
            ),
            lens = lens,
            orientationDegrees = 90,
            audioPermissionGranted = audioPermission,
            timestampOverlayEnabled = true,
        )

        suspend fun start(lens: RecordingLens = RecordingLens.BACK) {
            engine.prepare(request(lens = lens))
            engine.start()
            yield()
        }
    }

    private class FakeRecorder : SegmentRecorder {
        data class Start(
            val request: SegmentStartRequest,
            val listener: SegmentRecorderListener,
            val handle: FakeHandle,
        )

        val starts = mutableListOf<Start>()
        var failOnStart = false

        override fun start(
            request: SegmentStartRequest,
            listener: SegmentRecorderListener,
        ): SegmentRecordingHandle {
            if (failOnStart) throw IllegalStateException("start failure")
            val start = Start(request, listener, FakeHandle())
            starts += start
            listener.onStarted(request.sessionId, request.segmentId)
            return start.handle
        }

        fun finalizeSuccess(
            durationMillis: Long = 1_000L,
            output: FinalizedSegmentOutput = output(durationMillis = durationMillis),
            continueSession: Boolean = false,
        ) {
            val current = starts.last()
            current.listener.onFinalized(
                current.request.sessionId,
                current.request.segmentId,
                SegmentFinalizeResult.Success(output, continueSession),
            )
        }

        fun finalizeFailure(failure: RecordingFailure) {
            val current = starts.last()
            current.listener.onFinalized(
                current.request.sessionId,
                current.request.segmentId,
                SegmentFinalizeResult.Failure(failure, output(finalized = false)),
            )
        }
    }

    private class FakeHandle : SegmentRecordingHandle {
        var stopCount = 0
        var pauseCount = 0
        var resumeCount = 0
        override fun stop() { stopCount++ }
        override fun pause() { pauseCount++ }
        override fun resume() { resumeCount++ }
    }

    private class FakeStorage : RecordingStorageGateway {
        var startCheck: RecordingStorageCheck = RecordingStorageCheck.Available(
            10_000L,
            RecordingStorageLevel.HEALTHY,
        )
        var currentCheck: RecordingStorageCheck = startCheck

        override suspend fun validateForStart(policy: RecordingStoragePolicy) = startCheck
        override suspend fun createTarget(fileName: String) = RecordingTargetResult.Created("/recordings/$fileName")
        override suspend fun currentStatus(policy: RecordingStoragePolicy) = currentCheck
    }

    private class FakeMetadataStore : RecordingMetadataStore {
        val saved = mutableListOf<RecordingSegmentMetadata>()
        override suspend fun save(metadata: RecordingSegmentMetadata) {
            saved.removeAll { it.segmentId == metadata.segmentId }
            saved += metadata
        }
    }

    private class FakeClock : RecordingClock {
        var now = 1_000L
        override fun nowMillis(): Long = now++
    }

    private class FakeScheduler : RecordingScheduler {
        private data class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMillis: Long, task: () -> Unit): RecordingScheduledTask {
            val scheduled = Task(delayMillis, task)
            tasks += scheduled
            return RecordingScheduledTask { scheduled.cancelled = true }
        }

        fun runDelay(delayMillis: Long) {
            val task = tasks.firstOrNull { !it.cancelled && it.delay == delayMillis }
                ?: error("No active task scheduled for $delayMillis")
            task.cancelled = true
            task.action()
        }
    }

    private companion object {
        const val SEGMENT_DURATION = 10_000L
        const val STORAGE_INTERVAL = 2_000L
        val POLICY = RecordingStoragePolicy(
            minimumStartBytes = 100L,
            warningBytes = 500L,
            criticalBytes = 200L,
            stopBytes = 50L,
            checkIntervalMillis = STORAGE_INTERVAL,
        )

        fun failure(code: RecordingFailureCode) = RecordingFailure(
            code = code,
            retryAllowed = true,
            recoveryAction = RecoveryAction.RETRY,
        )

        fun output(
            durationMillis: Long = 1_000L,
            fileSizeBytes: Long = 2_000L,
            width: Int = 1_280,
            height: Int = 720,
            finalized: Boolean = true,
        ) = FinalizedSegmentOutput(
            durationMillis = durationMillis,
            fileSizeBytes = fileSizeBytes,
            width = width,
            height = height,
            rotationDegrees = 90,
            actualQuality = RecordingQuality.HD,
            audioEnabled = true,
            finalized = finalized,
        )
    }
}
