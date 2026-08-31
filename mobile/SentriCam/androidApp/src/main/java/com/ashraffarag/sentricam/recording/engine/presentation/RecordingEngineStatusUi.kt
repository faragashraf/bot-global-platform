package com.ashraffarag.sentricam.recording.engine.presentation

import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStorageLevel

enum class RecordingEngineStatusKind {
    STARTING,
    RECORDING,
    SAVING_SEGMENT,
    STOPPING,
}

data class RecordingEngineStatusUi(
    val visible: Boolean,
    val kind: RecordingEngineStatusKind? = null,
    val sessionDurationMillis: Long = 0L,
    val segmentDurationMillis: Long = 0L,
    val segmentIndex: Int = 0,
    val quality: RecordingQuality? = null,
    val lens: RecordingLens? = null,
    val availableStorageBytes: Long = 0L,
    val storageLevel: RecordingStorageLevel = RecordingStorageLevel.HEALTHY,
)

object RecordingEngineStatusUiFactory {
    fun from(state: RecordingState): RecordingEngineStatusUi = when (state) {
        is RecordingState.Starting -> active(
            state = state,
            kind = RecordingEngineStatusKind.STARTING,
            segmentIndex = state.segmentIndex,
            sessionDurationMillis = state.session.segments.sumOf { it.durationMillis },
        )
        is RecordingState.Recording -> RecordingEngineStatusUi(
            visible = true,
            kind = RecordingEngineStatusKind.RECORDING,
            sessionDurationMillis = state.sessionDurationMillis,
            segmentDurationMillis = state.segmentDurationMillis,
            segmentIndex = state.segment.index,
            quality = state.session.selectedQuality,
            lens = state.session.request.lens,
            availableStorageBytes = state.session.availableStorageBytes,
            storageLevel = state.storageLevel,
        )
        is RecordingState.RotatingSegment -> active(
            state = state,
            kind = RecordingEngineStatusKind.SAVING_SEGMENT,
            segmentIndex = state.completedSegmentIndex,
            sessionDurationMillis = state.sessionDurationMillis,
            segmentDurationMillis = state.segmentDurationMillis,
        )
        is RecordingState.Stopping -> active(
            state = state,
            kind = RecordingEngineStatusKind.STOPPING,
            segmentIndex = state.session.nextSegmentIndex,
            sessionDurationMillis = state.session.segments.sumOf { it.durationMillis },
        )
        else -> RecordingEngineStatusUi(visible = false)
    }

    private fun active(
        state: RecordingState,
        kind: RecordingEngineStatusKind,
        segmentIndex: Int,
        sessionDurationMillis: Long,
        segmentDurationMillis: Long = 0L,
    ): RecordingEngineStatusUi {
        val session = when (state) {
            is RecordingState.Starting -> state.session
            is RecordingState.RotatingSegment -> state.session
            is RecordingState.Stopping -> state.session
            else -> error("State has no active session")
        }
        return RecordingEngineStatusUi(
            visible = true,
            kind = kind,
            sessionDurationMillis = sessionDurationMillis,
            segmentDurationMillis = segmentDurationMillis,
            segmentIndex = segmentIndex,
            quality = session.selectedQuality,
            lens = session.request.lens,
            availableStorageBytes = session.availableStorageBytes,
        )
    }
}
