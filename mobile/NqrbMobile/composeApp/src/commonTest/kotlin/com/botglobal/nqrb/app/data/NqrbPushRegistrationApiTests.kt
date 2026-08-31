package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.OpaquePushDestinationId
import com.botglobal.mobile.platform.notifications.PushDestination
import com.botglobal.mobile.platform.notifications.PushDeviceInstallation
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NqrbPushRegistrationApiTests {
    @Test
    fun centralSessionEnrollsDeviceThenRegistersFidWithoutClientApplicationOrSubject() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/mobile/devices/enrollment" -> respond(
                    """{"deviceId":"device-id","credential":"device-secret"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/mobile/devices/push-registration" -> respond(
                    """{"deviceId":"device-id","applicationId":"app-id","provider":"fcm","updatedAtUtc":"2026-08-31T00:00:00Z"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request path")
            }
        }
        val credentialVault = MemoryCredentialVault()
        val api = api(HttpClient(engine), credentialVault)

        val outcome = api.register(destination("current-fid"))

        assertEquals(PushRegistrationOutcome.Registered, outcome)
        assertEquals(2, requests.size)
        assertTrue(requests[0].headers[HttpHeaders.Authorization]!!.startsWith("Bearer "))
        val enrollmentBody = requestBody(requests[0])
        assertTrue(enrollmentBody.contains("installation-id"))
        assertFalse(enrollmentBody.contains("applicationId", ignoreCase = true))
        assertFalse(enrollmentBody.contains("externalSubject", ignoreCase = true))
        assertTrue(requests[1].headers[HttpHeaders.Authorization]!!.startsWith("Device "))
        assertNotNull(credentialVault.value)
    }

    @Test
    fun revokedDeviceCredentialIsReenrolledOnceAndRegistrationRetries() = runTest {
        var registrations = 0
        var enrollments = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/mobile/devices/enrollment" -> {
                    enrollments++
                    respond(
                        """{"deviceId":"replacement-device","credential":"replacement-secret"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/api/mobile/devices/push-registration" -> {
                    registrations++
                    respond(
                        "{}",
                        if (registrations == 1) HttpStatusCode.Unauthorized else HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("Unexpected request path")
            }
        }
        val vault = MemoryCredentialVault(MobileDeviceCredential("old-device", "old-secret"))

        val result = api(HttpClient(engine), vault).register(destination("current-fid"))

        assertEquals(PushRegistrationOutcome.Registered, result)
        assertEquals(1, enrollments)
        assertEquals(2, registrations)
        assertEquals("replacement-device", vault.value?.deviceId)
    }

    @Test
    fun logoutUnpairsAndClearsCredentialWithoutCentralIdentityPayload() = runTest {
        var request: HttpRequestData? = null
        val engine = MockEngine {
            request = it
            respond("", HttpStatusCode.NoContent)
        }
        val vault = MemoryCredentialVault(MobileDeviceCredential("device", "secret"))

        val result = api(HttpClient(engine), vault).unregister()

        assertEquals(PushRegistrationOutcome.Unregistered, result)
        assertEquals("/api/mobile/devices/unpair", request!!.url.encodedPath)
        assertTrue(request.headers[HttpHeaders.Authorization]!!.startsWith("Device "))
        assertEquals(null, vault.value)
    }

    private fun api(client: HttpClient, vault: MemoryCredentialVault) = NqrbPushRegistrationApi(
        platformClient = client,
        apiBaseUrl = "https://api.example.test",
        sessionVault = FixedSessionVault(),
        deviceCredentialVault = vault,
        installation = PushDeviceInstallation(
            "installation-id",
            "android",
            "Test device",
            "1.0",
        ),
    )

    private fun destination(identifier: String) = PushDestination(
        "fcm",
        OpaquePushDestinationId(identifier),
    )

    private fun requestBody(request: HttpRequestData): String = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> body.toString()
    }

    private class MemoryCredentialVault(
        var value: MobileDeviceCredential? = null,
    ) : MobileDeviceCredentialVault {
        override suspend fun restore() = value
        override suspend fun save(credential: MobileDeviceCredential) { value = credential }
        override suspend fun clear() { value = null }
    }

    private class FixedSessionVault : SessionVault {
        private val session = MobileSession(
            "access-token",
            "2099-01-01T00:00:00Z",
            "refresh-token",
            "2099-02-01T00:00:00Z",
            ApplicationIdentity(
                "membership",
                "server-subject",
                "Test User",
                IdentityKind.Registered,
                "nqrb",
            ),
        )
        override suspend fun restore() = session
        override suspend fun save(session: MobileSession) = Unit
        override suspend fun clear() = Unit
    }
}
