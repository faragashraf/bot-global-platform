package com.enpo.connect.app.network

import com.botglobal.mobile.platform.networking.NetworkEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EnpoNetworkConfigurationTests {
    @Test
    fun authoritativePublicServiceRoutesComposeFromOneProductBaseUrl() {
        val configuration = EnpoNetworkConfiguration.from(
            "https://bgapi.challengershoes.com/",
            NetworkEnvironment.Production,
        )

        assertEquals("com.enpo.connect", configuration.applicationId)
        assertEquals(NetworkEnvironment.Production, configuration.environment)
        assertEquals(12_000, configuration.clientConfiguration.connectTimeoutMillis)
        assertEquals(18_000, configuration.clientConfiguration.requestTimeoutMillis)
        assertEquals(18_000, configuration.clientConfiguration.socketTimeoutMillis)
        assertEquals(
            "https://bgapi.challengershoes.com/api/mobile/pairing/claim",
            configuration.endpoint(EnpoPublicServiceRoute.PairingClaim),
        )
        assertEquals(
            "https://bgapi.challengershoes.com/api/mobile/devices/unpair",
            configuration.endpoint(EnpoPublicServiceRoute.DeviceUnpair),
        )
        assertEquals(
            "https://bgapi.challengershoes.com/api/mobile/devices/push-registration",
            configuration.endpoint(EnpoPublicServiceRoute.PushRegistration),
        )
        assertEquals(
            "https://bgapi.challengershoes.com/api/mobile/profile",
            configuration.endpoint(EnpoPublicServiceRoute.Profile),
        )
    }

    @Test
    fun configurationDiagnosticsAreRedactedAndContainNoSiblingProductConfiguration() {
        val configuration = EnpoNetworkConfiguration.from(
            "https://bgapi.challengershoes.com",
            NetworkEnvironment.Production,
        )
        val diagnostic = configuration.toString().lowercase()

        assertFalse("challengershoes" in diagnostic)
        assertFalse("nqrb" in diagnostic)
        assertFalse("lamma" in diagnostic)
    }
}
