package com.ashraffarag.sentricam.motion.capability

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig

interface MotionSettingsRepository {
    fun load(): MotionDetectionConfig
    fun save(config: MotionDetectionConfig)

    fun loadVersioned(): VersionedMotionSettings = VersionedMotionSettings(load(), 0L)

    fun saveIfVersion(
        config: MotionDetectionConfig,
        expectedVersion: Long?,
    ): MotionSettingsSaveResult {
        val current = loadVersioned()
        if (expectedVersion != null && expectedVersion != current.version) {
            return MotionSettingsSaveResult.Stale(current)
        }
        save(config)
        val updated = loadVersioned()
        return if (updated == current) {
            MotionSettingsSaveResult.Unchanged(updated)
        } else {
            MotionSettingsSaveResult.Updated(updated)
        }
    }
}

data class VersionedMotionSettings(
    val configuration: MotionDetectionConfig,
    val version: Long,
)

sealed interface MotionSettingsSaveResult {
    val current: VersionedMotionSettings

    data class Updated(override val current: VersionedMotionSettings) : MotionSettingsSaveResult
    data class Unchanged(override val current: VersionedMotionSettings) : MotionSettingsSaveResult
    data class Stale(override val current: VersionedMotionSettings) : MotionSettingsSaveResult
}
