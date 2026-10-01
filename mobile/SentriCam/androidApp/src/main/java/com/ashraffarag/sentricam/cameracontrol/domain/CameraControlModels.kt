package com.ashraffarag.sentricam.cameracontrol.domain

import com.ashraffarag.sentricam.motion.capability.MotionSettingsDeviceReport
import com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayConfiguration

data class CameraControlValue(
    val boolean: Boolean? = null,
    val number: Double? = null,
    val text: String? = null,
    val dateTimeOverlay: RecordingOverlayConfiguration? = null,
)

data class CameraCapabilityDescriptor(
    val id: String,
    val supported: Boolean,
    val writable: Boolean,
    val currentValue: CameraControlValue? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val step: Double? = null,
    val allowedValues: List<String>? = null,
    val unit: String? = null,
    val reason: String? = null,
)

data class CameraControlSettings(
    val lens: String = CameraControlValues.BACK,
    val zoom: Double = 1.0,
    val torch: Boolean = false,
    val exposureCompensation: Int = 0,
    val preview: String = CameraControlValues.VISIBLE,
    val framesPerSecond: Int = 30,
    val resolution: String = "1280x720",
    val bitrate: Int = 2_500_000,
    val quality: String = CameraControlValues.MEDIUM,
    val nightProfile: String = CameraControlValues.AUTO,
    val dateTimeOverlay: RecordingOverlayConfiguration = RecordingOverlayConfiguration(),
)

data class CameraControlTelemetry(
    val cameraOnline: Boolean,
    val streaming: Boolean,
    val recording: Boolean,
    val motionArmed: Boolean,
    val batteryPercent: Int?,
    val temperatureCelsius: Double?,
    val availableStorageBytes: Long?,
    val charging: Boolean?,
    val connectionQuality: String,
    val previewAvailable: Boolean,
    val audioAvailable: Boolean,
    val recordingState: String = CameraControlValues.IDLE,
    val recordingOrigin: String = CameraControlValues.NONE,
    val latestRecordingUpload: CameraControlRecordingUpload? = null,
)

data class CameraControlRecordingUpload(
    val clientRecordingId: String,
    val state: String,
    val progressPercent: Int,
    val lastErrorCode: String? = null,
    val serverRecordingId: String? = null,
    val recordedAtUtc: String,
    val uploadedAtUtc: String? = null,
)

data class CameraControlDeviceReport(
    val deviceId: String,
    val settings: CameraControlSettings,
    val capabilities: List<CameraCapabilityDescriptor>,
    val telemetry: CameraControlTelemetry,
    val reportedAtUtc: String,
    val motionSettings: MotionSettingsDeviceReport? = null,
)

data class CameraControlCommandEnvelope(
    val commandId: String,
    val deviceId: String,
    val control: String,
    val value: CameraControlValue,
    val desiredSettings: CameraControlSettings,
    val attempt: Int,
    val requestedAtUtc: String,
    val expectedVersion: Long? = null,
)

data class CameraControlCommandResult(
    val commandId: String,
    val deviceId: String,
    val succeeded: Boolean,
    val transientFailure: Boolean,
    val resultCode: String,
    val deviceState: CameraControlDeviceReport,
    val completedAtUtc: String,
)

data class CameraControlCancellation(val commandId: String, val deviceId: String)

object CameraControlIds {
    const val RECORDING = "recording"
    const val LENS = "lens"
    const val ZOOM = "zoom"
    const val TORCH = "torch"
    const val EXPOSURE = "exposureCompensation"
    const val PREVIEW = "preview"
    const val FPS = "framesPerSecond"
    const val RESOLUTION = "resolution"
    const val BITRATE = "bitrate"
    const val QUALITY = "quality"
    const val NIGHT_PROFILE = "nightProfile"
    const val DATE_TIME_OVERLAY = "dateTimeOverlay"
    const val RESTORE = "restore"
}

object CameraControlValues {
    const val START = "start"
    const val STOP = "stop"
    const val IDLE = "idle"
    const val STARTING = "starting"
    const val RECORDING = "recording"
    const val STOPPING = "stopping"
    const val FAILED = "failed"
    const val NONE = "none"
    const val FRONT = "front"
    const val BACK = "back"
    const val VISIBLE = "visible"
    const val HIDDEN = "hidden"
    const val DIMMED = "dimmed"
    const val AUTO = "auto"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val DAY = "day"
    const val NIGHT = "night"
    const val INDOOR = "indoor"
    const val OUTDOOR = "outdoor"
}
