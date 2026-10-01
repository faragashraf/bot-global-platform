package com.ashraffarag.sentricam.device.domain

import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.monitoring.domain.MonitoringState
import com.ashraffarag.sentricam.motion.domain.MotionDebugMetrics
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionEventSummary
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.recording.engine.domain.RecordingProfile
import com.ashraffarag.sentricam.recording.engine.domain.RecordingQuality
import com.ashraffarag.sentricam.recording.engine.domain.RecordingRequest
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSession
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.recording.engine.domain.RecordingTriggerContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSnapshotTest {
    @Test
    fun mapperIncludesMotionRecordingOriginHealthConnectivityAndIdentity() {
        val event = motionEvent(lastMotionAtMillis = 88L)
        val state = DeviceState(
            identity = identity(),
            monitoring = MonitoringState.stopped(),
            recording = RecordingState.Ready(recordingSession(RecordingStartReason.MOTION), false),
            motion = MotionDetectionState.MotionConfirmed(event, MotionDebugMetrics(score = 0.75)),
            motionConfiguration = MotionDetectionConfig(
                enabled = true,
                sensitivity = MotionSensitivity.HIGH,
            ),
            health = DeviceHealth(
                battery = BatteryState(75, true, false),
                storage = StorageState(10L, 100L, true),
                memory = MemorySummary(20L, 200L, false),
                activity = ActivityHistory(1L, 80L, 3L),
            ),
            connectivity = ConnectivityState.InternetAvailable(setOf("wifi"), false),
            camera = CameraState(DeviceCameraState.READY, DeviceCameraLens.BACK, 2),
            capabilities = DeviceCapabilities.of(
                AppCapability.MANUAL_RECORDING to CapabilityAccess.Available,
                AppCapability.REMOTE_CONTROL to CapabilityAccess.ComingSoon,
            ),
        )

        val snapshot = DeviceStatusMapper(DeviceClock { 999L }).map(state)

        assertEquals(state.identity.deviceId, snapshot.deviceId)
        assertEquals(DeviceRecordingStatus.READY, snapshot.recordingState)
        assertEquals(RecordingOrigin.MOTION, snapshot.recordingOrigin)
        assertTrue(snapshot.motionEnabled)
        assertEquals(DeviceMotionStatus.CONFIRMED, snapshot.motionState)
        assertEquals(event.eventId, snapshot.lastMotionEventId)
        assertEquals(1L, snapshot.lastActivityAtMillis)
        assertEquals(88L, snapshot.lastMotionAtMillis)
        assertEquals(3L, snapshot.lastRecordingAtMillis)
        assertEquals(75, snapshot.battery.levelPercent)
        assertEquals(999L, snapshot.updatedAtMillis)
        assertEquals(1L, snapshot.installedAtMillis)
        assertEquals(2L, snapshot.lastStartupAtMillis)
        assertEquals(DeviceSnapshot.CURRENT_SCHEMA_VERSION, snapshot.schemaVersion)
        assertFalse(snapshot.network is ConnectivityState.Offline)
        assertTrue(snapshot.capabilities.isAvailable(AppCapability.MANUAL_RECORDING))
    }

    @Test
    fun defaultDeviceKeepsRealEngineStateAsSingleSourceAndRegeneratesSnapshot() {
        var now = 10L
        val device = DefaultDevice(DeviceState(identity()), DeviceStatusMapper(DeviceClock { now }))
        val first = device.snapshot.value
        val recording = RecordingState.Ready(recordingSession(RecordingStartReason.MANUAL), false)
        now = 20L

        device.update { it.copy(recording = recording) }

        assertEquals(recording, device.state.value.recording)
        assertEquals(DeviceRecordingStatus.READY, device.snapshot.value.recordingState)
        assertEquals(RecordingOrigin.MANUAL, device.snapshot.value.recordingOrigin)
        assertEquals(20L, device.snapshot.value.updatedAtMillis)
        assertNotSame(first, device.snapshot.value)
    }

    @Test
    fun healthConnectivityAndMotionUpdatesRegenerateOneCurrentSnapshot() {
        val device = DefaultDevice(DeviceState(identity()), DeviceStatusMapper(DeviceClock { 30L }))
        val event = motionEvent(lastMotionAtMillis = 25L)

        device.update {
            it.copy(
                motion = MotionDetectionState.MotionConfirmed(event, MotionDebugMetrics(score = 0.9)),
                motionConfiguration = MotionDetectionConfig(enabled = true),
                health = it.health.copy(
                    battery = BatteryState(44, false, true),
                    storage = StorageState(40L, 400L, false),
                ),
                connectivity = ConnectivityState.CaptivePortal,
            )
        }

        val snapshot = device.snapshot.value
        assertTrue(snapshot.motionEnabled)
        assertEquals(DeviceMotionStatus.CONFIRMED, snapshot.motionState)
        assertEquals(44, snapshot.battery.levelPercent)
        assertEquals(40L, snapshot.storage.availableBytes)
        assertEquals(ConnectivityState.CaptivePortal, snapshot.network)
    }

    @Test
    fun manuallyClaimedMotionRecordingMapsToManualOrigin() {
        val session = recordingSession(RecordingStartReason.MOTION)
        val claimed = session.copy(
            request = session.request.copy(
                triggerContext = session.request.triggerContext.copy(manualControlClaimed = true),
            ),
        )

        val snapshot = DeviceStatusMapper(DeviceClock { 40L }).map(
            DeviceState(identity(), recording = RecordingState.Ready(claimed, false)),
        )

        assertEquals(RecordingOrigin.MANUAL, snapshot.recordingOrigin)
    }

    private fun recordingSession(origin: RecordingStartReason) = RecordingSession(
        id = "session",
        request = RecordingRequest(
            profile = RecordingProfile(audioEnabled = false),
            triggerContext = RecordingTriggerContext(origin),
        ),
        requestedQuality = RecordingQuality.HD,
        selectedQuality = RecordingQuality.HD,
        audioEnabled = false,
        createdAtMillis = 1L,
    )

    private fun motionEvent(lastMotionAtMillis: Long) = MotionEventSummary(
        eventId = "motion-event",
        detectedAtMillis = 70L,
        lastMotionAtMillis = lastMotionAtMillis,
        sensitivity = MotionSensitivity.HIGH,
        peakScore = 0.8,
        averageScore = 0.6,
        sampleCount = 3,
        burstCount = 1,
    )

    private fun identity() = DeviceIdentity(
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
