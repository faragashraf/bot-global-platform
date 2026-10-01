package com.ashraffarag.sentricam.recording.engine.domain

enum class RecordingProfileId {
    LOW,
    STANDARD,
    HIGH,
}

enum class RecordingLens {
    BACK,
    FRONT,
}

enum class RecordingQuality {
    SD,
    HD,
    FULL_HD,
}

data class RecordingStoragePolicy(
    val minimumStartBytes: Long = 300L * MEBIBYTE,
    val warningBytes: Long = 500L * MEBIBYTE,
    val criticalBytes: Long = 250L * MEBIBYTE,
    val stopBytes: Long = 100L * MEBIBYTE,
    val checkIntervalMillis: Long = 15_000L,
) {
    fun isValid(): Boolean =
        minimumStartBytes >= 0L &&
            warningBytes >= criticalBytes &&
            criticalBytes >= stopBytes &&
            stopBytes >= 0L &&
            checkIntervalMillis >= MINIMUM_STORAGE_CHECK_INTERVAL_MILLIS

    companion object {
        private const val MEBIBYTE = 1024L * 1024L
        const val MINIMUM_STORAGE_CHECK_INTERVAL_MILLIS = 1_000L
    }
}

data class RecordingProfile(
    val id: RecordingProfileId = RecordingProfileId.STANDARD,
    val audioEnabled: Boolean = true,
    val segmentDurationMillis: Long = DEFAULT_SEGMENT_DURATION_MILLIS,
    val maximumFileSizeBytes: Long? = null,
    val storagePolicy: RecordingStoragePolicy = RecordingStoragePolicy(),
) {
    fun validationFailure(): RecordingFailure? = when {
        segmentDurationMillis !in MINIMUM_SEGMENT_DURATION_MILLIS..MAXIMUM_SEGMENT_DURATION_MILLIS ->
            RecordingFailure.invalidRequest("segment_duration")
        maximumFileSizeBytes != null && maximumFileSizeBytes <= 0L ->
            RecordingFailure.invalidRequest("maximum_file_size")
        !storagePolicy.isValid() -> RecordingFailure.invalidRequest("storage_policy")
        else -> null
    }

    companion object {
        const val DEFAULT_SEGMENT_DURATION_MILLIS = 5L * 60L * 1_000L
        const val MINIMUM_SEGMENT_DURATION_MILLIS = 1_000L
        const val MAXIMUM_SEGMENT_DURATION_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

data class RecordingRequest(
    val profile: RecordingProfile = RecordingProfile(),
    val lens: RecordingLens = RecordingLens.BACK,
    val orientationDegrees: Int = 0,
    val audioPermissionGranted: Boolean = false,
    val timestampOverlayEnabled: Boolean = false,
    val triggerContext: RecordingTriggerContext = RecordingTriggerContext.manual(),
)

enum class RecordingStartReason {
    MANUAL,
    MOTION,
    REMOTE,
}

data class RecordingMotionMetadata(
    val eventId: String,
    val detectedAtMillis: Long,
    val recordingStartedAtMillis: Long? = null,
    val lastMotionAtMillis: Long,
    val motionEndedAtMillis: Long? = null,
    val sensitivity: String,
    val peakScore: Double,
    val averageScore: Double,
    val burstCount: Int,
)

data class RecordingTriggerContext(
    val startReason: RecordingStartReason,
    val motion: RecordingMotionMetadata? = null,
    val manualControlClaimed: Boolean = false,
) {
    companion object {
        fun manual() = RecordingTriggerContext(RecordingStartReason.MANUAL)
        fun motion(metadata: RecordingMotionMetadata) = RecordingTriggerContext(
            startReason = RecordingStartReason.MOTION,
            motion = metadata,
        )
    }
}

data class RecordingSession(
    val id: String,
    val request: RecordingRequest,
    val requestedQuality: RecordingQuality,
    val selectedQuality: RecordingQuality,
    val audioEnabled: Boolean,
    val createdAtMillis: Long,
    val availableStorageBytes: Long = 0L,
    val nextSegmentIndex: Int = 1,
    val segments: List<RecordingSegmentMetadata> = emptyList(),
)

data class RecordingSegment(
    val id: String,
    val sessionId: String,
    val index: Int,
    val absolutePath: String,
    val startedAtMillis: Long,
)

data class RecordingSegmentMetadata(
    val sessionId: String,
    val segmentId: String,
    val segmentIndex: Int,
    val createdAtMillis: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationMillis: Long,
    val fileSizeBytes: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val lens: RecordingLens,
    val requestedQuality: RecordingQuality,
    val actualQuality: RecordingQuality,
    val audioEnabled: Boolean,
    val timestampOverlayEnabled: Boolean,
    val status: RecordingSegmentStatus,
    val failureCode: RecordingFailureCode? = null,
    val finalized: Boolean,
    val absolutePath: String,
    val triggerContext: RecordingTriggerContext = RecordingTriggerContext.manual(),
    val stopReason: StopReason? = null,
)

enum class RecordingSegmentStatus {
    COMPLETED,
    FAILED,
}

data class RecordingSessionResult(
    val sessionId: String,
    val segments: List<RecordingSegmentMetadata>,
    val stopReason: StopReason,
) {
    val successfulSegments: List<RecordingSegmentMetadata>
        get() = segments.filter { it.status == RecordingSegmentStatus.COMPLETED }
}

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Preparing(val requestId: String) : RecordingState
    data class Ready(val session: RecordingSession, val audioFallbackApplied: Boolean) : RecordingState
    data class Starting(val session: RecordingSession, val segmentIndex: Int) : RecordingState
    data class Recording(
        val session: RecordingSession,
        val segment: RecordingSegment,
        val sessionDurationMillis: Long,
        val segmentDurationMillis: Long,
        val paused: Boolean = false,
        val storageLevel: RecordingStorageLevel = RecordingStorageLevel.HEALTHY,
    ) : RecordingState
    data class RotatingSegment(
        val session: RecordingSession,
        val completedSegmentIndex: Int,
        val sessionDurationMillis: Long,
        val segmentDurationMillis: Long,
    ) : RecordingState
    data class Stopping(val session: RecordingSession, val reason: StopReason) : RecordingState
    data class Completed(val result: RecordingSessionResult) : RecordingState
    data class Failed(val failure: RecordingFailure, val session: RecordingSession? = null) : RecordingState
}

enum class RecordingStorageLevel {
    HEALTHY,
    WARNING,
    CRITICAL,
}

enum class StopReason {
    USER,
    MOTION_ENDED,
    REMOTE,
    LIFECYCLE,
    STORAGE_LIMIT,
    CAMERA_UNAVAILABLE,
    SEGMENT_FAILURE,
    RELEASE,
}

enum class RecoveryAction {
    NONE,
    RETRY,
    FREE_STORAGE,
    GRANT_PERMISSION,
    RESTART_CAMERA,
}

enum class RecordingFailureCode(val stableCode: String) {
    INVALID_REQUEST("invalid_request"),
    CAMERA_UNAVAILABLE("camera_unavailable"),
    PERMISSION_MISSING("permission_missing"),
    STORAGE_UNAVAILABLE("storage_unavailable"),
    INSUFFICIENT_STORAGE("insufficient_storage"),
    AUDIO_PERMISSION_MISSING("audio_permission_missing"),
    RECORDER_INITIALIZATION_FAILED("recorder_initialization_failed"),
    RECORDING_START_FAILED("recording_start_failed"),
    SEGMENT_FINALIZATION_FAILED("segment_finalization_failed"),
    UNSUPPORTED_QUALITY("unsupported_quality"),
    UNEXPECTED_FAILURE("unexpected_failure"),
}

data class RecordingFailure(
    val code: RecordingFailureCode,
    val retryAllowed: Boolean,
    val recoveryAction: RecoveryAction,
    val diagnosticTag: String? = null,
    val technicalCause: Throwable? = null,
) {
    companion object {
        fun invalidRequest(tag: String) = RecordingFailure(
            code = RecordingFailureCode.INVALID_REQUEST,
            retryAllowed = false,
            recoveryAction = RecoveryAction.NONE,
            diagnosticTag = tag,
        )
    }
}

sealed interface PrepareResult {
    data class Prepared(val session: RecordingSession, val audioFallbackApplied: Boolean) : PrepareResult
    data class Rejected(val failure: RecordingFailure) : PrepareResult
    data object Busy : PrepareResult
}

sealed interface StartResult {
    data object Accepted : StartResult
    data object AlreadyActive : StartResult
    data class Rejected(val failure: RecordingFailure) : StartResult
}

sealed interface StopResult {
    data object Accepted : StopResult
    data object AlreadyStopping : StopResult
    data object NotActive : StopResult
}

sealed interface PauseResult {
    data object Accepted : PauseResult
    data object NotRecording : PauseResult
    data object AlreadyPaused : PauseResult
}

sealed interface ResumeResult {
    data object Accepted : ResumeResult
    data object NotRecording : ResumeResult
    data object AlreadyRecording : ResumeResult
}
