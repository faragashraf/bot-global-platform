package com.enpo.connect.app.network

import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.botglobal.mobile.platform.notifications.InMemoryMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.OpaquePushDestinationId
import com.botglobal.mobile.platform.notifications.PushDestination
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EnpoPushRegistrationApiTests {
    @Test
    fun pairedDeviceRegistersFcmWithoutClientSelectedApplication() = runTest {
        var captured: HttpRequestData? = null
        val api = api(HttpClient(MockEngine { request ->
            captured = request
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))

        val outcome = api.register(destination("test-fcm-value"))

        assertEquals(PushRegistrationOutcome.Registered, outcome)
        val request = captured!!
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/mobile/devices/push-registration", request.url.encodedPath)
        assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("Device "))
        val body = requestBody(request)
        assertTrue("\"provider\":\"fcm\"" in body)
        assertTrue("\"registrationToken\":\"test-fcm-value\"" in body)
        assertFalse("applicationId" in body)
        assertFalse("nqrb" in body.lowercase())
    }

    @Test
    fun unavailableCredentialDoesNotAttemptRegistrationOrAffectPairing() = runTest {
        var calls = 0
        val api = EnpoPushRegistrationApi(
            HttpClient(MockEngine { calls++; respond("{}") }),
            configuration(),
            InMemoryMobileDeviceCredentialVault(),
        )

        assertEquals(
            PushRegistrationOutcome.AuthenticationRequired,
            api.register(destination("test-fcm-value")),
        )
        assertEquals(0, calls)
    }

    @Test
    fun temporaryFailureRemainsRetryableAndTokenDiagnosticsStayRedacted() = runTest {
        val vault = InMemoryMobileDeviceCredentialVault(
            MobileDeviceCredential("test-device", "test-device-credential"),
        )
        val api = EnpoPushRegistrationApi(
            HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
            configuration(),
            vault,
        )

        assertEquals(
            PushRegistrationOutcome.RetryableFailure,
            api.register(destination("private-fcm-value")),
        )
        assertNotNull(vault.restore())
        assertFalse("private-fcm-value" in destination("private-fcm-value").toString())
    }

    @Test
    fun unsupportedProviderIsRejectedBeforeNetworkUse() = runTest {
        var calls = 0
        val api = api(HttpClient(MockEngine { calls++; respond("{}") }))

        assertEquals(
            PushRegistrationOutcome.Rejected,
            api.register(PushDestination("apns", OpaquePushDestinationId("test-value"))),
        )
        assertEquals(0, calls)
    }

    private fun api(client: HttpClient) = EnpoPushRegistrationApi(
        client,
        configuration(),
        InMemoryMobileDeviceCredentialVault(
            MobileDeviceCredential("test-device", "test-device-credential"),
        ),
    )

    private fun configuration() = EnpoNetworkConfiguration.from(
        "https://bgapi.challengershoes.com",
        NetworkEnvironment.Production,
    )

    private fun destination(value: String) =
        PushDestination("fcm", OpaquePushDestinationId(value))

    private fun requestBody(request: HttpRequestData): String = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> body.toString()
    }
}
