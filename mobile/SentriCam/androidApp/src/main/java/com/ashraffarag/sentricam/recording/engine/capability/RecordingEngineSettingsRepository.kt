package com.ashraffarag.sentricam.recording.engine.capability

import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineUiSettings

interface RecordingEngineSettingsRepository {
    fun load(): RecordingEngineUiSettings
    fun save(settings: RecordingEngineUiSettings)
}
