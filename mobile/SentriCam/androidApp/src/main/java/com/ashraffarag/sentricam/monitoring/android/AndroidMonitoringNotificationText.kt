package com.ashraffarag.sentricam.monitoring.android

import android.content.Context
import androidx.annotation.StringRes
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.device.domain.DeviceMotionStatus
import com.ashraffarag.sentricam.device.domain.DeviceRecordingStatus
import com.ashraffarag.sentricam.monitoring.domain.MonitoringNotificationText

class AndroidMonitoringNotificationText(
    private val context: Context,
) : MonitoringNotificationText {
    override fun title(): String = context.getString(R.string.monitoring_notification_title)

    override fun running(): String = context.getString(R.string.monitoring_notification_running)

    override fun motion(status: DeviceMotionStatus): String = context.getString(
        R.string.monitoring_notification_motion_format,
        context.getString(status.textResource()),
    )

    override fun recording(status: DeviceRecordingStatus): String = context.getString(
        R.string.monitoring_notification_recording_format,
        context.getString(status.textResource()),
    )

    override fun battery(levelPercent: Int): String = context.getString(
        R.string.monitoring_notification_battery_format,
        levelPercent,
    )

    override fun storageLow(): String = context.getString(R.string.monitoring_notification_storage_low)

    @StringRes
    private fun DeviceMotionStatus.textResource(): Int = when (this) {
        DeviceMotionStatus.DISABLED -> R.string.monitoring_motion_disabled
        DeviceMotionStatus.INITIALIZING -> R.string.monitoring_motion_initializing
        DeviceMotionStatus.NO_MOTION -> R.string.monitoring_motion_none
        DeviceMotionStatus.SUSPECTED -> R.string.monitoring_motion_suspected
        DeviceMotionStatus.CONFIRMED -> R.string.monitoring_motion_confirmed
        DeviceMotionStatus.HOLDING -> R.string.monitoring_motion_holding
        DeviceMotionStatus.COOLDOWN -> R.string.monitoring_motion_cooldown
        DeviceMotionStatus.ERROR -> R.string.monitoring_motion_error
    }

    @StringRes
    private fun DeviceRecordingStatus.textResource(): Int = when (this) {
        DeviceRecordingStatus.IDLE -> R.string.monitoring_recording_idle
        DeviceRecordingStatus.PREPARING -> R.string.monitoring_recording_preparing
        DeviceRecordingStatus.READY -> R.string.monitoring_recording_ready
        DeviceRecordingStatus.STARTING -> R.string.monitoring_recording_starting
        DeviceRecordingStatus.RECORDING -> R.string.monitoring_recording_active
        DeviceRecordingStatus.ROTATING_SEGMENT -> R.string.monitoring_recording_rotating
        DeviceRecordingStatus.STOPPING -> R.string.monitoring_recording_stopping
        DeviceRecordingStatus.COMPLETED -> R.string.monitoring_recording_completed
        DeviceRecordingStatus.FAILED -> R.string.monitoring_recording_failed
    }
}
