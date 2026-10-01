package com.botglobal.mobile.platform.networking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ProductEndpointConfigTests {
    @Test
    fun productionRequiresAPathFreeHttpsAuthority() {
        val config = ProductEndpointConfig.from("https://api.example.test/backend/", NetworkEnvironment.Production)

        assertEquals("https://api.example.test/backend/v1/device", config.endpoint("/v1/device"))
        assertFailsWith<IllegalArgumentException> { config.endpoint("../outside") }
        assertFailsWith<IllegalArgumentException> { config.endpoint("/api/sessions/%2e%2e%2foutside") }
        assertFalse("api.example.test" in config.toString())
        assertFailsWith<IllegalArgumentException> {
            ProductEndpointConfig.from("http://api.example.test", NetworkEnvironment.Production)
        }
        assertFailsWith<IllegalArgumentException> {
            ProductEndpointConfig.from("https://user@api.example.test", NetworkEnvironment.Production)
        }
        assertFailsWith<IllegalArgumentException> {
            ProductEndpointConfig.from("https://api.example.test:/backend", NetworkEnvironment.Production)
        }
        assertFailsWith<IllegalArgumentException> {
            ProductEndpointConfig.from("https://api.example.test/backend/../admin", NetworkEnvironment.Production)
        }
        assertFailsWith<IllegalArgumentException> {
            ProductEndpointConfig.from("https://api.example.test/backend/%2e%2e%2fadmin", NetworkEnvironment.Production)
        }
        assertFailsWith<IllegalArgumentException> {
            ProductEndpointConfig.from("https://api.example.test/backend/%252e%252e%252fadmin", NetworkEnvironment.Production)
        }
        assertFailsWith<IllegalArgumentException> {
            ProductEndpointConfig.from("https://api.example.test/backend?source=release", NetworkEnvironment.Production)
        }
    }

    @Test
    fun developmentAllowsExplicitHttpWithoutWeakeningProduction() {
        val config = ProductEndpointConfig.from("http://10.0.2.2:5062", NetworkEnvironment.Development)

        assertEquals("http://10.0.2.2:5062/health", config.endpoint("health"))
        val ipv6 = ProductEndpointConfig.from("http://[::1]:5062/backend", NetworkEnvironment.Development)
        assertEquals("http://[::1]:5062/backend/health", ipv6.endpoint("health"))
    }
}
