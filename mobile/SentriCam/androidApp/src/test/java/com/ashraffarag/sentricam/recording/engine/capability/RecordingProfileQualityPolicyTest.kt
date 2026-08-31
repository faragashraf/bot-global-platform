package com.ashraffarag.sentricam.recording.engine.capability

import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingProfileQualityPolicyTest {
    @Test
    fun unsupportedHighFallsBackToHdThenSd() {
        assertEquals(
            RecordingQuality.HD,
            RecordingProfileQualityPolicy.select(
                RecordingProfileId.HIGH,
                setOf(RecordingQuality.HD, RecordingQuality.SD),
            ),
        )
        assertEquals(
            RecordingQuality.SD,
            RecordingProfileQualityPolicy.select(
                RecordingProfileId.HIGH,
                setOf(RecordingQuality.SD),
            ),
        )
        assertNull(RecordingProfileQualityPolicy.select(RecordingProfileId.HIGH, emptySet()))
    }

    @Test
    fun standardPrefersHdAndLowPrefersSd() {
        val all = RecordingQuality.entries.toSet()
        assertEquals(RecordingQuality.HD, RecordingProfileQualityPolicy.select(RecordingProfileId.STANDARD, all))
        assertEquals(RecordingQuality.SD, RecordingProfileQualityPolicy.select(RecordingProfileId.LOW, all))
    }
}
