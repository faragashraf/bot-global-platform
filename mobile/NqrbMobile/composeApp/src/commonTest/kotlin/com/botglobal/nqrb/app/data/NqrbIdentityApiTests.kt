package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NqrbIdentityApiTests {
    @Test
    fun authenticatedProfileUsesCanonicalBackendNameAndEmail() = runTest {
        val vault = RecordingSessionVault(session())
        val engine = MockEngine { request ->
            assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
            respond(
                """{"displayName":"Canonical Person","email":"person@example.test"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = NqrbIdentityApi(HttpClient(engine), "https://api.example", vault).load()

        val available = assertIs<NqrbAccountProfileResult.Available>(result)
        assertEquals("Canonical Person", available.profile.displayName)
        assertEquals("person@example.test", available.profile.email)
    }

    @Test
    fun rejectedSessionClearsStoredSessionAndReturnsAuthenticationRequired() = runTest {
        val vault = RecordingSessionVault(session())
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }

        val result = NqrbIdentityApi(HttpClient(engine), "https://api.example", vault).load()

        assertIs<NqrbAccountProfileResult.AuthenticationRequired>(result)
        assertNull(vault.value)
    }

    private class RecordingSessionVault(var value: MobileSession?) : SessionVault {
        override suspend fun restore() = value
        override suspend fun save(session: MobileSession) { value = session }
        override suspend fun clear() { value = null }
    }

    private fun session() = MobileSession(
        "test-access",
        "2099-01-01T00:00:00Z",
        "session-refresh",
        "2099-02-01T00:00:00Z",
        ApplicationIdentity("membership-id", "subject-id", "Snapshot", IdentityKind.Registered, "nqrb"),
    )
}
