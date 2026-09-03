package com.enpo.connect.app.network

import com.botglobal.mobile.platform.device.InstallationId
import com.botglobal.mobile.platform.device.InstallationIdentity
import com.botglobal.mobile.platform.device.PreferenceInstallationIdStore
import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.enpo.connect.app.pairing.ConnectPairingToken
import com.enpo.connect.app.pairing.EnpoPairingClaimResult
import com.enpo.connect.app.pairing.EnpoPairingDeviceInfo
import com.enpo.connect.app.pairing.EnpoPairingError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EnpoConnectV2PairingApiTests {
    @Test
    fun claimMatchesTheProductionConnectV2RequestAndResponseContract() = runTest {
        var captured: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            captured = request
            respond(
                """{"challengeId":"00000000-0000-0000-0000-000000000001","status":"completed","expiresAtUtc":"2099-01-01T00:00:00Z","completedAtUtc":"2099-01-01T00:00:00Z","device":{"deviceId":"00000000-0000-0000-0000-000000000002","credential":"test-issued-value"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val token = ConnectPairingToken("A".repeat(43))

        val result = api(client).claim(token)

        assertIs<EnpoPairingClaimResult.Success>(result)
        val request = captured!!
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/mobile/pairing/claim", request.url.encodedPath)
        assertNull(request.headers[HttpHeaders.Authorization])
        val body = requestBody(request)
        assertTrue(body.contains("\"pairingToken\":\"${"A".repeat(43)}\""))
        assertTrue(body.contains("\"platform\":\"android\""))
        assertTrue(body.contains("\"installationId\":\"existing-installation\""))
        assertTrue(body.contains("\"deviceName\":\"Test tablet\""))
        assertTrue(body.contains("\"appVersion\":\"1.0.2\""))
        assertFalse("test-issued-value" in result.toString())
    }

    @Test
    fun currentCombinedChallengeRejectionRemainsCombinedWithoutFalsePrecision() = runTest {
        val result = api(
            HttpClient(MockEngine {
                respond(
                    """{"message":"Pairing challenge is invalid, expired, or already used."}""",
                    HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
        ).claim(ConnectPairingToken("A".repeat(43)))

        assertEquals(
            EnpoPairingClaimResult.Failure(
                EnpoPairingError.InvalidExpiredOrAlreadyUsed,
                400,
            ),
            result,
        )
    }

    @Test
    fun authorizationAndServerFailuresMapWithoutResponseBodies() = runTest {
        val expectations = mapOf(
            HttpStatusCode.Unauthorized to EnpoPairingError.Unauthorized,
            HttpStatusCode.Forbidden to EnpoPairingError.Forbidden,
            HttpStatusCode.TooManyRequests to EnpoPairingError.ServerUnavailable,
            HttpStatusCode.ServiceUnavailable to EnpoPairingError.ServerUnavailable,
            HttpStatusCode.InternalServerError to EnpoPairingError.ServerError,
        )

        expectations.forEach { (status, expected) ->
            val result = api(HttpClient(MockEngine { respondError(status) }))
                .claim(ConnectPairingToken("A".repeat(43)))
            val failure = assertIs<EnpoPairingClaimResult.Failure>(result)
            assertEquals(expected, failure.error)
            assertEquals(status.value, failure.httpStatus)
        }
    }

    @Test
    fun malformedSuccessAndTransportFailureNeverCreateCredentials() = runTest {
        val malformed = api(
            HttpClient(MockEngine {
                respond(
                    """{"status":"completed","device":{"deviceId":"test-device"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
        ).claim(ConnectPairingToken("A".repeat(43)))
        assertEquals(EnpoPairingClaimResult.Failure(EnpoPairingError.Unknown), malformed)

        val transport = api(HttpClient(MockEngine { error("offline") }))
            .claim(ConnectPairingToken("A".repeat(43)))
        assertEquals(
            EnpoPairingClaimResult.Failure(EnpoPairingError.NetworkUnavailable),
            transport,
        )
    }

    private fun api(client: HttpClient): EnpoConnectV2PairingApi {
        val preferences = InMemoryPreferenceStore(mapOf("installation_id" to "existing-installation"))
        return EnpoConnectV2PairingApi(
            client = client,
            configuration = EnpoNetworkConfiguration.from(
                "https://bgapi.challengershoes.com",
                NetworkEnvironment.Production,
            ),
            installationIdentity = InstallationIdentity(
                PreferenceInstallationIdStore(preferences, "installation_id"),
            ) { InstallationId("generated-installation") },
            deviceInfo = EnpoPairingDeviceInfo(
                platform = "android",
                deviceName = "Test tablet",
                appVersion = "1.0.2",
            ),
        )
    }

    private fun requestBody(request: HttpRequestData): String = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> body.toString()
    }
}
