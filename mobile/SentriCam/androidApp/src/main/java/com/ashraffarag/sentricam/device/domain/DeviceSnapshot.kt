package com.ashraffarag.sentricam.device.domain

import com.ashraffarag.sentricam.monitoring.domain.MonitoringStatus
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionEventSummary
import com.ashraffarag.sentricam.recording.engine.domain.RecordingSession
import com.ashraffarag.sentricam.recording.engine.domain.RecordingStartReason
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import com.ashraffarag.sentricam.device.recovery.OperationalHealthSnapshot

data class DeviceSnapshot(
    val deviceId: String,
    val friendlyName: String,
    val platform: DevicePlatform,
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
    val buildFingerprint: String?,
    val installedAtMillis: Long,
    val lastStartupAtMillis: Long,
    val monitoringState: MonitoringStatus,
    val monitoringEnabled: Boolean,
    val recordingState: DeviceRecordingStatus,
    val recordingOrigin: RecordingOrigin,
    val motionEnabled: Boolean,
    val motionState: DeviceMotionStatus,
    val motionConfiguration: MotionConfigurationSummary,
    val lastMotionEventId: String?,
    val motionErrorCode: String?,
    val lastActivityAtMillis: Long?,
    val lastMotionAtMillis: Long?,
    val lastRecordingAtMillis: Long?,
    val battery: BatteryState,
    val storage: StorageState,
    val memory: MemorySummary,
    val network: ConnectivityState,
    val camera: CameraState,
    val subsystemHealth: OperationalHealthSnapshot = OperationalHealthSnapshot.initial(),
    val capabilities: DeviceCapabilities,
    val pairing: FutureFeatureState,
    val remoteConnection: FutureFeatureState,
    val cloud: FutureFeatureState,
    val updatedAtMillis: Long,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

class DeviceStatusMapper(private val clock: DeviceClock) {
    fun map(state: DeviceState): DeviceSnapshot = DeviceSnapshot(
        deviceId = state.identity.deviceId,
        friendlyName = state.identity.friendlyName,
        platform = state.identity.platform,
        appVersion = state.identity.appVersion,
        deviceModel = state.identity.deviceModel,
        androidVersion = state.identity.androidVersion,
        buildFingerprint = state.identity.buildFingerprint,
        installedAtMillis = state.identity.installedAtMillis,
        lastStartupAtMillis = state.identity.lastStartupAtMillis,
        monitoringState = state.monitoring.status,
        monitoringEnabled = state.monitoring.status == MonitoringStatus.STARTING ||
            state.monitoring.status == MonitoringStatus.RUNNING ||
            state.monitoring.status == MonitoringStatus.RESTARTING,
        recordingState = state.recording.toDeviceStatus(),
        recordingOrigin = state.recording.recordingOrigin(),
        motionEnabled = state.motionConfiguration.enabled,
        motionState = state.motion.toDeviceStatus(),
        motionConfiguration = state.motionConfiguration.toSummary(),
        lastMotionEventId = state.motion.event()?.eventId,
        motionErrorCode = (state.motion as? MotionDetectionState.Error)?.failure?.code?.stableCode,
        lastActivityAtMillis = state.health.activity.lastActivityAtMillis,
        lastMotionAtMillis = state.motion.event()?.lastMotionAtMillis
            ?: state.health.activity.lastMotionAtMillis,
        lastRecordingAtMillis = state.health.activity.lastRecordingAtMillis,
        battery = state.health.battery,
        storage = state.health.storage,
        memory = state.health.memory,
        network = state.connectivity,
        camera = state.camera,
        subsystemHealth = state.operationalHealth,
        capabilities = state.capabilities,
        pairing = state.pairing,
        remoteConnection = state.remoteConnection,
        cloud = state.cloud,
        updatedAtMillis = clock.nowMillis(),
    )
}

private fun MotionDetectionConfig.toSummary() = MotionConfigurationSummary(
    enabled = enabled,
    sensitivity = sensitivity,
    advancedSensitivity = advancedSensitivity,
    triggerDelayMillis = triggerDelayMillis,
    stopDelayMillis = stopDelayMillis,
    cooldownMillis = cooldownMillis,
)

private fun MotionDetectionState.toDeviceStatus(): DeviceMotionStatus = when (this) {
    MotionDetectionState.Disabled -> DeviceMotionStatus.DISABLED
    is MotionDetectionState.Initializing -> DeviceMotionStatus.INITIALIZING
    is MotionDetectionState.NoMotion -> DeviceMotionStatus.NO_MOTION
    is MotionDetectionState.SuspectedMotion -> DeviceMotionStatus.SUSPECTED
    is MotionDetectionState.MotionConfirmed -> DeviceMotionStatus.CONFIRMED
    is MotionDetectionState.Holding -> DeviceMotionStatus.HOLDING
    is MotionDetectionState.Cooldown -> DeviceMotionStatus.COOLDOWN
    is MotionDetectionState.Error -> DeviceMotionStatus.ERROR
}

private fun MotionDetectionState.event(): MotionEventSummary? = when (this) {
    is MotionDetectionState.MotionConfirmed -> event
    is MotionDetectionState.Holding -> event
    is MotionDetectionState.Cooldown -> event
    else -> null
}

private fun RecordingState.toDeviceStatus(): DeviceRecordingStatus = when (this) {
    RecordingState.Idle -> DeviceRecordingStatus.IDLE
    is RecordingState.Preparing -> DeviceRecordingStatus.PREPARING
    is RecordingState.Ready -> DeviceRecordingStatus.READY
    is RecordingState.Starting -> DeviceRecordingStatus.STARTING
    is RecordingState.Recording -> DeviceRecordingStatus.RECORDING
    is RecordingState.RotatingSegment -> DeviceRecordingStatus.ROTATING_SEGMENT
    is RecordingState.Stopping -> DeviceRecordingStatus.STOPPING
    is RecordingState.Completed -> DeviceRecordingStatus.COMPLETED
    is RecordingState.Failed -> DeviceRecordingStatus.FAILED
}

private fun RecordingState.recordingOrigin(): RecordingOrigin {
    val context = activeSession()?.request?.triggerContext
        ?: (this as? RecordingState.Completed)?.result?.segments?.lastOrNull()?.triggerContext
    if (context?.manualControlClaimed == true) return RecordingOrigin.MANUAL
    return when (context?.startReason) {
    RecordingStartReason.MANUAL -> RecordingOrigin.MANUAL
    RecordingStartReason.MOTION -> RecordingOrigin.MOTION
    RecordingStartReason.REMOTE -> RecordingOrigin.REMOTE
    null -> RecordingOrigin.NONE
    }
}

private fun RecordingState.activeSession(): RecordingSession? = when (this) {
    is RecordingState.Ready -> session
    is RecordingState.Starting -> session
    is RecordingState.Recording -> session
    is RecordingState.RotatingSegment -> session
    is RecordingState.Stopping -> session
    is RecordingState.Failed -> session
    else -> null
}
