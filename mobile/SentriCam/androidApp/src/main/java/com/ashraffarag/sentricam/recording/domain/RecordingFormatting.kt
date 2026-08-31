package com.ashraffarag.sentricam.recording.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object RecordingFileName {
    fun create(
        timestampMillis: Long,
        sequence: Int = 0,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        require(sequence >= 0)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            this.timeZone = timeZone
        }.format(Date(timestampMillis))
        val suffix = if (sequence == 0) "" else "_$sequence"
        return "SentriCam_${timestamp}$suffix.mp4"
    }
}

object RecordingDurationFormatter {
    fun format(durationMillis: Long): String {
        val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
