package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NqrbAccountDeletionApiTests {
    @Test
    fun deletionUsesAuthenticatedCurrentAccountAndHasNoSelectableIdentityPayload() = runTest {
        var request: HttpRequestData? = null
        val engine = MockEngine {
            request = it
            respond("", HttpStatusCode.NoContent)
        }

        val outcome = api(HttpClient(engine)).deleteCurrentAccount()

        assertEquals(NqrbAccountDeletionOutcome.Deleted, outcome)
        assertEquals("DELETE", request?.method?.value)
        assertEquals("/api/mobile/nqrb/account", request?.url?.encodedPath)
        assertTrue(request?.headers?.get(HttpHeaders.Authorization)?.startsWith("Bearer ") == true)
        assertFalse(request.toString().contains("membership-id"))
        assertFalse(request.toString().contains("subject-id"))
    }

    @Test
    fun acceptedResumableDeletionIsAConfirmedSuccess() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Accepted) }

        assertEquals(
            NqrbAccountDeletionOutcome.Accepted,
            api(HttpClient(engine)).deleteCurrentAccount(),
        )
    }

    @Test
    fun serverFailureRemainsRetryable() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }

        assertEquals(
            NqrbAccountDeletionOutcome.RetryableFailure,
            api(HttpClient(engine)).deleteCurrentAccount(),
        )
    }

    private fun api(client: HttpClient) = NqrbAccountDeletionApi(
        client,
        "https://api.example.test",
        FixedSessionVault(),
    )

    private class FixedSessionVault : SessionVault {
        override suspend fun restore() = MobileSession(
            "access-token",
            "2099-01-01T00:00:00Z",
            "refresh-token",
            "2099-02-01T00:00:00Z",
            ApplicationIdentity(
                "membership-id",
                "subject-id",
                "Test User",
                IdentityKind.Registered,
                "nqrb",
            ),
        )
        override suspend fun save(session: MobileSession) = Unit
        override suspend fun clear() = Unit
    }
}
