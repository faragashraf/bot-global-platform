package com.ashraffarag.sentricam.cameracontrol.capability

import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCancellation
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandEnvelope
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandResult
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings

interface CameraControlSettingsRepository {
    fun load(): CameraControlSettings
    fun save(settings: CameraControlSettings)
}

fun interface CameraCapabilityReporter {
    fun report(deviceId: String): CameraControlDeviceReport
}

data class CameraHardwareResult(
    val succeeded: Boolean,
    val transientFailure: Boolean = false,
    val code: String = if (succeeded) "applied" else "camera_control_failed",
)

interface CameraControlHardware {
    suspend fun apply(settings: CameraControlSettings, control: String): CameraHardwareResult
}

fun interface CameraRecordingControl {
    suspend fun apply(action: String): CameraHardwareResult
}

fun interface MotionSettingsControl {
    suspend fun apply(
        control: String,
        value: com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValue,
        expectedVersion: Long?,
    ): CameraHardwareResult
}

object UnavailableMotionSettingsControl : MotionSettingsControl {
    override suspend fun apply(
        control: String,
        value: com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValue,
        expectedVersion: Long?,
    ) = CameraHardwareResult(false, transientFailure = true, code = "motion_adapter_unavailable")
}

object UnavailableCameraRecordingControl : CameraRecordingControl {
    override suspend fun apply(action: String) = CameraHardwareResult(
        succeeded = false,
        transientFailure = true,
        code = "camera_not_ready",
    )
}

class AttachableCameraControlHardware : CameraControlHardware {
    private val lock = Any()
    private var delegate: CameraControlHardware? = null

    fun attach(hardware: CameraControlHardware) = synchronized(lock) { delegate = hardware }
    fun detach(hardware: CameraControlHardware? = null) = synchronized(lock) {
        if (hardware == null || delegate === hardware) delegate = null
    }
    fun attached(): Boolean = synchronized(lock) { delegate != null }

    override suspend fun apply(settings: CameraControlSettings, control: String): CameraHardwareResult {
        val current = synchronized(lock) { delegate }
            ?: return CameraHardwareResult(false, transientFailure = true, code = "camera_unavailable")
        return current.apply(settings, control)
    }
}

interface CameraControlSignalReceiver {
    fun state(deviceId: String): CameraControlDeviceReport
    fun receive(command: CameraControlCommandEnvelope)
    fun cancel(cancellation: CameraControlCancellation)
}

object NoOpCameraControlSignalReceiver : CameraControlSignalReceiver {
    override fun state(deviceId: String) = CameraControlDeviceReport(
        deviceId,
        CameraControlSettings(),
        emptyList(),
        com.ashraffarag.sentricam.cameracontrol.domain.CameraControlTelemetry(
            false, false, false, false, null, null, null, null, "offline", false, false,
        ),
        java.time.Instant.EPOCH.toString(),
    )
    override fun receive(command: CameraControlCommandEnvelope) = Unit
    override fun cancel(cancellation: CameraControlCancellation) = Unit
}

interface CameraControlSignalingSender {
    suspend fun completeCameraControl(result: CameraControlCommandResult)
    suspend fun reportCameraControlState(report: CameraControlDeviceReport) = Unit
}

fun interface CameraControlLogger {
    fun info(message: String)
}

object NoOpCameraControlLogger : CameraControlLogger {
    override fun info(message: String) = Unit
}
