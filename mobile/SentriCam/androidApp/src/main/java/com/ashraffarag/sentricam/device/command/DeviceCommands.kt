package com.ashraffarag.sentricam.device.command

import com.ashraffarag.sentricam.device.domain.DeviceSnapshot
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId

sealed interface DeviceCommand {
    data object StartMonitoring : DeviceCommand
    data object StopMonitoring : DeviceCommand
    data object RestartMonitoring : DeviceCommand
    data class StartRecording(val origin: RecordingOrigin = RecordingOrigin.REMOTE) : DeviceCommand
    data class StopRecording(val origin: RecordingOrigin = RecordingOrigin.REMOTE) : DeviceCommand
    data object StartMotionDetection : DeviceCommand
    data object StopMotionDetection : DeviceCommand
    data class UpdateMotionConfig(
        val configuration: MotionDetectionConfig,
        val expectedVersion: Long? = null,
    ) : DeviceCommand
    data class UpdateRecordingConfig(val configuration: RecordingCommandConfig) : DeviceCommand
    data object Ping : DeviceCommand
    data object GetStatus : DeviceCommand
}

data class DeviceCommandRequest(
    val command: DeviceCommand,
    val commandId: String? = null,
)

data class RecordingCommandConfig(
    val profileId: RecordingProfileId,
    val audioEnabled: Boolean,
    val segmentDurationMillis: Long,
)

sealed interface DeviceCommandResult {
    data object Accepted : DeviceCommandResult
    data object AlreadyApplied : DeviceCommandResult
    data object Pong : DeviceCommandResult
    data class Status(val snapshot: DeviceSnapshot) : DeviceCommandResult
    data class Rejected(val code: String) : DeviceCommandResult
    data class Unsupported(val capability: String, val access: String) : DeviceCommandResult
}

interface DeviceCommandHandler {
    suspend fun handle(request: DeviceCommandRequest): DeviceCommandResult
}

suspend fun DeviceCommandHandler.handle(
    command: DeviceCommand,
    commandId: String? = null,
): DeviceCommandResult = handle(DeviceCommandRequest(command, commandId))

interface RecordingCommandPort {
    suspend fun startRecording(origin: RecordingOrigin): DeviceCommandResult
    suspend fun stopRecording(origin: RecordingOrigin): DeviceCommandResult
}

interface MotionDetectionCommandPort {
    suspend fun startMotionDetection(): DeviceCommandResult
    suspend fun stopMotionDetection(): DeviceCommandResult
    suspend fun updateMotionConfig(
        configuration: MotionDetectionConfig,
        expectedVersion: Long? = null,
    ): DeviceCommandResult
}

interface RecordingSettingsCommandPort {
    suspend fun updateRecordingConfig(configuration: RecordingCommandConfig): DeviceCommandResult
}
