package com.ashraffarag.sentricam.settings.capability

import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.monitoring.domain.MonitoringSettings
import com.ashraffarag.sentricam.recording.engine.presentation.RecordingEngineUiSettings
import com.ashraffarag.sentricam.recording.settings.domain.RecordingSettings
import com.ashraffarag.sentricam.settings.domain.AppSettingsRepository
import com.ashraffarag.sentricam.settings.domain.SettingsDestination
import com.ashraffarag.sentricam.settings.domain.SettingsNavigationState
import com.ashraffarag.sentricam.settings.domain.SettingsSection
import com.ashraffarag.sentricam.settings.domain.SettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettingsCoordinator(
    private val repository: AppSettingsRepository,
    initialDestination: SettingsDestination,
) {
    private val initial = repository.load()
    private val mutableState = MutableStateFlow(
        SettingsUiState(
            navigation = SettingsNavigationState(initialDestination.section),
            applied = initial,
            draft = initial,
        ),
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    fun select(section: SettingsSection) = update { copy(navigation = SettingsNavigationState(section)) }
    fun updateCamera(settings: RecordingSettings) = update { copy(draft = draft.copy(camera = settings)) }
    fun updateRecording(settings: RecordingEngineUiSettings) =
        update { copy(draft = draft.copy(recording = settings)) }
    fun updateMotion(config: MotionDetectionConfig) = update { copy(draft = draft.copy(motion = config)) }

    fun refreshMotion(config: MotionDetectionConfig): Boolean {
        if (state.value.draft.motion != state.value.applied.motion) return false
        if (state.value.applied.motion == config) return false
        update {
            copy(
                applied = applied.copy(motion = config),
                draft = draft.copy(motion = config),
            )
        }
        return true
    }
    fun updateMonitoring(settings: MonitoringSettings) =
        update { copy(draft = draft.copy(monitoring = settings)) }

    fun apply(): SettingsUiState {
        repository.save(state.value.draft)
        update { copy(applied = draft) }
        return state.value
    }

    fun discard() = update { copy(draft = applied) }

    private inline fun update(transform: SettingsUiState.() -> SettingsUiState) {
        mutableState.value = mutableState.value.transform()
    }
}
