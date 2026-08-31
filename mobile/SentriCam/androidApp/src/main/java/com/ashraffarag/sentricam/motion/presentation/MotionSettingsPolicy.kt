package com.ashraffarag.sentricam.motion.presentation

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionSensitivityPolicy

object MotionSettingsPolicy {
    const val IMMEDIATE = 0L
    const val ONE_SECOND = 1_000L
    const val TWO_SECONDS = 2_000L
    const val DEBUG_STOP_DELAY = 3_000L
    const val FIVE_SECONDS = 5_000L
    const val TEN_SECONDS = 10_000L
    const val TWENTY_SECONDS = 20_000L
    const val THIRTY_SECONDS = 30_000L

    fun stopDelays(isDebug: Boolean): List<Long> = buildList {
        if (isDebug) add(DEBUG_STOP_DELAY)
        add(FIVE_SECONDS)
        add(TEN_SECONDS)
        add(TWENTY_SECONDS)
        add(THIRTY_SECONDS)
    }

    fun normalize(config: MotionDetectionConfig, isDebug: Boolean): MotionDetectionConfig = config.copy(
        advancedSensitivity = MotionSensitivityPolicy.sanitizeAdvancedValue(config.advancedSensitivity),
        triggerDelayMillis = config.triggerDelayMillis.takeIf {
            it == IMMEDIATE || it == ONE_SECOND || it == TWO_SECONDS
        } ?: ONE_SECOND,
        stopDelayMillis = config.stopDelayMillis.takeIf { it in stopDelays(isDebug) } ?: TEN_SECONDS,
        cooldownMillis = config.cooldownMillis.takeIf {
            it == 3_000L || it == FIVE_SECONDS || it == TEN_SECONDS
        } ?: FIVE_SECONDS,
    )
}
