package com.ashraffarag.sentricam.monitoring.domain

import com.ashraffarag.sentricam.device.domain.BatteryState
import com.ashraffarag.sentricam.device.domain.DeviceClock
import com.ashraffarag.sentricam.device.domain.DeviceHealth
import com.ashraffarag.sentricam.device.domain.DeviceIdentity
import com.ashraffarag.sentricam.device.domain.DevicePlatform
import com.ashraffarag.sentricam.device.domain.DeviceMotionStatus
import com.ashraffarag.sentricam.device.domain.DeviceRecordingStatus
import com.ashraffarag.sentricam.device.domain.DeviceState
import com.ashraffarag.sentricam.device.domain.DeviceStatusMapper
import com.ashraffarag.sentricam.device.domain.StorageState
import com.ashraffarag.sentricam.motion.domain.MotionDebugMetrics
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionEventSummary
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringNotificationContentTest {
    @Test
    fun contentIncludesObservableMotionRecordingBatteryAndStorageState() {
        val event = MotionEventSummary(
            eventId = "event",
            detectedAtMillis = 1L,
            lastMotionAtMillis = 2L,
            sensitivity = MotionSensitivity.HIGH,
            peakScore = 0.8,
            averageScore = 0.6,
            sampleCount = 2,
            burstCount = 1,
        )
        val snapshot = DeviceStatusMapper(DeviceClock { 3L }).map(
            DeviceState(
                identity = identity(),
                motionConfiguration = MotionDetectionConfig(enabled = true),
                motion = MotionDetectionState.MotionConfirmed(event, MotionDebugMetrics(score = 0.8)),
                health = DeviceHealth(
                    battery = BatteryState(levelPercent = 42),
                    storage = StorageState(isLow = true),
                ),
            ),
        )

        val content = MonitoringNotificationContentMapper.map(
            snapshot,
            MonitoringNotificationOptions(),
            englishNotificationText,
        )

        assertEquals("Monitoring", content.title)
        assertTrue(content.statusLine.contains("Motion: confirmed"))
        assertTrue(content.statusLine.contains("Recording: idle"))
        assertTrue(content.detailLine.orEmpty().contains("Battery: 42%"))
        assertTrue(content.detailLine.orEmpty().contains("Storage low"))
        assertTrue(content.storageWarning)
    }

    @Test
    fun disabledNotificationDetailsAreOmitted() {
        val snapshot = DeviceStatusMapper(DeviceClock { 3L }).map(DeviceState(identity()))
        val content = MonitoringNotificationContentMapper.map(
            snapshot,
            MonitoringNotificationOptions(
                showMotionStatus = false,
                showRecordingStatus = false,
                showBatteryLevel = false,
                showStorageWarnings = false,
            ),
            englishNotificationText,
        )

        assertEquals("Monitoring is running", content.statusLine)
        assertEquals(null, content.detailLine)
        assertFalse(content.storageWarning)
    }

    private fun identity() = DeviceIdentity(
        deviceId = "device",
        friendlyName = "Entry",
        platform = DevicePlatform.ANDROID,
        appVersion = "1.0",
        deviceModel = "Phone",
        androidVersion = "6.0",
        buildFingerprint = null,
        installedAtMillis = 1L,
        lastStartupAtMillis = 2L,
    )

    private val englishNotificationText = object : MonitoringNotificationText {
        override fun title() = "Monitoring"
        override fun running() = "Monitoring is running"
        override fun motion(status: DeviceMotionStatus) =
            "Motion: ${status.name.lowercase().replace('_', ' ')}"
        override fun recording(status: DeviceRecordingStatus) =
            "Recording: ${status.name.lowercase().replace('_', ' ')}"
        override fun battery(levelPercent: Int) = "Battery: $levelPercent%"
        override fun storageLow() = "Storage low"
    }
}
