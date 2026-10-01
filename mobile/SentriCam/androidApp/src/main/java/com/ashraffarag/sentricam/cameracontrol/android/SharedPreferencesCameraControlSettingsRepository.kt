package com.ashraffarag.sentricam.cameracontrol.android

import android.content.Context
import androidx.core.content.edit
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlSettingsRepository
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayPositions

class SharedPreferencesCameraControlSettingsRepository(context: Context) : CameraControlSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun load() = CameraControlSettings(
        lens = preferences.getString("lens", null) ?: CameraControlSettings().lens,
        zoom = preferences.getFloat("zoom", 1f).toDouble(),
        torch = preferences.getBoolean("torch", false),
        exposureCompensation = preferences.getInt("exposure", 0),
        preview = preferences.getString("preview", null) ?: CameraControlSettings().preview,
        framesPerSecond = preferences.getInt("fps", 30),
        resolution = preferences.getString("resolution", null) ?: "1280x720",
        bitrate = preferences.getInt("bitrate", 2_500_000),
        quality = preferences.getString("quality", null) ?: CameraControlSettings().quality,
        nightProfile = preferences.getString("nightProfile", null) ?: CameraControlSettings().nightProfile,
        dateTimeOverlay = RecordingOverlayConfiguration(
            enabled = preferences.getBoolean("dateTimeOverlay.enabled", false),
            dateEnabled = preferences.getBoolean("dateTimeOverlay.dateEnabled", true),
            timeEnabled = preferences.getBoolean("dateTimeOverlay.timeEnabled", true),
            use24HourTime = preferences.getBoolean("dateTimeOverlay.use24HourTime", true),
            position = preferences.getString("dateTimeOverlay.position", null)
                ?.takeIf { it in RecordingOverlayPositions.ALL }
                ?: RecordingOverlayPositions.BOTTOM_LEFT,
        ),
    )

    override fun save(settings: CameraControlSettings) {
        preferences.edit(commit = true) {
            putString("lens", settings.lens)
            putFloat("zoom", settings.zoom.toFloat())
            putBoolean("torch", settings.torch)
            putInt("exposure", settings.exposureCompensation)
            putString("preview", settings.preview)
            putInt("fps", settings.framesPerSecond)
            putString("resolution", settings.resolution)
            putInt("bitrate", settings.bitrate)
            putString("quality", settings.quality)
            putString("nightProfile", settings.nightProfile)
            putBoolean("dateTimeOverlay.enabled", settings.dateTimeOverlay.showDateTime)
            putBoolean("dateTimeOverlay.dateEnabled", settings.dateTimeOverlay.dateEnabled)
            putBoolean("dateTimeOverlay.timeEnabled", settings.dateTimeOverlay.timeEnabled)
            putBoolean("dateTimeOverlay.use24HourTime", settings.dateTimeOverlay.use24HourTime)
            putString("dateTimeOverlay.position", settings.dateTimeOverlay.position)
        }
    }

    private companion object { const val NAME = "camera_control_settings_v1" }
}
