package com.ashraffarag.sentricam.capability.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class AppCapability {
    MANUAL_RECORDING,
    RECORDING_LIBRARY,
    RECORDING_PROFILES,
    SEGMENTED_RECORDING,
    STORAGE_MONITORING,
    BASIC_MOTION_DETECTION,
    ADVANCED_MOTION_SENSITIVITY,
    SMART_PERSON_DETECTION,
    ANIMAL_DETECTION,
    VEHICLE_DETECTION,
    KNOWN_PERSON_RECOGNITION,
    ADVANCED_DETECTION_RULES,
    MONITORING_SERVICE,
    REMOTE_CONTROL,
    MULTIPLE_DEVICES,
    MULTIPLE_CAMERAS,
    CLOUD_UPLOAD,
    EXTENDED_RECORDING_HISTORY,
    AUDIO_RECORDING,
    FLASH,
    NOTIFICATION_ACTIONS,
}

sealed interface CapabilityAccess {
    data object Available : CapabilityAccess
    data class RequiresUpgrade(val reasonCode: String? = null) : CapabilityAccess
    data object ComingSoon : CapabilityAccess
    data class UnsupportedOnDevice(val reasonCode: String? = null) : CapabilityAccess
    data class Unavailable(val reasonCode: String? = null) : CapabilityAccess
}

interface EntitlementService {
    fun getAccess(capability: AppCapability): CapabilityAccess
    fun observeAccess(capability: AppCapability): Flow<CapabilityAccess>
}

class DevelopmentEntitlementService(
    private val deviceOverrides: Map<AppCapability, CapabilityAccess> = emptyMap(),
) : EntitlementService {
    override fun getAccess(capability: AppCapability): CapabilityAccess = deviceOverrides[capability]
        ?: if (capability in IMPLEMENTED) CapabilityAccess.Available else CapabilityAccess.ComingSoon

    override fun observeAccess(capability: AppCapability): Flow<CapabilityAccess> =
        flowOf(getAccess(capability))

    private companion object {
        val IMPLEMENTED = setOf(
            AppCapability.MANUAL_RECORDING,
            AppCapability.RECORDING_LIBRARY,
            AppCapability.RECORDING_PROFILES,
            AppCapability.SEGMENTED_RECORDING,
            AppCapability.STORAGE_MONITORING,
            AppCapability.BASIC_MOTION_DETECTION,
            AppCapability.ADVANCED_MOTION_SENSITIVITY,
            AppCapability.MONITORING_SERVICE,
            AppCapability.MULTIPLE_CAMERAS,
            AppCapability.AUDIO_RECORDING,
            AppCapability.FLASH,
            AppCapability.NOTIFICATION_ACTIONS,
        )
    }
}

sealed interface CapabilityGuardResult {
    data object Allowed : CapabilityGuardResult
    data class Blocked(val access: CapabilityAccess) : CapabilityGuardResult
}

class CapabilityGuard(
    private val entitlements: EntitlementService,
    private val resolvedDeviceAccess: ((AppCapability) -> CapabilityAccess)? = null,
) {
    fun check(capability: AppCapability): CapabilityGuardResult {
        val entitlementAccess = entitlements.getAccess(capability)
        if (entitlementAccess != CapabilityAccess.Available) {
            return CapabilityGuardResult.Blocked(entitlementAccess)
        }
        return when (val deviceAccess = resolvedDeviceAccess?.invoke(capability)) {
            null,
            CapabilityAccess.Available,
            -> CapabilityGuardResult.Allowed
            else -> CapabilityGuardResult.Blocked(deviceAccess)
        }
    }

    inline fun <T> execute(capability: AppCapability, action: () -> T): GuardedActionResult<T> =
        when (val result = check(capability)) {
            CapabilityGuardResult.Allowed -> GuardedActionResult.Executed(action())
            is CapabilityGuardResult.Blocked -> GuardedActionResult.Rejected(result.access)
        }
}

sealed interface GuardedActionResult<out T> {
    data class Executed<T>(val value: T) : GuardedActionResult<T>
    data class Rejected(val access: CapabilityAccess) : GuardedActionResult<Nothing>
}
