package com.ashraffarag.sentricam.device.command

import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.capability.domain.CapabilityGuard
import com.ashraffarag.sentricam.capability.domain.CapabilityGuardResult
import com.ashraffarag.sentricam.device.domain.Device
import com.ashraffarag.sentricam.monitoring.domain.MonitoringServiceController
import com.ashraffarag.sentricam.monitoring.domain.MonitoringTransitionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultDeviceCommandHandler(
    private val device: Device,
    private val monitoring: MonitoringServiceController,
    private val recording: RecordingCommandPort,
    private val motion: MotionDetectionCommandPort,
    private val recordingSettings: RecordingSettingsCommandPort,
    private val capabilityGuard: CapabilityGuard,
    private val maximumRememberedCommands: Int = 100,
) : DeviceCommandHandler {
    private val mutex = Mutex()
    private val completedCommands = LinkedHashMap<String, DeviceCommandResult>()

    override suspend fun handle(request: DeviceCommandRequest): DeviceCommandResult = mutex.withLock {
        val commandId = request.commandId?.trim()?.takeIf { it.isNotEmpty() }
        if (request.commandId != null && commandId == null) {
            return@withLock DeviceCommandResult.Rejected("invalid_command_id")
        }
        commandId?.let { completedCommands[it] }?.let { return@withLock it }

        val result = requiredCapability(request.command)?.let { capability ->
            capabilityGuard.check(capability).toCommandResult(capability)
        } ?: execute(request.command)

        if (commandId != null) remember(commandId, result)
        result
    }

    private suspend fun execute(command: DeviceCommand): DeviceCommandResult = when (command) {
        DeviceCommand.StartMonitoring -> startMonitoring()
        DeviceCommand.StopMonitoring -> stopMonitoring()
        DeviceCommand.RestartMonitoring -> restartMonitoring()
        is DeviceCommand.StartRecording -> recording.startRecording(command.origin)
        is DeviceCommand.StopRecording -> recording.stopRecording(command.origin)
        DeviceCommand.StartMotionDetection -> motion.startMotionDetection()
        DeviceCommand.StopMotionDetection -> motion.stopMotionDetection()
        is DeviceCommand.UpdateMotionConfig -> {
            if (command.configuration.validationFailure() != null) {
                DeviceCommandResult.Rejected("invalid_motion_config")
            } else {
                motion.updateMotionConfig(command.configuration, command.expectedVersion)
            }
        }
        is DeviceCommand.UpdateRecordingConfig -> {
            if (command.configuration.segmentDurationMillis < 1_000L) {
                DeviceCommandResult.Rejected("invalid_recording_config")
            } else {
                recordingSettings.updateRecordingConfig(command.configuration)
            }
        }
        DeviceCommand.Ping -> DeviceCommandResult.Pong
        DeviceCommand.GetStatus -> DeviceCommandResult.Status(device.snapshot.value)
    }

    private suspend fun startMonitoring(): DeviceCommandResult {
        return monitoring.start().toCommandResult()
    }

    private suspend fun stopMonitoring(): DeviceCommandResult {
        return monitoring.stop().toCommandResult()
    }

    private suspend fun restartMonitoring(): DeviceCommandResult {
        return monitoring.restart().toCommandResult()
    }

    private fun requiredCapability(command: DeviceCommand): AppCapability? = when (command) {
        DeviceCommand.StartMonitoring,
        DeviceCommand.StopMonitoring,
        DeviceCommand.RestartMonitoring,
        -> AppCapability.MONITORING_SERVICE
        DeviceCommand.StartMotionDetection,
        DeviceCommand.StopMotionDetection,
        -> AppCapability.BASIC_MOTION_DETECTION
        is DeviceCommand.UpdateMotionConfig -> AppCapability.ADVANCED_MOTION_SENSITIVITY
        is DeviceCommand.StartRecording,
        is DeviceCommand.StopRecording,
        -> AppCapability.MANUAL_RECORDING
        is DeviceCommand.UpdateRecordingConfig -> AppCapability.RECORDING_PROFILES
        DeviceCommand.Ping,
        DeviceCommand.GetStatus,
        -> null
    }

    private fun CapabilityGuardResult.toCommandResult(capability: AppCapability): DeviceCommandResult? =
        when (this) {
            CapabilityGuardResult.Allowed -> null
            is CapabilityGuardResult.Blocked -> DeviceCommandResult.Unsupported(
                capability = capability.name,
                access = access.stableCode(),
            )
        }

    private fun MonitoringTransitionResult.toCommandResult(): DeviceCommandResult = when (this) {
        MonitoringTransitionResult.Accepted -> DeviceCommandResult.Accepted
        MonitoringTransitionResult.AlreadyApplied -> DeviceCommandResult.AlreadyApplied
        is MonitoringTransitionResult.Rejected -> DeviceCommandResult.Rejected(code)
    }

    private fun CapabilityAccess.stableCode(): String = when (this) {
        CapabilityAccess.Available -> "available"
        is CapabilityAccess.RequiresUpgrade -> "requires_upgrade"
        CapabilityAccess.ComingSoon -> "coming_soon"
        is CapabilityAccess.UnsupportedOnDevice -> "unsupported_on_device"
        is CapabilityAccess.Unavailable -> "unavailable"
    }

    private fun remember(commandId: String, result: DeviceCommandResult) {
        completedCommands[commandId] = result
        while (completedCommands.size > maximumRememberedCommands) {
            completedCommands.remove(completedCommands.keys.first())
        }
    }
}

object UnavailableRecordingCommandPort : RecordingCommandPort {
    override suspend fun startRecording(origin: com.ashraffarag.sentricam.device.domain.RecordingOrigin) =
        DeviceCommandResult.Rejected("recording_adapter_unavailable")
    override suspend fun stopRecording(origin: com.ashraffarag.sentricam.device.domain.RecordingOrigin) =
        DeviceCommandResult.Rejected("recording_adapter_unavailable")
}

object UnavailableMotionDetectionCommandPort : MotionDetectionCommandPort {
    override suspend fun startMotionDetection() = DeviceCommandResult.Rejected("motion_adapter_unavailable")
    override suspend fun stopMotionDetection() = DeviceCommandResult.Rejected("motion_adapter_unavailable")
    override suspend fun updateMotionConfig(
        configuration: com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig,
        expectedVersion: Long?,
    ) = DeviceCommandResult.Rejected("motion_adapter_unavailable")
}

object UnavailableRecordingSettingsCommandPort : RecordingSettingsCommandPort {
    override suspend fun updateRecordingConfig(configuration: RecordingCommandConfig) =
        DeviceCommandResult.Rejected("recording_settings_adapter_unavailable")
}
