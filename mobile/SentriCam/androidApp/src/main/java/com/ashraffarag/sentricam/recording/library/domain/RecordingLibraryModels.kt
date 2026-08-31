package com.ashraffarag.sentricam.recording.library.domain

import com.ashraffarag.sentricam.camera.domain.CameraOutputGeometry
import com.ashraffarag.sentricam.camera.domain.PixelDimensions
import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailureCode
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentStatus
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.StopReason

data class RecordingEntry(
    val id: String,
    val fileName: String,
    val metadata: RecordingMetadata,
    val storage: RecordingStorageInfo,
)

data class RecordingMetadata(
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val duration: RecordingDuration,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val audioEnabled: Boolean,
    val camera: RecordingCamera = RecordingCamera.UNKNOWN,
    val timestampOverlayEnabled: Boolean? = null,
    val sessionId: String? = null,
    val segmentId: String? = null,
    val segmentIndex: Int? = null,
    val requestedQuality: RecordingQuality? = null,
    val actualQuality: RecordingQuality? = null,
    val completionStatus: RecordingSegmentStatus? = null,
    val failureCode: RecordingFailureCode? = null,
    val finalized: Boolean? = null,
    val startReason: RecordingStartReason? = null,
    val motionEventId: String? = null,
    val motionDetectedAtMillis: Long? = null,
    val motionRecordingStartedAtMillis: Long? = null,
    val lastMotionAtMillis: Long? = null,
    val motionEndedAtMillis: Long? = null,
    val motionSensitivity: String? = null,
    val peakMotionScore: Double? = null,
    val averageMotionScore: Double? = null,
    val motionBurstCount: Int? = null,
    val stopReason: StopReason? = null,
    val manualControlClaimed: Boolean? = null,
) {
    val displayDimensions: PixelDimensions
        get() = CameraOutputGeometry.effectiveDimensions(width, height, rotationDegrees)
}

@JvmInline
value class RecordingDuration(val millis: Long) {
    init {
        require(millis >= 0L)
    }
}

data class RecordingStorageInfo(
    val id: String,
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

enum class RecordingCamera {
    REAR,
    FRONT,
    UNKNOWN,
}

enum class RecordingDateGroup {
    TODAY,
    YESTERDAY,
    OLDER,
}

enum class RecordingDateFilter {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    ALL,
}

data class RecordingLibraryQuery(
    val searchText: String = "",
    val filter: RecordingDateFilter = RecordingDateFilter.ALL,
)

data class RecordingSection(
    val group: RecordingDateGroup,
    val recordings: List<RecordingEntry>,
)
