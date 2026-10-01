package com.ashraffarag.sentricam.recording.overlay.domain

import com.ashraffarag.sentricam.recording.overlay.domain.RecordingOverlayGeometry.uprightOutputCorners
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecordingOverlayTimestampSourceTest {
    @Test
    fun utcPlusThreeDisplaysPhoneLocalTimeInsteadOfUtc() {
        val instant = Instant.parse("2026-08-02T21:59:58Z").toEpochMilli()

        assertEquals(
            "03/08/2026 00:59:58",
            RecordingOverlayTimestampFormatter.format(instant, ZoneId.of("UTC+03:00")),
        )
        assertEquals(
            "02/08/2026 21:59:58",
            RecordingOverlayTimestampFormatter.format(instant, ZoneId.of("UTC")),
        )
    }

    @Test
    fun currentTimezoneIsResolvedAgainAfterRestartOrTimezoneChange() {
        var zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-08-02T10:00:00Z").toEpochMilli()
        val running = RecordingOverlayTimestampSource({ now }, { zone })
        assertEquals("02/08/2026 10:00:00", running.current().text)

        zone = ZoneId.of("UTC+03:00")
        assertEquals("02/08/2026 13:00:00", running.current().text)
        val restarted = RecordingOverlayTimestampSource({ now }, { zone })
        assertEquals("02/08/2026 13:00:00", restarted.current().text)
    }

    @Test
    fun wallClockUpdatesEachSecondAndRollsOverAtLocalMidnight() {
        var now = Instant.parse("2026-08-02T20:59:59Z").toEpochMilli()
        val source = RecordingOverlayTimestampSource({ now }, { ZoneId.of("UTC+03:00") })
        assertEquals("02/08/2026 23:59:59", source.current().text)

        now += 1_000
        assertEquals("03/08/2026 00:00:00", source.current().text)
        now += 1_000
        assertEquals("03/08/2026 00:00:01", source.current().text)
    }

    @Test
    fun longRunningOverlayUsesCurrentWallClockWithoutFrameTimestampDrift() {
        var wallClock = Instant.parse("2026-08-02T00:00:00Z").toEpochMilli()
        val source = RecordingOverlayTimestampSource({ wallClock }, { ZoneId.of("UTC+03:00") })
        val start = source.current()

        val driftingFrameTimestamp = start.epochSecond + 17
        wallClock += 8 * 60 * 60 * 1_000L + 9_000L
        val later = source.current()

        assertEquals("02/08/2026 03:00:00", start.text)
        assertEquals("02/08/2026 11:00:09", later.text)
        assertFalse(later.epochSecond == driftingFrameTimestamp)
    }

    @Test
    fun portraitLandscapeAndCameraTransformsDoNotChangeTimestampValue() {
        val now = Instant.parse("2026-08-02T10:11:12Z").toEpochMilli()
        val source = RecordingOverlayTimestampSource({ now }, { ZoneId.of("UTC+03:00") })
        val expected = source.current().text

        uprightOutputCorners(1_280f, 720f, 0)
        assertEquals(expected, source.current().text)
        uprightOutputCorners(720f, 1_280f, 90)
        assertEquals(expected, source.current().text)
        uprightOutputCorners(720f, 1_280f, 270)
        assertEquals(expected, source.current().text)
    }
}
