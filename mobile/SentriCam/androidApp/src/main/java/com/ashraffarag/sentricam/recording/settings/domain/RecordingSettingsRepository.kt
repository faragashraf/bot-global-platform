package com.ashraffarag.sentricam.recording.settings.domain

interface RecordingSettingsRepository {
    fun load(): RecordingSettings

    fun save(settings: RecordingSettings)
}
