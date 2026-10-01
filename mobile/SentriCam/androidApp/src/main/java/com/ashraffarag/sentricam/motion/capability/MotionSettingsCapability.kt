package com.ashraffarag.sentricam.motion.capability

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.motion.domain.MotionSensitivityPolicy
import com.ashraffarag.sentricam.motion.domain.ResolvedMotionSensitivity
import com.ashraffarag.sentricam.motion.presentation.MotionSettingsPolicy

object MotionSettingIds {
    const val ENABLED = "motion.enabled"
    const val SENSITIVITY = "motion.sensitivity"
    const val ADVANCED_SENSITIVITY = "motion.advancedSensitivity"
    const val TRIGGER_DELAY = "motion.triggerDelayMillis"
    const val STOP_DELAY = "motion.stopDelayMillis"
    const val COOLDOWN = "motion.cooldownMillis"
    const val FRAME_INTERVAL = "motion.frameIntervalMillis"
    const val WARMUP_FRAMES = "motion.warmupFrameCount"

    val userConfigurable = setOf(
        ENABLED,
        SENSITIVITY,
        ADVANCED_SENSITIVITY,
        TRIGGER_DELAY,
        STOP_DELAY,
        COOLDOWN,
    )
}

enum class MotionSettingParityClassification {
    HUB_WRITABLE,
    HUB_READ_ONLY,
    ACTION_REQUIRED_ON_DEVICE,
    DEPRECATED,
}

data class MotionSettingParityDefinition(
    val id: String,
    val androidViewId: String,
    val classification: MotionSettingParityClassification,
    val requiresCameraRestart: Boolean,
)

object MotionSettingsParityInventory {
    val userSettings = listOf(
        MotionSettingParityDefinition(MotionSettingIds.ENABLED, "settings_motion_enabled", MotionSettingParityClassification.HUB_WRITABLE, false),
        MotionSettingParityDefinition(MotionSettingIds.SENSITIVITY, "settings_motion_sensitivity", MotionSettingParityClassification.HUB_WRITABLE, false),
        MotionSettingParityDefinition(MotionSettingIds.ADVANCED_SENSITIVITY, "settings_advanced_sensitivity_slider", MotionSettingParityClassification.HUB_WRITABLE, false),
        MotionSettingParityDefinition(MotionSettingIds.TRIGGER_DELAY, "settings_motion_trigger", MotionSettingParityClassification.HUB_WRITABLE, false),
        MotionSettingParityDefinition(MotionSettingIds.STOP_DELAY, "settings_motion_hold", MotionSettingParityClassification.HUB_WRITABLE, false),
        MotionSettingParityDefinition(MotionSettingIds.COOLDOWN, "settings_motion_cooldown", MotionSettingParityClassification.HUB_WRITABLE, false),
    )
}

data class MotionSettingValue(
    val boolean: Boolean? = null,
    val number: Double? = null,
    val text: String? = null,
)

data class MotionSettingDescriptor(
    val id: String,
    val supported: Boolean,
    val writable: Boolean,
    val currentValue: MotionSettingValue,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val step: Double? = null,
    val allowedValues: List<String>? = null,
    val unit: String? = null,
    val requiresCameraRestart: Boolean = false,
    val reason: String? = null,
)

data class MotionSettingsValues(
    val enabled: Boolean,
    val sensitivity: String,
    val advancedSensitivity: Int,
    val triggerDelayMillis: Long,
    val stopDelayMillis: Long,
    val cooldownMillis: Long,
)

data class MotionEffectiveConfiguration(
    val selectedMode: String,
    val source: String,
    val threshold: Double,
    val requiredPositiveFrames: Int,
    val noiseTolerance: Double,
    val changedAreaThreshold: Double,
    val brightnessChangeTolerance: Double,
    val confirmationBehavior: String,
)

data class MotionSettingsDeviceReport(
    val settings: MotionSettingsValues,
    val capabilities: List<MotionSettingDescriptor>,
    val version: Long,
    val effectiveConfiguration: MotionEffectiveConfiguration? = null,
)

fun interface MotionSettingsCapabilityReporter {
    fun report(): MotionSettingsDeviceReport
}

class DefaultMotionSettingsCapabilityReporter(
    private val repository: MotionSettingsRepository,
    private val isDebug: Boolean,
) : MotionSettingsCapabilityReporter {
    override fun report(): MotionSettingsDeviceReport {
        val current = repository.loadVersioned()
        val config = current.configuration
        return MotionSettingsDeviceReport(
            settings = config.toValues(),
            capabilities = listOf(
                descriptor(MotionSettingIds.ENABLED, MotionSettingValue(boolean = config.enabled)),
                descriptor(
                    MotionSettingIds.SENSITIVITY,
                    MotionSettingValue(text = config.sensitivity.name.lowercase()),
                    allowed = MotionSensitivity.entries.map { it.name.lowercase() },
                ),
                descriptor(
                    MotionSettingIds.ADVANCED_SENSITIVITY,
                    MotionSettingValue(number = config.advancedSensitivity.toDouble()),
                    minimum = MotionSensitivityPolicy.MIN_ADVANCED_VALUE.toDouble(),
                    maximum = MotionSensitivityPolicy.MAX_ADVANCED_VALUE.toDouble(),
                    step = 1.0,
                    unit = "percent",
                ),
                descriptor(
                    MotionSettingIds.TRIGGER_DELAY,
                    MotionSettingValue(number = config.triggerDelayMillis.toDouble()),
                    allowed = listOf(0L, 1_000L, 2_000L).map(Long::toString),
                    unit = "milliseconds",
                ),
                descriptor(
                    MotionSettingIds.STOP_DELAY,
                    MotionSettingValue(number = config.stopDelayMillis.toDouble()),
                    allowed = MotionSettingsPolicy.stopDelays(isDebug).map(Long::toString),
                    unit = "milliseconds",
                ),
                descriptor(
                    MotionSettingIds.COOLDOWN,
                    MotionSettingValue(number = config.cooldownMillis.toDouble()),
                    allowed = listOf(3_000L, 5_000L, 10_000L).map(Long::toString),
                    unit = "milliseconds",
                ),
                descriptor(
                    MotionSettingIds.FRAME_INTERVAL,
                    MotionSettingValue(number = config.frameIntervalMillis.toDouble()),
                    writable = false,
                    unit = "milliseconds",
                    reason = "Analyzer cadence is managed by the Motion engine.",
                ),
                descriptor(
                    MotionSettingIds.WARMUP_FRAMES,
                    MotionSettingValue(number = config.warmupFrameCount.toDouble()),
                    writable = false,
                    unit = "frames",
                    reason = "Warmup is managed by the Motion engine.",
                ),
            ),
            version = current.version,
            effectiveConfiguration = config.resolvedSensitivity().toEffectiveConfiguration(),
        )
    }

    private fun descriptor(
        id: String,
        current: MotionSettingValue,
        writable: Boolean = true,
        minimum: Double? = null,
        maximum: Double? = null,
        step: Double? = null,
        allowed: List<String>? = null,
        unit: String? = null,
        reason: String? = null,
    ) = MotionSettingDescriptor(
        id = id,
        supported = true,
        writable = writable,
        currentValue = current,
        minimum = minimum,
        maximum = maximum,
        step = step,
        allowedValues = allowed,
        unit = unit,
        reason = reason,
    )
}

sealed interface MotionSettingMutationResult {
    data class Valid(val configuration: MotionDetectionConfig) : MotionSettingMutationResult
    data class Invalid(val code: String) : MotionSettingMutationResult
}

object MotionSettingsCapabilityPolicy {
    fun apply(
        current: MotionDetectionConfig,
        settingId: String,
        value: MotionSettingValue,
        isDebug: Boolean,
    ): MotionSettingMutationResult {
        val updated = when (settingId) {
            MotionSettingIds.ENABLED -> current.copy(enabled = value.onlyBoolean() ?: return invalidType())
            MotionSettingIds.SENSITIVITY -> {
                val sensitivity = value.onlyText()?.let { raw ->
                    MotionSensitivity.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                } ?: return MotionSettingMutationResult.Invalid("invalid_motion_sensitivity")
                current.copy(sensitivity = sensitivity)
            }
            MotionSettingIds.ADVANCED_SENSITIVITY -> {
                val number = value.onlyWholeNumber() ?: return invalidType()
                if (number !in MotionSensitivityPolicy.MIN_ADVANCED_VALUE..MotionSensitivityPolicy.MAX_ADVANCED_VALUE) {
                    return MotionSettingMutationResult.Invalid("motion_value_out_of_range")
                }
                current.copy(advancedSensitivity = number)
            }
            MotionSettingIds.TRIGGER_DELAY -> current.copy(
                triggerDelayMillis = value.allowedLong(listOf(0L, 1_000L, 2_000L)) ?: return invalidOption(),
            )
            MotionSettingIds.STOP_DELAY -> current.copy(
                stopDelayMillis = value.allowedLong(MotionSettingsPolicy.stopDelays(isDebug)) ?: return invalidOption(),
            )
            MotionSettingIds.COOLDOWN -> current.copy(
                cooldownMillis = value.allowedLong(listOf(3_000L, 5_000L, 10_000L)) ?: return invalidOption(),
            )
            else -> return MotionSettingMutationResult.Invalid("unsupported_motion_setting")
        }
        val normalized = MotionSettingsPolicy.normalize(updated, isDebug)
        return if (normalized == updated && normalized.validationFailure() == null) {
            MotionSettingMutationResult.Valid(normalized)
        } else {
            MotionSettingMutationResult.Invalid("invalid_motion_setting")
        }
    }

    private fun invalidType() = MotionSettingMutationResult.Invalid("invalid_motion_value_type")
    private fun invalidOption() = MotionSettingMutationResult.Invalid("unsupported_motion_value")

    private fun MotionSettingValue.onlyBoolean() = boolean.takeIf { number == null && text == null }
    private fun MotionSettingValue.onlyText() = text?.trim()?.takeIf { it.isNotEmpty() && boolean == null && number == null }
    private fun MotionSettingValue.onlyWholeNumber(): Int? = number
        ?.takeIf { it.isFinite() && it == kotlin.math.floor(it) && boolean == null && text == null }
        ?.toInt()
    private fun MotionSettingValue.allowedLong(allowed: List<Long>): Long? = onlyWholeNumber()
        ?.toLong()
        ?.takeIf(allowed::contains)
}

private fun MotionDetectionConfig.toValues() = MotionSettingsValues(
    enabled = enabled,
    sensitivity = sensitivity.name.lowercase(),
    advancedSensitivity = advancedSensitivity,
    triggerDelayMillis = triggerDelayMillis,
    stopDelayMillis = stopDelayMillis,
    cooldownMillis = cooldownMillis,
)

private fun ResolvedMotionSensitivity.toEffectiveConfiguration(): MotionEffectiveConfiguration {
    val profile = effectiveProfile
    return MotionEffectiveConfiguration(
        selectedMode = selectedMode.name.lowercase(),
        source = if (advancedSensitivityActive) "custom" else "preset",
        threshold = profile.threshold,
        requiredPositiveFrames = profile.requiredPositiveFrames,
        noiseTolerance = profile.noiseTolerance,
        changedAreaThreshold = profile.changedAreaThreshold,
        brightnessChangeTolerance = profile.brightnessChangeTolerance,
        confirmationBehavior = profile.confirmationBehavior.name.lowercase(),
    )
}
