package com.ashraffarag.sentricam.motion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDifferenceMotionAnalyzerTest {
    private val analyzer = FrameDifferenceMotionAnalyzer()

    @Test
    fun firstFrameOnlyEstablishesBaseline() {
        val result = analyzer.analyze(frame(ByteArray(16)), 0.01)
        assertFalse(result.hasBaseline)
        assertEquals(0.0, result.score, 0.0)
    }

    @Test
    fun smallNoiseStaysBelowMediumThreshold() {
        analyzer.analyze(frame(ByteArray(16) { 100 }), 0.012)
        val result = analyzer.analyze(frame(ByteArray(16) { if (it % 2 == 0) 102 else 99 }), 0.012)
        assertTrue(result.score < MotionSensitivityPolicy.profile(MotionSensitivity.MEDIUM).threshold)
    }

    @Test
    fun spatialDifferenceProducesStrongMotionScore() {
        analyzer.analyze(frame(ByteArray(16)), 0.01)
        val result = analyzer.analyze(frame(ByteArray(16) { if (it < 8) 0 else 255.toByte() }), 0.01)
        assertTrue(result.score > 0.2)
        assertTrue(result.changedPixelRatio >= 0.5)
    }

    @Test
    fun uniformBrightnessChangeIsNormalized() {
        analyzer.analyze(frame(ByteArray(16) { 40 }), 0.01)
        val result = analyzer.analyze(frame(ByteArray(16) { 180.toByte() }), 0.01)
        assertTrue(result.score < 0.01)
        assertTrue(result.globalBrightnessDelta > 0.5)
    }

    @Test
    fun dimensionChangeResetsBaselineSafely() {
        analyzer.analyze(frame(ByteArray(16), 4, 4), 0.01)
        val result = analyzer.analyze(frame(ByteArray(12), 4, 3), 0.01)
        assertFalse(result.hasBaseline)
        assertEquals(0.0, result.score, 0.0)
    }

    @Test
    fun invalidFrameHasSafeZeroScore() {
        val result = analyzer.analyze(MotionFrame(ByteArray(0), 0, 0, 1L, 1L), 0.01)
        assertFalse(result.hasBaseline)
        assertEquals(0.0, result.safeScore, 0.0)
    }

    private fun frame(bytes: ByteArray, width: Int = 4, height: Int = 4) =
        MotionFrame(bytes, width, height, 1L, 1L)
}
