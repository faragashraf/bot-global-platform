package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NqrbIdentityApiTests {
    @Test
    fun authenticatedProfileUsesCanonicalBackendNameAndEmail() = runTest {
        val vault = RecordingSessionVault(session())
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/mobile/nqrb/identity/profile", request.url.encodedPath)
            assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
            respond(
                """{"displayName":"Canonical Person","email":"person@example.test"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = NqrbIdentityApi(HttpClient(engine), "https://api.example", vault).load(session())

        val available = assertIs<NqrbAccountProfileResult.Available>(result)
        assertEquals("Canonical Person", available.profile.displayName)
        assertEquals("person@example.test", available.profile.email)
    }

    @Test
    fun profileUsesRotatedSessionSavedByCallingWithoutSigningOut() = runTest {
        val signedIn = session()
        val vault = RecordingSessionVault(signedIn.copy(accessToken = "rotated-access"))
        val engine = MockEngine { request ->
            assertEquals("Bearer rotated-access", request.headers[HttpHeaders.Authorization])
            respond(
                """{"displayName":"Canonical Person","email":"person@example.test"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = NqrbIdentityApi(HttpClient(engine), "https://api.example", vault).load(signedIn)

        assertIs<NqrbAccountProfileResult.Available>(result)
        assertEquals("rotated-access", vault.value?.accessToken)
    }

    @Test
    fun expiredProfileAccessRefreshesAndRetriesOnce() = runTest {
        val signedIn = session()
        val vault = RecordingSessionVault(signedIn)
        var profileRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/mobile/nqrb/identity/profile" -> {
                    profileRequests++
                    assertEquals(
                        if (profileRequests == 1) "Bearer test-access" else "Bearer renewed-access",
                        request.headers[HttpHeaders.Authorization],
                    )
                    if (profileRequests == 1) respond("", HttpStatusCode.Unauthorized)
                    else respond(
                        """{"displayName":"Canonical Person","email":"person@example.test"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/api/mobile/nqrb/identity/refresh" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    respond(
                        """{"accessToken":"renewed-access","accessExpiresAtUtc":"2099-01-01T00:00:00Z","refreshToken":"renewed-refresh","refreshExpiresAtUtc":"2099-02-01T00:00:00Z","identity":{"membershipId":"test-membership-id","subjectId":"test-subject-id","displayName":"Snapshot","isGuest":false,"applicationKey":"nqrb"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("Unexpected request")
            }
        }

        val result = NqrbIdentityApi(HttpClient(engine), "https://api.example", vault).load(signedIn)

        assertIs<NqrbAccountProfileResult.Available>(result)
        assertEquals(2, profileRequests)
        assertEquals("renewed-access", vault.value?.accessToken)
    }

    @Test
    fun rejectedOlderProfileSessionDoesNotClearNewerStoredSession() = runTest {
        val newerSession = session("newer")
        val vault = RecordingSessionVault(newerSession)
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }

        val result = NqrbIdentityApi(HttpClient(engine), "https://api.example", vault).load(session("older"))

        assertIs<NqrbAccountProfileResult.AuthenticationRequired>(result)
        assertEquals(newerSession, vault.value)
    }

    @Test
    fun retryableServerFailurePreservesStoredSession() = runTest {
        val currentSession = session()
        val vault = RecordingSessionVault(currentSession)
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }

        val result = NqrbIdentityApi(HttpClient(engine), "https://api.example", vault).load(currentSession)

        assertIs<NqrbAccountProfileResult.RetryableFailure>(result)
        assertEquals(currentSession, vault.value)
    }

    @Test
    fun networkFailurePreservesStoredSession() = runTest {
        val currentSession = session()
        val vault = RecordingSessionVault(currentSession)
        val engine = MockEngine { error("Synthetic network failure") }

        val result = NqrbIdentityApi(HttpClient(engine), "https://api.example", vault).load(currentSession)

        assertIs<NqrbAccountProfileResult.RetryableFailure>(result)
        assertEquals(currentSession, vault.value)
    }

    @Test
    fun logout_waits_for_inflight_refresh_then_clears_the_rotated_session() = runTest {
        val vault = RecordingSessionVault(session())
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/mobile/nqrb/identity/refresh" -> {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                    respond(
                        """{"accessToken":"renewed-access","accessExpiresAtUtc":"2099-01-01T00:00:00Z","refreshToken":"renewed-refresh","refreshExpiresAtUtc":"2099-02-01T00:00:00Z","identity":{"membershipId":"test-membership-id","subjectId":"test-subject-id","displayName":"Snapshot","isGuest":false,"applicationKey":"nqrb"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/api/mobile/nqrb/identity/logout" -> {
                    assertEquals("Bearer renewed-access", request.headers[HttpHeaders.Authorization])
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("Unexpected request")
            }
        }
        val api = NqrbIdentityApi(HttpClient(engine), "https://api.example", vault)

        val refresh = async { api.restore() }
        refreshStarted.await()
        val logout = async { api.logout() }
        runCurrent()
        releaseRefresh.complete(Unit)
        refresh.await()
        logout.await()

        assertEquals(null, vault.value)
    }

    private class RecordingSessionVault(var value: MobileSession?) : SessionVault {
        override suspend fun restore() = value
        override suspend fun save(session: MobileSession) { value = session }
        override suspend fun clear() { value = null }
    }

    private fun session(marker: String = "test") = MobileSession(
        "$marker-access",
        "2099-01-01T00:00:00Z",
        "$marker-refresh",
        "2099-02-01T00:00:00Z",
        ApplicationIdentity("$marker-membership-id", "$marker-subject-id", "Snapshot", IdentityKind.Registered, "nqrb"),
    )
}
