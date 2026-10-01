package com.ashraffarag.sentricam.motion.domain

enum class MotionSensitivity {
    LOW,
    MEDIUM,
    HIGH,
    ADVANCED,
}

data class MotionSensitivityProfile(
    val threshold: Double,
    val requiredPositiveFrames: Int,
    val noiseTolerance: Double,
    val changedAreaThreshold: Double,
    val brightnessChangeTolerance: Double,
    val confirmationBehavior: MotionConfirmationBehavior,
)

data class ResolvedMotionSensitivity(
    val selectedMode: MotionSensitivity,
    val retainedAdvancedSensitivity: Int,
    val advancedSensitivityActive: Boolean,
    val effectiveProfile: MotionSensitivityProfile,
)

enum class MotionConfirmationBehavior { STRICT, BALANCED, FAST }

object MotionSensitivityPolicy {
    const val MIN_ADVANCED_VALUE = 0
    const val MAX_ADVANCED_VALUE = 100
    const val DEFAULT_ADVANCED_VALUE = 50

    fun resolve(
        sensitivity: MotionSensitivity,
        advancedValue: Int = DEFAULT_ADVANCED_VALUE,
    ): ResolvedMotionSensitivity {
        val retainedAdvanced = sanitizeAdvancedValue(advancedValue)
        val profile = when (sensitivity) {
            MotionSensitivity.LOW -> MotionSensitivityProfile(
                threshold = 0.120,
                requiredPositiveFrames = 4,
                noiseTolerance = 0.020,
                changedAreaThreshold = 0.080,
                brightnessChangeTolerance = 0.180,
                confirmationBehavior = MotionConfirmationBehavior.STRICT,
            )
            MotionSensitivity.MEDIUM -> MotionSensitivityProfile(
                threshold = 0.075,
                requiredPositiveFrames = 3,
                noiseTolerance = 0.014,
                changedAreaThreshold = 0.050,
                brightnessChangeTolerance = 0.220,
                confirmationBehavior = MotionConfirmationBehavior.BALANCED,
            )
            MotionSensitivity.HIGH -> MotionSensitivityProfile(
                threshold = 0.040,
                requiredPositiveFrames = 2,
                noiseTolerance = 0.008,
                changedAreaThreshold = 0.025,
                brightnessChangeTolerance = 0.280,
                confirmationBehavior = MotionConfirmationBehavior.FAST,
            )
            MotionSensitivity.ADVANCED -> advancedProfile(retainedAdvanced)
        }
        return ResolvedMotionSensitivity(
            selectedMode = sensitivity,
            retainedAdvancedSensitivity = retainedAdvanced,
            advancedSensitivityActive = sensitivity == MotionSensitivity.ADVANCED,
            effectiveProfile = profile,
        )
    }

    fun profile(
        sensitivity: MotionSensitivity,
        advancedValue: Int = DEFAULT_ADVANCED_VALUE,
    ): MotionSensitivityProfile = resolve(sensitivity, advancedValue).effectiveProfile

    fun sanitizeAdvancedValue(value: Int): Int = value.coerceIn(MIN_ADVANCED_VALUE, MAX_ADVANCED_VALUE)

    private fun advancedProfile(value: Int): MotionSensitivityProfile {
        val normalized = sanitizeAdvancedValue(value) / 100.0
        return MotionSensitivityProfile(
            threshold = interpolate(0.140, 0.030, normalized),
            requiredPositiveFrames = (5.0 - normalized * 3.0).toInt().coerceIn(2, 5),
            noiseTolerance = interpolate(0.025, 0.006, normalized),
            changedAreaThreshold = interpolate(0.100, 0.015, normalized),
            brightnessChangeTolerance = interpolate(0.160, 0.300, normalized),
            confirmationBehavior = when {
                normalized < 0.34 -> MotionConfirmationBehavior.STRICT
                normalized > 0.66 -> MotionConfirmationBehavior.FAST
                else -> MotionConfirmationBehavior.BALANCED
            },
        )
    }

    private fun interpolate(start: Double, end: Double, fraction: Double): Double =
        (start + (end - start) * fraction.coerceIn(0.0, 1.0)).coerceIn(
            minOf(start, end),
            maxOf(start, end),
        )
}

data class MotionDetectionConfig(
    val enabled: Boolean = false,
    val sensitivity: MotionSensitivity = MotionSensitivity.MEDIUM,
    val advancedSensitivity: Int = MotionSensitivityPolicy.DEFAULT_ADVANCED_VALUE,
    val triggerDelayMillis: Long = 1_000L,
    val stopDelayMillis: Long = 10_000L,
    val cooldownMillis: Long = 5_000L,
    val frameIntervalMillis: Long = 150L,
    val warmupFrameCount: Int = 6,
) {
    fun resolvedSensitivity(): ResolvedMotionSensitivity =
        MotionSensitivityPolicy.resolve(sensitivity, advancedSensitivity)

    fun validationFailure(): MotionFailure? = when {
        advancedSensitivity !in MotionSensitivityPolicy.MIN_ADVANCED_VALUE..MotionSensitivityPolicy.MAX_ADVANCED_VALUE ->
            MotionFailure.invalidConfig("advanced_sensitivity")
        triggerDelayMillis !in 0L..MAX_TRIGGER_DELAY_MILLIS ->
            MotionFailure.invalidConfig("trigger_delay")
        stopDelayMillis !in MIN_STOP_DELAY_MILLIS..MAX_STOP_DELAY_MILLIS ->
            MotionFailure.invalidConfig("stop_delay")
        cooldownMillis !in 0L..MAX_COOLDOWN_MILLIS ->
            MotionFailure.invalidConfig("cooldown")
        frameIntervalMillis !in MIN_FRAME_INTERVAL_MILLIS..MAX_FRAME_INTERVAL_MILLIS ->
            MotionFailure.invalidConfig("frame_interval")
        warmupFrameCount !in 1..MAX_WARMUP_FRAMES ->
            MotionFailure.invalidConfig("warmup_frames")
        else -> null
    }

    companion object {
        const val MIN_STOP_DELAY_MILLIS = 1_000L
        const val MAX_STOP_DELAY_MILLIS = 120_000L
        const val MAX_TRIGGER_DELAY_MILLIS = 10_000L
        const val MAX_COOLDOWN_MILLIS = 60_000L
        const val MIN_FRAME_INTERVAL_MILLIS = 50L
        const val MAX_FRAME_INTERVAL_MILLIS = 1_000L
        const val MAX_WARMUP_FRAMES = 30
    }
}

data class MotionFrame(
    val luminance: ByteArray,
    val sampledWidth: Int,
    val sampledHeight: Int,
    val timestampMillis: Long,
    val generation: Long,
) {
    val isValid: Boolean
        get() = sampledWidth > 0 && sampledHeight > 0 &&
            luminance.size == sampledWidth * sampledHeight
}

data class MotionAnalysis(
    val score: Double,
    val changedPixelRatio: Double,
    val globalBrightnessDelta: Double,
    val hasBaseline: Boolean,
) {
    val safeScore: Double
        get() = score.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
}

data class MotionDebugMetrics(
    val score: Double = 0.0,
    val threshold: Double = 0.0,
    val positiveFrames: Int = 0,
    val requiredPositiveFrames: Int = 0,
    val analyzerFps: Double = 0.0,
    val averageProcessingMillis: Double = 0.0,
    val droppedFrames: Long = 0L,
)

data class MotionEventSummary(
    val eventId: String,
    val detectedAtMillis: Long,
    val lastMotionAtMillis: Long,
    val endedAtMillis: Long? = null,
    val sensitivity: MotionSensitivity,
    val peakScore: Double,
    val averageScore: Double,
    val sampleCount: Int,
    val burstCount: Int,
)

sealed interface MotionDetectionState {
    data object Disabled : MotionDetectionState
    data class Initializing(
        val generation: Long,
        val warmupFramesRemaining: Int,
        val metrics: MotionDebugMetrics = MotionDebugMetrics(),
    ) : MotionDetectionState
    data class NoMotion(val metrics: MotionDebugMetrics) : MotionDetectionState
    data class SuspectedMotion(val metrics: MotionDebugMetrics) : MotionDetectionState
    data class MotionConfirmed(
        val event: MotionEventSummary,
        val metrics: MotionDebugMetrics,
    ) : MotionDetectionState
    data class Holding(
        val event: MotionEventSummary,
        val holdUntilMillis: Long,
        val metrics: MotionDebugMetrics,
    ) : MotionDetectionState
    data class Cooldown(
        val event: MotionEventSummary,
        val cooldownUntilMillis: Long,
        val metrics: MotionDebugMetrics,
    ) : MotionDetectionState
    data class Error(val failure: MotionFailure) : MotionDetectionState
}

sealed interface MotionEvent {
    val event: MotionEventSummary

    data class Confirmed(override val event: MotionEventSummary) : MotionEvent
    data class Activity(override val event: MotionEventSummary) : MotionEvent
    data class Ended(override val event: MotionEventSummary) : MotionEvent
}

enum class MotionFailureCode(val stableCode: String) {
    ANALYZER_UNAVAILABLE("analyzer_unavailable"),
    UNSUPPORTED_USE_CASE_COMBINATION("unsupported_use_case_combination"),
    INITIALIZATION_FAILED("motion_initialization_failed"),
    PROCESSING_FAILED("motion_processing_failed"),
    CAMERA_NOT_READY("camera_not_ready"),
    MONITORING_ALREADY_RUNNING("monitoring_already_running"),
    MONITORING_NOT_RUNNING("monitoring_not_running"),
    RECORDING_TRIGGER_REJECTED("recording_trigger_rejected"),
    INVALID_CONFIG("invalid_motion_config"),
    UNEXPECTED_FAILURE("unexpected_motion_failure"),
}

enum class MotionRecoveryAction {
    NONE,
    RETRY,
    RESTART_CAMERA,
    CHECK_RECORDING_STATE,
}

data class MotionFailure(
    val code: MotionFailureCode,
    val retryAllowed: Boolean,
    val recoveryAction: MotionRecoveryAction,
    val diagnosticTag: String? = null,
    val technicalCause: Throwable? = null,
) {
    companion object {
        fun invalidConfig(tag: String) = MotionFailure(
            code = MotionFailureCode.INVALID_CONFIG,
            retryAllowed = false,
            recoveryAction = MotionRecoveryAction.NONE,
            diagnosticTag = tag,
        )
    }
}

sealed interface MotionStartResult {
    data object Started : MotionStartResult
    data object AlreadyRunning : MotionStartResult
    data class Rejected(val failure: MotionFailure) : MotionStartResult
}

sealed interface MotionStopResult {
    data object Stopped : MotionStopResult
    data object NotRunning : MotionStopResult
}

sealed interface MotionUpdateResult {
    data object Updated : MotionUpdateResult
    data class Rejected(val failure: MotionFailure) : MotionUpdateResult
}
