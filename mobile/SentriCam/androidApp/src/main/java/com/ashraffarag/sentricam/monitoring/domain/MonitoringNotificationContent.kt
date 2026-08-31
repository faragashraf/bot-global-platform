package com.ashraffarag.sentricam.monitoring.domain

import com.ashraffarag.sentricam.device.domain.DeviceMotionStatus
import com.ashraffarag.sentricam.device.domain.DeviceRecordingStatus
import com.ashraffarag.sentricam.device.domain.DeviceSnapshot

data class MonitoringNotificationContent(
    val title: String,
    val statusLine: String,
    val detailLine: String?,
    val storageWarning: Boolean,
)

object MonitoringNotificationContentMapper {
    fun map(
        snapshot: DeviceSnapshot,
        options: MonitoringNotificationOptions,
        text: MonitoringNotificationText,
    ): MonitoringNotificationContent {
        val statuses = buildList {
            if (options.showMotionStatus) add(text.motion(snapshot.motionState))
            if (options.showRecordingStatus) add(text.recording(snapshot.recordingState))
        }
        val details = buildList {
            if (options.showBatteryLevel) snapshot.battery.levelPercent?.let { add(text.battery(it)) }
            if (options.showStorageWarnings && snapshot.storage.isLow) add(text.storageLow())
        }
        return MonitoringNotificationContent(
            title = text.title(),
            statusLine = statuses.joinToString(" · ").ifBlank(text::running),
            detailLine = details.joinToString(" · ").ifBlank { null },
            storageWarning = options.showStorageWarnings && snapshot.storage.isLow,
        )
    }
}

interface MonitoringNotificationText {
    fun title(): String
    fun running(): String
    fun motion(status: DeviceMotionStatus): String
    fun recording(status: DeviceRecordingStatus): String
    fun battery(levelPercent: Int): String
    fun storageLow(): String
}
