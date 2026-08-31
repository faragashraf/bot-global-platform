package com.ashraffarag.sentricam.motion.capability

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionEvent
import com.ashraffarag.sentricam.motion.domain.MotionEventSummary
import com.ashraffarag.sentricam.motion.domain.MotionFrame
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.motion.domain.MotionStartResult
import com.ashraffarag.sentricam.motion.domain.MotionStopResult
import com.ashraffarag.sentricam.motion.domain.MotionUpdateResult
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngine
import com.ashraffarag.sentricam.recording.engine.domain.PauseResult
import com.ashraffarag.sentricam.recording.engine.domain.PrepareResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfile
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegment
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSession
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSessionResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingTriggerContext
import com.ashraffarag.sentricam.recording.engine.domain.ResumeResult
import com.ashraffarag.sentricam.recording.engine.domain.StartResult
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.engine.domain.StopResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRecordingCoordinatorTest {
    @Test
    fun confirmedMotionStartsRecordingExactlyOnce() = runBlocking {
        val fixture = Fixture()
        fixture.emit(MotionEvent.Confirmed(event()))
        fixture.emit(MotionEvent.Confirmed(event()))
        assertEquals(1, fixture.recording.prepareCount)
        assertEquals(1, fixture.recording.startCount)
        assertTrue(fixture.coordinator.isMotionRecordingActive())
    }

    @Test
    fun motionDoesNotStartSecondRecordingWhenManualIsBusy() = runBlocking {
        val fixture = Fixture()
        fixture.recording.installActive(RecordingTriggerContext.manual())
        fixture.emit(MotionEvent.Confirmed(event()))
        assertEquals(0, fixture.recording.prepareCount)
        assertFalse(fixture.coordinator.isMotionRecordingActive())
    }

    @Test
    fun endedMotionStopsOnlyMotionOwnedRecording() = runBlocking {
        val fixture = Fixture()
        fixture.emit(MotionEvent.Confirmed(event()))
        fixture.emit(MotionEvent.Ended(event(endedAt = 9_000L)))
        assertEquals(StopReason.MOTION_ENDED, fixture.recording.lastStopReason)
        assertEquals(1, fixture.recording.stopCount)
    }

    @Test
    fun endedMotionNeverStopsManualRecording() = runBlocking {
        val fixture = Fixture()
        fixture.recording.installActive(RecordingTriggerContext.manual())
        fixture.emit(MotionEvent.Ended(event(endedAt = 9_000L)))
        assertEquals(0, fixture.recording.stopCount)
    }

    @Test
    fun activityUpdatesPeakAndBurstMetadata() = runBlocking {
        val fixture = Fixture()
        fixture.emit(MotionEvent.Confirmed(event(peak = 0.5)))
        fixture.emit(MotionEvent.Activity(event(peak = 0.9, bursts = 2)))
        fixture.emit(MotionEvent.Ended(event(peak = 0.9, bursts = 2, endedAt = 9_000L)))
        val motion = fixture.recording.lastUpdatedContext!!.motion!!
        assertEquals(0.9, motion.peakScore, 0.0)
        assertEquals(2, motion.burstCount)
    }

    @Test
    fun manualRequestPromotesExistingMotionRecordingWithoutRestart() = runBlocking {
        val fixture = Fixture()
        fixture.emit(MotionEvent.Confirmed(event()))
        val result = fixture.coordinator.startManual(request())
        assertEquals(ManualRecordingRequestResult.PromotedExistingMotionRecording, result)
        assertEquals(1, fixture.recording.startCount)
        val context = fixture.recording.currentSession()!!.request.triggerContext
        assertEquals(RecordingStartReason.MOTION, context.startReason)
        assertTrue(context.manualControlClaimed)
    }

    @Test
    fun endedMotionDoesNotStopRecordingAfterManualPromotion() = runBlocking {
        val fixture = Fixture()
        fixture.emit(MotionEvent.Confirmed(event()))
        fixture.coordinator.promoteMotionRecordingToManual()
        fixture.emit(MotionEvent.Ended(event(endedAt = 9_000L)))
        assertEquals(0, fixture.recording.stopCount)
    }

    @Test
    fun motionEndingDuringSegmentRotationStopsSafely() = runBlocking {
        val fixture = Fixture()
        fixture.emit(MotionEvent.Confirmed(event()))
        fixture.recording.installRotatingFromCurrent()
        fixture.emit(MotionEvent.Ended(event(endedAt = 9_000L)))
        assertEquals(1, fixture.recording.stopCount)
        assertEquals(StopReason.MOTION_ENDED, fixture.recording.lastStopReason)
    }

    @Test
    fun manualRecordingStartsNormallyWhenEngineIsIdle() = runBlocking {
        val fixture = Fixture()
        assertEquals(ManualRecordingRequestResult.Started, fixture.coordinator.startManual(request()))
        assertEquals(RecordingStartReason.MANUAL, fixture.recording.currentSession()!!.request.triggerContext.startReason)
    }

    private class Fixture {
        val motion = FakeMotionEngine()
        val recording = FakeRecordingEngine()
        val coordinator = MotionRecordingCoordinator(
            motion,
            recording,
            MotionRecordingRequestProvider { request() },
            nowMillis = { 2_000L },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ).also(MotionRecordingCoordinator::start)

        suspend fun emit(event: MotionEvent) {
            motion.mutableEvents.emit(event)
            yield()
        }
    }

    private class FakeMotionEngine : MotionDetectionEngine {
        override val state = MutableStateFlow<MotionDetectionState>(MotionDetectionState.Disabled)
        val mutableEvents = MutableSharedFlow<MotionEvent>(extraBufferCapacity = 8)
        override val events: Flow<MotionEvent> = mutableEvents
        override val generation = 1L
        override suspend fun start(config: MotionDetectionConfig) = MotionStartResult.Started
        override suspend fun stop() = MotionStopResult.Stopped
        override suspend fun updateConfig(config: MotionDetectionConfig) = MotionUpdateResult.Updated
        override fun submitFrame(frame: MotionFrame) = Unit
        override fun resetForCameraChange() = generation
        override fun simulateMotion(timestampMillis: Long) = Unit
        override fun reportAnalyzerFailure(failure: Throwable, unsupportedCombination: Boolean) = Unit
    }

    private class FakeRecordingEngine : RecordingEngine {
        private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val state: StateFlow<RecordingState> = mutableState
        var prepareCount = 0
        var startCount = 0
        var stopCount = 0
        var lastStopReason: StopReason? = null
        var lastUpdatedContext: RecordingTriggerContext? = null

        override suspend fun prepare(request: RecordingRequest): PrepareResult {
            if (mutableState.value.isBusy()) return PrepareResult.Busy
            prepareCount++
            val session = session(request)
            mutableState.value = RecordingState.Ready(session, false)
            return PrepareResult.Prepared(session, false)
        }

        override suspend fun start(): StartResult {
            val ready = mutableState.value as? RecordingState.Ready ?: return StartResult.AlreadyActive
            startCount++
            mutableState.value = RecordingState.Recording(
                ready.session,
                RecordingSegment("seg", ready.session.id, 1, "/test.mp4", 1L),
                0L,
                0L,
            )
            return StartResult.Accepted
        }

        override suspend fun stop(reason: StopReason): StopResult {
            val session = currentSession() ?: return StopResult.NotActive
            stopCount++
            lastStopReason = reason
            mutableState.value = RecordingState.Completed(RecordingSessionResult(session.id, emptyList(), reason))
            return StopResult.Accepted
        }

        override suspend fun pause() = PauseResult.NotRecording
        override suspend fun resume() = ResumeResult.NotRecording
        override suspend fun release() = Unit

        override suspend fun updateTriggerContext(context: RecordingTriggerContext): Boolean {
            lastUpdatedContext = context
            val current = mutableState.value
            val session = currentSession() ?: return false
            val updated = session.copy(request = session.request.copy(triggerContext = context))
            mutableState.value = when (current) {
                is RecordingState.Ready -> current.copy(session = updated)
                is RecordingState.Recording -> current.copy(session = updated)
                is RecordingState.RotatingSegment -> current.copy(session = updated)
                else -> return false
            }
            return true
        }

        fun installActive(context: RecordingTriggerContext) {
            val session = session(request().copy(triggerContext = context))
            mutableState.value = RecordingState.Recording(
                session,
                RecordingSegment("seg", session.id, 1, "/test.mp4", 1L),
                0L,
                0L,
            )
        }

        fun installRotatingFromCurrent() {
            val session = currentSession()!!
            mutableState.value = RecordingState.RotatingSegment(session, 1, 1_000L, 1_000L)
        }

        fun currentSession(): RecordingSession? = when (val current = mutableState.value) {
            is RecordingState.Ready -> current.session
            is RecordingState.Recording -> current.session
            is RecordingState.RotatingSegment -> current.session
            else -> null
        }

        private fun RecordingState.isBusy() = this !is RecordingState.Idle &&
            this !is RecordingState.Completed && this !is RecordingState.Failed
    }

    private companion object {
        fun request() = RecordingRequest(
            profile = RecordingProfile(audioEnabled = false),
            lens = RecordingLens.BACK,
        )

        fun session(request: RecordingRequest) = RecordingSession(
            id = "session",
            request = request,
            requestedQuality = RecordingQuality.HD,
            selectedQuality = RecordingQuality.HD,
            audioEnabled = false,
            createdAtMillis = 1L,
        )

        fun event(peak: Double = 0.8, bursts: Int = 1, endedAt: Long? = null) = MotionEventSummary(
            eventId = "motion-event",
            detectedAtMillis = 1_000L,
            lastMotionAtMillis = 5_000L,
            endedAtMillis = endedAt,
            sensitivity = MotionSensitivity.MEDIUM,
            peakScore = peak,
            averageScore = peak / 2,
            sampleCount = 4,
            burstCount = bursts,
        )
    }
}
