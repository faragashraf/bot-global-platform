package com.ashraffarag.sentricam.recording.settings.android

import android.content.Context
import androidx.core.content.edit
import com.ashraffarag.sentricam.recording.settings.domain.RecordingCamera
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayPositions
import com.ashraffarag.sentricam.recording.settings.domain.RecordingSettings
import com.ashraffarag.sentricam.recording.settings.domain.RecordingSettingsRepository
import com.ashraffarag.sentricam.recording.settings.domain.RecordingVideoQuality

class SharedPreferencesRecordingSettingsRepository(context: Context) :
    RecordingSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): RecordingSettings = RecordingSettings(
        videoQuality = enumValueOrDefault(
            preferences.getString(KEY_VIDEO_QUALITY, null),
            RecordingVideoQuality.HD,
        ),
        audioEnabled = preferences.getBoolean(KEY_AUDIO_ENABLED, true),
        camera = enumValueOrDefault(
            preferences.getString(KEY_CAMERA, null),
            RecordingCamera.REAR,
        ),
        overlay = RecordingOverlayConfiguration(
            enabled = preferences.getBoolean(KEY_TIMESTAMP_ENABLED, false),
            dateEnabled = preferences.getBoolean(KEY_DATE_ENABLED, true),
            timeEnabled = preferences.getBoolean(KEY_TIME_ENABLED, true),
            use24HourTime = preferences.getBoolean(KEY_USE_24_HOUR_TIME, true),
            position = preferences.getString(KEY_POSITION, null)
                ?.takeIf { it in RecordingOverlayPositions.ALL }
                ?: RecordingOverlayPositions.BOTTOM_LEFT,
        ),
    )

    override fun save(settings: RecordingSettings) {
        preferences.edit {
            putString(KEY_VIDEO_QUALITY, settings.videoQuality.name)
            putBoolean(KEY_AUDIO_ENABLED, settings.audioEnabled)
            putString(KEY_CAMERA, settings.camera.name)
            putBoolean(KEY_TIMESTAMP_ENABLED, settings.overlay.showDateTime)
            putBoolean(KEY_DATE_ENABLED, settings.overlay.dateEnabled)
            putBoolean(KEY_TIME_ENABLED, settings.overlay.timeEnabled)
            putBoolean(KEY_USE_24_HOUR_TIME, settings.overlay.use24HourTime)
            putString(KEY_POSITION, settings.overlay.position)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        default: T,
    ): T = enumValues<T>().firstOrNull { it.name == value } ?: default

    private companion object {
        const val PREFERENCES_NAME = "recording_settings"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_AUDIO_ENABLED = "audio_enabled"
        const val KEY_CAMERA = "camera"
        const val KEY_TIMESTAMP_ENABLED = "timestamp_enabled"
        const val KEY_DATE_ENABLED = "timestamp_date_enabled"
        const val KEY_TIME_ENABLED = "timestamp_time_enabled"
        const val KEY_USE_24_HOUR_TIME = "timestamp_use_24_hour_time"
        const val KEY_POSITION = "timestamp_position"
    }
}
