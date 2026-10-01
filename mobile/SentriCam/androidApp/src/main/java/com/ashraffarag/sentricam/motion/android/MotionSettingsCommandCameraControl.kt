package com.ashraffarag.sentricam.motion.android

import com.ashraffarag.sentricam.cameracontrol.capability.CameraHardwareResult
import com.ashraffarag.sentricam.cameracontrol.capability.MotionSettingsControl
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValue
import com.ashraffarag.sentricam.device.command.DeviceCommand
import com.ashraffarag.sentricam.device.command.DeviceCommandHandler
import com.ashraffarag.sentricam.device.command.DeviceCommandResult
import com.ashraffarag.sentricam.device.command.handle
import com.ashraffarag.sentricam.device.domain.DeviceRecordingStatus
import com.ashraffarag.sentricam.device.domain.DeviceSnapshot
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import com.ashraffarag.sentricam.motion.capability.MotionSettingMutationResult
import com.ashraffarag.sentricam.motion.capability.MotionSettingValue
import com.ashraffarag.sentricam.motion.capability.MotionSettingsCapabilityPolicy
import com.ashraffarag.sentricam.motion.capability.MotionSettingsRepository

/** Routes remote Motion settings through the existing device command and Motion engine. */
class MotionSettingsCommandCameraControl(
    private val repository: MotionSettingsRepository,
    private val commands: DeviceCommandHandler,
    private val snapshot: () -> DeviceSnapshot,
    private val isDebug: Boolean,
) : MotionSettingsControl {
    override suspend fun apply(
        control: String,
        value: CameraControlValue,
        expectedVersion: Long?,
    ): CameraHardwareResult {
        val current = repository.loadVersioned()
        if (expectedVersion == null || expectedVersion != current.version) {
            return CameraHardwareResult(false, code = "stale_motion_settings_version")
        }
        val mutation = MotionSettingsCapabilityPolicy.apply(
            current.configuration,
            control,
            MotionSettingValue(value.boolean, value.number, value.text),
            isDebug,
        )
        if (mutation is MotionSettingMutationResult.Invalid) {
            return CameraHardwareResult(false, code = mutation.code)
        }
        val updated = (mutation as MotionSettingMutationResult.Valid).configuration
        val result = commands.handle(DeviceCommand.UpdateMotionConfig(updated, expectedVersion))
        return result.toHardwareResult(
            disablingDuringMotionRecording = current.configuration.enabled &&
                !updated.enabled &&
                snapshot().isActiveMotionRecording(),
        )
    }

    private fun DeviceCommandResult.toHardwareResult(
        disablingDuringMotionRecording: Boolean,
    ): CameraHardwareResult = when (this) {
        DeviceCommandResult.Accepted -> CameraHardwareResult(
            true,
            code = if (disablingDuringMotionRecording) {
                "motion_disabled_recording_continues"
            } else {
                "motion_settings_applied"
            },
        )
        DeviceCommandResult.AlreadyApplied -> CameraHardwareResult(true, code = "motion_settings_already_applied")
        is DeviceCommandResult.Rejected -> CameraHardwareResult(
            false,
            transientFailure = code in TRANSIENT_CODES,
            code = code,
        )
        is DeviceCommandResult.Unsupported -> CameraHardwareResult(false, code = "motion_settings_unsupported")
        DeviceCommandResult.Pong,
        is DeviceCommandResult.Status,
        -> CameraHardwareResult(false, code = "motion_settings_invalid_result")
    }

    private fun DeviceSnapshot.isActiveMotionRecording(): Boolean =
        recordingOrigin == RecordingOrigin.MOTION && recordingState in setOf(
            DeviceRecordingStatus.PREPARING,
            DeviceRecordingStatus.READY,
            DeviceRecordingStatus.STARTING,
            DeviceRecordingStatus.RECORDING,
            DeviceRecordingStatus.ROTATING_SEGMENT,
            DeviceRecordingStatus.STOPPING,
        )

    private companion object {
        val TRANSIENT_CODES = setOf(
            "motion_adapter_unavailable",
            "camera_not_ready",
            "motion_start_rejected",
        )
    }
}
