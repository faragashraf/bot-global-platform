package com.ashraffarag.sentricam.recording.library.android.storage

import android.util.Log
import com.ashraffarag.sentricam.recording.library.capability.RecordingMetadataReader
import com.ashraffarag.sentricam.recording.library.domain.RecordingDuration
import com.ashraffarag.sentricam.recording.library.domain.RecordingMetadata
import com.ashraffarag.sentricam.recording.library.domain.RecordingStorageInfo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AndroidRecordingMetadataReader(
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val videoMetadataProbe: AndroidVideoMetadataProbe = AndroidVideoMetadataProbe(),
) : RecordingMetadataReader {
    override fun read(storage: RecordingStorageInfo): RecordingMetadata? {
        return try {
            val videoMetadata = videoMetadataProbe.probe(storage.absolutePath)
            val sidecar = RecordingSidecarMetadataStore.read(storage.absolutePath)
            val durationMillis = sidecar?.durationMillis?.takeIf { it >= 0L }
                ?: videoMetadata.durationMillis
            val startedAt = sidecar?.startedAtMillis?.takeIf { it >= 0L }
                ?: parseStartTime(storage.fileName)
                ?: (storage.lastModifiedMillis - durationMillis).coerceAtLeast(0L)
            val finishedAt = sidecar?.endedAtMillis?.takeIf { it >= startedAt }
                ?: (startedAt + durationMillis)

            RecordingMetadata(
                startedAtMillis = startedAt,
                finishedAtMillis = finishedAt,
                duration = RecordingDuration(durationMillis),
                width = sidecar?.width?.takeIf { it > 0 } ?: videoMetadata.encodedWidth,
                height = sidecar?.height?.takeIf { it > 0 } ?: videoMetadata.encodedHeight,
                rotationDegrees = sidecar?.rotationDegrees ?: videoMetadata.rotationDegrees,
                audioEnabled = sidecar?.audioEnabled ?: videoMetadata.hasAudio,
                camera = sidecar?.camera ?: com.ashraffarag.sentricam.recording.library.domain.RecordingCamera.UNKNOWN,
                timestampOverlayEnabled = sidecar?.timestampOverlayEnabled,
                sessionId = sidecar?.sessionId,
                segmentId = sidecar?.segmentId,
                segmentIndex = sidecar?.segmentIndex,
                requestedQuality = sidecar?.requestedQuality,
                actualQuality = sidecar?.actualQuality,
                completionStatus = sidecar?.completionStatus,
                failureCode = sidecar?.failureCode,
                finalized = sidecar?.finalized,
                startReason = sidecar?.startReason,
                manualControlClaimed = sidecar?.manualControlClaimed,
                motionEventId = sidecar?.motionEventId,
                motionDetectedAtMillis = sidecar?.motionDetectedAtMillis,
                motionRecordingStartedAtMillis = sidecar?.motionRecordingStartedAtMillis,
                lastMotionAtMillis = sidecar?.lastMotionAtMillis,
                motionEndedAtMillis = sidecar?.motionEndedAtMillis,
                motionSensitivity = sidecar?.motionSensitivity,
                peakMotionScore = sidecar?.peakMotionScore,
                averageMotionScore = sidecar?.averageMotionScore,
                motionBurstCount = sidecar?.motionBurstCount,
                stopReason = sidecar?.stopReason,
            )
        } catch (failure: Throwable) {
            Log.w(TAG, "Skipping unreadable recording ${storage.fileName}", failure)
            null
        }
    }

    private fun parseStartTime(fileName: String): Long? {
        val match = V2_FILE_NAME_PATTERN.matchEntire(fileName)
            ?: LEGACY_FILE_NAME_PATTERN.matchEntire(fileName)
            ?: return null
        val timestamp = match.groupValues[1]
        val pattern = if (timestamp.length == V2_TIMESTAMP_LENGTH) V2_TIMESTAMP_PATTERN else LEGACY_TIMESTAMP_PATTERN
        return SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = this@AndroidRecordingMetadataReader.timeZone
        }.parse(timestamp)?.time
    }

    private companion object {
        const val TAG = "RecordingMetadata"
        const val LEGACY_TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"
        const val V2_TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss_SSS"
        const val V2_TIMESTAMP_LENGTH = 19
        val LEGACY_FILE_NAME_PATTERN = Regex("^SentriCam_(\\d{8}_\\d{6})(?:_\\d+)?\\.mp4$")
        val V2_FILE_NAME_PATTERN = Regex(
            "^SentriCam_(\\d{8}_\\d{6}_\\d{3})_session-[a-z0-9-]+_seg-\\d{4}_(?:back|front)(?:-\\d+)?\\.mp4$",
        )
    }
}
