package com.ashraffarag.sentricam.recording.engine.capability

import com.ashraffarag.sentricam.recording.engine.domain.RecordingFailure
import com.ashraffarag.sentricam.recording.engine.domain.RecordingLens
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentMetadata
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStorageLevel
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStoragePolicy

fun interface RecordingClock {
    fun nowMillis(): Long
}

fun interface RecordingIdFactory {
    fun createId(): String
}

interface RecordingFileNameFactory {
    fun create(
        timestampMillis: Long,
        sessionId: String,
        segmentIndex: Int,
        lens: RecordingLens,
    ): String
}

interface RecordingStorageGateway {
    suspend fun validateForStart(policy: RecordingStoragePolicy): RecordingStorageCheck

    suspend fun createTarget(fileName: String): RecordingTargetResult

    suspend fun currentStatus(policy: RecordingStoragePolicy): RecordingStorageCheck
}

sealed interface RecordingStorageCheck {
    data class Available(
        val availableBytes: Long,
        val level: RecordingStorageLevel,
        val mustStop: Boolean = false,
    ) : RecordingStorageCheck
    data class Unavailable(val failure: RecordingFailure) : RecordingStorageCheck
}

sealed interface RecordingTargetResult {
    data class Created(val absolutePath: String) : RecordingTargetResult
    data class Failed(val failure: RecordingFailure) : RecordingTargetResult
}

fun interface RecordingQualityProvider {
    fun supportedQualities(): Set<RecordingQuality>
}

interface RecordingMetadataStore {
    suspend fun save(metadata: RecordingSegmentMetadata)
}

interface RecordingScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): RecordingScheduledTask
}

fun interface RecordingScheduledTask {
    fun cancel()
}

interface SegmentRecorder {
    fun start(request: SegmentStartRequest, listener: SegmentRecorderListener): SegmentRecordingHandle

    fun release() = Unit
}

data class SegmentStartRequest(
    val sessionId: String,
    val segmentId: String,
    val segmentIndex: Int,
    val absolutePath: String,
    val audioEnabled: Boolean,
    val maximumFileSizeBytes: Long?,
)

interface SegmentRecordingHandle {
    fun stop()
    fun pause()
    fun resume()
}

interface SegmentRecorderListener {
    fun onStarted(sessionId: String, segmentId: String)
    fun onFinalized(sessionId: String, segmentId: String, result: SegmentFinalizeResult)
}

sealed interface SegmentFinalizeResult {
    data class Success(
        val output: FinalizedSegmentOutput,
        val continueSession: Boolean = false,
    ) : SegmentFinalizeResult
    data class Failure(
        val failure: RecordingFailure,
        val partialOutput: FinalizedSegmentOutput? = null,
    ) : SegmentFinalizeResult
}

data class FinalizedSegmentOutput(
    val durationMillis: Long,
    val fileSizeBytes: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val actualQuality: RecordingQuality,
    val audioEnabled: Boolean,
    val finalized: Boolean,
)

object RecordingProfileQualityPolicy {
    fun requestedQuality(profile: RecordingProfileId): RecordingQuality = when (profile) {
        RecordingProfileId.LOW -> RecordingQuality.SD
        RecordingProfileId.STANDARD -> RecordingQuality.HD
        RecordingProfileId.HIGH -> RecordingQuality.FULL_HD
    }

    fun select(
        profile: RecordingProfileId,
        supported: Set<RecordingQuality>,
    ): RecordingQuality? {
        val candidates = when (profile) {
            RecordingProfileId.LOW -> listOf(RecordingQuality.SD, RecordingQuality.HD)
            RecordingProfileId.STANDARD -> listOf(RecordingQuality.HD, RecordingQuality.SD)
            RecordingProfileId.HIGH -> listOf(
                RecordingQuality.FULL_HD,
                RecordingQuality.HD,
                RecordingQuality.SD,
            )
        }
        return candidates.firstOrNull(supported::contains)
    }
}
