package com.ashraffarag.sentricam.recording.engine.domain

import com.ashraffarag.sentricam.recording.engine.capability.RecordingFileNameFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DefaultRecordingFileNameFactory(
    private val timeZone: TimeZone = TimeZone.getDefault(),
) : RecordingFileNameFactory {
    override fun create(
        timestampMillis: Long,
        sessionId: String,
        segmentIndex: Int,
        lens: RecordingLens,
    ): String {
        require(segmentIndex > 0) { "Segment index must be positive" }
        val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).apply {
            timeZone = this@DefaultRecordingFileNameFactory.timeZone
        }.format(Date(timestampMillis.coerceAtLeast(0L)))
        val safeSessionId = sessionId.lowercase(Locale.US)
            .replace(INVALID_ID_CHARACTERS, "-")
            .trim('-')
            .take(MAX_SESSION_ID_LENGTH)
            .ifBlank { DEFAULT_SESSION_ID }
        val lensName = when (lens) {
            RecordingLens.BACK -> "back"
            RecordingLens.FRONT -> "front"
        }
        return "SentriCam_${timestamp}_session-${safeSessionId}_seg-${segmentIndex.toString().padStart(4, '0')}_${lensName}.mp4"
    }

    private companion object {
        const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss_SSS"
        const val MAX_SESSION_ID_LENGTH = 12
        const val DEFAULT_SESSION_ID = "session"
        val INVALID_ID_CHARACTERS = Regex("[^a-z0-9-]+")
    }
}
