package com.ashraffarag.sentricam.recording.upload.android

import android.content.Context
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSegmentStatus
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.library.android.storage.AndroidRecordingMetadataReader
import com.ashraffarag.sentricam.recording.library.android.storage.AppSpecificRecordingScanner
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadCandidate

class AndroidRecordingUploadCandidateScanner(context: Context) {
    private val scanner = AppSpecificRecordingScanner(context.applicationContext)
    private val metadataReader = AndroidRecordingMetadataReader()

    fun scan(): List<RecordingUploadCandidate> = scanner.scan().mapNotNull { storage ->
        val metadata = metadataReader.read(storage) ?: return@mapNotNull null
        val clientId = metadata.segmentId ?: return@mapNotNull null
        val sessionId = metadata.sessionId ?: return@mapNotNull null
        if (metadata.completionStatus != RecordingSegmentStatus.COMPLETED || metadata.finalized != true) {
            return@mapNotNull null
        }
        val motion = metadata.startReason == RecordingStartReason.MOTION
        RecordingUploadCandidate(
            clientRecordingId = clientId,
            sessionId = sessionId,
            absolutePath = storage.absolutePath,
            fileName = storage.fileName,
            durationMillis = metadata.duration.millis,
            sizeBytes = storage.sizeBytes,
            createdAtMillis = metadata.startedAtMillis,
            motion = motion,
            manual = !motion,
        )
    }
}
