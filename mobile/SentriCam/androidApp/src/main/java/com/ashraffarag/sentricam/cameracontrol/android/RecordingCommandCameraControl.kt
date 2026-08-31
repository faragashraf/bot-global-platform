package com.ashraffarag.sentricam.cameracontrol.android

import com.ashraffarag.sentricam.cameracontrol.capability.CameraHardwareResult
import com.ashraffarag.sentricam.cameracontrol.capability.CameraRecordingControl
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues
import com.ashraffarag.sentricam.device.command.DeviceCommandResult
import com.ashraffarag.sentricam.device.command.RecordingCommandPort
import com.ashraffarag.sentricam.device.domain.RecordingOrigin

/** Routes the unified Camera Control command into the one shared recording engine. */
class RecordingCommandCameraControl(
    private val commands: RecordingCommandPort,
) : CameraRecordingControl {
    override suspend fun apply(action: String): CameraHardwareResult {
        val result = when (action) {
            CameraControlValues.START -> commands.startRecording(RecordingOrigin.REMOTE)
            CameraControlValues.STOP -> commands.stopRecording(RecordingOrigin.REMOTE)
            else -> return CameraHardwareResult(false, code = "invalid_recording_action")
        }
        return result.toCameraResult(action)
    }

    private fun DeviceCommandResult.toCameraResult(action: String): CameraHardwareResult = when (this) {
        DeviceCommandResult.Accepted -> CameraHardwareResult(
            true,
            code = if (action == CameraControlValues.START) "recording_start_accepted" else "recording_stop_accepted",
        )
        DeviceCommandResult.AlreadyApplied -> CameraHardwareResult(
            true,
            code = if (action == CameraControlValues.START) "recording_already_active" else "recording_already_idle",
        )
        is DeviceCommandResult.Rejected -> CameraHardwareResult(
            false,
            transientFailure = code == "camera_not_ready" || code.endsWith("_timeout"),
            code = code,
        )
        is DeviceCommandResult.Unsupported -> CameraHardwareResult(false, code = "recording_unsupported")
        DeviceCommandResult.Pong,
        is DeviceCommandResult.Status,
        -> CameraHardwareResult(false, code = "recording_command_invalid_result")
    }
}
