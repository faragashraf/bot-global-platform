package com.ashraffarag.sentricam.motion.presentation

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSettingsPolicyTest {
    @Test
    fun debugStopDelayIsExcludedFromRelease() {
        assertTrue(3_000L in MotionSettingsPolicy.stopDelays(true))
        assertFalse(3_000L in MotionSettingsPolicy.stopDelays(false))
    }

    @Test
    fun releaseNormalizesDebugDelayToSafeDefault() {
        val normalized = MotionSettingsPolicy.normalize(
            MotionDetectionConfig(enabled = true, stopDelayMillis = 3_000L),
            isDebug = false,
        )
        assertEquals(10_000L, normalized.stopDelayMillis)
    }

    @Test
    fun invalidUiOptionsUseSafeDefaults() {
        val normalized = MotionSettingsPolicy.normalize(
            MotionDetectionConfig(
                enabled = true,
                triggerDelayMillis = 9_000L,
                stopDelayMillis = 99_000L,
                cooldownMillis = 99_000L,
            ),
            isDebug = true,
        )
        assertEquals(1_000L, normalized.triggerDelayMillis)
        assertEquals(10_000L, normalized.stopDelayMillis)
        assertEquals(5_000L, normalized.cooldownMillis)
    }
}
