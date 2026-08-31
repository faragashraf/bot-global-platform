package com.ashraffarag.sentricam.settings.domain

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettings
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineUiSettings
import com.ashraffarag.sentricam.recording.settings.domain.RecordingSettings

enum class SettingsSection {
    CAMERA,
    RECORDING,
    MOTION_DETECTION,
    MONITORING,
    SMART_DETECTION,
    DEVICE,
    SYSTEM_CONNECTIVITY,
}

data class SettingsDestination(val section: SettingsSection) {
    companion object {
        fun general() = SettingsDestination(SettingsSection.CAMERA)
        fun recording() = SettingsDestination(SettingsSection.RECORDING)
        fun motion() = SettingsDestination(SettingsSection.MOTION_DETECTION)
        fun monitoring() = SettingsDestination(SettingsSection.MONITORING)
        fun device() = SettingsDestination(SettingsSection.DEVICE)
        fun connectivity() = SettingsDestination(SettingsSection.SYSTEM_CONNECTIVITY)
    }
}

enum class SettingsNavigationMode { COMPACT_TABS, TWO_PANE, PERSISTENT_RAIL }

object SettingsResponsivePolicy {
    fun navigationMode(widthDp: Int, heightDp: Int): SettingsNavigationMode = when {
        widthDp >= 600 -> SettingsNavigationMode.PERSISTENT_RAIL
        widthDp > heightDp -> SettingsNavigationMode.TWO_PANE
        else -> SettingsNavigationMode.COMPACT_TABS
    }
}

data class AppSettingsSnapshot(
    val camera: RecordingSettings = RecordingSettings(),
    val recording: RecordingEngineUiSettings = RecordingEngineUiSettings(),
    val motion: MotionDetectionConfig = MotionDetectionConfig(),
    val monitoring: MonitoringSettings = MonitoringSettings(),
)

data class SettingsNavigationState(
    val selectedSection: SettingsSection = SettingsSection.CAMERA,
)

data class SettingsUiState(
    val navigation: SettingsNavigationState,
    val applied: AppSettingsSnapshot,
    val draft: AppSettingsSnapshot,
) {
    val hasUnsavedChanges: Boolean get() = draft != applied
}

interface AppSettingsRepository {
    fun load(): AppSettingsSnapshot
    fun save(settings: AppSettingsSnapshot)
}
