package com.ashraffarag.sentricam.device.domain

import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.monitoring.domain.MonitoringState
import com.ashraffarag.sentricam.motion.domain.MotionDetectionConfig
import com.ashraffarag.sentricam.motion.domain.MotionDetectionState
import com.ashraffarag.sentricam.motion.domain.MotionSensitivity
import com.ashraffarag.sentricam.recording.engine.domain.RecordingState
import kotlinx.coroutines.flow.StateFlow
import com.ashraffarag.sentricam.device.recovery.OperationalHealthSnapshot

enum class DeviceRecordingStatus {
    IDLE,
    PREPARING,
    READY,
    STARTING,
    RECORDING,
    ROTATING_SEGMENT,
    STOPPING,
    COMPLETED,
    FAILED,
}

enum class DeviceMotionStatus {
    DISABLED,
    INITIALIZING,
    NO_MOTION,
    SUSPECTED,
    CONFIRMED,
    HOLDING,
    COOLDOWN,
    ERROR,
}

enum class RecordingOrigin {
    NONE,
    MANUAL,
    MOTION,
    REMOTE,
}

data class MotionConfigurationSummary(
    val enabled: Boolean,
    val sensitivity: MotionSensitivity,
    val advancedSensitivity: Int,
    val triggerDelayMillis: Long,
    val stopDelayMillis: Long,
    val cooldownMillis: Long,
)

enum class DeviceCameraState {
    UNAVAILABLE,
    STARTING,
    READY,
    SWITCHING,
    ERROR,
}

enum class DeviceCameraLens {
    BACK,
    FRONT,
    UNKNOWN,
}

data class CameraState(
    val state: DeviceCameraState = DeviceCameraState.UNAVAILABLE,
    val selectedLens: DeviceCameraLens = DeviceCameraLens.UNKNOWN,
    val availableCameraCount: Int = 0,
)

data class BatteryState(
    val levelPercent: Int? = null,
    val isCharging: Boolean? = null,
    val isPowerSaveMode: Boolean? = null,
)

data class StorageState(
    val availableBytes: Long? = null,
    val totalBytes: Long? = null,
    val isLow: Boolean = false,
)

data class MemorySummary(
    val availableBytes: Long? = null,
    val totalBytes: Long? = null,
    val isLow: Boolean = false,
)

data class ActivityHistory(
    val lastActivityAtMillis: Long? = null,
    val lastMotionAtMillis: Long? = null,
    val lastRecordingAtMillis: Long? = null,
)

data class DeviceHealth(
    val battery: BatteryState = BatteryState(),
    val storage: StorageState = StorageState(),
    val memory: MemorySummary = MemorySummary(),
    val activity: ActivityHistory = ActivityHistory(),
)

sealed interface ConnectivityState {
    val networkAvailable: Boolean

    data object Offline : ConnectivityState {
        override val networkAvailable = false
    }

    data class LocalNetwork(val transports: Set<String> = emptySet()) : ConnectivityState {
        override val networkAvailable = true
    }

    data class InternetAvailable(
        val transports: Set<String> = emptySet(),
        val metered: Boolean = false,
    ) : ConnectivityState {
        override val networkAvailable = true
    }

    data class Limited(val reason: String) : ConnectivityState {
        override val networkAvailable = true
    }

    data object CaptivePortal : ConnectivityState {
        override val networkAvailable = true
    }
}

enum class FutureFeatureState {
    COMING_SOON,
}

data class DeviceCapabilities(
    private val accessByCapability: Map<AppCapability, CapabilityAccess>,
) {
    fun access(capability: AppCapability): CapabilityAccess =
        accessByCapability[capability] ?: CapabilityAccess.Unavailable("not_declared")

    fun all(): Map<AppCapability, CapabilityAccess> = accessByCapability.toMap()

    fun isAvailable(capability: AppCapability): Boolean = access(capability) == CapabilityAccess.Available

    companion object {
        fun of(vararg entries: Pair<AppCapability, CapabilityAccess>) =
            DeviceCapabilities(entries.toMap())

        fun unavailable() = DeviceCapabilities(emptyMap())
    }
}

data class DeviceState(
    val identity: DeviceIdentity,
    val monitoring: MonitoringState = MonitoringState.stopped(),
    val recording: RecordingState = RecordingState.Idle,
    val motion: MotionDetectionState = MotionDetectionState.Disabled,
    val motionConfiguration: MotionDetectionConfig = MotionDetectionConfig(),
    val health: DeviceHealth = DeviceHealth(),
    val connectivity: ConnectivityState = ConnectivityState.Offline,
    val camera: CameraState = CameraState(),
    val operationalHealth: OperationalHealthSnapshot = OperationalHealthSnapshot.initial(),
    val capabilities: DeviceCapabilities = DeviceCapabilities.unavailable(),
    val pairing: FutureFeatureState = FutureFeatureState.COMING_SOON,
    val remoteConnection: FutureFeatureState = FutureFeatureState.COMING_SOON,
    val cloud: FutureFeatureState = FutureFeatureState.COMING_SOON,
)

interface Device {
    val state: StateFlow<DeviceState>
    val snapshot: StateFlow<DeviceSnapshot>
}
