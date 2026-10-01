package com.ashraffarag.sentricam.device.registration

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SentriCamApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun registrationPostsVersionedContractAndMapsUtcFields() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SUCCESS_JSON))
        val client = SentriCamApiClient()
        val request = AndroidRegistrationRequestMapper().map(identity(), capabilities(), NOW)

        val result = client.register(server.url("/").toString(), request)
        val recorded = server.takeRequest()

        assertEquals("/api/v1/devices/registrations", recorded.path)
        assertEquals("POST", recorded.method)
        assertTrue(recorded.body.readUtf8().contains(INSTALLATION_ID))
        assertEquals(SERVER_DEVICE_ID, (result as RegistrationCallResult.Success).value.deviceId)
        assertEquals("2026-07-31T13:00:00Z", result.value.accessTokenExpiresAtUtc)
        assertEquals("2026-07-31T12:00:00Z", result.value.serverUtcNow)
    }

    @Test
    fun malformedJsonIsAControlledSerializationFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not-json"))

        val result = SentriCamApiClient().register(
            server.url("/").toString(),
            AndroidRegistrationRequestMapper().map(identity(), capabilities(), NOW),
        )

        assertTrue((result as RegistrationCallResult.Failure).failure is RegistrationFailure.SerializationFailure)
    }

    @Test
    fun validationUnauthorizedAndConflictResponsesAreStructured() = runBlocking {
        listOf(
            400 to RegistrationFailure.ValidationRejected::class.java,
            401 to RegistrationFailure.Unauthorized::class.java,
            409 to RegistrationFailure.Conflict::class.java,
        ).forEach { (status, failureType) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("server detail must not escape"))
            val result = SentriCamApiClient().register(
                server.url("/").toString(),
                AndroidRegistrationRequestMapper().map(identity(), capabilities(), NOW),
            )
            assertTrue(failureType.isInstance((result as RegistrationCallResult.Failure).failure))
            assertFalseContains(result.failure.userSafeMessage, "server detail")
        }
    }

    @Test
    fun timeoutIsRetryableAndDoesNotExposeResponseData() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder()
            .connectTimeout(100, TimeUnit.MILLISECONDS)
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(150, TimeUnit.MILLISECONDS)
            .build()

        val result = SentriCamApiClient(http).testConnection(server.url("/").toString())

        val failure = (result as RegistrationCallResult.Failure).failure
        assertTrue(failure is RegistrationFailure.Timeout)
        assertTrue(failure.retryAllowed)
    }

    @Test
    fun healthEndpointSupportsExplicitConnectionTest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = SentriCamApiClient().testConnection(server.url("/").toString())

        assertTrue(result is RegistrationCallResult.Success)
        assertEquals("/health", server.takeRequest().path)
    }

    private fun assertFalseContains(value: String, forbidden: String) {
        assertTrue("Unexpected sensitive server content", !value.contains(forbidden))
    }

    private companion object {
        const val SUCCESS_JSON = """
            {
              "deviceId":"$SERVER_DEVICE_ID",
              "isNewDevice":true,
              "accessToken":"$ACCESS_TOKEN",
              "accessTokenExpiresAtUtc":"2026-07-31T13:00:00Z",
              "registeredAtUtc":"2026-07-31T12:00:00Z",
              "refreshToken":null,
              "refreshTokenExpirationUtc":null,
              "serverUtcNow":"2026-07-31T12:00:00Z",
              "installationId":"$INSTALLATION_ID",
              "registrationState":"Registered",
              "registrationSchemaVersion":1
            }
        """
    }
}
