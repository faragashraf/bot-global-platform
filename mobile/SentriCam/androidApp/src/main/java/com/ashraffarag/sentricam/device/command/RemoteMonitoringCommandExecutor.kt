package com.ashraffarag.sentricam.device.command

import com.ashraffarag.sentricam.communication.signalr.RemoteDeviceCommand
import com.ashraffarag.sentricam.communication.signalr.RemoteDeviceCommandResult
import com.ashraffarag.sentricam.communication.signalr.SignalRClock
import com.ashraffarag.sentricam.communication.signalr.SignalRCommandExecutor
import java.time.Instant
import kotlinx.coroutines.CancellationException

class RemoteMonitoringCommandExecutor(
    private val handler: DeviceCommandHandler,
    private val clock: SignalRClock,
) : SignalRCommandExecutor {
    override suspend fun execute(command: RemoteDeviceCommand): RemoteDeviceCommandResult {
        val mapped = when (command.commandType) {
            COMMAND_START_MONITORING -> DeviceCommand.StartMonitoring
            COMMAND_STOP_MONITORING -> DeviceCommand.StopMonitoring
            COMMAND_PING -> DeviceCommand.Ping
            COMMAND_GET_STATUS -> DeviceCommand.GetStatus
            else -> null
        }
        val result = try {
            if (mapped == null) {
                DeviceCommandResult.Unsupported("REMOTE_COMMAND", "not_available")
            } else {
                handler.handle(mapped, command.commandId)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            DeviceCommandResult.Rejected("command_execution_failed")
        }
        return result.toRemoteResult(command)
    }

    private fun DeviceCommandResult.toRemoteResult(command: RemoteDeviceCommand) = when (this) {
        DeviceCommandResult.Accepted -> success(command, "accepted")
        DeviceCommandResult.AlreadyApplied -> success(command, "already_applied")
        DeviceCommandResult.Pong -> success(command, "pong")
        is DeviceCommandResult.Status -> success(command, "status", snapshot)
        is DeviceCommandResult.Rejected -> failure(command, code)
        is DeviceCommandResult.Unsupported -> failure(
            command,
            "unsupported_${capability.lowercase()}_${access.lowercase()}",
        )
    }

    private fun success(
        command: RemoteDeviceCommand,
        code: String,
        snapshot: com.ashraffarag.sentricam.device.domain.DeviceSnapshot? = null,
    ) = result(command, OUTCOME_SUCCEEDED, code, snapshot)

    private fun failure(command: RemoteDeviceCommand, code: String) =
        result(command, OUTCOME_FAILED, code, null)

    private fun result(
        command: RemoteDeviceCommand,
        outcome: Int,
        code: String,
        snapshot: com.ashraffarag.sentricam.device.domain.DeviceSnapshot?,
    ) = RemoteDeviceCommandResult(
        commandId = command.commandId,
        deviceId = command.deviceId,
        outcome = outcome,
        resultCode = code.take(MAX_RESULT_CODE_LENGTH),
        snapshot = snapshot,
        completedAtUtc = Instant.ofEpochMilli(clock.nowMillis()).toString(),
    )

    private companion object {
        const val COMMAND_START_MONITORING = 1
        const val COMMAND_STOP_MONITORING = 2
        const val COMMAND_PING = 6
        const val COMMAND_GET_STATUS = 7
        const val OUTCOME_SUCCEEDED = 1
        const val OUTCOME_FAILED = 2
        const val MAX_RESULT_CODE_LENGTH = 100
    }
}
