package com.ashraffarag.sentricam.motion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSensitivityPolicyTest {
    @Test
    fun lowMediumAndHighUseDocumentedSafePresets() {
        val low = MotionSensitivityPolicy.profile(MotionSensitivity.LOW)
        val medium = MotionSensitivityPolicy.profile(MotionSensitivity.MEDIUM)
        val high = MotionSensitivityPolicy.profile(MotionSensitivity.HIGH)

        assertEquals(0.120, low.threshold, 0.0001)
        assertEquals(4, low.requiredPositiveFrames)
        assertEquals(0.075, medium.threshold, 0.0001)
        assertEquals(3, medium.requiredPositiveFrames)
        assertEquals(0.040, high.threshold, 0.0001)
        assertEquals(2, high.requiredPositiveFrames)
        assertTrue(low.changedAreaThreshold > medium.changedAreaThreshold)
        assertTrue(medium.changedAreaThreshold > high.changedAreaThreshold)
    }

    @Test
    fun advancedSliderIsClampedAndMonotonicAcrossFullRange() {
        val minimum = MotionSensitivityPolicy.profile(MotionSensitivity.ADVANCED, -100)
        val midpoint = MotionSensitivityPolicy.profile(MotionSensitivity.ADVANCED, 50)
        val maximum = MotionSensitivityPolicy.profile(MotionSensitivity.ADVANCED, 500)

        assertEquals(0, MotionSensitivityPolicy.sanitizeAdvancedValue(-100))
        assertEquals(100, MotionSensitivityPolicy.sanitizeAdvancedValue(500))
        assertTrue(minimum.threshold > midpoint.threshold)
        assertTrue(midpoint.threshold > maximum.threshold)
        assertTrue(minimum.requiredPositiveFrames >= midpoint.requiredPositiveFrames)
        assertTrue(midpoint.requiredPositiveFrames >= maximum.requiredPositiveFrames)
        listOf(minimum, midpoint, maximum).forEach { profile ->
            assertTrue(profile.threshold in 0.01..0.20)
            assertTrue(profile.noiseTolerance in 0.001..0.05)
            assertTrue(profile.changedAreaThreshold in 0.01..0.20)
            assertTrue(profile.brightnessChangeTolerance in 0.10..0.40)
            assertTrue(profile.requiredPositiveFrames in 2..5)
        }
    }

    @Test
    fun presetResolutionRetainsButNeverAppliesTheCustomValue() {
        val lowCustom = MotionSensitivityPolicy.resolve(MotionSensitivity.LOW, 8)
        val highCustom = MotionSensitivityPolicy.resolve(MotionSensitivity.LOW, 92)

        assertEquals(8, lowCustom.retainedAdvancedSensitivity)
        assertEquals(92, highCustom.retainedAdvancedSensitivity)
        assertEquals(false, lowCustom.advancedSensitivityActive)
        assertEquals(false, highCustom.advancedSensitivityActive)
        assertEquals(lowCustom.effectiveProfile, highCustom.effectiveProfile)
        assertEquals(MotionSensitivityPolicy.profile(MotionSensitivity.LOW), lowCustom.effectiveProfile)
    }

    @Test
    fun switchingBackToAdvancedRestoresAndAppliesTheRetainedCustomValue() {
        val retained = 83
        val preset = MotionSensitivityPolicy.resolve(MotionSensitivity.MEDIUM, retained)
        val advanced = MotionSensitivityPolicy.resolve(MotionSensitivity.ADVANCED, retained)

        assertEquals(retained, preset.retainedAdvancedSensitivity)
        assertEquals(false, preset.advancedSensitivityActive)
        assertEquals(true, advanced.advancedSensitivityActive)
        assertEquals(retained, advanced.retainedAdvancedSensitivity)
        assertEquals(MotionSensitivityPolicy.profile(MotionSensitivity.ADVANCED, retained), advanced.effectiveProfile)
        assertTrue(preset.effectiveProfile != advanced.effectiveProfile)
    }

    @Test
    fun advancedConfigValidationRejectsNoAllowedSliderValue() {
        for (value in 0..100) {
            val config = MotionDetectionConfig(
                enabled = true,
                sensitivity = MotionSensitivity.ADVANCED,
                advancedSensitivity = value,
            )
            assertEquals(null, config.validationFailure())
        }
    }
}
