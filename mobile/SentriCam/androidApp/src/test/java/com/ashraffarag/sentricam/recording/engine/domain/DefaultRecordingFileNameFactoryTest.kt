package com.ashraffarag.sentricam.recording.engine.domain

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRecordingFileNameFactoryTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val factory = DefaultRecordingFileNameFactory(utc)

    @Test
    fun createsSortableUniqueSegmentNamesWithLensAndSanitizedSession() {
        val timestamp = Calendar.getInstance(utc).apply {
            set(2026, Calendar.JULY, 30, 22, 15, 30)
            set(Calendar.MILLISECOND, 123)
        }.timeInMillis

        val first = factory.create(timestamp, "Session / Unsafe!?", 1, RecordingLens.BACK)
        val second = factory.create(timestamp, "Session / Unsafe!?", 2, RecordingLens.FRONT)

        assertEquals(
            "SentriCam_20260730_221530_123_session-session-unsa_seg-0001_back.mp4",
            first,
        )
        assertTrue(second.endsWith("_seg-0002_front.mp4"))
        assertNotEquals(first, second)
        assertTrue(first.matches(FILE_PATTERN))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSegmentIndex() {
        factory.create(0L, "session", 0, RecordingLens.BACK)
    }

    private companion object {
        val FILE_PATTERN = Regex(
            "SentriCam_[0-9]{8}_[0-9]{6}_[0-9]{3}_session-[a-z0-9-]+_seg-[0-9]{4}_(back|front)\\.mp4",
        )
    }
}
