package com.enpo.connect.app.network

import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.enpo.connect.app.pairing.EnpoDeviceUnpairResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class EnpoDeviceUnpairApiTests {
    private val configuration = EnpoNetworkConfiguration.from(
        "https://api.example.test",
        NetworkEnvironment.Development,
    )

    @Test
    fun postsTheDeviceCredentialToTheExistingUnpairEndpoint() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/mobile/devices/unpair", request.url.encodedPath)
            assertEquals("Device test-credential", request.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.NoContent)
        })

        val outcome = EnpoDeviceUnpairApi(client, configuration).revoke(
            MobileDeviceCredential("device", "test-credential"),
        )

        assertEquals(EnpoDeviceUnpairResponse.Revoked, outcome)
    }

    @Test
    fun onlyAnExplicitRevocationOrInvalidCredentialAllowsLocalCleanup() = runTest {
        val credential = MobileDeviceCredential("device", "test-credential")
        val expected = mapOf(
            HttpStatusCode.Unauthorized to EnpoDeviceUnpairResponse.AlreadyInvalid,
            HttpStatusCode.Forbidden to EnpoDeviceUnpairResponse.Unavailable,
            HttpStatusCode.InternalServerError to EnpoDeviceUnpairResponse.Unavailable,
        )
        expected.forEach { (status, outcome) ->
            val client = HttpClient(MockEngine { respond("", status) })
            assertEquals(outcome, EnpoDeviceUnpairApi(client, configuration).revoke(credential))
        }
    }
}
