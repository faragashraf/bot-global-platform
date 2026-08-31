package com.ashraffarag.sentricam.recording.engine.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEngineStateMachineTest {
    @Test
    fun rejectsIllegalTransitionsAndAllowsExpectedLifecycle() {
        val machine = RecordingEngineStateMachine()
        val request = RecordingRequest()
        val session = session(request)

        assertFalse(machine.transitionTo(RecordingState.Starting(session, 1)))
        assertTrue(machine.transitionTo(RecordingState.Preparing("request")))
        assertFalse(machine.transitionTo(RecordingState.Recording(session, segment(session), 0L, 0L)))
        assertTrue(machine.transitionTo(RecordingState.Ready(session, false)))
        assertTrue(machine.transitionTo(RecordingState.Starting(session, 1)))
        assertTrue(machine.transitionTo(RecordingState.Recording(session, segment(session), 0L, 0L)))
        assertFalse(machine.transitionTo(RecordingState.Preparing("duplicate")))
        assertTrue(machine.transitionTo(RecordingState.Stopping(session, StopReason.USER)))
        assertTrue(
            machine.transitionTo(
                RecordingState.Completed(RecordingSessionResult(session.id, emptyList(), StopReason.USER)),
            ),
        )
    }

    private fun session(request: RecordingRequest) = RecordingSession(
        id = "session",
        request = request,
        requestedQuality = RecordingQuality.HD,
        selectedQuality = RecordingQuality.HD,
        audioEnabled = false,
        createdAtMillis = 0L,
    )

    private fun segment(session: RecordingSession) = RecordingSegment(
        id = "segment",
        sessionId = session.id,
        index = 1,
        absolutePath = "/segment.mp4",
        startedAtMillis = 0L,
    )
}
