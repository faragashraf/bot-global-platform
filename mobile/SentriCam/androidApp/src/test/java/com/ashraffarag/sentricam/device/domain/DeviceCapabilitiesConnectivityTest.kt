package com.ashraffarag.sentricam.device.domain

import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesConnectivityTest {
    @Test
    fun capabilitiesAreQueryableOutsideUiAndPreserveFutureState() {
        val capabilities = DeviceCapabilities.of(
            AppCapability.MANUAL_RECORDING to CapabilityAccess.Available,
            AppCapability.REMOTE_CONTROL to CapabilityAccess.ComingSoon,
        )

        assertTrue(capabilities.isAvailable(AppCapability.MANUAL_RECORDING))
        assertFalse(capabilities.isAvailable(AppCapability.REMOTE_CONTROL))
        assertEquals(CapabilityAccess.ComingSoon, capabilities.access(AppCapability.REMOTE_CONTROL))
        assertEquals(
            CapabilityAccess.Unavailable("not_declared"),
            capabilities.access(AppCapability.CLOUD_UPLOAD),
        )
    }

    @Test
    fun connectivityStatesDistinguishOfflineLocalInternetLimitedAndCaptive() {
        val states = listOf(
            ConnectivityState.Offline,
            ConnectivityState.LocalNetwork(setOf("wifi")),
            ConnectivityState.InternetAvailable(setOf("cellular"), metered = true),
            ConnectivityState.Limited("not_validated"),
            ConnectivityState.CaptivePortal,
        )

        assertFalse(states[0].networkAvailable)
        assertTrue(states.drop(1).all { it.networkAvailable })
        assertEquals(setOf("wifi"), (states[1] as ConnectivityState.LocalNetwork).transports)
        assertTrue((states[2] as ConnectivityState.InternetAvailable).metered)
    }
}
