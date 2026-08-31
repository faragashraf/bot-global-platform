package com.ashraffarag.sentricam.recording.domain

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFormattingTest {
    @Test
    fun filenameUsesSentriCamTimestampAndCollisionSuffix() {
        val utc = TimeZone.getTimeZone("UTC")
        val timestamp = Calendar.getInstance(utc).apply {
            set(2026, Calendar.JULY, 30, 12, 34, 56)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(
            "SentriCam_20260730_123456.mp4",
            RecordingFileName.create(timestamp, timeZone = utc),
        )
        assertEquals(
            "SentriCam_20260730_123456_1.mp4",
            RecordingFileName.create(timestamp, sequence = 1, timeZone = utc),
        )
        assertTrue(RecordingFileName.create(timestamp, timeZone = utc).matches(FILE_PATTERN))
    }

    @Test(expected = IllegalArgumentException::class)
    fun filenameRejectsNegativeSequence() {
        RecordingFileName.create(0L, sequence = -1)
    }

    @Test
    fun durationFormatsAsHoursMinutesAndSeconds() {
        assertEquals("00:00:00", RecordingDurationFormatter.format(0L))
        assertEquals("00:01:05", RecordingDurationFormatter.format(65_999L))
        assertEquals("12:34:56", RecordingDurationFormatter.format(45_296_000L))
        assertEquals("00:00:00", RecordingDurationFormatter.format(-1L))
    }

    private companion object {
        val FILE_PATTERN = Regex("SentriCam_[0-9]{8}_[0-9]{6}\\.mp4")
    }
}
