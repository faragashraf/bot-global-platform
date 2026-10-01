package com.ashraffarag.sentricam.motion.android

import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValue
import com.ashraffarag.sentricam.device.command.DeviceCommand
import com.ashraffarag.sentricam.device.command.DeviceCommandHandler
import com.ashraffarag.sentricam.device.command.DeviceCommandRequest
import com.ashraffarag.sentricam.device.command.DeviceCommandResult
import com.ashraffarag.sentricam.device.domain.BatteryState
import com.ashraffarag.sentricam.device.domain.CameraState
import com.ashraffarag.sentricam.device.domain.ConnectivityState
import com.ashraffarag.sentricam.device.domain.DeviceCapabilities
import com.ashraffarag.sentricam.device.domain.DeviceMotionStatus
import com.ashraffarag.sentricam.device.domain.DevicePlatform
import com.ashraffarag.sentricam.device.domain.DeviceRecordingStatus
import com.ashraffarag.sentricam.device.domain.DeviceSnapshot
import com.ashraffarag.sentricam.device.domain.FutureFeatureState
import com.ashraffarag.sentricam.device.domain.MemorySummary
import com.ashraffarag.sentricam.device.domain.MotionConfigurationSummary
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import com.ashraffarag.sentricam.device.domain.StorageState
import com.ashraffarag.sentricam.motion.capability.MotionSettingIds
import com.ashraffarag.sentricam.motion.capability.MotionSettingsRepository
import com.ashraffarag.sentricam.motion.capability.MotionSettingsSaveResult
import com.ashraffarag.sentricam.motion.capability.VersionedMotionSettings
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSettingsCommandCameraControlTest {
    @Test
    fun disablingDuringMotionRecordingAppliesOnlyTheSettingAndLeavesRecordingToFinalize() = runBlocking {
        val repository = MemoryRepository(MotionDetectionConfig(enabled = true), version = 5)
        val handler = RecordingHandler(DeviceCommandResult.Accepted)
        val control = MotionSettingsCommandCameraControl(
            repository,
            handler,
            snapshot = { snapshot(DeviceRecordingStatus.RECORDING, RecordingOrigin.MOTION) },
            isDebug = true,
        )

        val result = control.apply(
            MotionSettingIds.ENABLED,
            CameraControlValue(boolean = false),
            expectedVersion = 5,
        )

        assertTrue(result.succeeded)
        assertEquals("motion_disabled_recording_continues", result.code)
        val command = (handler.requests.single().command as DeviceCommand.UpdateMotionConfig)
        assertFalse(command.configuration.enabled)
        assertEquals(5L, command.expectedVersion)
        assertTrue(handler.requests.none { it.command is DeviceCommand.StopRecording })
    }

    @Test
    fun staleVersionAndInvalidValueNeverReachTheMotionEngine() = runBlocking {
        val repository = MemoryRepository(MotionDetectionConfig(enabled = true), version = 9)
        val handler = RecordingHandler(DeviceCommandResult.Accepted)
        val control = MotionSettingsCommandCameraControl(repository, handler, { snapshot() }, isDebug = false)

        val stale = control.apply(MotionSettingIds.COOLDOWN, CameraControlValue(number = 10_000.0), 8)
        val invalid = control.apply(MotionSettingIds.COOLDOWN, CameraControlValue(number = 4_000.0), 9)

        assertFalse(stale.succeeded)
        assertEquals("stale_motion_settings_version", stale.code)
        assertFalse(invalid.succeeded)
        assertEquals("unsupported_motion_value", invalid.code)
        assertTrue(handler.requests.isEmpty())
    }

    private class RecordingHandler(private val result: DeviceCommandResult) : DeviceCommandHandler {
        val requests = mutableListOf<DeviceCommandRequest>()
        override suspend fun handle(request: DeviceCommandRequest): DeviceCommandResult {
            requests += request
            return result
        }
    }

    private class MemoryRepository(config: MotionDetectionConfig, version: Long) : MotionSettingsRepository {
        private var current = VersionedMotionSettings(config, version)
        override fun load() = current.configuration
        override fun loadVersioned() = current
        override fun save(config: MotionDetectionConfig) {
            current = VersionedMotionSettings(config, current.version + 1)
        }
        override fun saveIfVersion(config: MotionDetectionConfig, expectedVersion: Long?): MotionSettingsSaveResult {
            if (expectedVersion != null && expectedVersion != current.version) return MotionSettingsSaveResult.Stale(current)
            save(config)
            return MotionSettingsSaveResult.Updated(current)
        }
    }

    private fun snapshot(
        recording: DeviceRecordingStatus = DeviceRecordingStatus.IDLE,
        origin: RecordingOrigin = RecordingOrigin.NONE,
    ) = DeviceSnapshot(
        deviceId = "8cc58e6b-9519-40cb-b3b8-ca73c7a4a3af",
        friendlyName = "Camera",
        platform = DevicePlatform.ANDROID,
        appVersion = "1.0",
        deviceModel = "Test",
        androidVersion = "16",
        buildFingerprint = null,
        installedAtMillis = 1,
        lastStartupAtMillis = 2,
        monitoringState = com.ashraffarag.sentricam.monitoring.domain.MonitoringStatus.RUNNING,
        monitoringEnabled = true,
        recordingState = recording,
        recordingOrigin = origin,
        motionEnabled = true,
        motionState = DeviceMotionStatus.NO_MOTION,
        motionConfiguration = MotionConfigurationSummary(true, MotionSensitivity.MEDIUM, 50, 1_000, 10_000, 5_000),
        lastMotionEventId = null,
        motionErrorCode = null,
        lastActivityAtMillis = null,
        lastMotionAtMillis = null,
        lastRecordingAtMillis = null,
        battery = BatteryState(),
        storage = StorageState(),
        memory = MemorySummary(),
        network = ConnectivityState.LocalNetwork(),
        camera = CameraState(),
        capabilities = DeviceCapabilities.unavailable(),
        pairing = FutureFeatureState.COMING_SOON,
        remoteConnection = FutureFeatureState.COMING_SOON,
        cloud = FutureFeatureState.COMING_SOON,
        updatedAtMillis = 3,
    )
}
