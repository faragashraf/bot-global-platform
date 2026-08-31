package com.ashraffarag.sentricam.device.integration

import com.ashraffarag.sentricam.device.command.DeviceCommandResult
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngine
import com.ashraffarag.sentricam.recording.engine.domain.PauseResult
import com.ashraffarag.sentricam.recording.engine.domain.PrepareResult
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingEngineCommandPortTest {
    @Test
    fun remoteStartUsesRemoteOriginAndIsIdempotentForTheSameOwner() = runBlocking {
        val engine = FakeRecordingEngine()
        val port = RecordingEngineCommandPort(engine, requestProvider = { RecordingRequest() })

        assertEquals(DeviceCommandResult.Accepted, port.startRecording(RecordingOrigin.REMOTE))
        assertEquals(true, engine.state.value is RecordingState.Recording)
        assertEquals(RecordingStartReason.REMOTE, engine.preparedRequest?.triggerContext?.startReason)
        assertEquals(
            DeviceCommandResult.AlreadyApplied,
            port.startRecording(RecordingOrigin.REMOTE),
        )
        assertEquals(1, engine.prepareCount)
    }

    @Test
    fun remoteStartReportsExplicitMotionConflictWithoutStoppingMotion() = runBlocking {
        val engine = FakeRecordingEngine()
        val port = RecordingEngineCommandPort(engine, requestProvider = { RecordingRequest() })
        engine.installActive(RecordingStartReason.MOTION)

        assertEquals(
            DeviceCommandResult.Rejected("recording_owned_by_motion"),
            port.startRecording(RecordingOrigin.REMOTE),
        )
        assertEquals(0, engine.stopCount)
        assertEquals(0, engine.prepareCount)
    }

    @Test
    fun remoteStopCannotEndManualOrMotionOwnedRecording() = runBlocking {
        val engine = FakeRecordingEngine()
        val port = RecordingEngineCommandPort(engine, requestProvider = { RecordingRequest() })

        engine.installActive(RecordingStartReason.MANUAL)
        assertEquals(
            DeviceCommandResult.Rejected("recording_owned_by_manual"),
            port.stopRecording(RecordingOrigin.REMOTE),
        )
        engine.installActive(RecordingStartReason.MOTION)
        assertEquals(
            DeviceCommandResult.Rejected("recording_owned_by_motion"),
            port.stopRecording(RecordingOrigin.REMOTE),
        )
        assertEquals(0, engine.stopCount)
    }

    @Test
    fun matchingOwnerCanStopWithoutChangingOwnershipRules() = runBlocking {
        val engine = FakeRecordingEngine()
        val port = RecordingEngineCommandPort(engine, requestProvider = { RecordingRequest() })

        engine.installActive(RecordingStartReason.MANUAL)
        assertEquals(DeviceCommandResult.Accepted, port.stopRecording(RecordingOrigin.MANUAL))
        assertEquals(StopReason.USER, engine.lastStopReason)
        assertEquals(true, engine.state.value is RecordingState.Completed)
    }

    @Test
    fun remoteStopWhileIdleIsIdempotent() = runBlocking {
        val engine = FakeRecordingEngine()
        val port = RecordingEngineCommandPort(engine, requestProvider = { RecordingRequest() })

        assertEquals(
            DeviceCommandResult.AlreadyApplied,
            port.stopRecording(RecordingOrigin.REMOTE),
        )
        assertEquals(0, engine.stopCount)
    }

    @Test
    fun manualClaimOnMotionRecordingCanBeStoppedOnlyByManualOwner() = runBlocking {
        val engine = FakeRecordingEngine()
        val port = RecordingEngineCommandPort(engine, requestProvider = { RecordingRequest() })
        engine.installActive(RecordingStartReason.MOTION, manualClaimed = true)

        assertEquals(DeviceCommandResult.Accepted, port.stopRecording(RecordingOrigin.MANUAL))
        assertEquals(StopReason.USER, engine.lastStopReason)
    }

    private class FakeRecordingEngine : RecordingEngine {
        private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val state: StateFlow<RecordingState> = mutableState
        var preparedRequest: RecordingRequest? = null
        var prepareCount = 0
        var stopCount = 0
        var lastStopReason: StopReason? = null

        override suspend fun prepare(request: RecordingRequest): PrepareResult {
            prepareCount++
            preparedRequest = request
            val session = session(request.triggerContext)
            mutableState.value = RecordingState.Ready(session, false)
            return PrepareResult.Prepared(session, false)
        }

        override suspend fun start(): StartResult {
            val ready = mutableState.value as? RecordingState.Ready ?: return StartResult.AlreadyActive
            mutableState.value = RecordingState.Recording(
                ready.session,
                RecordingSegment("segment", ready.session.id, 1, "/recording.mp4", 1L),
                0L,
                0L,
            )
            return StartResult.Accepted
        }

        override suspend fun stop(reason: StopReason): StopResult {
            stopCount++
            lastStopReason = reason
            val active = mutableState.value
            val session = when (active) {
                is RecordingState.Ready -> active.session
                is RecordingState.Recording -> active.session
                else -> return StopResult.NotActive
            }
            mutableState.value = RecordingState.Completed(
                RecordingSessionResult(session.id, emptyList(), reason),
            )
            return StopResult.Accepted
        }

        override suspend fun pause() = PauseResult.NotRecording
        override suspend fun resume() = ResumeResult.NotRecording
        override suspend fun updateTriggerContext(context: RecordingTriggerContext) = false
        override suspend fun release() = Unit

        fun installActive(reason: RecordingStartReason, manualClaimed: Boolean = false) {
            mutableState.value = RecordingState.Ready(
                session(RecordingTriggerContext(reason, manualControlClaimed = manualClaimed)),
                audioFallbackApplied = false,
            )
        }

        private fun session(context: RecordingTriggerContext) = RecordingSession(
            id = "session",
            request = RecordingRequest(triggerContext = context),
            requestedQuality = RecordingQuality.HD,
            selectedQuality = RecordingQuality.HD,
            audioEnabled = false,
            createdAtMillis = 1L,
        )
    }
}
