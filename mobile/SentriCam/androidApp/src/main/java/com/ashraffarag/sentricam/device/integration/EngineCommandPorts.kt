package com.ashraffarag.sentricam.device.integration

import com.ashraffarag.sentricam.device.command.DeviceCommandResult
import com.ashraffarag.sentricam.device.command.MotionDetectionCommandPort
import com.ashraffarag.sentricam.device.command.RecordingCommandConfig
import com.ashraffarag.sentricam.device.command.RecordingCommandPort
import com.ashraffarag.sentricam.device.command.RecordingSettingsCommandPort
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import com.ashraffarag.sentricam.motion.capability.MotionDetectionEngine
import com.ashraffarag.sentricam.motion.capability.ManualRecordingRequestResult
import com.ashraffarag.sentricam.motion.capability.MotionSettingsRepository
import com.ashraffarag.sentricam.motion.capability.MotionSettingsSaveResult
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionStartResult
import com.ashraffarag.sentricam.motion.domain.MotionStopResult
import com.ashraffarag.sentricam.motion.domain.MotionUpdateResult
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngine
import com.ashraffarag.sentricam.recording.engine.capability.RecordingEngineSettingsRepository
import com.ashraffarag.sentricam.recording.engine.domain.PrepareResult
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSession
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingTriggerContext
import com.ashraffarag.sentricam.recording.engine.domain.StartResult
import com.ashraffarag.sentricam.recording.engine.domain.StopReason
import com.ashraffarag.sentricam.recording.engine.domain.StopResult
import com.ashraffarag.sentricam.recording.settings.domain.RecordingSettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class RecordingEngineCommandPort(
    private val engine: RecordingEngine,
    private val manualStarter: (suspend (RecordingRequest) -> ManualRecordingRequestResult)? = null,
    private val requestProvider: suspend (RecordingOrigin) -> RecordingRequest?,
    private val transitionTimeoutMillis: Long = DEFAULT_TRANSITION_TIMEOUT_MILLIS,
) : RecordingCommandPort {
    private val mutex = Mutex()

    override suspend fun startRecording(origin: RecordingOrigin): DeviceCommandResult = mutex.withLock {
        if (origin == RecordingOrigin.NONE || origin == RecordingOrigin.MOTION) {
            return@withLock DeviceCommandResult.Rejected("invalid_recording_origin")
        }
        if (origin == RecordingOrigin.REMOTE) {
            engine.state.value.activeSession()?.let { session ->
                val owner = session.recordingOwner()
                return@withLock if (owner == RecordingOrigin.REMOTE) {
                    DeviceCommandResult.AlreadyApplied
                } else {
                    DeviceCommandResult.Rejected("recording_owned_by_${owner.name.lowercase()}")
                }
            }
            if (engine.state.value.isBusy()) {
                return@withLock DeviceCommandResult.Rejected("recording_transition_in_progress")
            }
        }
        val base = requestProvider(origin)
            ?: return@withLock DeviceCommandResult.Rejected("camera_not_ready")
        if (origin == RecordingOrigin.MANUAL && manualStarter != null) {
            return@withLock when (manualStarter.invoke(base)) {
                ManualRecordingRequestResult.Started,
                ManualRecordingRequestResult.PromotedExistingMotionRecording,
                -> DeviceCommandResult.Accepted
                ManualRecordingRequestResult.Busy -> DeviceCommandResult.Rejected("recording_already_active")
                ManualRecordingRequestResult.Rejected -> DeviceCommandResult.Rejected("recording_start_rejected")
            }
        }
        if (engine.state.value.isBusy()) {
            return@withLock DeviceCommandResult.Rejected("recording_already_active")
        }
        val request = base.copy(
            triggerContext = when (origin) {
                RecordingOrigin.MANUAL -> RecordingTriggerContext.manual()
                RecordingOrigin.REMOTE -> RecordingTriggerContext(RecordingStartReason.REMOTE)
                RecordingOrigin.MOTION,
                RecordingOrigin.NONE,
                -> return@withLock DeviceCommandResult.Rejected("invalid_recording_origin")
            },
        )
        when (engine.prepare(request)) {
            is PrepareResult.Prepared -> when (engine.start()) {
                StartResult.Accepted -> awaitStarted()
                StartResult.AlreadyActive -> DeviceCommandResult.Rejected("recording_already_active")
                is StartResult.Rejected -> DeviceCommandResult.Rejected("recording_start_rejected")
            }
            PrepareResult.Busy -> DeviceCommandResult.Rejected("recording_already_active")
            is PrepareResult.Rejected -> DeviceCommandResult.Rejected("recording_prepare_rejected")
        }
    }

    override suspend fun stopRecording(origin: RecordingOrigin): DeviceCommandResult = mutex.withLock {
        val session = engine.state.value.activeSession()
            ?: return@withLock if (engine.state.value.isBusy()) {
                DeviceCommandResult.Rejected("recording_transition_in_progress")
            } else {
                DeviceCommandResult.AlreadyApplied
            }
        val owner = session.recordingOwner()
        if (owner != origin) {
            return@withLock DeviceCommandResult.Rejected("recording_owned_by_${owner.name.lowercase()}")
        }
        when (engine.stop(if (origin == RecordingOrigin.REMOTE) StopReason.REMOTE else StopReason.USER)) {
            StopResult.Accepted -> awaitFinalized(DeviceCommandResult.Accepted)
            StopResult.AlreadyStopping -> awaitFinalized(DeviceCommandResult.AlreadyApplied)
            StopResult.NotActive -> DeviceCommandResult.AlreadyApplied
        }
    }

    private suspend fun awaitStarted(): DeviceCommandResult {
        val terminal = withTimeoutOrNull(transitionTimeoutMillis) {
            engine.state.first { state -> state is RecordingState.Recording || state is RecordingState.Failed }
        } ?: return DeviceCommandResult.Rejected("recording_start_timeout")
        return when (terminal) {
            is RecordingState.Recording -> DeviceCommandResult.Accepted
            is RecordingState.Failed -> DeviceCommandResult.Rejected(
                "recording_start_${terminal.failure.code.stableCode}",
            )
            else -> DeviceCommandResult.Rejected("recording_start_rejected")
        }
    }

    private suspend fun awaitFinalized(success: DeviceCommandResult): DeviceCommandResult {
        val terminal = withTimeoutOrNull(transitionTimeoutMillis) {
            engine.state.first { state ->
                state is RecordingState.Completed || state is RecordingState.Failed || state == RecordingState.Idle
            }
        } ?: return DeviceCommandResult.Rejected("recording_finalization_timeout")
        return when (terminal) {
            is RecordingState.Completed,
            RecordingState.Idle,
            -> success
            is RecordingState.Failed -> DeviceCommandResult.Rejected(
                "recording_finalization_${terminal.failure.code.stableCode}",
            )
            else -> DeviceCommandResult.Rejected("recording_finalization_failed")
        }
    }

    private fun RecordingState.activeSession(): RecordingSession? = when (this) {
        is RecordingState.Ready -> session
        is RecordingState.Starting -> session
        is RecordingState.Recording -> session
        is RecordingState.RotatingSegment -> session
        is RecordingState.Stopping -> session
        is RecordingState.Failed -> session
        else -> null
    }

    private fun RecordingState.isBusy(): Boolean = when (this) {
        is RecordingState.Preparing,
        is RecordingState.Ready,
        is RecordingState.Starting,
        is RecordingState.Recording,
        is RecordingState.RotatingSegment,
        is RecordingState.Stopping,
        -> true
        else -> false
    }

    private fun RecordingStartReason.toOrigin(): RecordingOrigin = when (this) {
        RecordingStartReason.MANUAL -> RecordingOrigin.MANUAL
        RecordingStartReason.MOTION -> RecordingOrigin.MOTION
        RecordingStartReason.REMOTE -> RecordingOrigin.REMOTE
    }

    private fun RecordingSession.recordingOwner(): RecordingOrigin =
        if (request.triggerContext.manualControlClaimed) {
            RecordingOrigin.MANUAL
        } else {
            request.triggerContext.startReason.toOrigin()
        }

    private companion object {
        const val DEFAULT_TRANSITION_TIMEOUT_MILLIS = 15_000L
    }
}

class MotionEngineCommandPort(
    private val engine: MotionDetectionEngine,
    private val repository: MotionSettingsRepository,
    private val onConfigurationChanged: (MotionDetectionConfig) -> Unit,
) : MotionDetectionCommandPort {
    private val mutex = Mutex()

    override suspend fun startMotionDetection(): DeviceCommandResult = mutex.withLock {
        val configuration = repository.load().copy(enabled = true)
        when (engine.start(configuration)) {
            MotionStartResult.Started -> {
                save(configuration)
                DeviceCommandResult.Accepted
            }
            MotionStartResult.AlreadyRunning -> DeviceCommandResult.AlreadyApplied
            is MotionStartResult.Rejected -> DeviceCommandResult.Rejected("motion_start_rejected")
        }
    }

    override suspend fun stopMotionDetection(): DeviceCommandResult = mutex.withLock {
        val configuration = repository.load().copy(enabled = false)
        when (engine.stop()) {
            MotionStopResult.Stopped -> {
                save(configuration)
                DeviceCommandResult.Accepted
            }
            MotionStopResult.NotRunning -> {
                save(configuration)
                DeviceCommandResult.AlreadyApplied
            }
        }
    }

    override suspend fun updateMotionConfig(
        configuration: MotionDetectionConfig,
        expectedVersion: Long?,
    ): DeviceCommandResult =
        mutex.withLock {
            when {
                !configuration.enabled -> when (engine.stop()) {
                    MotionStopResult.Stopped,
                    MotionStopResult.NotRunning,
                    -> persistOrRestore(configuration, expectedVersion)
                }
                engine.state.value == MotionDetectionState.Disabled -> when (engine.start(configuration)) {
                    MotionStartResult.Started,
                    MotionStartResult.AlreadyRunning,
                    -> persistOrRestore(configuration, expectedVersion)
                    is MotionStartResult.Rejected -> DeviceCommandResult.Rejected("motion_start_rejected")
                }
                else -> when (engine.updateConfig(configuration)) {
                    MotionUpdateResult.Updated -> persistOrRestore(configuration, expectedVersion)
                    is MotionUpdateResult.Rejected -> DeviceCommandResult.Rejected("invalid_motion_config")
                }
            }
        }

    private suspend fun persistOrRestore(
        configuration: MotionDetectionConfig,
        expectedVersion: Long?,
    ): DeviceCommandResult = when (val saved = repository.saveIfVersion(configuration, expectedVersion)) {
        is MotionSettingsSaveResult.Stale -> {
            restoreEngine(saved.current.configuration)
            onConfigurationChanged(saved.current.configuration)
            DeviceCommandResult.Rejected("stale_motion_settings_version")
        }
        is MotionSettingsSaveResult.Updated,
        is MotionSettingsSaveResult.Unchanged,
        -> {
            onConfigurationChanged(saved.current.configuration)
            DeviceCommandResult.Accepted
        }
    }

    private suspend fun restoreEngine(configuration: MotionDetectionConfig) {
        if (!configuration.enabled) {
            engine.stop()
        } else if (engine.state.value == MotionDetectionState.Disabled) {
            engine.start(configuration)
        } else {
            engine.updateConfig(configuration)
        }
    }

    private fun save(configuration: MotionDetectionConfig) {
        repository.save(configuration)
        onConfigurationChanged(configuration)
    }
}

class AppRecordingSettingsCommandPort(
    private val engineSettings: RecordingEngineSettingsRepository,
    private val recordingSettings: RecordingSettingsRepository,
) : RecordingSettingsCommandPort {
    override suspend fun updateRecordingConfig(configuration: RecordingCommandConfig): DeviceCommandResult {
        engineSettings.save(
            engineSettings.load().copy(
                profileId = configuration.profileId,
                segmentDurationMillis = configuration.segmentDurationMillis,
            ),
        )
        recordingSettings.save(
            recordingSettings.load().copy(audioEnabled = configuration.audioEnabled),
        )
        return DeviceCommandResult.Accepted
    }
}
