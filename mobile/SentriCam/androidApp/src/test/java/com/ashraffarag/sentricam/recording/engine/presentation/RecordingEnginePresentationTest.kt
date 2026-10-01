package com.ashraffarag.sentricam.recording.engine.presentation

import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegment
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSession
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStorageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEnginePresentationTest {
    @Test
    fun uiSettingsBindDirectlyToRecordingProfile() {
        val settings = RecordingEngineUiSettings(
            profileId = RecordingProfileId.HIGH,
            segmentDurationMillis = RecordingEngineSettingsPolicy.THIRTY_SECONDS,
        )

        val profile = settings.toProfile(audioEnabled = false)

        assertEquals(RecordingProfileId.HIGH, profile.id)
        assertEquals(30_000L, profile.segmentDurationMillis)
        assertFalse(profile.audioEnabled)
    }

    @Test
    fun settingsCannotChangeDuringAnyActiveOrRotatingState() {
        val session = session()
        val segment = segment(session, 1)

        assertTrue(RecordingEngineSettingsPolicy.canEdit(RecordingState.Idle))
        assertFalse(RecordingEngineSettingsPolicy.canEdit(RecordingState.Starting(session, 1)))
        assertFalse(
            RecordingEngineSettingsPolicy.canEdit(
                RecordingState.Recording(session, segment, 1_000L, 1_000L),
            ),
        )
        assertFalse(
            RecordingEngineSettingsPolicy.canEdit(
                RecordingState.RotatingSegment(session, 1, 15_000L, 15_000L),
            ),
        )
    }

    @Test
    fun debugDurationsAndSimulationNeverAppearInReleasePolicy() {
        val debugDurations = RecordingEngineSettingsPolicy.availableSegmentDurations(isDebug = true)
        val releaseDurations = RecordingEngineSettingsPolicy.availableSegmentDurations(isDebug = false)

        assertTrue(RecordingEngineSettingsPolicy.FIFTEEN_SECONDS in debugDurations)
        assertTrue(RecordingEngineSettingsPolicy.THIRTY_SECONDS in debugDurations)
        assertFalse(RecordingEngineSettingsPolicy.FIFTEEN_SECONDS in releaseDurations)
        assertFalse(RecordingEngineSettingsPolicy.THIRTY_SECONDS in releaseDurations)
        val normalized = RecordingEngineSettingsPolicy.normalize(
            RecordingEngineUiSettings(
                segmentDurationMillis = RecordingEngineSettingsPolicy.FIFTEEN_SECONDS,
                simulateStorageWarning = true,
            ),
            isDebug = false,
        )
        assertEquals(RecordingEngineSettingsPolicy.FIVE_MINUTES, normalized.segmentDurationMillis)
        assertFalse(normalized.simulateStorageWarning)
    }

    @Test
    fun segmentIndicatorTracksNewIndexAndRotationNeverLooksIdle() {
        val session = session()
        val first = RecordingEngineStatusUiFactory.from(
            RecordingState.Recording(session, segment(session, 1), 14_000L, 14_000L),
        )
        val rotating = RecordingEngineStatusUiFactory.from(
            RecordingState.RotatingSegment(session, 1, 15_000L, 15_000L),
        )
        val second = RecordingEngineStatusUiFactory.from(
            RecordingState.Recording(session, segment(session, 2), 16_000L, 1_000L),
        )

        assertEquals(1, first.segmentIndex)
        assertEquals(RecordingEngineStatusKind.SAVING_SEGMENT, rotating.kind)
        assertTrue(rotating.visible)
        assertEquals(2, second.segmentIndex)
        assertEquals(RecordingEngineStatusKind.RECORDING, second.kind)
    }

    @Test
    fun storageWarningIsExposedByOverlayState() {
        val session = session().copy(availableStorageBytes = 200L)
        val model = RecordingEngineStatusUiFactory.from(
            RecordingState.Recording(
                session = session,
                segment = segment(session, 1),
                sessionDurationMillis = 1_000L,
                segmentDurationMillis = 1_000L,
                storageLevel = RecordingStorageLevel.WARNING,
            ),
        )

        assertEquals(RecordingStorageLevel.WARNING, model.storageLevel)
        assertEquals(200L, model.availableStorageBytes)
    }

    private fun session() = RecordingSession(
        id = "session-1234",
        request = RecordingRequest(lens = RecordingLens.FRONT),
        requestedQuality = RecordingQuality.HD,
        selectedQuality = RecordingQuality.HD,
        audioEnabled = true,
        createdAtMillis = 1L,
        availableStorageBytes = 10_000L,
    )

    private fun segment(session: RecordingSession, index: Int) = RecordingSegment(
        id = "segment-$index",
        sessionId = session.id,
        index = index,
        absolutePath = "/segment-$index.mp4",
        startedAtMillis = 1L,
    )
}
