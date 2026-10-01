package com.ashraffarag.sentricam.settings.capability

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig

enum class MotionConfigApplyMode { NONE, LIVE_UPDATE }

object MotionLiveConfigPolicy {
    fun mode(previous: MotionDetectionConfig, updated: MotionDetectionConfig): MotionConfigApplyMode = when {
        previous == updated -> MotionConfigApplyMode.NONE
        else -> MotionConfigApplyMode.LIVE_UPDATE
    }
}
