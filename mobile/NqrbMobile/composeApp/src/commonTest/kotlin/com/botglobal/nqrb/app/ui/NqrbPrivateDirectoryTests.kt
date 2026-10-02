package com.botglobal.nqrb.app.ui

import com.botglobal.mobile.platform.calling.CallHistoryItem
import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallingParticipantAvailability
import com.botglobal.mobile.platform.calling.CallingDirectorySnapshot
import com.botglobal.mobile.platform.calling.CallingDirectoryStatus
import com.botglobal.nqrb.app.data.NqrbContact
import com.botglobal.nqrb.app.data.NqrbBlockedAccount
import com.botglobal.nqrb.app.state.NqrbContactBookLoadState
import com.botglobal.nqrb.app.state.NqrbContactBookSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import androidx.compose.ui.unit.LayoutDirection
import kotlin.time.Instant

class NqrbPrivateDirectoryTests {
    private val serverDirectory = CallingDirectorySnapshot(
        CallingDirectoryStatus.Ready,
        listOf(CallableParticipant("saved", "Friend"), CallableParticipant("other", "Stranger")),
    )

    @Test
    fun homeNeverShowsGlobalDirectoryEntriesOutsideThisAccountsContacts() {
        val book = NqrbContactBookSnapshot(
            contactsState = NqrbContactBookLoadState.Ready,
            contacts = listOf(NqrbContact("saved", "Friend")),
        )
        val visible = privateCallingDirectory(serverDirectory, book)

        assertEquals(listOf("saved"), visible.participants.map { it.membershipId })
        assertEquals(CallingDirectoryStatus.Ready, visible.status)
    }

    @Test
    fun blocked_saved_account_disappears_from_home_call_actions() {
        val book = NqrbContactBookSnapshot(
            contactsState = NqrbContactBookLoadState.Ready,
            contacts = listOf(NqrbContact("saved", "Friend")),
            blockedAccounts = listOf(NqrbBlockedAccount("saved", "Friend")),
        )
        val visible = privateCallingDirectory(serverDirectory, book)

        assertTrue(visible.participants.isEmpty())
        assertEquals(CallingDirectoryStatus.Empty, visible.status)
    }

    @Test
    fun homeFailsClosedWhenPrivateListCannotLoad() {
        val visible = privateCallingDirectory(serverDirectory, NqrbContactBookSnapshot(contactsState = NqrbContactBookLoadState.Error))

        assertTrue(visible.participants.isEmpty())
        assertEquals(CallingDirectoryStatus.Error, visible.status)
    }

    @Test
    fun call_swipe_uses_the_visible_direction_of_each_language() {
        assertTrue(shouldStartCallFromSwipe(80f, 80f, LayoutDirection.Rtl))
        assertFalse(shouldStartCallFromSwipe(-100f, 80f, LayoutDirection.Rtl))
        assertTrue(shouldStartCallFromSwipe(-80f, 80f, LayoutDirection.Ltr))
        assertFalse(shouldStartCallFromSwipe(100f, 80f, LayoutDirection.Ltr))
        assertFalse(shouldStartCallFromSwipe(40f, 80f, LayoutDirection.Rtl))
    }

    @Test
    fun history_add_contact_is_only_offered_for_a_known_unsaved_person() {
        assertTrue(shouldShowHistoryContactAdd(false, false, true, false))
        assertFalse(shouldShowHistoryContactAdd(false, true, true, false))
        assertFalse(shouldShowHistoryContactAdd(false, null, true, false))
        assertFalse(shouldShowHistoryContactAdd(true, false, true, false))
        assertFalse(shouldShowHistoryContactAdd(false, false, false, false))
        assertFalse(shouldShowHistoryContactAdd(false, false, true, true))
    }

    @Test
    fun history_swipe_calls_an_unsaved_server_eligible_counterpart() {
        val item = CallHistoryItem(
            callId = "call-1", direction = "incoming", participantDisplayName = "Bero Ashraf",
            outcome = "completed", startedAtUtc = "2026-10-02T12:00:00Z",
            connectedDurationSeconds = 10, totalBytes = 100,
            isSavedContact = false, counterpartMembershipId = "bero",
            canRedial = true, canAddContact = true,
        )

        val callable = callableHistoryParticipant(item)

        assertEquals("bero", callable?.membershipId)
        assertEquals("Bero Ashraf", callable?.displayName)
        assertEquals(CallingParticipantAvailability.Reachable, callable?.availability)
        assertEquals(null, callableHistoryParticipant(item.copy(canRedial = false)))
        assertEquals("bero", callableHistoryParticipant(item.copy(isSavedContact = true, canRedial = false))?.membershipId)
        assertEquals(null, callableHistoryParticipant(item.copy(isGuestCall = true)))
        assertEquals(null, callableHistoryParticipant(item.copy(counterpartMembershipId = null)))
    }

    @Test
    fun private_nickname_is_presented_locally_with_public_name_fallback() {
        val book = NqrbContactBookSnapshot(
            contactsState = NqrbContactBookLoadState.Ready,
            contacts = listOf(NqrbContact("saved", "Public friend", "صديقي")),
        )

        assertEquals("صديقي", privateContactDisplayName(book, "saved", "Public friend"))
        assertEquals("Guest", privateContactDisplayName(book, "guest", "Guest"))
        assertEquals("Public friend", privateContactDisplayName(book.copy(contacts = listOf(NqrbContact("saved", "Public friend"))), "saved", "Public friend"))
        assertEquals("صديقي", privateContactDisplayName(
            book.copy(contacts = emptyList(), resolvedCallContacts = mapOf("saved" to NqrbContact("saved", "Public friend", "صديقي"))),
            "saved", "Public friend",
        ))
        assertEquals("Friend", privateCallingDirectory(serverDirectory, book).participants.single().displayName)
    }

    @Test
    fun expired_guest_links_cannot_be_offered_for_sharing() {
        val now = Instant.parse("2026-10-02T00:15:00Z")

        assertFalse(isGuestCallInviteExpired("2026-10-02T00:15:01Z", now))
        assertTrue(isGuestCallInviteExpired("2026-10-02T00:15:00Z", now))
        assertTrue(isGuestCallInviteExpired("invalid", now))
    }
}
