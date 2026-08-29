package com.botglobal.mobile.platform.device

enum class HapticEvent { Selection, LightImpact, Success, Warning, Error, ImportantAction, GameEvent }

interface SemanticHaptics {
    fun perform(event: HapticEvent)
}

enum class PermissionKind { Notifications, LocationApproximate, LocationPrecise, Microphone, Camera, Contacts }
enum class PermissionState { Unknown, Granted, Denied, PermanentlyDenied, Unavailable }

interface PermissionController {
    suspend fun state(permission: PermissionKind): PermissionState
    suspend fun requestAfterExplanation(permission: PermissionKind): PermissionState
}

object UnavailablePermissionController : PermissionController {
    override suspend fun state(permission: PermissionKind) = PermissionState.Unavailable
    override suspend fun requestAfterExplanation(permission: PermissionKind) = PermissionState.Unavailable
}

data class LocationReading(
    val latitude: Double,
    val longitude: Double,
    val isPrecise: Boolean,
    val capturedAtUtc: String,
)

interface LocationCapability {
    suspend fun currentLocation(requirePrecise: Boolean = false): LocationReading?
}
