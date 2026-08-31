package com.ashraffarag.sentricam.recording.engine.domain

import com.ashraffarag.sentricam.recording.library.domain.RecordingCamera
import com.ashraffarag.sentricam.recording.library.domain.RecordingDuration
import com.ashraffarag.sentricam.recording.library.domain.RecordingMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingModelsTest {
    @Test
    fun invalidSegmentDurationAndStoragePolicyAreRejected() {
        assertEquals(
            RecordingFailureCode.INVALID_REQUEST,
            RecordingProfile(segmentDurationMillis = 0L).validationFailure()?.code,
        )
        assertFalse(
            RecordingStoragePolicy(
                warningBytes = 10L,
                criticalBytes = 20L,
                stopBytes = 5L,
            ).isValid(),
        )
    }

    @Test
    fun oldLibraryMetadataKeepsSafeV2Fallbacks() {
        val legacy = RecordingMetadata(
            startedAtMillis = 1L,
            finishedAtMillis = 1L,
            duration = RecordingDuration(0L),
            width = 0,
            height = 0,
            audioEnabled = false,
            camera = RecordingCamera.UNKNOWN,
        )

        assertNull(legacy.sessionId)
        assertNull(legacy.segmentId)
        assertNull(legacy.actualQuality)
        assertNull(legacy.finalized)
        assertNull(legacy.startReason)
        assertNull(legacy.motionEventId)
        assertNull(legacy.stopReason)
        assertEquals(0, legacy.displayDimensions.width)
        assertEquals(0, legacy.displayDimensions.height)
    }

    @Test
    fun motionTriggerMetadataPreservesEventStatistics() {
        val motion = RecordingMotionMetadata(
            eventId = "event-1",
            detectedAtMillis = 10L,
            lastMotionAtMillis = 20L,
            sensitivity = "HIGH",
            peakScore = 0.9,
            averageScore = 0.4,
            burstCount = 2,
        )
        val context = RecordingTriggerContext.motion(motion)
        assertEquals(RecordingStartReason.MOTION, context.startReason)
        assertEquals("event-1", context.motion?.eventId)
        assertEquals(2, context.motion?.burstCount)
    }
}
