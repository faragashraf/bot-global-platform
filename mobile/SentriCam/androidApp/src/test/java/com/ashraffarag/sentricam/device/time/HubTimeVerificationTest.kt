package com.ashraffarag.sentricam.device.time

import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubTimeVerificationTest {
    private val now = Instant.parse("2026-08-02T12:00:00Z").toEpochMilli()

    @Test
    fun reachableAuthenticatedEndpointVerifiesCorrectPhoneTime() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"serverUtcNow":"2026-08-02T12:00:00.050Z","serverUtcOffsetMinutes":180,"serverTimeZoneId":"Africa/Cairo"}""",
            ),
        )
        server.start()
        try {
            val times = ArrayDeque(listOf(now, now + 100))
            val client = HubTimeVerificationHttpClient(
                OkHttpClient(),
                nowMillis = { times.removeFirst() },
            )
            val result = client.verify(credentials(server, "device-token"))
            val request = server.takeRequest()

            assertTrue(result is HubTimeVerificationResult.Success)
            assertEquals("/api/v1/device/time", request.path)
            assertEquals("Bearer device-token", request.getHeader("Authorization"))
            val response = (result as HubTimeVerificationResult.Success).response
            assertEquals(180, response.serverUtcOffsetMinutes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aspNetDateTimeOffsetUtcShapeVerifiesOnAndroid() {
        val controller = controller(ZoneId.of("Africa/Cairo"))

        controller.update(
            serverUtc = "2026-08-02T12:00:00.0500000+00:00",
            serverUtcOffsetMinutes = 180,
            hubTimeZoneId = "Africa/Cairo",
            receivedAtMillis = now + 50,
        )

        assertEquals(DeviceTimeValidationKind.VALID, controller.state.value.kind)
    }

    @Test
    fun correctPhoneTimeButMissingEndpointIsInformationalAndRetryable() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val controller = controller(ZoneId.of("Africa/Cairo"))
            val coordinator = HubTimeVerificationCoordinator(
                HubTimeVerificationHttpClient(OkHttpClient()),
                { credentials(server, "device-token") },
                controller,
            )

            val result = coordinator.verify()

            assertEquals(DeviceTimeValidationKind.UNABLE_TO_VALIDATE, result.kind)
            assertEquals(HubTimeVerificationFailureReason.ENDPOINT_UNAVAILABLE, result.verificationFailureReason)
            assertEquals(DeviceTimeValidationAction.RETRY, result.action())
            assertFalse(result.action() == DeviceTimeValidationAction.OPEN_DATE_TIME_SETTINGS)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun timeoutAuthorizationParseAndUnreachableReasonsRemainDistinct() = runBlocking {
        assertEquals(
            HubTimeVerificationFailureReason.AUTHORIZATION_FAILURE,
            responseFailure(401),
        )
        assertEquals(
            HubTimeVerificationFailureReason.PARSE_FAILURE,
            responseFailure(200, "not-json"),
        )
        assertEquals(
            HubTimeVerificationFailureReason.HUB_UNREACHABLE,
            HubTimeVerificationCoordinator(
                HubTimeVerificationApi {
                    HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.HUB_UNREACHABLE)
                },
                { HubTimeVerificationCredentials("http://127.0.0.1/", "token") },
                controller(ZoneId.of("UTC")),
            ).verify().verificationFailureReason,
        )
        assertEquals(
            HubTimeVerificationFailureReason.TIMEOUT,
            HubTimeVerificationCoordinator(
                HubTimeVerificationApi {
                    HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.TIMEOUT)
                },
                { HubTimeVerificationCredentials("http://127.0.0.1/", "token") },
                controller(ZoneId.of("UTC")),
            ).verify().verificationFailureReason,
        )
    }

    @Test
    fun retryAfterConnectionRecoveryReplacesUnavailableWithVerified() = runBlocking {
        val results = ArrayDeque<HubTimeVerificationResult>().apply {
            add(HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.HUB_UNREACHABLE))
            add(
                HubTimeVerificationResult.Success(
                    HubTimeResponse("2026-08-02T12:00:00.050Z", 180, "Africa/Cairo"),
                    now,
                    now + 100,
                ),
            )
        }
        val coordinator = HubTimeVerificationCoordinator(
            HubTimeVerificationApi { results.removeFirst() },
            { HubTimeVerificationCredentials("http://hub/", "token") },
            controller(ZoneId.of("Africa/Cairo")),
        )

        assertEquals(DeviceTimeValidationKind.UNABLE_TO_VALIDATE, coordinator.verify().kind)
        assertEquals(DeviceTimeValidationKind.VALID, coordinator.verify().kind)
    }

    @Test
    fun canceledVerificationCannotLeaveHubTimeStuckVerifying() = runBlocking {
        val validation = controller(ZoneId.of("UTC"))
        val coordinator = HubTimeVerificationCoordinator(
            HubTimeVerificationApi { awaitCancellation() },
            { HubTimeVerificationCredentials("http://hub/", "token") },
            validation,
        )

        val verification = async { coordinator.verify() }
        withTimeout(1_000L) {
            while (!validation.state.value.isVerifying) kotlinx.coroutines.yield()
        }
        verification.cancelAndJoin()

        assertFalse(validation.state.value.isVerifying)
    }

    @Test
    fun realClockDriftUsesAbsoluteInstantsAndIgnoresNormalRoundTrip() = runBlocking {
        val coordinator = HubTimeVerificationCoordinator(
            HubTimeVerificationApi {
                HubTimeVerificationResult.Success(
                    HubTimeResponse("2026-08-02T11:55:00Z", 180, "Africa/Cairo"),
                    now,
                    now + 800,
                )
            },
            { HubTimeVerificationCredentials("http://hub/", "token") },
            controller(ZoneId.of("Africa/Cairo")),
        )

        val result = coordinator.verify()

        assertEquals(DeviceTimeValidationKind.CLOCK_DRIFT, result.kind)
        assertTrue(requireNotNull(result.driftMillis) > 120_000)
        assertEquals(800L, result.roundTripMillis)
    }

    @Test
    fun runtimeRetriesOnceForEachHealthyRealtimeConnection() {
        val source = java.io.File(
            "src/main/java/com/ashraffarag/sentricam/device/android/DeviceRuntime.kt",
        ).readText()

        assertTrue(source.contains("is SignalRState.Connected ->"))
        assertTrue(source.contains("state.connectionId != lastVerifiedConnectionId"))
        assertTrue(source.contains("replaceHubTimeVerification(verify = true)"))
        assertTrue(source.contains("previous?.cancelAndJoin()"))
    }

    private suspend fun responseFailure(status: Int, body: String = ""): HubTimeVerificationFailureReason? {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        server.start()
        return try {
            val result = HubTimeVerificationHttpClient(
                OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build(),
            ).verify(credentials(server, "token"))
            (result as HubTimeVerificationResult.Failure).reason
        } finally {
            server.shutdown()
        }
    }

    private fun controller(zone: ZoneId) = DeviceTimeValidationController(
        validator = DeviceTimeValidator(120_000),
        nowMillis = { now },
        zoneId = { zone },
    )

    private fun credentials(server: MockWebServer, token: String) = HubTimeVerificationCredentials(
        server.url("/").toString(),
        token,
    )
}
