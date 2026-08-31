package com.ashraffarag.sentricam.cameracontrol.capability

import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCancellation
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandEnvelope
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandResult
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlIds
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlSettings
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CameraControlService(
    private val localDeviceId: () -> String,
    private val settings: CameraControlSettingsRepository,
    private val hardware: CameraControlHardware,
    private val recording: CameraRecordingControl = UnavailableCameraRecordingControl,
    private val motion: MotionSettingsControl = UnavailableMotionSettingsControl,
    private val capabilities: CameraCapabilityReporter,
    private val signaling: CameraControlSignalingSender,
    private val onSettingsChanged: (CameraControlSettings) -> Unit,
    scope: CoroutineScope,
    private val logger: CameraControlLogger = NoOpCameraControlLogger,
) : CameraControlSignalReceiver, AutoCloseable {
    private val events = Channel<CameraControlCommandEnvelope>(Channel.UNLIMITED)
    private val canceled = ConcurrentHashMap.newKeySet<String>()
    private val completedLock = Any()
    private val completed = LinkedHashMap<String, CameraControlCommandResult>()
    private val hardwareGate = Mutex()
    private val worker = scope.launch {
        for (command in events) execute(command)
    }

    override fun state(deviceId: String): CameraControlDeviceReport = capabilities.report(deviceId)

    override fun receive(command: CameraControlCommandEnvelope) {
        diagnostic("event=received command=${command.commandId} control=${command.control} attempt=${command.attempt}")
        events.trySend(command)
    }

    override fun cancel(cancellation: CameraControlCancellation) {
        canceled += cancellation.commandId
    }

    suspend fun restoreAttachedHardware() {
        diagnostic("event=restore_waiting")
        hardwareGate.withLock {
            diagnostic("event=restore_started")
            val restored = hardware.apply(settings.load(), CameraControlIds.RESTORE)
            diagnostic("event=restore_finished result=${restored.code}")
        }
    }

    /** Applies an on-device UI request through the same hardware, persistence, and publish path. */
    suspend fun applyLocal(
        control: String,
        desiredSettings: CameraControlSettings,
    ): CameraHardwareResult = hardwareGate.withLock {
        val descriptor = state(localDeviceId()).capabilities.firstOrNull { it.id == control }
        if (descriptor == null || !descriptor.supported || !descriptor.writable ||
            !localValueIsValid(control, desiredSettings, descriptor.minimum, descriptor.maximum)
        ) {
            return@withLock CameraHardwareResult(false, code = "capability_validation_failed")
        }
        val applied = hardware.apply(desiredSettings, control)
        if (applied.succeeded) {
            settings.save(desiredSettings)
            onSettingsChanged(desiredSettings)
        }
        applied
    }

    suspend fun publishState() {
        val deviceId = localDeviceId()
        runCatching { signaling.reportCameraControlState(state(deviceId)) }
            .onSuccess { diagnostic("event=state_published") }
            .onFailure { failure ->
                diagnostic("event=state_publish_failed reason=${failure::class.java.simpleName}")
            }
    }

    private suspend fun execute(command: CameraControlCommandEnvelope) {
        if (!command.deviceId.equals(localDeviceId(), ignoreCase = true)) {
            diagnostic("event=device_mismatch command=${command.commandId}")
            return
        }
        diagnostic("event=execution_started command=${command.commandId} control=${command.control}")
        val cached = synchronized(completedLock) { completed[command.commandId] }
        if (cached != null) {
            diagnostic("event=duplicate command=${command.commandId} result=${cached.resultCode}")
            acknowledge(cached)
            return
        }
        val result = when {
            canceled.remove(command.commandId) -> result(command, false, false, "operator_canceled")
            !valid(command) -> result(command, false, false, "capability_validation_failed")
            else -> apply(command)
        }
        remember(result)
        acknowledge(result)
    }

    private suspend fun acknowledge(result: CameraControlCommandResult) {
        runCatching { signaling.completeCameraControl(result) }
            .onSuccess {
                diagnostic("event=acknowledged command=${result.commandId} result=${result.resultCode}")
            }
            .onFailure { failure ->
                diagnostic(
                    "event=acknowledgment_failed command=${result.commandId} reason=${failure::class.java.simpleName}",
                )
            }
    }

    private fun remember(result: CameraControlCommandResult) = synchronized(completedLock) {
        completed[result.commandId] = result
        while (completed.size > MAXIMUM_COMPLETED_RESULTS) {
            completed.remove(completed.entries.first().key)
        }
    }

    private fun diagnostic(message: String) {
        logger.info(message)
    }

    private suspend fun apply(command: CameraControlCommandEnvelope): CameraControlCommandResult {
        diagnostic("event=hardware_waiting command=${command.commandId} control=${command.control}")
        return hardwareGate.withLock {
            diagnostic("event=hardware_started command=${command.commandId} control=${command.control}")
            if (command.control == CameraControlIds.RECORDING) {
                val applied = recording.apply(requireNotNull(command.value.text))
                diagnostic("event=recording_finished command=${command.commandId} result=${applied.code}")
                return@withLock result(command, applied.succeeded, applied.transientFailure, applied.code)
            }
            if (command.control.startsWith(MOTION_CONTROL_PREFIX)) {
                val applied = motion.apply(command.control, command.value, command.expectedVersion)
                diagnostic("event=motion_finished command=${command.commandId} result=${applied.code}")
                return@withLock result(command, applied.succeeded, applied.transientFailure, applied.code)
            }
            val previous = settings.load()
            val rebindControl = command.control in setOf(
                CameraControlIds.LENS,
                CameraControlIds.FPS,
                CameraControlIds.RESOLUTION,
                CameraControlIds.BITRATE,
                CameraControlIds.QUALITY,
                CameraControlIds.NIGHT_PROFILE,
                CameraControlIds.DATE_TIME_OVERLAY,
            )
            if (rebindControl) {
                settings.save(command.desiredSettings)
                onSettingsChanged(command.desiredSettings)
            }
            val applied = hardware.apply(command.desiredSettings, command.control)
            if (canceled.remove(command.commandId)) {
                settings.save(previous)
                onSettingsChanged(previous)
                hardware.apply(previous, command.control)
                return@withLock result(command, false, false, "operator_canceled")
            }
            if (applied.succeeded) {
                if (!rebindControl) {
                    settings.save(command.desiredSettings)
                    onSettingsChanged(command.desiredSettings)
                }
            } else if (rebindControl) {
                settings.save(previous)
                onSettingsChanged(previous)
            }
            diagnostic("event=hardware_finished command=${command.commandId} result=${applied.code}")
            result(command, applied.succeeded, applied.transientFailure, applied.code)
        }
    }

    private fun valid(command: CameraControlCommandEnvelope): Boolean {
        val report = state(command.deviceId)
        if (command.control == CameraControlIds.RESTORE) return true
        val motionReport = report.motionSettings
        val motionDescriptor = motionReport?.capabilities?.firstOrNull { it.id == command.control }
        if (motionDescriptor != null) {
            if (!motionDescriptor.supported || !motionDescriptor.writable) return false
            if (command.expectedVersion == null || command.expectedVersion != motionReport.version) return false
            command.value.number?.let { number ->
                if (motionDescriptor.minimum?.let { number < it } == true ||
                    motionDescriptor.maximum?.let { number > it } == true ||
                    !motionDescriptor.allowedValues.isNullOrEmpty() &&
                    number.toLong().toString() !in motionDescriptor.allowedValues
                ) return false
            }
            command.value.text?.let { text ->
                if (!motionDescriptor.allowedValues.isNullOrEmpty() &&
                    motionDescriptor.allowedValues.none { it.equals(text, true) }
                ) return false
            }
            return listOfNotNull(command.value.boolean, command.value.number, command.value.text).size == 1
        }
        val descriptor = report.capabilities.firstOrNull { it.id == command.control } ?: return false
        if (!descriptor.supported || !descriptor.writable) return false
        if (command.control == CameraControlIds.DATE_TIME_OVERLAY) {
            val overlay = command.value.dateTimeOverlay ?: return false
            if (overlay.position !in com.ashraffarag.sentricam.recording.settings.domain.RecordingOverlayPositions.ALL ||
                overlay.showDateTime && !overlay.dateEnabled && !overlay.timeEnabled
            ) return false
        }
        command.value.number?.let { number ->
            if (descriptor.minimum?.let { number < it } == true || descriptor.maximum?.let { number > it } == true) return false
        }
        command.value.text?.let { text ->
            if (!descriptor.allowedValues.isNullOrEmpty() && descriptor.allowedValues.none { it.equals(text, true) }) return false
        }
        if (command.control == CameraControlIds.RECORDING && command.value.text !in setOf(
                com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues.START,
                com.ashraffarag.sentricam.cameracontrol.domain.CameraControlValues.STOP,
            )
        ) return false
        return listOfNotNull(
            command.value.boolean,
            command.value.number,
            command.value.text,
            command.value.dateTimeOverlay,
        ).size == 1
    }

    private fun localValueIsValid(
        control: String,
        desiredSettings: CameraControlSettings,
        minimum: Double?,
        maximum: Double?,
    ): Boolean = when (control) {
        CameraControlIds.ZOOM ->
            minimum?.let { desiredSettings.zoom >= it } != false &&
                maximum?.let { desiredSettings.zoom <= it } != false
        CameraControlIds.TORCH -> true
        else -> false
    }

    private fun result(
        command: CameraControlCommandEnvelope,
        succeeded: Boolean,
        transient: Boolean,
        code: String,
    ) = CameraControlCommandResult(
        commandId = command.commandId,
        deviceId = command.deviceId,
        succeeded = succeeded,
        transientFailure = transient,
        resultCode = code,
        deviceState = state(command.deviceId),
        completedAtUtc = Instant.now().toString(),
    )

    override fun close() {
        events.close()
        worker.cancel()
    }

    private companion object {
        const val MAXIMUM_COMPLETED_RESULTS = 100
        const val MOTION_CONTROL_PREFIX = "motion."
    }
}
