package com.ashraffarag.sentricam.device.command

import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.capability.domain.CapabilityGuard
import com.ashraffarag.sentricam.capability.domain.DevelopmentEntitlementService
import com.ashraffarag.sentricam.device.domain.DefaultDevice
import com.ashraffarag.sentricam.device.domain.DeviceClock
import com.ashraffarag.sentricam.device.domain.DeviceIdentity
import com.ashraffarag.sentricam.device.domain.DevicePlatform
import com.ashraffarag.sentricam.device.domain.DeviceState
import com.ashraffarag.sentricam.device.domain.DeviceStatusMapper
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import com.ashraffarag.sentricam.monitoring.domain.DefaultMonitoringServiceController
import com.ashraffarag.sentricam.monitoring.domain.MonitoringStatus
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfileId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCommandHandlerTest {
    @Test
    fun routesMonitoringMotionRecordingSettingsPingAndStatus() = runBlocking {
        val fixture = Fixture()

        assertEquals(DeviceCommandResult.Pong, fixture.handler.handle(DeviceCommand.Ping))
        assertEquals(
            DeviceCommandResult.Status(fixture.device.snapshot.value),
            fixture.handler.handle(DeviceCommand.GetStatus),
        )
        assertEquals(DeviceCommandResult.Accepted, fixture.handler.handle(DeviceCommand.StartMonitoring))
        assertEquals(MonitoringStatus.RUNNING, fixture.monitoring.state.value.status)
        assertEquals(0, fixture.motion.starts)
        assertEquals(DeviceCommandResult.Accepted, fixture.handler.handle(DeviceCommand.RestartMonitoring))
        assertEquals(DeviceCommandResult.Accepted, fixture.handler.handle(DeviceCommand.StopMonitoring))

        assertEquals(
            DeviceCommandResult.Accepted,
            fixture.handler.handle(DeviceCommand.StartRecording(RecordingOrigin.REMOTE)),
        )
        assertEquals(
            DeviceCommandResult.Accepted,
            fixture.handler.handle(DeviceCommand.StopRecording(RecordingOrigin.REMOTE)),
        )

        assertEquals(DeviceCommandResult.Accepted, fixture.handler.handle(DeviceCommand.StartMotionDetection))
        assertEquals(DeviceCommandResult.Accepted, fixture.handler.handle(DeviceCommand.StopMotionDetection))

        val motion = MotionDetectionConfig(enabled = true, sensitivity = MotionSensitivity.HIGH)
        assertEquals(
            DeviceCommandResult.Accepted,
            fixture.handler.handle(DeviceCommand.UpdateMotionConfig(motion)),
        )
        assertEquals(motion, fixture.motion.lastConfiguration)

        val recording = RecordingCommandConfig(RecordingProfileId.HIGH, true, 5_000L)
        assertEquals(
            DeviceCommandResult.Accepted,
            fixture.handler.handle(DeviceCommand.UpdateRecordingConfig(recording)),
        )
        assertEquals(recording, fixture.settings.last)
    }

    @Test
    fun duplicateStartRecordingIsRejectedSafely() = runBlocking {
        val fixture = Fixture()
        assertEquals(
            DeviceCommandResult.Accepted,
            fixture.handler.handle(DeviceCommand.StartRecording(RecordingOrigin.REMOTE)),
        )
        assertEquals(
            DeviceCommandResult.Rejected("recording_already_active"),
            fixture.handler.handle(DeviceCommand.StartRecording(RecordingOrigin.REMOTE)),
        )
    }

    @Test
    fun executionTokenMakesRetryIdempotent() = runBlocking {
        val fixture = Fixture()
        val first = fixture.handler.handle(
            DeviceCommand.StartRecording(RecordingOrigin.REMOTE),
            commandId = "command-1",
        )
        val retry = fixture.handler.handle(
            DeviceCommand.StartRecording(RecordingOrigin.REMOTE),
            commandId = "command-1",
        )

        assertEquals(DeviceCommandResult.Accepted, first)
        assertEquals(first, retry)
        assertEquals(1, fixture.recording.starts)
    }

    @Test
    fun monitoringCanStopRemainControllableAndStartAgainIdempotently() = runBlocking {
        val fixture = Fixture()

        assertEquals(DeviceCommandResult.Accepted, fixture.handler.handle(DeviceCommand.StartMonitoring))
        assertEquals(DeviceCommandResult.AlreadyApplied, fixture.handler.handle(DeviceCommand.StartMonitoring))
        assertEquals(DeviceCommandResult.Accepted, fixture.handler.handle(DeviceCommand.StopMonitoring))
        assertEquals(DeviceCommandResult.AlreadyApplied, fixture.handler.handle(DeviceCommand.StopMonitoring))
        assertEquals(DeviceCommandResult.Pong, fixture.handler.handle(DeviceCommand.Ping))
        assertTrue(fixture.handler.handle(DeviceCommand.GetStatus) is DeviceCommandResult.Status)
        assertEquals(DeviceCommandResult.Accepted, fixture.handler.handle(DeviceCommand.StartMonitoring))
        assertEquals(MonitoringStatus.RUNNING, fixture.monitoring.state.value.status)
    }

    @Test
    fun centralizedCapabilityGuardBlocksUnsupportedCommandBeforePort() = runBlocking {
        val fixture = Fixture(
            overrides = mapOf(
                AppCapability.BASIC_MOTION_DETECTION to CapabilityAccess.UnsupportedOnDevice("no_camera"),
            ),
        )

        assertEquals(
            DeviceCommandResult.Unsupported("BASIC_MOTION_DETECTION", "unsupported_on_device"),
            fixture.handler.handle(DeviceCommand.StartMotionDetection),
        )
        assertEquals(0, fixture.motion.starts)
    }

    @Test
    fun resolvedHardwareCapabilityBlocksCommandBeforePort() = runBlocking {
        val fixture = Fixture(
            hardwareOverrides = mapOf(
                AppCapability.BASIC_MOTION_DETECTION to CapabilityAccess.UnsupportedOnDevice("no_camera"),
            ),
        )

        assertEquals(
            DeviceCommandResult.Unsupported("BASIC_MOTION_DETECTION", "unsupported_on_device"),
            fixture.handler.handle(DeviceCommand.StartMotionDetection),
        )
        assertEquals(0, fixture.motion.starts)
    }

    @Test
    fun invalidConfigurationIsRejectedBeforeSettingsPort() = runBlocking {
        val fixture = Fixture()

        assertEquals(
            DeviceCommandResult.Rejected("invalid_recording_config"),
            fixture.handler.handle(
                DeviceCommand.UpdateRecordingConfig(
                    RecordingCommandConfig(RecordingProfileId.LOW, false, 999L),
                ),
            ),
        )
        assertEquals(null, fixture.settings.last)
    }

    private class Fixture(
        overrides: Map<AppCapability, CapabilityAccess> = emptyMap(),
        hardwareOverrides: Map<AppCapability, CapabilityAccess> = emptyMap(),
    ) {
        private val clock = DeviceClock { 100L }
        private val entitlements = DevelopmentEntitlementService(overrides)
        val monitoring = DefaultMonitoringServiceController(clock, sessionIdFactory = { "session" })
        val recording = FakeRecordingPort()
        val motion = FakeMotionPort()
        val settings = FakeRecordingSettingsPort()
        val device = DefaultDevice(
            DeviceState(
                identity = identity(),
                capabilities = com.ashraffarag.sentricam.device.domain.DeviceCapabilities.of(
                    *AppCapability.entries.map { capability ->
                        capability to (
                            hardwareOverrides[capability]
                                ?: entitlements.getAccess(capability)
                            )
                    }.toTypedArray(),
                ),
            ),
            DeviceStatusMapper(clock),
        )
        val handler: DeviceCommandHandler = DefaultDeviceCommandHandler(
            device = device,
            monitoring = monitoring,
            recording = recording,
            motion = motion,
            recordingSettings = settings,
            capabilityGuard = CapabilityGuard(entitlements) { capability ->
                device.state.value.capabilities.access(capability)
            },
        )
    }

    private class FakeRecordingPort : RecordingCommandPort {
        var starts = 0
        private var active = false
        override suspend fun startRecording(origin: RecordingOrigin): DeviceCommandResult {
            if (active) return DeviceCommandResult.Rejected("recording_already_active")
            active = true
            starts++
            return DeviceCommandResult.Accepted
        }
        override suspend fun stopRecording(origin: RecordingOrigin): DeviceCommandResult {
            if (!active) return DeviceCommandResult.AlreadyApplied
            active = false
            return DeviceCommandResult.Accepted
        }
    }

    private class FakeMotionPort : MotionDetectionCommandPort {
        var starts = 0
        var lastConfiguration: MotionDetectionConfig? = null
        override suspend fun startMotionDetection(): DeviceCommandResult {
            starts++
            return DeviceCommandResult.Accepted
        }
        override suspend fun stopMotionDetection() = DeviceCommandResult.Accepted
        override suspend fun updateMotionConfig(
            configuration: MotionDetectionConfig,
            expectedVersion: Long?,
        ): DeviceCommandResult {
            lastConfiguration = configuration
            return DeviceCommandResult.Accepted
        }
    }

    private class FakeRecordingSettingsPort : RecordingSettingsCommandPort {
        var last: RecordingCommandConfig? = null
        override suspend fun updateRecordingConfig(configuration: RecordingCommandConfig): DeviceCommandResult {
            last = configuration
            return DeviceCommandResult.Accepted
        }
    }

    private companion object {
        fun identity() = DeviceIdentity(
            deviceId = "1cf83aa2-bd35-4299-8fe3-55d4fe75d6b8",
            friendlyName = "Entry",
            platform = DevicePlatform.ANDROID,
            appVersion = "1.0",
            deviceModel = "Pixel",
            androidVersion = "16",
            buildFingerprint = null,
            installedAtMillis = 1L,
            lastStartupAtMillis = 2L,
        )
    }
}
