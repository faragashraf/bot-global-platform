package com.botglobal.lamma.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.FederatedCredential
import com.botglobal.mobile.platform.identity.FederatedCredentialType
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FamilyGamesSessionRaceTests {
    @Test
    fun late_google_response_after_clear_does_not_resurrect_credentials() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val vault = RaceVault()
        val api = api(vault) { request ->
            assertEquals("/api/mobile/family-games/identity/federated", request.url.encodedPath)
            requestStarted.complete(Unit)
            releaseResponse.await()
            jsonResponse(sessionJson("access-a", "refresh-a", "member-a", "Google A"))
        }

        supervisorScope {
            val signIn = async {
                api.authenticateFederated(FederatedCredential(
                    FederatedIdentityProvider.Google,
                    FederatedCredentialType.IdToken,
                    "id-token-a",
                ))
            }
            requestStarted.await()
            api.clearLocalSession()
            releaseResponse.complete(Unit)

            assertFailsWith<ApiException> { signIn.await() }
        }
        assertNull(vault.restore())
    }

    @Test
    fun late_profile_patch_after_account_switch_does_not_mix_identity_into_new_tokens() = runTest {
        val patchStarted = CompletableDeferred<Unit>()
        val releasePatch = CompletableDeferred<Unit>()
        val vault = RaceVault(session("access-a", "refresh-a", "member-a", "Original A"))
        val api = api(vault) { request ->
            when (request.url.encodedPath) {
                "/api/mobile/family-games/identity/profile" -> {
                    patchStarted.complete(Unit)
                    releasePatch.await()
                    jsonResponse(identityJson("member-a", "Patched A"))
                }
                "/api/mobile/family-games/identity/login" ->
                    jsonResponse(sessionJson("access-b", "refresh-b", "member-b", "Password B"))
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }

        supervisorScope {
            val patch = async { api.updateProfile("Patched A") }
            patchStarted.await()
            api.login("b@example.com", "secret")
            releasePatch.complete(Unit)

            assertFailsWith<ApiException> { patch.await() }
        }
        assertEquals("access-b", vault.restore()?.accessToken)
        assertEquals("Password B", vault.restore()?.identity?.displayName)
    }

    @Test
    fun refresh_response_after_clear_does_not_restore_deleted_session() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val vault = RaceVault(session("expired-a", "refresh-a", "member-a", "Player A"))
        val api = api(vault) { request ->
            when (request.url.encodedPath) {
                "/api/games/sessions/active" -> {
                    if (request.headers[HttpHeaders.Authorization] == "Bearer expired-a") {
                        respond("", HttpStatusCode.Unauthorized)
                    } else {
                        jsonResponse(activeSessionJson("member-a", "Player A"))
                    }
                }
                "/api/mobile/family-games/identity/refresh" -> {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                    jsonResponse(sessionJson("fresh-a", "refresh-a2", "member-a", "Player A"))
                }
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }

        supervisorScope {
            val active = async { api.activeSession() }
            refreshStarted.await()
            api.clearLocalSession()
            releaseRefresh.complete(Unit)

            assertFailsWith<ApiException> { active.await() }
        }
        assertNull(vault.restore())
    }

    @Test
    fun late_logout_for_old_account_cannot_clear_new_login() = runTest {
        val logoutStarted = CompletableDeferred<Unit>()
        val releaseLogout = CompletableDeferred<Unit>()
        val vault = RaceVault(session("access-a", "refresh-a", "member-a", "Player A"))
        val api = api(vault) { request ->
            when (request.url.encodedPath) {
                "/api/mobile/family-games/identity/logout" -> {
                    logoutStarted.complete(Unit)
                    releaseLogout.await()
                    respond("", HttpStatusCode.NoContent)
                }
                "/api/mobile/family-games/identity/login" ->
                    jsonResponse(sessionJson("access-b", "refresh-b", "member-b", "Player B"))
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }

        val logout = async { api.logout() }
        logoutStarted.await()
        api.login("b@example.com", "secret")
        releaseLogout.complete(Unit)
        logout.await()

        assertEquals("access-b", vault.restore()?.accessToken)
        assertEquals("member-b", vault.restore()?.identity?.membershipId)
    }

    @Test
    fun cancelled_password_login_during_delayed_vault_save_does_not_persist() = runTest {
        val vault = RaceVault(blockSave = true)
        val api = api(vault) { request ->
            assertEquals("/api/mobile/family-games/identity/login", request.url.encodedPath)
            jsonResponse(sessionJson("access-a", "refresh-a", "member-a", "Player A"))
        }

        val login = async { api.login("a@example.com", "secret") }
        vault.saveStarted.await()
        login.cancelAndJoin()
        vault.releaseSave.complete(Unit)

        assertNull(vault.restore())
    }

    @Test
    fun restore_reconciles_authoritative_lamma_name_without_deleting_offline_session_on_failure() = runTest {
        val vault = RaceVault(session("access-a", "refresh-a", "member-a", "Old Local"))
        val api = api(vault) { request ->
            assertEquals("/api/mobile/family-games/identity/me", request.url.encodedPath)
            jsonResponse(identityJson("member-a", "Authoritative LAMMA"))
        }

        assertEquals("Authoritative LAMMA", api.restore()?.identity?.displayName)
        assertEquals("Authoritative LAMMA", vault.restore()?.identity?.displayName)

        val offlineVault = RaceVault(session("access-b", "refresh-b", "member-b", "Offline Name"))
        val offlineApi = api(offlineVault) {
            respond("", HttpStatusCode.ServiceUnavailable)
        }
        assertEquals("Offline Name", offlineApi.restore()?.identity?.displayName)
        assertEquals("access-b", offlineVault.restore()?.accessToken)
    }

    private fun api(
        vault: SessionVault,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = FamilyGamesApi(
        HttpClient(MockEngine(handler)),
        FamilyGamesEnvironment.from("https://example.invalid"),
        vault,
    )

    private fun MockRequestHandleScope.jsonResponse(content: String) = respond(
        content,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private class RaceVault(
        initial: MobileSession? = null,
        private val blockSave: Boolean = false,
    ) : SessionVault {
        private var session = initial
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()

        override suspend fun restore(): MobileSession? = session

        override suspend fun save(session: MobileSession) {
            if (blockSave) {
                saveStarted.complete(Unit)
                releaseSave.await()
            }
            this.session = session
        }

        override suspend fun clear() {
            session = null
        }
    }

    private fun session(
        access: String,
        refresh: String,
        membershipId: String,
        displayName: String,
    ) = MobileSession(
        access,
        "2099-01-01T00:00:00Z",
        refresh,
        "2099-01-01T00:00:00Z",
        ApplicationIdentity(membershipId, "subject-$membershipId", displayName, IdentityKind.Registered, "family-games"),
    )

    private fun sessionJson(
        access: String,
        refresh: String,
        membershipId: String,
        displayName: String,
    ) = """
        {
          "accessToken": "$access",
          "accessExpiresAtUtc": "2099-01-01T00:00:00Z",
          "refreshToken": "$refresh",
          "refreshExpiresAtUtc": "2099-01-01T00:00:00Z",
          "identity": ${identityJson(membershipId, displayName)}
        }
    """.trimIndent()

    private fun identityJson(membershipId: String, displayName: String) = """
        {
          "membershipId": "$membershipId",
          "subjectId": "subject-$membershipId",
          "displayName": "$displayName",
          "isGuest": false,
          "applicationKey": "family-games"
        }
    """.trimIndent()

    private fun activeSessionJson(membershipId: String, displayName: String) = """
        {
          "sessionId": "session-1",
          "joinCode": "ABC123",
          "gameType": "classic",
          "status": "started",
          "matchNumber": 1,
          "ruleset": {
            "key": "classic-3x3",
            "boardSize": 3,
            "winLength": 3,
            "playerCount": 2,
            "rematchEnabled": true,
            "voiceEnabled": false
          },
          "players": [
            {
              "membershipId": "$membershipId",
              "displayName": "$displayName",
              "seat": 1,
              "mark": "X",
              "isReady": true,
              "isConnected": true
            }
          ],
          "board": ["", "", "", "", "", "", "", "", ""],
          "version": 1,
          "matchStatus": "active",
          "lastActivityAtUtc": "2026-10-03T00:00:00Z"
        }
    """.trimIndent()
}
