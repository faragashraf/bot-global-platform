package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class NqrbContactBookApiTests {
    @Test
    fun list_search_add_remove_and_invites_use_nqrb_contact_endpoints_with_bearer_session() = runTest {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath}${request.url.encodedQuery.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()}"
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
            val content = when (request.url.encodedPath) {
                "/api/mobile/nqrb/contacts" -> """{"items":[{"membershipId":"member-a","displayName":"Amina"}],"page":1,"pageSize":20,"hasMore":false}"""
                "/api/mobile/nqrb/users/search" -> """{"items":[{"membershipId":"member-b","displayName":"Basma"}],"page":1,"pageSize":20,"hasMore":false}"""
                "/api/mobile/nqrb/contacts/member-b" -> """{"membershipId":"member-b","displayName":"Basma"}"""
                "/api/mobile/nqrb/contacts/member-b/nickname" -> """{"membershipId":"member-b","displayName":"Basma","nickname":"صديقتي"}"""
                "/api/mobile/nqrb/contacts/from-history/call-1" -> """{"membershipId":"member-c","displayName":"Counterpart"}"""
                "/api/mobile/nqrb/contact-invites" -> """{"code":"NQ-2345-6789-ABCD-EFGH-JKLM-NPQR","shareLink":"nqrb://invite/NQ-2345-6789-ABCD-EFGH-JKLM-NPQR","expiresAtUtc":"2099-01-01T00:00:00Z"}"""
                "/api/mobile/nqrb/guest-call-invites" -> """{"inviteId":"10000000-0000-0000-0000-000000000001","shareLink":"https://api.example/nqrb/guest-call/capability","expiresAtUtc":"2099-01-01T00:00:00Z"}"""
                "/api/mobile/nqrb/contact-invites/NQ-2345-6789/preview" -> """{"issuerDisplayName":"Amina"}"""
                "/api/mobile/nqrb/contact-invites/NQ-2345-6789/accept" -> """{"membershipId":"member-a","displayName":"Amina"}"""
                else -> """{}"""
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val session = session("member-self")
        val api = NqrbContactBookApi(HttpClient(engine), "https://api.example/", FixedSessionVault(session))

        val list = api.listContacts(session) as NqrbContactBookResult.Available
        val found = api.findContact(session, "member-b") as NqrbContactLookupResult.Found
        val search = api.searchUsers(session, "Ba") as NqrbContactBookResult.Available
        val add = api.addContact(session, "member-b") as NqrbContactMutationResult.Saved
        val addFromHistory = api.addContactFromCallHistory(session, "call-1") as NqrbContactMutationResult.Saved
        val remove = api.removeContact(session, "member-b")
        val renamed = api.updateContactNickname(session, "member-b", "صديقتي") as NqrbContactMutationResult.Saved
        val invite = api.createInvite(session) as NqrbContactInviteCreateResult.Created
        val guestInvite = api.createGuestCallInvite(session) as NqrbGuestCallInviteCreateResult.Created
        val guestInviteRevoked = api.revokeGuestCallInvite(session, guestInvite.invite.inviteId)
        val preview = api.previewInvite(session, "NQ-2345-6789") as NqrbContactInvitePreviewResult.Available
        val accept = api.acceptInvite(session, "NQ-2345-6789") as NqrbContactInviteAcceptResult.Accepted

        assertEquals("member-a", list.page.items.single().membershipId)
        assertEquals("Basma", found.contact.displayName)
        assertEquals("member-b", search.page.items.single().membershipId)
        assertEquals("Basma", add.contact.displayName)
        assertEquals("member-c", addFromHistory.contact.membershipId)
        assertEquals(NqrbContactMutationResult.Removed, remove)
        assertEquals("صديقتي", renamed.contact.nickname)
        assertEquals("NQ-2345-6789-ABCD-EFGH-JKLM-NPQR", invite.invite.code)
        assertEquals("https://api.example/nqrb/guest-call/capability", guestInvite.invite.shareLink)
        assertEquals(NqrbGuestCallInviteRevokeResult.Revoked, guestInviteRevoked)
        assertEquals("Amina", preview.preview.issuerDisplayName)
        assertEquals("member-a", accept.issuer.membershipId)
        assertEquals(
            listOf(
                "GET /api/mobile/nqrb/contacts?page=1",
                "GET /api/mobile/nqrb/contacts/member-b",
                "GET /api/mobile/nqrb/users/search?query=Ba&page=1",
                "POST /api/mobile/nqrb/contacts/member-b",
                "POST /api/mobile/nqrb/contacts/from-history/call-1",
                "DELETE /api/mobile/nqrb/contacts/member-b",
                "PUT /api/mobile/nqrb/contacts/member-b/nickname",
                "POST /api/mobile/nqrb/contact-invites",
                "POST /api/mobile/nqrb/guest-call-invites",
                "DELETE /api/mobile/nqrb/guest-call-invites/10000000-0000-0000-0000-000000000001",
                "GET /api/mobile/nqrb/contact-invites/NQ-2345-6789/preview",
                "POST /api/mobile/nqrb/contact-invites/NQ-2345-6789/accept",
            ),
            calls,
        )
    }

    @Test
    fun stale_or_missing_session_does_not_call_server() = runTest {
        var requests = 0
        val api = NqrbContactBookApi(
            HttpClient(MockEngine {
                requests++
                respond("{}", HttpStatusCode.OK)
            }),
            "https://api.example",
            FixedSessionVault(session("other-member")),
        )

        assertEquals(NqrbContactBookResult.AuthenticationRequired, api.listContacts(session("member-self")))
        assertEquals(0, requests)
    }

    @Test
    fun background_token_rotation_keeps_same_account_contact_actions_usable() = runTest {
        val shownSession = session("member-self")
        val refreshedSession = shownSession.copy(accessToken = "rotated-access-token", refreshToken = "rotated-refresh-token")
        var requests = 0
        val api = NqrbContactBookApi(
            HttpClient(MockEngine { request ->
                requests++
                assertEquals("Bearer rotated-access-token", request.headers[HttpHeaders.Authorization])
                respond(
                    """{"inviteId":"10000000-0000-0000-0000-000000000001","shareLink":"https://api.example/guest","expiresAtUtc":"2099-01-01T00:00:00Z"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }),
            "https://api.example",
            FixedSessionVault(refreshedSession),
        )

        val result = api.createGuestCallInvite(shownSession)

        assertEquals(1, requests)
        assertEquals("https://api.example/guest", (result as NqrbGuestCallInviteCreateResult.Created).invite.shareLink)
    }

    @Test
    fun cancelled_contact_request_propagates_cancellation() = runTest {
        val active = session("member-self")
        val api = NqrbContactBookApi(
            HttpClient(MockEngine { throw CancellationException("request cancelled") }),
            "https://api.example",
            FixedSessionVault(active),
        )

        assertFailsWith<CancellationException> { api.listContacts(active) }
    }

    private class FixedSessionVault(private val restored: MobileSession?) : SessionVault {
        override suspend fun restore() = restored
        override suspend fun save(session: MobileSession) = Unit
        override suspend fun clear() = Unit
    }

    private fun session(membershipId: String) = MobileSession(
        accessToken = "access-token",
        accessExpiresAtUtc = "2099-01-01T00:00:00Z",
        refreshToken = "refresh-token",
        refreshExpiresAtUtc = "2099-02-01T00:00:00Z",
        identity = ApplicationIdentity(
            membershipId = membershipId,
            subjectId = "subject-$membershipId",
            displayName = "Current user",
            kind = IdentityKind.Registered,
            applicationKey = "nqrb",
        ),
    )
}
