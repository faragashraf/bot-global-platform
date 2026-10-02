package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.calling.FinalCallUsage
import com.botglobal.mobile.platform.calling.CallHistoryFilter
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
        lateinit var captured: HttpRequestData
        val api = NqrbCallActivityApi(HttpClient(MockEngine { request ->
            captured = request
            assertEquals("/api/mobile/calling/history", request.url.encodedPath)
            assertEquals("2", request.url.parameters["page"])
            assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
            respond("""{"items":[{"callId":"call-1","direction":"incoming","participantDisplayName":"Remote","outcome":"missed","startedAtUtc":"2026-09-01T12:00:00Z","connectedDurationSeconds":null,"totalBytes":null}],"page":2,"pageSize":20,"hasMore":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://api.example", FixedSessionVault())

        val page = api.history(2, 20, CallHistoryFilter.Missed)

        assertEquals(2, page.page)
        assertEquals("missed", captured.url.parameters["filter"])
        assertEquals(true, page.hasMore)
        assertEquals("missed", page.items.single().outcome)
        assertFalse(page.items.single().isGuestCall)
        assertEquals(null, page.items.single().isSavedContact)
        assertEquals(null, page.items.single().counterpartMembershipId)
        assertFalse(page.items.single().canRedial)
        assertFalse(page.items.single().canAddContact)
    }

    @Test
    fun contact_and_call_capabilities_are_read_from_the_server_for_each_history_entry_and_detail() = runTest {
        val api = NqrbCallActivityApi(HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/history"))
                """{"items":[{"callId":"saved","direction":"incoming","participantDisplayName":"Friend","startedAtUtc":"2026-10-02T12:00:00Z","isSavedContact":true,"counterpartMembershipId":"member-friend","canRedial":true,"canAddContact":false},{"callId":"new","direction":"incoming","participantDisplayName":"Bero Ashraf","startedAtUtc":"2026-10-02T12:01:00Z","isSavedContact":false,"counterpartMembershipId":"member-bero","canRedial":true,"canAddContact":true}],"page":1,"pageSize":20,"hasMore":false}"""
            else
                """{"callId":"saved","direction":"incoming","participantDisplayNames":["Friend"],"startedAtUtc":"2026-10-02T12:00:00Z","isSavedContact":true,"counterpartMembershipId":"member-friend","canRedial":true,"canAddContact":false}"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://api.example", FixedSessionVault())

        val page = api.history(1, 20, CallHistoryFilter.All)

        assertEquals(true, page.items.first().isSavedContact)
        assertEquals("member-friend", page.items.first().counterpartMembershipId)
        assertEquals(false, page.items.last().isSavedContact)
        assertEquals("member-bero", page.items.last().counterpartMembershipId)
        assertEquals(true, page.items.last().canRedial)
        assertEquals(true, page.items.last().canAddContact)
        assertEquals(true, api.detail("saved")?.isSavedContact)
        assertEquals("member-friend", api.detail("saved")?.counterpartMembershipId)
        assertEquals(true, api.detail("saved")?.canRedial)
        assertEquals(false, api.detail("saved")?.canAddContact)
    }

    @Test
    fun guest_call_history_and_detail_keep_the_guest_marker() = runTest {
        val api = NqrbCallActivityApi(HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/history"))
                """{"items":[{"callId":"guest-1","direction":"incoming","participantDisplayName":"Browser guest","outcome":"completed","startedAtUtc":"2026-10-02T12:00:00Z","isGuestCall":true}],"page":1,"pageSize":20,"hasMore":false}"""
            else
                """{"callId":"guest-1","direction":"incoming","participantDisplayNames":["Browser guest"],"outcome":"completed","startedAtUtc":"2026-10-02T12:00:00Z","isGuestCall":true}"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://api.example", FixedSessionVault())

        val item = api.history(1, 20, CallHistoryFilter.All).items.single()
        val detail = api.detail(item.callId)

        assertEquals("Browser guest", item.participantDisplayName)
        assertEquals(true, item.isGuestCall)
        assertEquals(true, detail?.isGuestCall)
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
