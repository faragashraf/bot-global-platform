package com.ashraffarag.sentricam.monitoring.android

import android.content.Context
import com.ashraffarag.sentricam.monitoring.domain.MonitoringNotificationOptions
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettings
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettingsRepository

class SharedPreferencesMonitoringSettingsRepository(context: Context) : MonitoringSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load() = MonitoringSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        autoStart = preferences.getBoolean(KEY_AUTO_START, false),
        notification = MonitoringNotificationOptions(
            showMotionStatus = preferences.getBoolean(KEY_SHOW_MOTION, true),
            showRecordingStatus = preferences.getBoolean(KEY_SHOW_RECORDING, true),
            showBatteryLevel = preferences.getBoolean(KEY_SHOW_BATTERY, true),
            showStorageWarnings = preferences.getBoolean(KEY_SHOW_STORAGE, true),
        ),
        restartOnFailure = preferences.getBoolean(KEY_RESTART_ON_FAILURE, true),
        verboseLogging = preferences.getBoolean(KEY_VERBOSE_LOGGING, false),
    )

    override fun save(settings: MonitoringSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putBoolean(KEY_AUTO_START, settings.autoStart)
            .putBoolean(KEY_SHOW_MOTION, settings.notification.showMotionStatus)
            .putBoolean(KEY_SHOW_RECORDING, settings.notification.showRecordingStatus)
            .putBoolean(KEY_SHOW_BATTERY, settings.notification.showBatteryLevel)
            .putBoolean(KEY_SHOW_STORAGE, settings.notification.showStorageWarnings)
            .putBoolean(KEY_RESTART_ON_FAILURE, settings.restartOnFailure)
            .putBoolean(KEY_VERBOSE_LOGGING, settings.verboseLogging)
            .apply()
    }

    private companion object {
        const val PREFERENCES = "monitoring_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_SHOW_MOTION = "notification_motion"
        const val KEY_SHOW_RECORDING = "notification_recording"
        const val KEY_SHOW_BATTERY = "notification_battery"
        const val KEY_SHOW_STORAGE = "notification_storage"
        const val KEY_RESTART_ON_FAILURE = "restart_on_failure"
        const val KEY_VERBOSE_LOGGING = "verbose_logging"
    }
}
