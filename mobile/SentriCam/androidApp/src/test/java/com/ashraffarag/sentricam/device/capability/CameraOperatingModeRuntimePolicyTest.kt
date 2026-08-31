package com.ashraffarag.sentricam.device.capability

import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import com.ashraffarag.sentricam.device.registration.RegistrationDetails
import com.ashraffarag.sentricam.device.registration.RegistrationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOperatingModeRuntimePolicyTest {
    @Test
    fun standaloneDisablesEveryHubOnlyRuntimeOwner() {
        val policy = CameraOperatingModeRuntimePolicyResolver.resolve(
            CameraOperatingMode.Standalone,
            RegistrationState.Unregistered(),
        )

        assertFalse(policy.hubRuntimeAllowed)
        assertFalse(policy.startHubConnectivity)
        assertTrue(policy.stopHubConnectivity)
        assertFalse(policy.scheduleCredentialRefresh)
    }

    @Test
    fun registeredHubManagedCameraEnablesExistingConnectivityLifecycle() {
        val policy = CameraOperatingModeRuntimePolicyResolver.resolve(
            CameraOperatingMode.HubManaged("server-device-id"),
            RegistrationState.Registered(RegistrationDetails("https://hub.example/")),
        )

        assertTrue(policy.hubRuntimeAllowed)
        assertTrue(policy.startHubConnectivity)
        assertFalse(policy.stopHubConnectivity)
        assertTrue(policy.scheduleCredentialRefresh)
    }

    @Test
    fun hubConnectionFailureDoesNotBecomeStandalone() {
        val mode = CameraOperatingMode.HubManaged("server-device-id")
        val policy = CameraOperatingModeRuntimePolicyResolver.resolve(
            mode,
            RegistrationState.ConnectionFailed(
                com.ashraffarag.sentricam.device.registration.RegistrationFailure.ServerUnreachable(503),
                RegistrationDetails("https://hub.example/", serverDeviceId = "server-device-id"),
            ),
        )

        assertTrue(policy.hubRuntimeAllowed)
        assertFalse(policy.stopHubConnectivity)
    }
}
