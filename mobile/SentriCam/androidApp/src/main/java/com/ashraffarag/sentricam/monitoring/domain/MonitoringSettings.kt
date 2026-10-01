package com.ashraffarag.sentricam.monitoring.domain

data class MonitoringNotificationOptions(
    val showMotionStatus: Boolean = true,
    val showRecordingStatus: Boolean = true,
    val showBatteryLevel: Boolean = true,
    val showStorageWarnings: Boolean = true,
)

data class MonitoringSettings(
    val enabled: Boolean = false,
    val autoStart: Boolean = false,
    val notification: MonitoringNotificationOptions = MonitoringNotificationOptions(),
    val restartOnFailure: Boolean = true,
    val verboseLogging: Boolean = false,
)

interface MonitoringSettingsRepository {
    fun load(): MonitoringSettings
    fun save(settings: MonitoringSettings)
}
