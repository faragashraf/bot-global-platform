package com.ashraffarag.sentricam.recording.upload.capability

import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentMetadata
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentStatus
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSessionResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadCandidate
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadQueue
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadScheduler

class RecordingUploadCoordinator(
    private val queue: RecordingUploadQueue,
    private val scheduler: RecordingUploadScheduler,
) {
    fun enqueueCompleted(result: RecordingSessionResult, schedule: Boolean): Int =
        enqueue(result.segments.mapNotNull(::toCandidate), schedule)

    fun enqueueCompleted(segment: RecordingSegmentMetadata, schedule: Boolean): Boolean {
        val candidate = toCandidate(segment) ?: return false
        val existing = queue.get(candidate.clientRecordingId)
        val item = queue.enqueue(candidate)
        if (schedule && existing == null && item.serverRecordingId == null) {
            scheduler.schedule(item.clientRecordingId, replaceCompleted = false)
        }
        return existing == null
    }

    fun enqueue(candidates: List<RecordingUploadCandidate>, schedule: Boolean): Int {
        candidates.forEach { candidate ->
            val existing = queue.get(candidate.clientRecordingId)
            val item = queue.enqueue(candidate)
            if (schedule && existing == null && item.serverRecordingId == null) {
                scheduler.schedule(item.clientRecordingId, replaceCompleted = false)
            }
        }
        return candidates.size
    }

    fun restoreAndSchedule(): Int {
        queue.recoverInterrupted()
        val pending = queue.pending()
        pending.forEach { scheduler.schedule(it.clientRecordingId, replaceCompleted = false) }
        return pending.size
    }

    fun retryFailedAfterRegistration(): Int {
        val reset = queue.resetFailed()
        reset.forEach { scheduler.schedule(it.clientRecordingId, replaceCompleted = true) }
        return reset.size
    }

    private fun toCandidate(segment: RecordingSegmentMetadata): RecordingUploadCandidate? {
        if (segment.status != RecordingSegmentStatus.COMPLETED || !segment.finalized || segment.fileSizeBytes <= 0) {
            return null
        }
        val motion = segment.triggerContext.startReason == RecordingStartReason.MOTION
        return RecordingUploadCandidate(
            clientRecordingId = segment.segmentId,
            sessionId = segment.sessionId,
            absolutePath = segment.absolutePath,
            fileName = java.io.File(segment.absolutePath).name,
            durationMillis = segment.durationMillis,
            sizeBytes = segment.fileSizeBytes,
            createdAtMillis = segment.startedAtMillis,
            motion = motion,
            manual = !motion,
        )
    }
}
