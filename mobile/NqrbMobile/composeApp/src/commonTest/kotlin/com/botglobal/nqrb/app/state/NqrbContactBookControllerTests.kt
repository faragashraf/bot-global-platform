package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.nqrb.app.data.NqrbContact
import com.botglobal.nqrb.app.data.NqrbBlockedAccount
import com.botglobal.nqrb.app.data.NqrbBlockListResult
import com.botglobal.nqrb.app.data.NqrbContactBookGateway
import com.botglobal.nqrb.app.data.NqrbContactBookResult
import com.botglobal.nqrb.app.data.NqrbContactInvite
import com.botglobal.nqrb.app.data.NqrbContactInviteAcceptResult
import com.botglobal.nqrb.app.data.NqrbContactInviteCreateResult
import com.botglobal.nqrb.app.data.NqrbContactInvitePreview
import com.botglobal.nqrb.app.data.NqrbContactInvitePreviewResult
import com.botglobal.nqrb.app.data.NqrbContactLookupResult
import com.botglobal.nqrb.app.data.NqrbContactMutationResult
import com.botglobal.nqrb.app.data.NqrbContactPage
import com.botglobal.nqrb.app.data.NqrbGuestCallInvite
import com.botglobal.nqrb.app.data.NqrbGuestCallInviteCreateResult
import com.botglobal.nqrb.app.data.NqrbGuestCallInviteRevokeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class NqrbContactBookControllerTests {
    @Test
    fun refresh_keeps_saved_contacts_visible_while_loading() = runTest {
        val refresh = CompletableDeferred<NqrbContactBookResult>()
        var loads = 0
        val gateway = object : NqrbContactBookGateway by CountingGateway() {
            override suspend fun listContacts(session: MobileSession, page: Int): NqrbContactBookResult {
                loads++
                return if (loads == 1) page("saved") else refresh.await()
            }
        }
        val controller = NqrbContactBookController(gateway)
        val session = session("owner")
        controller.load(session)

        backgroundScope.launch { controller.load(session) }
        runCurrent()

        assertEquals(NqrbContactBookLoadState.Ready, controller.state.value.contactsState)
        assertEquals(listOf("saved"), controller.state.value.contacts.map(NqrbContact::membershipId))
        assertEquals(true, controller.state.value.contactsRefreshing)

        refresh.complete(page("updated"))
        runCurrent()

        assertEquals(listOf("updated"), controller.state.value.contacts.map(NqrbContact::membershipId))
        assertEquals(false, controller.state.value.contactsRefreshing)
    }

    @Test
    fun temporary_refresh_failure_keeps_saved_contacts_visible_and_can_retry() = runTest {
        var loads = 0
        val gateway = object : NqrbContactBookGateway by CountingGateway() {
            override suspend fun listContacts(session: MobileSession, page: Int): NqrbContactBookResult = when (++loads) {
                1 -> page("saved")
                2 -> NqrbContactBookResult.RetryableFailure
                else -> page("updated")
            }
        }
        val controller = NqrbContactBookController(gateway)
        val session = session("owner")

        controller.load(session)
        controller.load(session)
        assertEquals(NqrbContactBookLoadState.Ready, controller.state.value.contactsState)
        assertEquals(listOf("saved"), controller.state.value.contacts.map(NqrbContact::membershipId))
        assertEquals(true, controller.state.value.contactsRefreshFailed)

        controller.load(session)
        assertEquals(listOf("updated"), controller.state.value.contacts.map(NqrbContact::membershipId))
        assertEquals(false, controller.state.value.contactsRefreshFailed)
    }
    @Test
    fun failed_block_list_load_can_be_distinguished_from_a_failed_mutation_and_retried() = runTest {
        var fails = true
        val gateway = object : NqrbContactBookGateway by CountingGateway() {
            override suspend fun listBlockedAccounts(session: MobileSession): NqrbBlockListResult =
                if (fails) NqrbBlockListResult.Failed else NqrbBlockListResult.Available(emptyList())
        }
        val controller = NqrbContactBookController(gateway)
        val session = session("owner")

        controller.loadBlockedAccounts(session)
        assertEquals(NqrbBlockState.Error, controller.state.value.blockState)
        assertEquals(true, controller.state.value.blockErrorIsLoad)

        fails = false
        controller.loadBlockedAccounts(session)
        assertEquals(NqrbBlockState.Ready, controller.state.value.blockState)
        assertEquals(false, controller.state.value.blockErrorIsLoad)
    }

    @Test
    fun blocked_accounts_are_scoped_to_session_and_unblock_updates_the_list() = runTest {
        val gateway = CountingGateway()
        val controller = NqrbContactBookController(gateway)
        val first = session("first")
        controller.load(first)
        controller.loadBlockedAccounts(first)
        assertEquals(NqrbBlockState.Ready, controller.state.value.blockState)

        controller.block(first, "other")
        assertEquals("other", controller.state.value.blockedAccounts.single().membershipId)
        controller.unblock(first, "other")
        assertEquals(emptyList(), controller.state.value.blockedAccounts)

        controller.block(first, "other")
        controller.loadBlockedAccounts(session("second"))
        assertEquals(emptyList(), controller.state.value.blockedAccounts)
    }

    @Test
    fun stale_response_is_ignored_after_account_switch_and_clear() = runTest {
        val firstResponse = CompletableDeferred<NqrbContactBookResult>()
        val secondResponse = CompletableDeferred<NqrbContactBookResult>()
        val gateway = DeferredGateway(firstResponse, secondResponse)
        val controller = NqrbContactBookController(gateway)

        backgroundScope.launch { controller.load(session("first")) }
        runCurrent()
        controller.clear()
        backgroundScope.launch { controller.load(session("second")) }
        runCurrent()

        firstResponse.complete(page("first-contact"))
        secondResponse.complete(page("second-contact"))
        runCurrent()

        assertEquals(NqrbContactBookLoadState.Ready, controller.state.value.contactsState)
        assertEquals("second-contact", controller.state.value.contacts.single().membershipId)
    }

    @Test
    fun short_search_query_is_local_state_and_does_not_call_gateway() = runTest {
        val gateway = CountingGateway()
        val controller = NqrbContactBookController(gateway)

        controller.search(session("self"), "a")

        assertEquals(NqrbContactSearchState.TooShort, controller.state.value.searchState)
        assertEquals(0, gateway.searches)
    }

    @Test
    fun searching_while_contacts_load_does_not_discard_the_contact_response() = runTest {
        val contacts = CompletableDeferred<NqrbContactBookResult>()
        val controller = NqrbContactBookController(DeferredGateway(contacts, CompletableDeferred()))
        val session = session("self")

        backgroundScope.launch { controller.load(session) }
        runCurrent()
        controller.search(session, "friend")
        contacts.complete(page("saved"))
        runCurrent()

        assertEquals(NqrbContactBookLoadState.Ready, controller.state.value.contactsState)
        assertEquals("saved", controller.state.value.contacts.single().membershipId)
        assertEquals("search", controller.state.value.searchResults.single().membershipId)
    }

    @Test
    fun contact_and_search_pagination_keep_prior_results() = runTest {
        val controller = NqrbContactBookController(PagedGateway())
        val session = session("self")

        controller.load(session)
        controller.loadMoreContacts(session)
        controller.search(session, "friend")
        controller.loadMoreSearchResults(session)

        assertEquals(listOf("contact-1", "contact-2"), controller.state.value.contacts.map(NqrbContact::membershipId))
        assertEquals(listOf("search-1", "search-2"), controller.state.value.searchResults.map(NqrbContact::membershipId))
        assertEquals(false, controller.state.value.contactsHasMore)
        assertEquals(false, controller.state.value.searchHasMore)
    }

    @Test
    fun invite_link_preview_requires_confirmation_and_accept_refreshes_contacts() = runTest {
        val gateway = CountingGateway()
        val controller = NqrbContactBookController(gateway)
        val session = session("recipient")

        controller.updateInviteCodeInput("nqrb://invite/NQ-2345-6789")
        controller.previewInvite(session)

        assertEquals("NQ-2345-6789", controller.state.value.inviteCodeInput)
        assertEquals(NqrbContactInviteAcceptState.Confirming, controller.state.value.inviteAcceptState)
        assertEquals("Issuer", controller.state.value.invitePreview?.issuerDisplayName)

        controller.acceptInvite(session)

        assertEquals(NqrbContactInviteAcceptState.Accepted, controller.state.value.inviteAcceptState)
        assertEquals(1, gateway.accepts)
        assertEquals(1, gateway.loads)
    }

    @Test
    fun guest_call_invite_can_be_created_and_revoked_without_changing_contact_invite() = runTest {
        val gateway = CountingGateway()
        val controller = NqrbContactBookController(gateway)
        val session = session("host")

        controller.createGuestCallInvite(session)

        assertEquals(NqrbGuestCallInviteCreateState.Ready, controller.state.value.guestCallInviteCreateState)
        assertEquals("https://api.example/nqrb/guest-call/capability", controller.state.value.createdGuestCallInvite?.shareLink)
        assertEquals("2099-01-01T00:00:00Z", controller.state.value.createdGuestCallInvite?.expiresAtUtc)
        assertEquals(null, controller.state.value.createdInvite)

        controller.toggleGuestCallDetails()
        controller.createGuestCallInvite(session)
        assertEquals(false, controller.state.value.guestCallDetailsVisible)
        assertEquals(1, gateway.guestCreates)

        controller.revokeGuestCallInvite(session)

        assertEquals(NqrbGuestCallInviteCreateState.Idle, controller.state.value.guestCallInviteCreateState)
        assertEquals(null, controller.state.value.createdGuestCallInvite)
    }

    @Test
    fun overlapping_guest_link_requests_create_only_one_server_link() = runTest {
        val firstResult = CompletableDeferred<NqrbGuestCallInviteCreateResult>()
        var calls = 0
        val gateway = object : NqrbContactBookGateway by CountingGateway() {
            override suspend fun createGuestCallInvite(session: MobileSession): NqrbGuestCallInviteCreateResult {
                calls++
                return firstResult.await()
            }
        }
        val controller = NqrbContactBookController(gateway)
        val session = session("host")

        backgroundScope.launch { controller.createGuestCallInvite(session) }
        backgroundScope.launch { controller.createGuestCallInvite(session) }
        runCurrent()
        assertEquals(1, calls)

        firstResult.complete(NqrbGuestCallInviteCreateResult.Created(guestCallInvite()))
        runCurrent()
        assertEquals(1, calls)
        assertEquals(NqrbGuestCallInviteCreateState.Ready, controller.state.value.guestCallInviteCreateState)
    }

    @Test
    fun expired_authentication_shows_recoverable_errors_instead_of_endless_loading() = runTest {
        val controller = NqrbContactBookController(CountingGateway(authRequired = true))
        val session = session("host")

        controller.load(session)
        controller.createGuestCallInvite(session)

        assertEquals(NqrbContactBookLoadState.Error, controller.state.value.contactsState)
        assertEquals(NqrbGuestCallInviteCreateState.Error, controller.state.value.guestCallInviteCreateState)
    }

    @Test
    fun nickname_save_updates_the_visible_contact_without_a_second_request() = runTest {
        val gateway = CountingGateway()
        val controller = NqrbContactBookController(gateway)
        val session = session("host")
        controller.load(session)

        val saved = controller.updateNickname(session, "contact", "صديقي")

        assertEquals(true, saved)
        assertEquals("صديقي", controller.state.value.contacts.single().nickname)
        assertEquals(1, gateway.loads)
    }

    @Test
    fun incoming_call_can_resolve_a_saved_contact_beyond_the_visible_page() = runTest {
        val controller = NqrbContactBookController(CountingGateway())
        val session = session("host")
        controller.load(session)

        controller.resolveContactForCall(session, "later")

        assertEquals("صديقتي", controller.state.value.resolvedCallContacts["later"]?.nickname)
        assertEquals(listOf("contact"), controller.state.value.contacts.map(NqrbContact::membershipId))
    }

    @Test
    fun incoming_call_refreshes_a_contact_and_removes_an_alias_deleted_on_another_device() = runTest {
        val gateway = CountingGateway()
        val controller = NqrbContactBookController(gateway)
        val session = session("host")
        controller.load(session)

        controller.resolveContactForCall(session, "contact")
        assertEquals("صديقتي", controller.state.value.contacts.single().nickname)

        gateway.lookupAvailable = false
        controller.resolveContactForCall(session, "contact")
        assertEquals(NqrbContactBookLoadState.Empty, controller.state.value.contactsState)
        assertEquals(null, controller.state.value.resolvedCallContacts["contact"])
    }

    @Test
    fun removing_a_contact_clears_its_resolved_call_alias() = runTest {
        val controller = NqrbContactBookController(CountingGateway())
        val session = session("host")
        controller.resolveContactForCall(session, "later")

        controller.remove(session, "later")

        assertEquals(null, controller.state.value.resolvedCallContacts["later"])
    }

    @Test
    fun failed_guest_invite_retry_does_not_leave_an_expired_link_visible() = runTest {
        val gateway = CountingGateway()
        gateway.guestExpiry = "2000-01-01T00:00:00Z"
        val controller = NqrbContactBookController(gateway)
        val session = session("host")
        controller.createGuestCallInvite(session)

        gateway.guestCreateFails = true
        controller.createGuestCallInvite(session)

        assertEquals(NqrbGuestCallInviteCreateState.Error, controller.state.value.guestCallInviteCreateState)
        assertEquals(null, controller.state.value.createdGuestCallInvite)
    }

    private inner class DeferredGateway(
        private val first: CompletableDeferred<NqrbContactBookResult>,
        private val second: CompletableDeferred<NqrbContactBookResult>,
    ) : NqrbContactBookGateway {
        private var calls = 0
        override suspend fun listContacts(session: MobileSession, page: Int) =
            if (calls++ == 0) first.await() else second.await()
        override suspend fun searchUsers(session: MobileSession, query: String, page: Int) = page("search")
        override suspend fun addContact(session: MobileSession, membershipId: String) = NqrbContactMutationResult.Saved(NqrbContact(membershipId, "Saved"))
        override suspend fun addContactFromCallHistory(session: MobileSession, callId: String) = NqrbContactMutationResult.Saved(NqrbContact(callId, "Saved"))
        override suspend fun removeContact(session: MobileSession, membershipId: String) = NqrbContactMutationResult.Removed
        override suspend fun updateContactNickname(session: MobileSession, membershipId: String, nickname: String?) =
            NqrbContactMutationResult.Saved(NqrbContact(membershipId, "Saved", nickname))
        override suspend fun createInvite(session: MobileSession) = NqrbContactInviteCreateResult.Created(invite())
        override suspend fun createGuestCallInvite(session: MobileSession) = NqrbGuestCallInviteCreateResult.Created(guestCallInvite())
        override suspend fun revokeGuestCallInvite(session: MobileSession, inviteId: String) = NqrbGuestCallInviteRevokeResult.Revoked
        override suspend fun previewInvite(session: MobileSession, code: String) =
            NqrbContactInvitePreviewResult.Available(NqrbContactInvitePreview("Issuer"))
        override suspend fun acceptInvite(session: MobileSession, code: String) =
            NqrbContactInviteAcceptResult.Accepted(NqrbContact("issuer", "Issuer"))
    }

    private inner class CountingGateway(private val authRequired: Boolean = false) : NqrbContactBookGateway {
        private val blocks = mutableMapOf<String, MutableList<NqrbBlockedAccount>>()
        override suspend fun listBlockedAccounts(session: MobileSession) = NqrbBlockListResult.Available(
            blocks[session.identity.membershipId]?.toList().orEmpty(),
        )
        override suspend fun blockAccount(session: MobileSession, membershipId: String): Boolean {
            blocks.getOrPut(session.identity.membershipId) { mutableListOf() }.add(NqrbBlockedAccount(membershipId, "Other"))
            return true
        }
        override suspend fun unblockAccount(session: MobileSession, membershipId: String): Boolean {
            blocks[session.identity.membershipId]?.removeAll { it.membershipId == membershipId }
            return true
        }
        var searches = 0
        var loads = 0
        var accepts = 0
        var lookupAvailable = true
        var guestCreateFails = false
        var guestCreates = 0
        var guestExpiry = "2099-01-01T00:00:00Z"
        override suspend fun findContact(session: MobileSession, membershipId: String) =
            if (lookupAvailable) NqrbContactLookupResult.Found(NqrbContact(membershipId, "Public name", "صديقتي"))
            else NqrbContactLookupResult.Unavailable
        override suspend fun listContacts(session: MobileSession, page: Int): NqrbContactBookResult {
            loads++
            return if (authRequired) NqrbContactBookResult.AuthenticationRequired else page("contact")
        }
        override suspend fun searchUsers(session: MobileSession, query: String, page: Int): NqrbContactBookResult {
            searches++
            return page("search")
        }
        override suspend fun addContact(session: MobileSession, membershipId: String) = NqrbContactMutationResult.Saved(NqrbContact(membershipId, "Saved"))
        override suspend fun addContactFromCallHistory(session: MobileSession, callId: String) = NqrbContactMutationResult.Saved(NqrbContact(callId, "Saved"))
        override suspend fun removeContact(session: MobileSession, membershipId: String) = NqrbContactMutationResult.Removed
        override suspend fun updateContactNickname(session: MobileSession, membershipId: String, nickname: String?) =
            NqrbContactMutationResult.Saved(NqrbContact(membershipId, "Saved", nickname))
        override suspend fun createInvite(session: MobileSession) = NqrbContactInviteCreateResult.Created(invite())
        override suspend fun createGuestCallInvite(session: MobileSession): NqrbGuestCallInviteCreateResult {
            guestCreates++
            return if (authRequired) NqrbGuestCallInviteCreateResult.AuthenticationRequired
            else if (guestCreateFails) NqrbGuestCallInviteCreateResult.RetryableFailure
            else NqrbGuestCallInviteCreateResult.Created(guestCallInvite().copy(expiresAtUtc = guestExpiry))
        }
        override suspend fun revokeGuestCallInvite(session: MobileSession, inviteId: String) = NqrbGuestCallInviteRevokeResult.Revoked
        override suspend fun previewInvite(session: MobileSession, code: String) =
            NqrbContactInvitePreviewResult.Available(NqrbContactInvitePreview("Issuer"))
        override suspend fun acceptInvite(session: MobileSession, code: String): NqrbContactInviteAcceptResult {
            accepts++
            return NqrbContactInviteAcceptResult.Accepted(NqrbContact("issuer", "Issuer"))
        }
    }

    private inner class PagedGateway : NqrbContactBookGateway {
        override suspend fun listContacts(session: MobileSession, page: Int) = paged("contact", page)
        override suspend fun searchUsers(session: MobileSession, query: String, page: Int) = paged("search", page)
        override suspend fun addContact(session: MobileSession, membershipId: String) = NqrbContactMutationResult.Saved(NqrbContact(membershipId, "Saved"))
        override suspend fun addContactFromCallHistory(session: MobileSession, callId: String) = NqrbContactMutationResult.Saved(NqrbContact(callId, "Saved"))
        override suspend fun removeContact(session: MobileSession, membershipId: String) = NqrbContactMutationResult.Removed
        override suspend fun updateContactNickname(session: MobileSession, membershipId: String, nickname: String?) =
            NqrbContactMutationResult.Saved(NqrbContact(membershipId, "Saved", nickname))
        override suspend fun createInvite(session: MobileSession) = NqrbContactInviteCreateResult.Created(invite())
        override suspend fun createGuestCallInvite(session: MobileSession) = NqrbGuestCallInviteCreateResult.Created(guestCallInvite())
        override suspend fun revokeGuestCallInvite(session: MobileSession, inviteId: String) = NqrbGuestCallInviteRevokeResult.Revoked
        override suspend fun previewInvite(session: MobileSession, code: String) =
            NqrbContactInvitePreviewResult.Available(NqrbContactInvitePreview("Issuer"))
        override suspend fun acceptInvite(session: MobileSession, code: String) =
            NqrbContactInviteAcceptResult.Accepted(NqrbContact("issuer", "Issuer"))

        private fun paged(prefix: String, page: Int) = NqrbContactBookResult.Available(
            NqrbContactPage(listOf(NqrbContact("$prefix-$page", "Contact $page")), page, 1, page == 1),
        )
    }

    private fun page(id: String) = NqrbContactBookResult.Available(
        NqrbContactPage(listOf(NqrbContact(id, "Contact $id")), 1, 20, false),
    )

    private fun invite() = NqrbContactInvite(
        "NQ-2345-6789-ABCD-EFGH-JKLM-NPQR",
        "nqrb://invite/NQ-2345-6789-ABCD-EFGH-JKLM-NPQR",
        "2099-01-01T00:00:00Z",
    )

    private fun guestCallInvite() = NqrbGuestCallInvite(
        "10000000-0000-0000-0000-000000000001",
        "https://api.example/nqrb/guest-call/capability",
        "2099-01-01T00:00:00Z",
    )

    private fun session(membershipId: String) = MobileSession(
        "access-$membershipId",
        "2099-01-01T00:00:00Z",
        "refresh-$membershipId",
        "2099-02-01T00:00:00Z",
        ApplicationIdentity(membershipId, "subject-$membershipId", "User $membershipId", IdentityKind.Registered, "nqrb"),
    )
}
