package com.ashraffarag.sentricam.recording.overlay.domain

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration

class RecordingOverlayTimestampFormatterTest {
    @Test
    fun timestampUsesTheApprovedVideoFormat() {
        val utc = TimeZone.getTimeZone("UTC")
        val timestamp = Calendar.getInstance(utc).apply {
            set(2026, Calendar.JULY, 30, 15, 33, 48)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(
            "30/07/2026 15:33:48",
            RecordingOverlayTimestampFormatter.format(timestamp, utc),
        )
    }


    @Test
    fun sharedConfigurationControlsDateTimeAndTwelveHourFormatting() {
        val utc = TimeZone.getTimeZone("UTC")
        val timestamp = Calendar.getInstance(utc).apply {
            set(2026, Calendar.JULY, 30, 15, 33, 48)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(
            "03:33:48 PM",
            RecordingOverlayTimestampFormatter.format(
                timestamp,
                utc.toZoneId(),
                RecordingOverlayConfiguration(enabled = true, dateEnabled = false, use24HourTime = false),
            ),
        )
    }
}
