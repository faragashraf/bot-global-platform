package com.botglobal.lamma.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FamilyGamesAccountDeletionTests {
    @Test
    fun only_completed_and_accepted_responses_are_success_and_vault_is_cleared_by_teardown() = runTest {
        for ((status, expected) in listOf(HttpStatusCode.NoContent to AccountDeletionAcceptance.Completed,
            HttpStatusCode.Accepted to AccountDeletionAcceptance.Pending)) {
            val vault = Vault()
            val api = FamilyGamesApi(HttpClient(MockEngine { request ->
                assertEquals(HttpMethod.Delete, request.method)
                assertEquals("/api/mobile/family-games/account", request.url.encodedPath)
                assertEquals("Bearer test-access", request.headers["Authorization"])
                respond("", status)
            }), FamilyGamesEnvironment.from("https://example.invalid"), vault)
            assertEquals(expected, api.deleteAccount())
            assertEquals(0, vault.clearCalls)
            api.clearLocalSession()
            assertEquals(1, vault.clearCalls)
        }
    }

    @Test
    fun failure_does_not_clear_credentials() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.TooManyRequests,
            HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK)) {
            val vault = Vault()
            val api = FamilyGamesApi(HttpClient(MockEngine { respond("", status) }),
                FamilyGamesEnvironment.from("https://example.invalid"), vault)
            assertFailsWith<ApiException> { api.deleteAccount() }
            assertEquals(0, vault.clearCalls)
            assertEquals("test-access", vault.restore()?.accessToken)
        }
    }

    private class Vault : SessionVault {
        var clearCalls = 0
        private var session: MobileSession? = MobileSession("test-access", "2099-01-01T00:00:00Z",
            "test-refresh", "2099-01-01T00:00:00Z",
            ApplicationIdentity("test-member", "test-subject", "Test", IdentityKind.Registered, "family-games"))
        override suspend fun restore() = session
        override suspend fun save(session: MobileSession) { this.session = session }
        override suspend fun clear() { clearCalls++; session = null }
    }
}
