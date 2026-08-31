package com.ashraffarag.sentricam.cameracontrol.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraControlDeviceIdentityTest {
    @Test
    fun authenticatedHubIdentityWinsWhenInstallationIdentityDiffers() {
        assertEquals(
            "hub-device",
            cameraControlDeviceId("hub-device", "installation-device"),
        )
    }

    @Test
    fun installationIdentityIsUsedBeforeRegistration() {
        assertEquals(
            "installation-device",
            cameraControlDeviceId(null, "installation-device"),
        )
        assertEquals(
            "installation-device",
            cameraControlDeviceId("", "installation-device"),
        )
    }

    @Test
    fun activeRegistrationIdentitySurvivesTransientSecureStoreFailure() {
        assertEquals(
            "hub-device",
            cameraControlDeviceId(
                activeRegistrationDeviceId = "hub-device",
                storedAuthenticatedDeviceId = { error("transient key store failure") },
                installationDeviceId = "installation-device",
            ),
        )
    }

    @Test
    fun storedIdentityBridgesCoordinatorStartupBeforeActiveStateIsAvailable() {
        assertEquals(
            "stored-hub-device",
            cameraControlDeviceId(
                activeRegistrationDeviceId = null,
                storedAuthenticatedDeviceId = { "stored-hub-device" },
                installationDeviceId = "installation-device",
            ),
        )
    }
}
