package com.ashraffarag.sentricam.motion.android

import android.content.Context
import androidx.core.content.edit
import com.ashraffarag.sentricam.motion.capability.MotionSettingsRepository
import com.ashraffarag.sentricam.motion.capability.MotionSettingsSaveResult
import com.ashraffarag.sentricam.motion.capability.VersionedMotionSettings
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.motion.domain.MotionSensitivityPolicy
import com.ashraffarag.sentricam.motion.presentation.MotionSettingsPolicy

class SharedPreferencesMotionSettingsRepository(
    context: Context,
    private val isDebug: Boolean,
) : MotionSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): MotionDetectionConfig = synchronized(PROCESS_LOCK) { loadUnlocked() }

    override fun loadVersioned(): VersionedMotionSettings = synchronized(PROCESS_LOCK) {
        VersionedMotionSettings(loadUnlocked(), preferences.getLong(KEY_VERSION, INITIAL_VERSION))
    }

    override fun save(config: MotionDetectionConfig) {
        synchronized(PROCESS_LOCK) {
            saveUnlocked(config, expectedVersion = null)
        }
    }

    override fun saveIfVersion(
        config: MotionDetectionConfig,
        expectedVersion: Long?,
    ): MotionSettingsSaveResult = synchronized(PROCESS_LOCK) {
        saveUnlocked(config, expectedVersion)
    }

    private fun loadUnlocked(): MotionDetectionConfig = MotionSettingsPolicy.normalize(
        MotionDetectionConfig(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            sensitivity = preferences.getString(KEY_SENSITIVITY, null)
                ?.let { stored -> MotionSensitivity.entries.firstOrNull { it.name == stored } }
                ?: MotionSensitivity.MEDIUM,
            advancedSensitivity = MotionSensitivityPolicy.sanitizeAdvancedValue(
                preferences.getInt(
                    KEY_ADVANCED_SENSITIVITY,
                    MotionSensitivityPolicy.DEFAULT_ADVANCED_VALUE,
                ),
            ),
            triggerDelayMillis = preferences.getLong(KEY_TRIGGER_DELAY, 1_000L),
            stopDelayMillis = preferences.getLong(KEY_STOP_DELAY, 10_000L),
            cooldownMillis = preferences.getLong(KEY_COOLDOWN, 5_000L),
        ),
        isDebug,
    )

    private fun saveUnlocked(
        config: MotionDetectionConfig,
        expectedVersion: Long?,
    ): MotionSettingsSaveResult {
        val current = VersionedMotionSettings(
            loadUnlocked(),
            preferences.getLong(KEY_VERSION, INITIAL_VERSION),
        )
        if (expectedVersion != null && expectedVersion != current.version) {
            return MotionSettingsSaveResult.Stale(current)
        }
        val safe = MotionSettingsPolicy.normalize(config, isDebug)
        if (safe == current.configuration) {
            return MotionSettingsSaveResult.Unchanged(current)
        }
        val updated = VersionedMotionSettings(safe, current.version + 1L)
        preferences.edit(commit = true) {
            putBoolean(KEY_ENABLED, safe.enabled)
            putString(KEY_SENSITIVITY, safe.sensitivity.name)
            putInt(KEY_ADVANCED_SENSITIVITY, safe.advancedSensitivity)
            putLong(KEY_TRIGGER_DELAY, safe.triggerDelayMillis)
            putLong(KEY_STOP_DELAY, safe.stopDelayMillis)
            putLong(KEY_COOLDOWN, safe.cooldownMillis)
            putLong(KEY_VERSION, updated.version)
        }
        return MotionSettingsSaveResult.Updated(updated)
    }

    private companion object {
        val PROCESS_LOCK = Any()
        const val PREFERENCES_NAME = "motion_detection_v1_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_ADVANCED_SENSITIVITY = "advanced_sensitivity"
        const val KEY_TRIGGER_DELAY = "trigger_delay"
        const val KEY_STOP_DELAY = "stop_delay"
        const val KEY_COOLDOWN = "cooldown"
        const val KEY_VERSION = "settings_version"
        const val INITIAL_VERSION = 1L
    }
}
