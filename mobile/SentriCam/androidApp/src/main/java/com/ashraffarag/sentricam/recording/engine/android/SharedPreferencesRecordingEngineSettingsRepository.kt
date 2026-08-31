package com.ashraffarag.sentricam.recording.engine.android

import android.content.Context
import androidx.core.content.edit
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngineSettingsRepository
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineSettingsPolicy
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineUiSettings

class SharedPreferencesRecordingEngineSettingsRepository(
    context: Context,
    private val isDebug: Boolean,
) : RecordingEngineSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): RecordingEngineUiSettings = RecordingEngineSettingsPolicy.normalize(
        RecordingEngineUiSettings(
            profileId = preferences.getString(KEY_PROFILE, null)
                ?.let { stored -> RecordingProfileId.entries.firstOrNull { it.name == stored } }
                ?: RecordingProfileId.STANDARD,
            segmentDurationMillis = preferences.getLong(
                KEY_SEGMENT_DURATION,
                RecordingEngineSettingsPolicy.FIVE_MINUTES,
            ),
            simulateStorageWarning = preferences.getBoolean(KEY_SIMULATE_STORAGE_WARNING, false),
        ),
        isDebug = isDebug,
    )

    override fun save(settings: RecordingEngineUiSettings) {
        val safeSettings = RecordingEngineSettingsPolicy.normalize(settings, isDebug)
        preferences.edit {
            putString(KEY_PROFILE, safeSettings.profileId.name)
            putLong(KEY_SEGMENT_DURATION, safeSettings.segmentDurationMillis)
            putBoolean(KEY_SIMULATE_STORAGE_WARNING, safeSettings.simulateStorageWarning)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "recording_engine_v2_settings"
        const val KEY_PROFILE = "profile"
        const val KEY_SEGMENT_DURATION = "segment_duration"
        const val KEY_SIMULATE_STORAGE_WARNING = "simulate_storage_warning"
    }
}
