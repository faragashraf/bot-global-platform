package com.ashraffarag.sentricam.recording.engine.presentation

import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfile
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState

data class RecordingEngineUiSettings(
    val profileId: RecordingProfileId = RecordingProfileId.STANDARD,
    val segmentDurationMillis: Long = RecordingProfile.DEFAULT_SEGMENT_DURATION_MILLIS,
    val simulateStorageWarning: Boolean = false,
) {
    fun toProfile(audioEnabled: Boolean): RecordingProfile = RecordingProfile(
        id = profileId,
        audioEnabled = audioEnabled,
        segmentDurationMillis = segmentDurationMillis,
    )
}

object RecordingEngineSettingsPolicy {
    const val FIFTEEN_SECONDS = 15_000L
    const val THIRTY_SECONDS = 30_000L
    const val ONE_MINUTE = 60_000L
    const val FIVE_MINUTES = 5L * 60_000L
    const val TEN_MINUTES = 10L * 60_000L

    fun availableSegmentDurations(isDebug: Boolean): List<Long> = buildList {
        if (isDebug) {
            add(FIFTEEN_SECONDS)
            add(THIRTY_SECONDS)
        }
        add(ONE_MINUTE)
        add(FIVE_MINUTES)
        add(TEN_MINUTES)
    }

    fun normalize(settings: RecordingEngineUiSettings, isDebug: Boolean): RecordingEngineUiSettings {
        val durations = availableSegmentDurations(isDebug)
        return settings.copy(
            segmentDurationMillis = settings.segmentDurationMillis.takeIf(durations::contains)
                ?: FIVE_MINUTES,
            simulateStorageWarning = settings.simulateStorageWarning && isDebug,
        )
    }

    fun canEdit(state: RecordingState): Boolean = when (state) {
        RecordingState.Idle,
        is RecordingState.Ready,
        is RecordingState.Completed,
        is RecordingState.Failed,
        -> true
        is RecordingState.Preparing,
        is RecordingState.Starting,
        is RecordingState.Recording,
        is RecordingState.RotatingSegment,
        is RecordingState.Stopping,
        -> false
    }
}
