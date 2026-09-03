package com.botglobal.mobile.platform.networking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ProductEndpointConfigTests {
    @Test
    fun productionRequiresAPathFreeHttpsAuthority() {
        val config = ProductEndpointConfig.from("https://api.example.test/", NetworkEnvironment.Production)

        assertEquals("https://api.example.test/v1/device", config.endpoint("/v1/device"))
        assertFalse("api.example.test" in config.toString())
        assertFailsWith<IllegalArgumentException> {
            ProductEndpointConfig.from("http://api.example.test", NetworkEnvironment.Production)
        }
        assertFailsWith<IllegalArgumentException> {
            ProductEndpointConfig.from("https://user@api.example.test", NetworkEnvironment.Production)
        }
    }

    @Test
    fun developmentAllowsExplicitHttpWithoutWeakeningProduction() {
        val config = ProductEndpointConfig.from("http://10.0.2.2:5062", NetworkEnvironment.Development)

        assertEquals("http://10.0.2.2:5062/health", config.endpoint("health"))
    }
}
