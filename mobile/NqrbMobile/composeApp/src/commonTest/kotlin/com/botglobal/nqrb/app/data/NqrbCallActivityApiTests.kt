package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.calling.FinalCallUsage
import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class NqrbCallActivityApiTests {
    @Test
    fun maps_paged_user_relative_history_contract() = runTest {
        val api = NqrbCallActivityApi(HttpClient(MockEngine { request ->
            assertEquals("/api/mobile/calling/history", request.url.encodedPath)
            assertEquals("2", request.url.parameters["page"])
            assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
            respond("""{"items":[{"callId":"call-1","direction":"incoming","participantDisplayName":"Remote","outcome":"missed","startedAtUtc":"2026-09-01T12:00:00Z","connectedDurationSeconds":null,"totalBytes":null}],"page":2,"pageSize":20,"hasMore":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://api.example", FixedSessionVault())

        val page = api.history(2, 20)

        assertEquals(2, page.page)
        assertEquals(true, page.hasMore)
        assertEquals("missed", page.items.single().outcome)
    }

    @Test
    fun final_usage_sends_only_call_media_measurements_and_not_local_owner_identity() = runTest {
        lateinit var captured: HttpRequestData
        val api = NqrbCallActivityApi(HttpClient(MockEngine { request ->
            captured = request
            respond("""{"accepted":true}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://api.example", FixedSessionVault())

        api.finalizeUsage(FinalCallUsage("call-1", 100, 200, 30, "local-owner"))

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/api/mobile/calling/history/call-1/usage", captured.url.encodedPath)
        val body = requestBody(captured)
        assertFalse(body.contains("local-owner"))
        assertFalse(body.contains("ownerMembershipId"))
    }

    private fun requestBody(request: HttpRequestData): String = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> body.toString()
    }

    private class FixedSessionVault : SessionVault {
        override suspend fun restore() = MobileSession(
            "access", "2099-01-01T00:00:00Z", "refresh", "2099-02-01T00:00:00Z",
            ApplicationIdentity("membership", "subject", "User", IdentityKind.Registered, "nqrb"),
        )
        override suspend fun save(session: MobileSession) = Unit
        override suspend fun clear() = Unit
    }
}
