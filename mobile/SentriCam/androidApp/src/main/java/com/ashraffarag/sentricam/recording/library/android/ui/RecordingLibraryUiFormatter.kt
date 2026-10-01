package com.ashraffarag.sentricam.recording.library.android.ui

import com.ashraffarag.sentricam.recording.domain.RecordingDurationFormatter
import com.ashraffarag.sentricam.recording.library.domain.RecordingDuration
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object RecordingLibraryUiFormatter {
    fun duration(duration: RecordingDuration): String =
        RecordingDurationFormatter.format(duration.millis)

    fun dateTime(timestampMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timestampMillis))

    fun time(timestampMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMillis))

    fun resolution(width: Int, height: Int): String = when {
        width <= 0 || height <= 0 -> "—"
        else -> String.format(Locale.US, "%d × %d", width, height)
    }

    fun fileSize(sizeBytes: Long): String {
        val safeBytes = sizeBytes.coerceAtLeast(0L)
        if (safeBytes < BYTES_PER_KILOBYTE) return "$safeBytes B"
        val kilobytes = safeBytes.toDouble() / BYTES_PER_KILOBYTE
        if (kilobytes < BYTES_PER_KILOBYTE) return String.format(Locale.US, "%.1f KB", kilobytes)
        val megabytes = kilobytes / BYTES_PER_KILOBYTE
        if (megabytes < BYTES_PER_KILOBYTE) return String.format(Locale.US, "%.1f MB", megabytes)
        return String.format(Locale.US, "%.2f GB", megabytes / BYTES_PER_KILOBYTE)
    }

    private const val BYTES_PER_KILOBYTE = 1_024.0
}
