package com.ashraffarag.sentricam.recording.engine.android.ui

import com.ashraffarag.sentricam.recording.domain.RecordingDurationFormatter
import java.util.Locale

object RecordingEngineUiFormatter {
    fun duration(durationMillis: Long): String = RecordingDurationFormatter.format(durationMillis)

    fun storageSize(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val mebibytes = safeBytes.toDouble() / MEBIBYTE
        return if (mebibytes < MEBIBYTES_PER_GIBIBYTE) {
            String.format(Locale.US, "%.0f MB", mebibytes)
        } else {
            String.format(Locale.US, "%.1f GB", mebibytes / MEBIBYTES_PER_GIBIBYTE)
        }
    }

    fun shortDuration(durationMillis: Long): String = when {
        durationMillis < MILLIS_PER_MINUTE -> "${durationMillis / 1_000L}s"
        else -> "${durationMillis / MILLIS_PER_MINUTE}m"
    }

    private const val MEBIBYTE = 1024.0 * 1024.0
    private const val MEBIBYTES_PER_GIBIBYTE = 1024.0
    private const val MILLIS_PER_MINUTE = 60_000L
}
