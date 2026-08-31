package com.ashraffarag.sentricam.device.capability

import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import com.ashraffarag.sentricam.device.registration.RegistrationState

/**
 * Keeps Hub-only runtime ownership separate from transient connection health.
 */
data class CameraOperatingModeRuntimePolicy(
    val hubRuntimeAllowed: Boolean,
    val startHubConnectivity: Boolean,
    val stopHubConnectivity: Boolean,
    val scheduleCredentialRefresh: Boolean,
)

object CameraOperatingModeRuntimePolicyResolver {
    fun resolve(
        mode: CameraOperatingMode,
        registration: RegistrationState,
    ): CameraOperatingModeRuntimePolicy {
        if (mode is CameraOperatingMode.Standalone) {
            return CameraOperatingModeRuntimePolicy(
                hubRuntimeAllowed = false,
                startHubConnectivity = false,
                stopHubConnectivity = true,
                scheduleCredentialRefresh = false,
            )
        }

        return CameraOperatingModeRuntimePolicy(
            hubRuntimeAllowed = true,
            startHubConnectivity = registration is RegistrationState.Registered,
            stopHubConnectivity = registration is RegistrationState.Unregistered ||
                registration is RegistrationState.TokenExpired,
            scheduleCredentialRefresh = registration is RegistrationState.Registered,
        )
    }
}
