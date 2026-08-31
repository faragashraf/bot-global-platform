package com.ashraffarag.sentricam.cameracontrol.capability

import com.ashraffarag.sentricam.cameracontrol.domain.CameraCapabilityDescriptor
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCancellation
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandEnvelope
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandResult
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlIds
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlTelemetry
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValue
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues
import com.ashraffarag.sentricam.motion.capability.MotionSettingDescriptor
import com.ashraffarag.sentricam.motion.capability.MotionSettingIds
import com.ashraffarag.sentricam.motion.capability.MotionSettingValue
import com.ashraffarag.sentricam.motion.capability.MotionSettingsDeviceReport
import com.ashraffarag.sentricam.motion.capability.MotionSettingsValues
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayPositions
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlServiceTest {
    @Test
    fun localUiControlUsesTheSharedHardwarePersistenceAndChangePipeline() = runBlocking {
        val fixture = Fixture()
        try {
            val result = fixture.service.applyLocal(
                CameraControlIds.ZOOM,
                CameraControlSettings(zoom = 2.5),
            )

            assertTrue(result.succeeded)
            assertEquals(listOf(CameraControlIds.ZOOM), fixture.hardware.controls)
            assertEquals(2.5, fixture.repository.load().zoom, 0.0)
            assertEquals(2.5, fixture.changed.single().zoom, 0.0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun invalidLocalUiZoomFailsBeforeHardwareMutation() = runBlocking {
        val fixture = Fixture()
        try {
            val result = fixture.service.applyLocal(
                CameraControlIds.ZOOM,
                CameraControlSettings(zoom = 8.0),
            )

            assertFalse(result.succeeded)
            assertEquals("capability_validation_failed", result.code)
            assertTrue(fixture.hardware.controls.isEmpty())
            assertEquals(1.0, fixture.repository.load().zoom, 0.0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun validatesExecutesPersistsAndReportsThroughOnePipeline() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.service.receive(command("one", CameraControlIds.ZOOM, CameraControlValue(number = 2.5), CameraControlSettings(zoom = 2.5)))

            val result = fixture.signaling.next()
            assertTrue(result.succeeded)
            assertEquals(CameraControlIds.ZOOM, fixture.hardware.controls.single())
            assertEquals(2.5, fixture.repository.load().zoom, 0.0)
            assertEquals(2.5, fixture.changed.single().zoom, 0.0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dateTimeOverlayUsesOnePersistedConfigurationAndReconfiguresTheSharedCamera() = runBlocking {
        val fixture = Fixture()
        val overlay = RecordingOverlayConfiguration(
            enabled = true,
            dateEnabled = true,
            timeEnabled = true,
            use24HourTime = false,
            position = RecordingOverlayPositions.TOP_RIGHT,
        )
        try {
            fixture.service.receive(
                command(
                    "overlay-one",
                    CameraControlIds.DATE_TIME_OVERLAY,
                    CameraControlValue(dateTimeOverlay = overlay),
                    CameraControlSettings(dateTimeOverlay = overlay),
                ),
            )

            assertTrue(fixture.signaling.next().succeeded)
            assertEquals(CameraControlIds.DATE_TIME_OVERLAY, fixture.hardware.controls.single())
            assertEquals(overlay, fixture.repository.load().dateTimeOverlay)
            assertEquals(overlay, fixture.changed.single().dateTimeOverlay)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serializesCommandsPerCamera() = runBlocking {
        val hardware = BlockingHardware()
        val fixture = Fixture(hardware)
        try {
            fixture.service.receive(command("one", CameraControlIds.ZOOM, CameraControlValue(number = 2.0), CameraControlSettings(zoom = 2.0)))
            fixture.service.receive(command("two", CameraControlIds.TORCH, CameraControlValue(boolean = true), CameraControlSettings(torch = true)))

            hardware.firstStarted.await()
            assertEquals(listOf(CameraControlIds.ZOOM), hardware.controls)
            hardware.releaseFirst.complete(Unit)
            fixture.signaling.next()
            fixture.signaling.next()
            assertEquals(listOf(CameraControlIds.ZOOM, CameraControlIds.TORCH), hardware.controls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsupportedCommandFailsBeforeCameraMutation() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.service.receive(command("bad", "manualFocus", CameraControlValue(number = 1.0), CameraControlSettings()))

            val result = fixture.signaling.next()
            assertFalse(result.succeeded)
            assertEquals("capability_validation_failed", result.resultCode)
            assertTrue(fixture.hardware.controls.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun duplicateDeliveryAcknowledgesAgainWithoutRepeatingCameraMutation() = runBlocking {
        val fixture = Fixture()
        try {
            val command = command(
                "duplicate",
                CameraControlIds.TORCH,
                CameraControlValue(boolean = true),
                CameraControlSettings(torch = true),
            )

            fixture.service.receive(command)
            assertTrue(fixture.signaling.next().succeeded)
            fixture.service.receive(command)
            assertTrue(fixture.signaling.next().succeeded)

            assertEquals(listOf(CameraControlIds.TORCH), fixture.hardware.controls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun acceptsServerGuidCasingForTheLocalDevice() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.service.receive(
                command(
                    "case-insensitive-device",
                    CameraControlIds.TORCH,
                    CameraControlValue(boolean = true),
                    CameraControlSettings(torch = true),
                    deviceId = DEVICE_ID.uppercase(),
                ),
            )

            assertTrue(fixture.signaling.next().succeeded)
            assertEquals(listOf(CameraControlIds.TORCH), fixture.hardware.controls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationOfQueuedCommandPreservesActiveCommand() = runBlocking {
        val hardware = BlockingHardware()
        val fixture = Fixture(hardware)
        try {
            fixture.service.receive(command("one", CameraControlIds.ZOOM, CameraControlValue(number = 2.0), CameraControlSettings(zoom = 2.0)))
            hardware.firstStarted.await()
            fixture.service.cancel(CameraControlCancellation("two", DEVICE_ID))
            fixture.service.receive(command("two", CameraControlIds.TORCH, CameraControlValue(boolean = true), CameraControlSettings(torch = true)))
            hardware.releaseFirst.complete(Unit)

            assertTrue(fixture.signaling.next().succeeded)
            val canceled = fixture.signaling.next()
            assertFalse(canceled.succeeded)
            assertEquals("operator_canceled", canceled.resultCode)
            assertEquals(listOf(CameraControlIds.ZOOM), hardware.controls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationDuringCameraOperationRollsBackBeforeAcknowledgement() = runBlocking {
        val hardware = BlockingHardware()
        val fixture = Fixture(hardware)
        try {
            fixture.service.receive(command("one", CameraControlIds.ZOOM, CameraControlValue(number = 2.0), CameraControlSettings(zoom = 2.0)))
            hardware.firstStarted.await()
            fixture.service.cancel(CameraControlCancellation("one", DEVICE_ID))
            hardware.releaseFirst.complete(Unit)

            val canceled = fixture.signaling.next()
            assertFalse(canceled.succeeded)
            assertEquals("operator_canceled", canceled.resultCode)
            assertEquals(1.0, fixture.repository.load().zoom, 0.0)
            assertEquals(listOf(CameraControlIds.ZOOM, CameraControlIds.ZOOM), hardware.controls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun persistedSettingsRestoreWhenCameraReattachesAfterRestart() = runBlocking {
        val repository = MemoryRepository(CameraControlSettings(zoom = 3.0, torch = true, preview = "dimmed"))
        val fixture = Fixture(repository = repository)
        try {
            fixture.service.restoreAttachedHardware()

            assertEquals(CameraControlIds.RESTORE, fixture.hardware.controls.single())
            assertEquals(3.0, fixture.hardware.settings.single().zoom, 0.0)
            assertTrue(fixture.hardware.settings.single().torch)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun recordingCommandUsesUnifiedPipelineWithoutMutatingCameraSettings() = runBlocking {
        val actions = mutableListOf<String>()
        val fixture = Fixture(recording = CameraRecordingControl { action ->
            actions += action
            CameraHardwareResult(true, code = "recording_start_accepted")
        })
        try {
            fixture.service.receive(
                command(
                    "recording-one",
                    CameraControlIds.RECORDING,
                    CameraControlValue(text = CameraControlValues.START),
                    CameraControlSettings(),
                ),
            )

            val result = fixture.signaling.next()
            assertTrue(result.succeeded)
            assertEquals("recording_start_accepted", result.resultCode)
            assertEquals(listOf(CameraControlValues.START), actions)
            assertTrue(fixture.hardware.controls.isEmpty())
            assertTrue(fixture.changed.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun motionCommandUsesDedicatedAdapterReportedCapabilityAndConcurrencyVersion() = runBlocking {
        val applied = mutableListOf<Triple<String, CameraControlValue, Long?>>()
        val fixture = Fixture(motion = MotionSettingsControl { control, value, expectedVersion ->
            applied += Triple(control, value, expectedVersion)
            CameraHardwareResult(true, code = "motion_settings_applied")
        })
        try {
            fixture.service.receive(
                command(
                    "motion-one",
                    MotionSettingIds.SENSITIVITY,
                    CameraControlValue(text = "high"),
                    CameraControlSettings(),
                    expectedVersion = 7,
                ),
            )

            val result = fixture.signaling.next()
            assertTrue(result.succeeded)
            assertEquals("motion_settings_applied", result.resultCode)
            assertEquals(Triple(MotionSettingIds.SENSITIVITY, CameraControlValue(text = "high"), 7L), applied.single())
            assertTrue(fixture.hardware.controls.isEmpty())
            assertTrue(fixture.changed.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleMotionCommandFailsBeforeEngineMutation() = runBlocking {
        var applications = 0
        val fixture = Fixture(motion = MotionSettingsControl { _, _, _ ->
            applications += 1
            CameraHardwareResult(true)
        })
        try {
            fixture.service.receive(
                command(
                    "motion-stale",
                    MotionSettingIds.ENABLED,
                    CameraControlValue(boolean = false),
                    CameraControlSettings(),
                    expectedVersion = 6,
                ),
            )

            val result = fixture.signaling.next()
            assertFalse(result.succeeded)
            assertEquals("capability_validation_failed", result.resultCode)
            assertEquals(0, applications)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        val hardware: RecordingHardware = RecordingHardware(),
        val repository: MemoryRepository = MemoryRepository(),
        val recording: CameraRecordingControl = UnavailableCameraRecordingControl,
        val motion: MotionSettingsControl = UnavailableMotionSettingsControl,
    ) {
        val signaling = QueueSignaling()
        val changed = Collections.synchronizedList(mutableListOf<CameraControlSettings>())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CameraControlService(
            localDeviceId = { DEVICE_ID },
            settings = repository,
            hardware = hardware,
            recording = recording,
            motion = motion,
            capabilities = CameraCapabilityReporter(::report),
            signaling = signaling,
            onSettingsChanged = changed::add,
            scope = scope,
        )

        fun close() {
            service.close()
            scope.cancel()
        }
    }

    private open class RecordingHardware : CameraControlHardware {
        val controls = Collections.synchronizedList(mutableListOf<String>())
        val settings = Collections.synchronizedList(mutableListOf<CameraControlSettings>())
        override suspend fun apply(settings: CameraControlSettings, control: String): CameraHardwareResult {
            this.settings += settings
            controls += control
            return CameraHardwareResult(true)
        }
    }

    private class BlockingHardware : RecordingHardware() {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        override suspend fun apply(settings: CameraControlSettings, control: String): CameraHardwareResult {
            val result = super.apply(settings, control)
            if (controls.size == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            return result
        }
    }

    private class MemoryRepository(initial: CameraControlSettings = CameraControlSettings()) : CameraControlSettingsRepository {
        private var value = initial
        override fun load() = value
        override fun save(settings: CameraControlSettings) { value = settings }
    }

    private class QueueSignaling : CameraControlSignalingSender {
        private val results = LinkedBlockingQueue<CameraControlCommandResult>()
        override suspend fun completeCameraControl(result: CameraControlCommandResult) { results.put(result) }
        fun next(): CameraControlCommandResult = results.take()
    }

    companion object {
        private const val DEVICE_ID = "8cc58e6b-9519-40cb-b3b8-ca73c7a4a3af"

        private fun report(deviceId: String) = CameraControlDeviceReport(
            deviceId,
            CameraControlSettings(),
            listOf(
                CameraCapabilityDescriptor(CameraControlIds.ZOOM, true, true, CameraControlValue(number = 1.0), 1.0, 4.0, .1),
                CameraCapabilityDescriptor(CameraControlIds.TORCH, true, true, CameraControlValue(boolean = false)),
                CameraCapabilityDescriptor(CameraControlIds.PREVIEW, true, true, CameraControlValue(text = "visible"), allowedValues = listOf("visible", "hidden", "dimmed")),
                CameraCapabilityDescriptor(CameraControlIds.RECORDING, true, true, CameraControlValue(text = "idle"), allowedValues = listOf("start", "stop")),
                CameraCapabilityDescriptor(
                    CameraControlIds.DATE_TIME_OVERLAY,
                    true,
                    true,
                    CameraControlValue(dateTimeOverlay = RecordingOverlayConfiguration()),
                ),
            ),
            CameraControlTelemetry(true, false, false, true, 80, 31.0, 10_000, false, "good", true, true),
            "2026-08-02T10:00:00Z",
            motionReport(),
        )

        private fun motionReport() = MotionSettingsDeviceReport(
            settings = MotionSettingsValues(false, "medium", 50, 1_000, 10_000, 5_000),
            capabilities = listOf(
                MotionSettingDescriptor(
                    MotionSettingIds.ENABLED,
                    supported = true,
                    writable = true,
                    currentValue = MotionSettingValue(boolean = false),
                ),
                MotionSettingDescriptor(
                    MotionSettingIds.SENSITIVITY,
                    supported = true,
                    writable = true,
                    currentValue = MotionSettingValue(text = "medium"),
                    allowedValues = listOf("low", "medium", "high", "advanced"),
                ),
            ),
            version = 7,
        )

        private fun command(
            id: String,
            control: String,
            value: CameraControlValue,
            desired: CameraControlSettings,
            deviceId: String = DEVICE_ID,
            expectedVersion: Long? = null,
        ) = CameraControlCommandEnvelope(id, deviceId, control, value, desired, 1, "2026-08-02T10:00:00Z", expectedVersion)
    }
}
