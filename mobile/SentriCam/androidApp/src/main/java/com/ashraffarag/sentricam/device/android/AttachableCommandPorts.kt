package com.ashraffarag.sentricam.device.android

import com.ashraffarag.sentricam.device.command.DeviceCommandResult
import com.ashraffarag.sentricam.device.command.MotionDetectionCommandPort
import com.ashraffarag.sentricam.device.command.RecordingCommandConfig
import com.ashraffarag.sentricam.device.command.RecordingCommandPort
import com.ashraffarag.sentricam.device.command.RecordingSettingsCommandPort
import com.ashraffarag.sentricam.device.command.UnavailableMotionDetectionCommandPort
import com.ashraffarag.sentricam.device.command.UnavailableRecordingCommandPort
import com.ashraffarag.sentricam.device.command.UnavailableRecordingSettingsCommandPort
import com.ashraffarag.sentricam.device.domain.RecordingOrigin
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig

class AttachableRecordingCommandPort : RecordingCommandPort {
    @Volatile private var delegate: RecordingCommandPort = UnavailableRecordingCommandPort
    @Volatile private var attached = false
    fun attach(port: RecordingCommandPort) { delegate = port; attached = true }
    fun detach() { delegate = UnavailableRecordingCommandPort; attached = false }
    fun isAttached(): Boolean = attached
    override suspend fun startRecording(origin: RecordingOrigin) = delegate.startRecording(origin)
    override suspend fun stopRecording(origin: RecordingOrigin) = delegate.stopRecording(origin)
}

class AttachableMotionDetectionCommandPort : MotionDetectionCommandPort {
    @Volatile private var delegate: MotionDetectionCommandPort = UnavailableMotionDetectionCommandPort
    fun attach(port: MotionDetectionCommandPort) { delegate = port }
    fun detach() { delegate = UnavailableMotionDetectionCommandPort }
    override suspend fun startMotionDetection() = delegate.startMotionDetection()
    override suspend fun stopMotionDetection() = delegate.stopMotionDetection()
    override suspend fun updateMotionConfig(
        configuration: MotionDetectionConfig,
        expectedVersion: Long?,
    ): DeviceCommandResult = delegate.updateMotionConfig(configuration, expectedVersion)
}

class AttachableRecordingSettingsCommandPort : RecordingSettingsCommandPort {
    @Volatile private var delegate: RecordingSettingsCommandPort = UnavailableRecordingSettingsCommandPort
    fun attach(port: RecordingSettingsCommandPort) { delegate = port }
    fun detach() { delegate = UnavailableRecordingSettingsCommandPort }
    override suspend fun updateRecordingConfig(configuration: RecordingCommandConfig): DeviceCommandResult =
        delegate.updateRecordingConfig(configuration)
}
