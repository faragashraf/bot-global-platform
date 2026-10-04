package com.botglobal.nqrb.app.state

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
import com.botglobal.nqrb.app.data.NqrbGuestCallInvite
import com.botglobal.nqrb.app.data.NqrbGuestCallInviteCreateResult
import com.botglobal.nqrb.app.data.NqrbGuestCallInviteRevokeResult
import com.botglobal.nqrb.app.data.UnavailableNqrbContactBookGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

enum class NqrbContactBookLoadState { Idle, Loading, Ready, Empty, Error }
enum class NqrbContactSearchState { Idle, Loading, Ready, Empty, Error, TooShort }
enum class NqrbContactMutationState { Idle, Saving, Removing, Error }
enum class NqrbBlockState { Idle, Loading, Ready, Working, Error }
enum class NqrbContactInviteCreateState { Idle, Creating, Ready, Error }
enum class NqrbGuestCallInviteCreateState { Idle, Creating, Ready, Revoking, Error }
enum class NqrbContactInviteAcceptState {
    Idle,
    Previewing,
    Confirming,
    Accepting,
    Accepted,
    Invalid,
    Expired,
    SelfInvite,
    AlreadyClaimed,
    Error,
}

data class NqrbContactBookSnapshot(
    val blockedAccounts: List<NqrbBlockedAccount> = emptyList(),
    val blockState: NqrbBlockState = NqrbBlockState.Idle,
    val blockErrorIsLoad: Boolean = false,
    val contactsState: NqrbContactBookLoadState = NqrbContactBookLoadState.Idle,
    val contactsRefreshing: Boolean = false,
    val contactsRefreshFailed: Boolean = false,
    val contacts: List<NqrbContact> = emptyList(),
    val resolvedCallContacts: Map<String, NqrbContact> = emptyMap(),
    val contactsPage: Int = 0,
    val contactsHasMore: Boolean = false,
    val contactsLoadingMore: Boolean = false,
    val searchState: NqrbContactSearchState = NqrbContactSearchState.Idle,
    val searchQuery: String = "",
    val searchResults: List<NqrbContact> = emptyList(),
    val searchPage: Int = 0,
    val searchHasMore: Boolean = false,
    val searchLoadingMore: Boolean = false,
    val mutationState: NqrbContactMutationState = NqrbContactMutationState.Idle,
    val inviteCreateState: NqrbContactInviteCreateState = NqrbContactInviteCreateState.Idle,
    val createdInvite: NqrbContactInvite? = null,
    val guestCallInviteCreateState: NqrbGuestCallInviteCreateState = NqrbGuestCallInviteCreateState.Idle,
    val createdGuestCallInvite: NqrbGuestCallInvite? = null,
    val accountInviteDetailsVisible: Boolean = true,
    val guestCallDetailsVisible: Boolean = true,
    val inviteCodeInput: String = "",
    val inviteAcceptState: NqrbContactInviteAcceptState = NqrbContactInviteAcceptState.Idle,
    val invitePreview: NqrbContactInvitePreview? = null,
)

class NqrbContactBookController(
    private val gateway: NqrbContactBookGateway = UnavailableNqrbContactBookGateway,
) {
    private val mutex = Mutex()
    private val guestCreateMutex = Mutex()
    private var generation = 0L
    private var sessionKey: String? = null
    private val requestVersions = mutableMapOf<ContactBookRequestLane, Long>()
    private val mutableState = MutableStateFlow(NqrbContactBookSnapshot())
    val state = mutableState.asStateFlow()

    suspend fun loadBlockedAccounts(session: MobileSession) {
        val request = begin(session, ContactBookRequestLane.Blocks) { copy(blockState = NqrbBlockState.Loading, blockErrorIsLoad = false) }
        when (val result = gateway.listBlockedAccounts(session)) {
            is NqrbBlockListResult.Available -> apply(request) {
                copy(blockedAccounts = result.accounts, blockState = NqrbBlockState.Ready, blockErrorIsLoad = false)
            }
            NqrbBlockListResult.Failed -> apply(request) { copy(blockState = NqrbBlockState.Error, blockErrorIsLoad = true) }
        }
    }

    suspend fun block(session: MobileSession, membershipId: String): Boolean {
        val request = begin(session, ContactBookRequestLane.Blocks) { copy(blockState = NqrbBlockState.Working) }
        if (!gateway.blockAccount(session, membershipId)) {
            apply(request) { copy(blockState = NqrbBlockState.Error, blockErrorIsLoad = false) }
            return false
        }
        apply(request) {
            val contact = contacts.firstOrNull { it.membershipId == membershipId }
                ?: searchResults.firstOrNull { it.membershipId == membershipId }
            copy(
                blockedAccounts = (blockedAccounts + NqrbBlockedAccount(membershipId, contact?.displayName ?: "Nqrb account"))
                    .distinctBy { it.membershipId },
                blockState = NqrbBlockState.Ready,
            )
        }
        return true
    }

    suspend fun unblock(session: MobileSession, membershipId: String): Boolean {
        val request = begin(session, ContactBookRequestLane.Blocks) { copy(blockState = NqrbBlockState.Working) }
        if (!gateway.unblockAccount(session, membershipId)) {
            apply(request) { copy(blockState = NqrbBlockState.Error, blockErrorIsLoad = false) }
            return false
        }
        apply(request) {
            copy(blockedAccounts = blockedAccounts.filterNot { it.membershipId == membershipId }, blockState = NqrbBlockState.Ready)
        }
        return true
    }

    suspend fun resolveContactForCall(session: MobileSession, membershipId: String) {
        if (membershipId.isBlank()) return
        val request = begin(session, ContactBookRequestLane.CallContact) { this }
        when (val result = gateway.findContact(session, membershipId)) {
            is NqrbContactLookupResult.Found -> apply(request) {
                copy(
                    contacts = contacts.map { if (it.membershipId == membershipId) result.contact else it },
                    resolvedCallContacts = resolvedCallContacts + (membershipId to result.contact),
                )
            }
            NqrbContactLookupResult.Unavailable -> apply(request) {
                val remaining = contacts.filterNot { it.membershipId == membershipId }
                copy(
                    contacts = remaining,
                    contactsState = if (remaining.isEmpty() && contactsState == NqrbContactBookLoadState.Ready) {
                        NqrbContactBookLoadState.Empty
                    } else contactsState,
                    resolvedCallContacts = resolvedCallContacts - membershipId,
                )
            }
            else -> Unit
        }
    }

    suspend fun load(session: MobileSession) {
        val request = begin(session, ContactBookRequestLane.Contacts) {
            val canRefreshInBackground = contactsState in setOf(
                NqrbContactBookLoadState.Ready,
                NqrbContactBookLoadState.Empty,
            )
            copy(
                contactsState = if (canRefreshInBackground) contactsState else NqrbContactBookLoadState.Loading,
                contactsRefreshing = canRefreshInBackground,
                contactsLoadingMore = false,
                contactsRefreshFailed = false,
            )
        }
        val result = gateway.listContacts(session, 1)
        apply(request) {
            when (result) {
                is NqrbContactBookResult.Available -> copy(
                    contactsState = if (result.page.items.isEmpty()) {
                        NqrbContactBookLoadState.Empty
                    } else {
                        NqrbContactBookLoadState.Ready
                    },
                    contacts = result.page.items,
                    contactsPage = result.page.page,
                    contactsHasMore = result.page.hasMore,
                    contactsRefreshing = false,
                    contactsRefreshFailed = false,
                )
                NqrbContactBookResult.AuthenticationRequired -> copy(
                    contactsState = NqrbContactBookLoadState.Error,
                    contactsRefreshing = false,
                )
                NqrbContactBookResult.RetryableFailure -> copy(
                    contactsState = if (contacts.isEmpty()) NqrbContactBookLoadState.Error else NqrbContactBookLoadState.Ready,
                    contactsRefreshing = false,
                    contactsRefreshFailed = contacts.isNotEmpty(),
                )
            }
        }
    }

    suspend fun loadMoreContacts(session: MobileSession) {
        val previous = state.value
        if (previous.contactsState !in setOf(NqrbContactBookLoadState.Ready, NqrbContactBookLoadState.Empty) ||
            !previous.contactsHasMore || previous.contactsLoadingMore
        ) return
        val request = begin(session, ContactBookRequestLane.Contacts) { copy(contactsLoadingMore = true) }
        val result = gateway.listContacts(session, previous.contactsPage + 1)
        apply(request) {
            when (result) {
                is NqrbContactBookResult.Available -> {
                    val merged = (contacts + result.page.items).distinctBy(NqrbContact::membershipId)
                    copy(
                        contactsState = if (merged.isEmpty()) NqrbContactBookLoadState.Empty else NqrbContactBookLoadState.Ready,
                        contacts = merged,
                        contactsPage = result.page.page,
                        contactsHasMore = result.page.hasMore,
                        contactsLoadingMore = false,
                    )
                }
                NqrbContactBookResult.AuthenticationRequired -> copy(contactsLoadingMore = false, contactsState = NqrbContactBookLoadState.Error)
                NqrbContactBookResult.RetryableFailure -> copy(contactsLoadingMore = false)
            }
        }
    }

    suspend fun search(session: MobileSession, query: String) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            begin(session, ContactBookRequestLane.Search) {
                copy(
                    searchQuery = normalized,
                    searchState = if (normalized.isEmpty()) NqrbContactSearchState.Idle else NqrbContactSearchState.TooShort,
                    searchResults = emptyList(),
                    searchPage = 0,
                    searchHasMore = false,
                    searchLoadingMore = false,
                )
            }
            return
        }
        val request = begin(session, ContactBookRequestLane.Search) {
            copy(
                searchQuery = normalized,
                searchState = NqrbContactSearchState.Loading,
                searchResults = emptyList(),
                searchPage = 0,
                searchHasMore = false,
                searchLoadingMore = false,
            )
        }
        val result = gateway.searchUsers(session, normalized, 1)
        apply(request) {
            when (result) {
                is NqrbContactBookResult.Available -> copy(
                    searchState = if (result.page.items.isEmpty()) {
                        NqrbContactSearchState.Empty
                    } else {
                        NqrbContactSearchState.Ready
                    },
                    searchResults = result.page.items,
                    searchPage = result.page.page,
                    searchHasMore = result.page.hasMore,
                )
                NqrbContactBookResult.AuthenticationRequired -> copy(searchState = NqrbContactSearchState.Error)
                NqrbContactBookResult.RetryableFailure -> copy(searchState = NqrbContactSearchState.Error)
            }
        }
    }

    suspend fun loadMoreSearchResults(session: MobileSession) {
        val previous = state.value
        if (previous.searchState != NqrbContactSearchState.Ready ||
            !previous.searchHasMore || previous.searchLoadingMore
        ) return
        val request = begin(session, ContactBookRequestLane.Search) { copy(searchLoadingMore = true) }
        val result = gateway.searchUsers(session, previous.searchQuery, previous.searchPage + 1)
        apply(request) {
            when (result) {
                is NqrbContactBookResult.Available -> copy(
                    searchResults = (searchResults + result.page.items).distinctBy(NqrbContact::membershipId),
                    searchPage = result.page.page,
                    searchHasMore = result.page.hasMore,
                    searchLoadingMore = false,
                )
                NqrbContactBookResult.AuthenticationRequired -> copy(searchLoadingMore = false, searchState = NqrbContactSearchState.Error)
                NqrbContactBookResult.RetryableFailure -> copy(searchLoadingMore = false)
            }
        }
    }

    suspend fun add(session: MobileSession, membershipId: String): Boolean {
        return saveContact(session) { gateway.addContact(session, membershipId) }
    }

    suspend fun addFromCallHistory(session: MobileSession, callId: String): Boolean {
        return saveContact(session) { gateway.addContactFromCallHistory(session, callId) }
    }

    private suspend fun saveContact(
        session: MobileSession,
        action: suspend () -> NqrbContactMutationResult,
    ): Boolean {
        val request = begin(session, ContactBookRequestLane.Mutation) { copy(mutationState = NqrbContactMutationState.Saving) }
        return try {
            when (action()) {
                is NqrbContactMutationResult.Saved -> {
                    apply(request) { copy(mutationState = NqrbContactMutationState.Idle) }
                    load(session)
                    true
                }
                NqrbContactMutationResult.AuthenticationRequired -> {
                    apply(request) { copy(mutationState = NqrbContactMutationState.Error) }
                    false
                }
                else -> {
                    apply(request) { copy(mutationState = NqrbContactMutationState.Error) }
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    suspend fun remove(session: MobileSession, membershipId: String): Boolean {
        val request = begin(session, ContactBookRequestLane.Mutation) { copy(mutationState = NqrbContactMutationState.Removing) }
        return try {
            when (gateway.removeContact(session, membershipId)) {
                NqrbContactMutationResult.Removed -> {
                    apply(request) {
                        copy(
                            mutationState = NqrbContactMutationState.Idle,
                            resolvedCallContacts = resolvedCallContacts - membershipId,
                        )
                    }
                    load(session)
                    true
                }
                NqrbContactMutationResult.AuthenticationRequired -> {
                    apply(request) { copy(mutationState = NqrbContactMutationState.Error) }
                    false
                }
                else -> {
                    apply(request) { copy(mutationState = NqrbContactMutationState.Error) }
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    suspend fun updateNickname(session: MobileSession, membershipId: String, nickname: String?): Boolean {
        val request = begin(session, ContactBookRequestLane.Mutation) { copy(mutationState = NqrbContactMutationState.Saving) }
        return try {
            when (val result = gateway.updateContactNickname(session, membershipId, nickname)) {
                is NqrbContactMutationResult.Saved -> {
                    apply(request) {
                        copy(
                            mutationState = NqrbContactMutationState.Idle,
                            contacts = contacts.map { if (it.membershipId == membershipId) result.contact else it },
                            resolvedCallContacts = if (membershipId in resolvedCallContacts) {
                                resolvedCallContacts + (membershipId to result.contact)
                            } else resolvedCallContacts,
                        )
                    }
                    true
                }
                else -> {
                    apply(request) { copy(mutationState = NqrbContactMutationState.Error) }
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    suspend fun createInvite(session: MobileSession): Boolean {
        val request = begin(session, ContactBookRequestLane.InviteCreation) { copy(inviteCreateState = NqrbContactInviteCreateState.Creating) }
        return try {
            when (val result = gateway.createInvite(session)) {
                is NqrbContactInviteCreateResult.Created -> {
                    apply(request) {
                        copy(
                            inviteCreateState = NqrbContactInviteCreateState.Ready,
                            createdInvite = result.invite,
                            accountInviteDetailsVisible = true,
                        )
                    }
                    true
                }
                NqrbContactInviteCreateResult.AuthenticationRequired -> {
                    apply(request) { copy(inviteCreateState = NqrbContactInviteCreateState.Error) }
                    false
                }
                NqrbContactInviteCreateResult.RetryableFailure -> {
                    apply(request) { copy(inviteCreateState = NqrbContactInviteCreateState.Error) }
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    suspend fun createGuestCallInvite(session: MobileSession): Boolean = guestCreateMutex.withLock {
        val currentInvite = state.value.createdGuestCallInvite
        if (sessionKey == session.identity.membershipId && currentInvite != null && runCatching {
                Instant.parse(currentInvite.expiresAtUtc) > Clock.System.now()
            }.getOrDefault(false)) return@withLock true
        val request = begin(session, ContactBookRequestLane.GuestCallInvite) {
            copy(
                guestCallInviteCreateState = NqrbGuestCallInviteCreateState.Creating,
                createdGuestCallInvite = null,
            )
        }
        return@withLock try {
            when (val result = gateway.createGuestCallInvite(session)) {
                is NqrbGuestCallInviteCreateResult.Created -> {
                    apply(request) {
                        copy(
                            guestCallInviteCreateState = NqrbGuestCallInviteCreateState.Ready,
                            createdGuestCallInvite = result.invite,
                            guestCallDetailsVisible = true,
                        )
                    }
                    true
                }
                NqrbGuestCallInviteCreateResult.AuthenticationRequired -> {
                    apply(request) { copy(guestCallInviteCreateState = NqrbGuestCallInviteCreateState.Error) }
                    false
                }
                NqrbGuestCallInviteCreateResult.RetryableFailure -> {
                    apply(request) { copy(guestCallInviteCreateState = NqrbGuestCallInviteCreateState.Error) }
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    suspend fun revokeGuestCallInvite(session: MobileSession): Boolean {
        val inviteId = state.value.createdGuestCallInvite?.inviteId ?: return false
        val request = begin(session, ContactBookRequestLane.GuestCallInvite) {
            copy(guestCallInviteCreateState = NqrbGuestCallInviteCreateState.Revoking)
        }
        return try {
            when (gateway.revokeGuestCallInvite(session, inviteId)) {
                NqrbGuestCallInviteRevokeResult.Revoked -> {
                    apply(request) {
                        copy(
                            guestCallInviteCreateState = NqrbGuestCallInviteCreateState.Idle,
                            createdGuestCallInvite = null,
                        )
                    }
                    true
                }
                NqrbGuestCallInviteRevokeResult.AuthenticationRequired -> {
                    apply(request) { copy(guestCallInviteCreateState = NqrbGuestCallInviteCreateState.Error) }
                    false
                }
                NqrbGuestCallInviteRevokeResult.RetryableFailure -> {
                    apply(request) { copy(guestCallInviteCreateState = NqrbGuestCallInviteCreateState.Error) }
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    suspend fun previewInvite(session: MobileSession, code: String = state.value.inviteCodeInput): Boolean {
        val normalized = normalizeInviteCode(code)
        if (normalized.isBlank()) {
            begin(session, ContactBookRequestLane.InviteAcceptance) {
                copy(
                    inviteCodeInput = "",
                    inviteAcceptState = NqrbContactInviteAcceptState.Invalid,
                    invitePreview = null,
                )
            }
            return false
        }

        val request = begin(session, ContactBookRequestLane.InviteAcceptance) {
            copy(
                inviteCodeInput = normalized,
                inviteAcceptState = NqrbContactInviteAcceptState.Previewing,
                invitePreview = null,
            )
        }
        return try {
            when (val result = gateway.previewInvite(session, normalized)) {
                is NqrbContactInvitePreviewResult.Available -> {
                    apply(request) {
                        copy(
                            inviteAcceptState = NqrbContactInviteAcceptState.Confirming,
                            invitePreview = result.preview,
                        )
                    }
                    true
                }
                NqrbContactInvitePreviewResult.AuthenticationRequired -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.Error) }
                    false
                }
                NqrbContactInvitePreviewResult.Invalid -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.Invalid) }
                    false
                }
                NqrbContactInvitePreviewResult.Expired -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.Expired) }
                    false
                }
                NqrbContactInvitePreviewResult.SelfInvite -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.SelfInvite) }
                    false
                }
                NqrbContactInvitePreviewResult.AlreadyClaimed -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.AlreadyClaimed) }
                    false
                }
                NqrbContactInvitePreviewResult.RetryableFailure -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.Error) }
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    suspend fun acceptInvite(session: MobileSession): Boolean {
        val code = state.value.inviteCodeInput
        val request = begin(session, ContactBookRequestLane.InviteAcceptance) { copy(inviteAcceptState = NqrbContactInviteAcceptState.Accepting) }
        return try {
            when (gateway.acceptInvite(session, code)) {
                is NqrbContactInviteAcceptResult.Accepted -> {
                    apply(request) {
                        copy(
                            inviteAcceptState = NqrbContactInviteAcceptState.Accepted,
                            invitePreview = null,
                            inviteCodeInput = "",
                        )
                    }
                    load(session)
                    true
                }
                NqrbContactInviteAcceptResult.AuthenticationRequired -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.Error) }
                    false
                }
                NqrbContactInviteAcceptResult.Invalid -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.Invalid) }
                    false
                }
                NqrbContactInviteAcceptResult.Expired -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.Expired) }
                    false
                }
                NqrbContactInviteAcceptResult.SelfInvite -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.SelfInvite) }
                    false
                }
                NqrbContactInviteAcceptResult.AlreadyClaimed -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.AlreadyClaimed) }
                    false
                }
                NqrbContactInviteAcceptResult.RetryableFailure -> {
                    apply(request) { copy(inviteAcceptState = NqrbContactInviteAcceptState.Error) }
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    fun updateInviteCodeInput(value: String) {
        val normalized = normalizeInviteCode(value)
        invalidate(ContactBookRequestLane.InviteAcceptance)
        mutableState.value = mutableState.value.copy(
            inviteCodeInput = normalized,
            invitePreview = null,
            inviteAcceptState = NqrbContactInviteAcceptState.Idle,
        )
    }

    fun toggleAccountInviteDetails() {
        mutableState.value = mutableState.value.copy(
            accountInviteDetailsVisible = !mutableState.value.accountInviteDetailsVisible,
        )
    }

    fun toggleGuestCallDetails() {
        mutableState.value = mutableState.value.copy(
            guestCallDetailsVisible = !mutableState.value.guestCallDetailsVisible,
        )
    }

    fun clearInvitePreview() {
        invalidate(ContactBookRequestLane.InviteAcceptance)
        mutableState.value = mutableState.value.copy(
            inviteAcceptState = NqrbContactInviteAcceptState.Idle,
            invitePreview = null,
        )
    }

    fun clear() {
        generation++
        sessionKey = null
        requestVersions.clear()
        mutableState.value = NqrbContactBookSnapshot()
    }

    private suspend fun begin(
        session: MobileSession,
        lane: ContactBookRequestLane,
        update: NqrbContactBookSnapshot.() -> NqrbContactBookSnapshot,
    ): ContactBookRequest = mutex.withLock {
        val key = session.identity.membershipId
        if (sessionKey != key) {
            generation++
            sessionKey = key
            requestVersions.clear()
            mutableState.value = NqrbContactBookSnapshot()
        }
        val version = (requestVersions[lane] ?: 0L) + 1
        requestVersions[lane] = version
        val request = ContactBookRequest(generation, key, lane, version)
        mutableState.value = mutableState.value.update()
        request
    }

    private fun invalidate(lane: ContactBookRequestLane) {
        requestVersions[lane] = (requestVersions[lane] ?: 0L) + 1
    }

    private suspend fun apply(
        request: ContactBookRequest,
        update: NqrbContactBookSnapshot.() -> NqrbContactBookSnapshot,
    ) = mutex.withLock {
        if (request.generation == generation &&
            request.sessionKey == sessionKey &&
            request.version == requestVersions[request.lane]
        ) {
            mutableState.value = mutableState.value.update()
        }
    }
}

private data class ContactBookRequest(
    val generation: Long,
    val sessionKey: String,
    val lane: ContactBookRequestLane,
    val version: Long,
)

private enum class ContactBookRequestLane { Contacts, CallContact, Search, Mutation, Blocks, InviteCreation, GuestCallInvite, InviteAcceptance }

internal fun normalizeInviteCode(value: String): String {
    val trimmed = value.trim()
    val fromLink = Regex("""(?i)nqrb://invite/([^?#\s]+)""")
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
    return (fromLink ?: trimmed)
        .replace("%2D", "-", ignoreCase = true)
        .replace("%20", " ", ignoreCase = true)
        .trim()
        .uppercase()
}
