package com.botglobal.nqrb.app.ui

import com.botglobal.mobile.platform.calling.CallDeliveryState
import com.botglobal.mobile.platform.calling.CallDeliveryStatus
import com.botglobal.mobile.platform.calling.CallDirection
import com.botglobal.mobile.platform.calling.CallId
import com.botglobal.mobile.platform.calling.CallNetworkUsage
import com.botglobal.mobile.platform.calling.CallSessionSnapshot
import com.botglobal.mobile.platform.calling.CallState
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallingParticipantAvailability
import com.botglobal.mobile.platform.presence.PresenceEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NqrbCallPresentationTests {
    private val english = nqrbStrings("en")
    private val arabic = nqrbStrings("ar")

    @Test
    fun outgoing_copy_moves_from_checking_to_trying_then_waiting_only_after_receipt() {
        assertEquals(english.checkingAvailability, present(CallState.Preparing).status)
        assertEquals("Call has not reached their device yet", present(CallState.Connecting).status)
        assertEquals("Call has not reached their device yet", present(CallState.Ringing).status)
        assertEquals(
            arabic.tryingToReach,
            nqrbCallPresentation(arabic, CallSessionSnapshot(direction = CallDirection.Outgoing, state = CallState.Ringing)).status,
        )
        assertEquals(
            english.waitingAfterReceipt,
            present(
                CallState.Ringing,
                CallDeliveryStatus(CallId("call"), CallDeliveryState.Presented, revision = 2),
            ).status,
        )
    }

    @Test
    fun stronger_incoming_active_and_reconnecting_states_outrank_delivery_copy() {
        val receipt = CallDeliveryStatus(CallId("call"), CallDeliveryState.Presented, revision = 2)
        assertEquals(english.incomingCall, present(CallState.Ringing, receipt, CallDirection.Incoming).status)
        assertEquals(english.activeCall, present(CallState.Active, receipt).status)
        assertEquals(english.reconnecting, present(CallState.Reconnecting, receipt).status)
    }

    @Test
    fun unavailable_unverified_and_no_answer_are_distinct_in_both_languages() {
        val unavailable = CallSessionSnapshot(
            direction = CallDirection.Outgoing,
            state = CallState.Failed,
            error = "call_peer_unavailable",
        )
        val noAnswer = CallSessionSnapshot(
            direction = CallDirection.Outgoing,
            state = CallState.Expired,
            terminationReason = CallTerminationReason.Expired,
            delivery = CallDeliveryStatus(
                CallId("received"), CallDeliveryState.Terminal, terminal = true, wasPresented = true,
            ),
        )
        val unableToReach = CallSessionSnapshot(
            direction = CallDirection.Outgoing,
            state = CallState.Expired,
            terminationReason = CallTerminationReason.Expired,
        )

        assertEquals(english.callUnavailable, nqrbCallPresentation(english, unavailable).status)
        assertEquals(arabic.callUnavailable, nqrbCallPresentation(arabic, unavailable).status)
        assertEquals(english.noAnswer, nqrbCallPresentation(english, noAnswer).status)
        assertEquals(arabic.noAnswer, nqrbCallPresentation(arabic, noAnswer).status)
        assertEquals(english.unableToReach, nqrbCallPresentation(english, unableToReach).status)
        assertEquals(arabic.unableToReach, nqrbCallPresentation(arabic, unableToReach).status)
        assertEquals(
            english.unableToReach,
            nqrbCallPresentation(
                english,
                CallSessionSnapshot(direction = CallDirection.Outgoing, state = CallState.Ended),
            ).status,
        )
        assertEquals(
            english.callEnded,
            nqrbCallPresentation(
                english,
                CallSessionSnapshot(
                    direction = CallDirection.Outgoing,
                    state = CallState.Ended,
                    networkUsage = CallNetworkUsage(connectedAtEpochMillis = 5),
                ),
            ).status,
        )
        assertFalse(english.callUnavailable.contains("Firebase", ignoreCase = true))
        assertFalse(arabic.availabilityUnverified.contains("Firebase", ignoreCase = true))
    }

    @Test
    fun cached_wire_offline_is_not_presented_as_authoritative_unavailable() {
        val cached = CallableParticipant(
            "member", "Person", CallingParticipantAvailability.Offline,
            presenceEvidence = PresenceEvidence.Unknown,
        )
        val verified = cached.copy(
            presenceEvidence = PresenceEvidence.Disconnected,
            presenceObservedAtEpochMillis = 100_000,
            presenceFullyCovered = true,
        )

        assertEquals(english.availabilityUnverified, nqrbDirectoryAvailability(english, cached, nowEpochMillis = 100_000))
        assertEquals(english.currentlyUnavailable, nqrbDirectoryAvailability(english, verified, nowEpochMillis = 100_000))
        assertEquals(english.availabilityUnverified, nqrbDirectoryAvailability(english, verified, nowEpochMillis = 150_000))
        assertTrue(nqrbCallPresentation(english, presentSnapshot(CallState.Ended)).terminal)
    }

    private fun present(
        state: CallState,
        delivery: CallDeliveryStatus? = null,
        direction: CallDirection = CallDirection.Outgoing,
    ) = nqrbCallPresentation(english, CallSessionSnapshot(direction = direction, state = state, delivery = delivery))

    private fun presentSnapshot(state: CallState) = CallSessionSnapshot(direction = CallDirection.Outgoing, state = state)
}
