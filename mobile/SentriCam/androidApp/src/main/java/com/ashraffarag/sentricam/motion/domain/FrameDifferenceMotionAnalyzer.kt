package com.ashraffarag.sentricam.motion.domain

class FrameDifferenceMotionAnalyzer {
    private var baseline: ByteArray? = null
    private var baselineWidth = 0
    private var baselineHeight = 0

    fun reset() {
        baseline = null
        baselineWidth = 0
        baselineHeight = 0
    }

    fun analyze(frame: MotionFrame, noiseTolerance: Double): MotionAnalysis {
        if (!frame.isValid) {
            reset()
            return MotionAnalysis(0.0, 0.0, 0.0, hasBaseline = false)
        }

        val previous = baseline
        if (previous == null || baselineWidth != frame.sampledWidth || baselineHeight != frame.sampledHeight) {
            baseline = frame.luminance.copyOf()
            baselineWidth = frame.sampledWidth
            baselineHeight = frame.sampledHeight
            return MotionAnalysis(0.0, 0.0, 0.0, hasBaseline = false)
        }

        var signedDifferenceSum = 0L
        for (index in frame.luminance.indices) {
            signedDifferenceSum += unsigned(frame.luminance[index]) - unsigned(previous[index])
        }
        val globalDelta = signedDifferenceSum.toDouble() / frame.luminance.size
        val noiseFloor = noiseTolerance.coerceIn(0.0, 1.0) * MAX_LUMA
        var residualSum = 0.0
        var changedPixels = 0
        for (index in frame.luminance.indices) {
            val difference = unsigned(frame.luminance[index]) - unsigned(previous[index])
            val residual = kotlin.math.abs(difference - globalDelta)
            residualSum += residual
            if (residual > noiseFloor) changedPixels++
            previous[index] = frame.luminance[index]
        }

        val pixelCount = frame.luminance.size.toDouble()
        val residualScore = (residualSum / pixelCount / MAX_LUMA).coerceIn(0.0, 1.0)
        val changedRatio = (changedPixels / pixelCount).coerceIn(0.0, 1.0)
        val combinedScore = (residualScore * RESIDUAL_WEIGHT + changedRatio * COVERAGE_WEIGHT)
            .coerceIn(0.0, 1.0)
        return MotionAnalysis(
            score = combinedScore,
            changedPixelRatio = changedRatio,
            globalBrightnessDelta = (globalDelta / MAX_LUMA).coerceIn(-1.0, 1.0),
            hasBaseline = true,
        )
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff

    private companion object {
        const val MAX_LUMA = 255.0
        const val RESIDUAL_WEIGHT = 0.7
        const val COVERAGE_WEIGHT = 0.3
    }
}
