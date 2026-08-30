package com.botglobal.mobile.platform.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedVoiceConsentControllerTests {
    @Test
    fun request_is_idempotent_and_waits_without_starting_media() = runTest {
        val signaling = FakeConsentSignaling()
        val controller = ManagedVoiceConsentController(backgroundScope, signaling)
        controller.bind("room", 1)

        controller.request()
        controller.request()

        assertEquals(1, signaling.requests)
        assertEquals(VoiceConsentState.Requesting, controller.snapshot.value.state)
    }

    @Test
    fun incoming_request_accepts_once_and_transitions_to_joining_only_when_media_starts() = runTest {
        val signaling = FakeConsentSignaling()
        val controller = ManagedVoiceConsentController(backgroundScope, signaling)
        controller.bind("room", 1)
        runCurrent()
        signaling.events.emit(signalRequested())
        runCurrent()

        controller.accept()
        controller.accept()
        assertEquals(1, signaling.accepts)
        assertEquals(VoiceConsentState.Accepted, controller.snapshot.value.state)

        controller.mediaStateChanged(VoiceRoomSnapshot(state = VoiceRoomState.Negotiating))
        assertEquals(VoiceConsentState.Joining, controller.snapshot.value.state)
    }

    @Test
    fun decline_timeout_and_cancel_never_enter_joining() = runTest {
        val signaling = FakeConsentSignaling()
        val controller = ManagedVoiceConsentController(backgroundScope, signaling)
        controller.bind("room", 1)
        runCurrent()
        signaling.events.emit(signalRequested())
        runCurrent()
        controller.decline()
        assertEquals(VoiceConsentState.Declined, controller.snapshot.value.state)

        controller.bind("room", 2)
        signaling.events.emit(signalRequested(match = 2).let { VoiceConsentSignal.TimedOut(
            it.roomId, it.matchNumber, it.requestId, it.requesterMembershipId, it.requesterConnectionId,
            it.recipientMembershipId, it.recipientConnectionId, it.expiresAtUtc,
        ) })
        runCurrent()
        assertEquals(VoiceConsentState.TimedOut, controller.snapshot.value.state)
    }

    @Test
    fun stale_request_from_prior_match_is_ignored() = runTest {
        val signaling = FakeConsentSignaling()
        val controller = ManagedVoiceConsentController(backgroundScope, signaling)
        controller.bind("room", 2)
        runCurrent()

        signaling.events.emit(signalRequested(match = 1))
        runCurrent()

        assertEquals(VoiceConsentState.Idle, controller.snapshot.value.state)
    }

    @Test
    fun newer_request_is_allowed_after_decline_but_stale_old_action_cannot_override_it() = runTest {
        val signaling = FakeConsentSignaling()
        val controller = ManagedVoiceConsentController(backgroundScope, signaling)
        controller.bind("room", 1)
        signaling.events.emit(signalRequested())
        runCurrent()
        controller.decline()

        val newer = signalRequested().copy(requestId = "request-2")
        signaling.events.emit(newer)
        runCurrent()
        assertEquals("request-2", controller.snapshot.value.requestId)
        assertEquals(VoiceConsentState.IncomingRequest, controller.snapshot.value.state)

        signaling.events.emit(VoiceConsentSignal.Declined(
            "room", 1, "request", "member-a", "connection-a", "member-b", "connection-b",
            "2099-01-01T00:00:00Z",
        ))
        runCurrent()
        assertEquals("request-2", controller.snapshot.value.requestId)
        assertEquals(VoiceConsentState.IncomingRequest, controller.snapshot.value.state)
    }

    @Test
    fun restart_reconciliation_discards_stale_local_request_state() = runTest {
        val signaling = FakeConsentSignaling()
        val controller = ManagedVoiceConsentController(backgroundScope, signaling)
        controller.bind("room", 1)
        controller.request()
        assertEquals(VoiceConsentState.Requesting, controller.snapshot.value.state)

        signaling.authoritative = VoiceConsentAuthoritativeState(
            false, "room", 1, "", "", "", "", VoiceConsentState.Idle,
        )
        controller.reconcile()

        assertEquals(VoiceConsentState.Idle, controller.snapshot.value.state)
        assertEquals(null, controller.snapshot.value.requestId)
    }

    @Test
    fun authoritative_accepted_request_is_restored_after_relaunch() = runTest {
        val signaling = FakeConsentSignaling().apply {
            authoritative = VoiceConsentAuthoritativeState(
                true, "room", 1, "request-10", "member-a", "member-b",
                "2099-01-01T00:00:00Z", VoiceConsentState.Accepted,
            )
        }
        val controller = ManagedVoiceConsentController(backgroundScope, signaling)
        controller.bind("room", 1)

        controller.reconcile()

        assertEquals(VoiceConsentState.Accepted, controller.snapshot.value.state)
        assertEquals("request-10", controller.snapshot.value.requestId)
    }

    private fun signalRequested(match: Int = 1) = VoiceConsentSignal.Requested(
        "room", match, "request", "member-a", "connection-a", "member-b", "connection-b", "2099-01-01T00:00:00Z",
    )

    private class FakeConsentSignaling : VoiceConsentSignalingTransport {
        val events = MutableSharedFlow<VoiceConsentSignal>(replay = 1, extraBufferCapacity = 8)
        override val consentSignals = events
        var requests = 0
        var accepts = 0
        var authoritative = VoiceConsentAuthoritativeState(
            false, "room", 1, "", "", "", "", VoiceConsentState.Idle,
        )
        override suspend fun voiceConsentState(roomId: String, matchNumber: Int) = authoritative
        override suspend fun requestVoice(roomId: String, matchNumber: Int): VoiceConsentResult {
            requests++
            return VoiceConsentResult(roomId, matchNumber, "request", "member-a", "member-b", "2099-01-01T00:00:00Z", requests == 1)
        }
        override suspend fun acceptVoice(roomId: String, matchNumber: Int, requestId: String) { accepts++ }
        override suspend fun declineVoice(roomId: String, matchNumber: Int, requestId: String) = Unit
        override suspend fun cancelVoiceRequest(roomId: String, matchNumber: Int, requestId: String) = Unit
        override suspend fun voiceUnavailable(roomId: String, matchNumber: Int, requestId: String, reason: String) = Unit
        override suspend fun endVoice(roomId: String, matchNumber: Int, requestId: String) = Unit
    }
}
