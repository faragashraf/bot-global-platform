package com.enpo.connect.app.network

import com.botglobal.mobile.platform.networking.NetworkEnvironment
import com.botglobal.mobile.platform.notifications.InMemoryMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.profile.ProfileFetchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class EnpoProfileApiTests {
    @Test
    fun pairedDeviceReadsOnlyItsBotGlobalProfileProjection() = runTest {
        var requestedUrl = ""
        val client = client(MockEngine { request ->
            requestedUrl = request.url.toString()
            assertEquals("Device safe-test-credential", request.headers[HttpHeaders.Authorization])
            respond(
                """{
                  "displayName":"Synthetic User",
                  "jobTitle":"Specialist",
                  "organizationUnit":"Operations",
                  "version":7,
                  "updatedAtUtc":"2026-09-04T08:00:00Z"
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

        val result = api(client).fetchMyProfile()

        val available = assertIs<ProfileFetchResult.Available>(result)
        assertEquals("Synthetic User", available.snapshot.displayName)
        assertEquals(7, available.snapshot.version)
        assertTrue(requestedUrl.endsWith("/api/mobile/profile"))
        assertFalse(requestedUrl.contains("connect.egyptpost", ignoreCase = true))
    }

    @Test
    fun missingProjectionMapsToNotAvailableYet() = runTest {
        val result = api(client(MockEngine {
            respondError(HttpStatusCode.NotFound)
        })).fetchMyProfile()

        assertEquals(ProfileFetchResult.NotAvailableYet, result)
    }

    @Test
    fun revokedCredentialAndTransportFailureAreSafeStates() = runTest {
        val unauthorized = api(client(MockEngine {
            respondError(HttpStatusCode.Unauthorized)
        })).fetchMyProfile()
        val failed = api(client(MockEngine {
            error("offline")
        })).fetchMyProfile()

        assertEquals(ProfileFetchResult.AuthenticationRequired, unauthorized)
        assertEquals(ProfileFetchResult.Failed, failed)
    }

    @Test
    fun noCredentialPreventsNetworkRequestAndDiagnosticsAreRedacted() = runTest {
        var requests = 0
        val api = EnpoProfileApi(
            client(MockEngine {
                requests += 1
                respondError(HttpStatusCode.InternalServerError)
            }),
            configuration(),
            InMemoryMobileDeviceCredentialVault(),
        )

        assertEquals(ProfileFetchResult.AuthenticationRequired, api.fetchMyProfile())
        assertEquals(0, requests)
        assertFalse(api.toString().contains("safe-test-credential"))
    }

    private fun api(client: HttpClient) = EnpoProfileApi(
        client,
        configuration(),
        InMemoryMobileDeviceCredentialVault(
            MobileDeviceCredential("safe-test-device", "safe-test-credential"),
        ),
    )

    private fun configuration() = EnpoNetworkConfiguration.from(
        "https://bgapi.example.test",
        NetworkEnvironment.Production,
    )

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}
