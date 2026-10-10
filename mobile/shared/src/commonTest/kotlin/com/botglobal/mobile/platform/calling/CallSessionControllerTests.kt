package com.botglobal.mobile.platform.calling

import com.botglobal.mobile.platform.voice.VoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot
import com.botglobal.mobile.platform.voice.VoiceRoomState
import com.botglobal.mobile.platform.voice.VoiceMediaStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionControllerTests {
    @Test
    fun outgoing_ringback_follows_waiting_state_and_stops_when_media_connects() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())
        fixture.voice.emit(VoiceRoomState.WaitingForPeer)
        runCurrent()
        assertEquals(CallState.Ringing, fixture.session.state.value.state)
        assertEquals(listOf(true), fixture.platform.ringbackEvents)

        fixture.voice.emit(VoiceRoomState.Connected)
        runCurrent()
        assertEquals(CallState.Active, fixture.session.state.value.state)
        assertEquals(listOf(true, false), fixture.platform.ringbackEvents)
    }

    @Test
    fun nqrb_ringback_waits_for_authenticated_presentation_receipt() = runTest {
        val operationOrder = mutableListOf<String>()
        val signaling = FakeSignaling(operationOrder)
        val voice = FakeVoice(operationOrder)
        val platform = FakePlatform(operationOrder)
        val session = CallSessionController(
            backgroundScope, signaling, voice, platform, requirePresentationForRingback = true,
        )
        val started = assertIs<StartCallResult.Started>(session.start(request()))

        voice.emit(VoiceRoomState.WaitingForPeer)
        runCurrent()
        assertEquals(CallState.Connecting, session.state.value.state)
        assertEquals(emptyList(), platform.ringbackEvents)

        signaling.mutableEvents.emit(CallSignalingEvent.DeliveryUpdated(
            CallDeliveryStatus(started.callId, CallDeliveryState.Presented, revision = 2),
        ))
        runCurrent()

        assertEquals(CallState.Ringing, session.state.value.state)
        assertEquals(listOf(true), platform.ringbackEvents)
    }


    @Test
    fun nqrb_waiting_peer_state_does_not_become_ringing_before_presentation_receipt() = runTest {
        val operationOrder = mutableListOf<String>()
        val signaling = FakeSignaling(operationOrder)
        val voice = FakeVoice(operationOrder)
        val platform = FakePlatform(operationOrder)
        val session = CallSessionController(
            backgroundScope, signaling, voice, platform, requirePresentationForRingback = true,
        )
        val started = assertIs<StartCallResult.Started>(session.start(request()))

        voice.emit(VoiceRoomState.WaitingForPeer)
        runCurrent()

        assertEquals(CallState.Connecting, session.state.value.state)
        assertEquals(emptyList(), platform.ringbackEvents)

        signaling.mutableEvents.emit(CallSignalingEvent.DeliveryUpdated(
            CallDeliveryStatus(started.callId, CallDeliveryState.Presented, revision = 2),
        ))
        runCurrent()

        assertEquals(CallState.Ringing, session.state.value.state)
        assertEquals(listOf(true), platform.ringbackEvents)
    }

    @Test
    fun optional_delivery_query_failure_does_not_end_a_legacy_call() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.signaling.deliveryFailure = UnsupportedOperationException("method unavailable")

        assertIs<StartCallResult.Started>(fixture.session.start(request()))

        assertEquals(CallState.Connecting, fixture.session.state.value.state)
        assertEquals(0, fixture.signaling.ends)
        assertEquals(1, fixture.voice.joinCount)
    }

    @Test
    fun reconnect_applies_a_terminal_delivery_revision_and_releases_media() = runTest {
        val fixture = fixture(backgroundScope)
        val started = assertIs<StartCallResult.Started>(fixture.session.start(request()))
        fixture.session.signalingInterrupted()
        fixture.signaling.delivery = CallDeliveryStatus(
            started.callId,
            CallDeliveryState.Terminal,
            revision = 4,
            terminal = true,
            terminalReason = "expired",
        )

        fixture.session.signalingRecovered()

        assertEquals(CallState.Expired, fixture.session.state.value.state)
        assertEquals(CallTerminationReason.Expired, fixture.session.state.value.terminationReason)
        assertEquals(1, fixture.voice.leaves)
        assertEquals(1, fixture.platform.endCount)
    }

    @Test
    fun lifecycle_is_authoritative_and_independent_of_ui_observers() = runTest {
        val fixture = fixture(backgroundScope)
        assertEquals(CallState.Idle, fixture.session.state.value.state)

        assertIs<StartCallResult.Started>(fixture.session.start(request()))
        assertEquals(CallState.Connecting, fixture.session.state.value.state)
        fixture.voice.emit(VoiceRoomState.Connected)
        runCurrent()

        assertEquals(CallState.Active, fixture.session.state.value.state)
        assertEquals(42L, fixture.session.state.value.activeSinceEpochMillis)
        assertEquals(1, fixture.voice.joinCount)
        assertEquals(1, fixture.platform.activeCount)

        // A recreated observer reads the same session and never creates media.
        fixture.session.state.value
        assertEquals(1, fixture.voice.joinCount)
    }

    @Test
    fun mute_route_reconnect_and_end_are_owned_by_session() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())
        fixture.voice.emit(VoiceRoomState.Connected)
        runCurrent()

        fixture.session.setMuted(true)
        fixture.session.requestRoute(CallAudioRoute.Speaker)
        fixture.session.signalingInterrupted()
        assertEquals(CallState.Reconnecting, fixture.session.state.value.state)
        fixture.session.signalingRecovered()
        fixture.session.end()

        assertEquals(listOf(true), fixture.voice.mutes)
        assertEquals(CallAudioRoute.System, fixture.session.state.value.media.route)
        assertTrue(fixture.session.state.value.media.availableRoutes.isEmpty())
        assertEquals(1, fixture.voice.interruptions)
        assertEquals(1, fixture.voice.recoveries)
        assertEquals(1, fixture.voice.leaves)
        assertEquals(1, fixture.platform.endCount)
        assertEquals(CallState.Ended, fixture.session.state.value.state)
    }

    @Test
    fun phone_routes_expose_a_real_earpiece_speaker_toggle() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())
        runCurrent()
        fixture.platform.events.emit(
            CallPlatformAction.AvailableRoutesChanged(setOf(CallAudioRoute.Earpiece, CallAudioRoute.Speaker)),
        )
        fixture.platform.events.emit(CallPlatformAction.RouteChanged(CallAudioRoute.Earpiece))
        runCurrent()

        assertEquals(CallAudioRoute.Speaker, fixture.session.state.value.media.speakerControlTarget())
        fixture.session.requestRoute(CallAudioRoute.Speaker)
        assertEquals(CallAudioRoute.Speaker, fixture.session.state.value.media.route)
        assertEquals(CallAudioRoute.Earpiece, fixture.session.state.value.media.speakerControlTarget())
    }

    @Test
    fun speaker_only_tablet_does_not_expose_an_invalid_earpiece_transition() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())
        runCurrent()
        fixture.platform.events.emit(CallPlatformAction.AvailableRoutesChanged(setOf(CallAudioRoute.Speaker)))
        fixture.platform.events.emit(CallPlatformAction.RouteChanged(CallAudioRoute.Speaker))
        runCurrent()

        assertEquals(setOf(CallAudioRoute.Speaker), fixture.session.state.value.media.availableRoutes)
        assertEquals(null, fixture.session.state.value.media.speakerControlTarget())
    }

    @Test
    fun failed_route_request_preserves_the_actual_platform_route() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())
        runCurrent()
        fixture.platform.events.emit(
            CallPlatformAction.AvailableRoutesChanged(setOf(CallAudioRoute.Earpiece, CallAudioRoute.Speaker)),
        )
        fixture.platform.events.emit(CallPlatformAction.RouteChanged(CallAudioRoute.Earpiece))
        fixture.platform.appliedRoute = CallAudioRoute.Earpiece
        runCurrent()

        fixture.session.requestRoute(CallAudioRoute.Speaker)

        assertEquals(CallAudioRoute.Earpiece, fixture.session.state.value.media.route)
    }

    @Test
    fun route_state_is_session_owned_and_survives_observer_recreation() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())
        runCurrent()
        fixture.platform.events.emit(CallPlatformAction.AvailableRoutesChanged(setOf(CallAudioRoute.Speaker)))
        fixture.platform.events.emit(CallPlatformAction.RouteChanged(CallAudioRoute.Speaker))
        runCurrent()

        val recreatedObserverSnapshot = fixture.session.state.value

        assertEquals(CallAudioRoute.Speaker, recreatedObserverSnapshot.media.route)
        assertEquals(setOf(CallAudioRoute.Speaker), recreatedObserverSnapshot.media.availableRoutes)
    }

    @Test
    fun one_active_call_policy_is_deterministic_and_application_context_is_preserved() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())

        assertEquals(StartCallResult.ActiveCallExists, fixture.session.start(request("member-b")))
        assertEquals("nqrb", fixture.session.state.value.applicationContext)
        assertEquals(1, fixture.signaling.starts)
    }

    @Test
    fun media_failure_is_deterministic() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())
        fixture.voice.emit(VoiceRoomState.Failed)
        runCurrent()

        assertEquals(CallState.Failed, fixture.session.state.value.state)
        assertEquals(CallTerminationReason.Failed, fixture.session.state.value.terminationReason)
        assertEquals("call_media_failed", fixture.session.state.value.error)
        assertTrue(fixture.session.state.value.networkUsage.isFinal)
        assertEquals(1, fixture.voice.leaves)
        assertEquals(1, fixture.platform.endCount)
    }

    @Test
    fun a_new_call_waits_for_owned_failed_media_cleanup() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())
        fixture.voice.leaveGate = CompletableDeferred()
        fixture.voice.emit(VoiceRoomState.Failed)
        runCurrent()

        val next = async { fixture.session.start(request("member-b")) }
        runCurrent()
        assertTrue(!next.isCompleted)

        fixture.voice.leaveGate!!.complete(Unit)
        assertIs<StartCallResult.Started>(next.await())
        assertEquals("member-b", fixture.session.state.value.participant?.membershipId)
    }

    @Test
    fun usage_duration_starts_at_active_media_and_excludes_preconnection_time() = runTest {
        var now = 1_000L
        val fixture = fixture(backgroundScope) { now }
        fixture.session.start(request())

        now = 31_000L
        fixture.voice.emit(VoiceRoomState.Connected, sent = 100, received = 200)
        runCurrent()
        fixture.voice.emit(VoiceRoomState.Connected, sent = 1_100, received = 2_200)
        runCurrent()

        now = 91_000L
        fixture.session.end()
        val usage = fixture.session.state.value.networkUsage

        assertEquals(31_000, usage.connectedAtEpochMillis)
        assertEquals(91_000, usage.endedAtEpochMillis)
        assertEquals(60, usage.connectedDurationSeconds)
        assertEquals(3_000, usage.totalBytes)
        assertEquals(3_000.0, usage.bytesPerConnectedMinute)
    }

    @Test
    fun platform_end_action_terminates_media_and_signaling() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.session.start(request())
        runCurrent()
        fixture.platform.events.emit(CallPlatformAction.End)
        runCurrent()

        assertEquals(CallState.Ended, fixture.session.state.value.state)
        assertEquals(1, fixture.voice.leaves)
        assertEquals(1, fixture.signaling.ends)
    }

    @Test
    fun remote_end_releases_locally_without_echoing_to_signaling() = runTest {
        val fixture = fixture(backgroundScope)
        val started = assertIs<StartCallResult.Started>(fixture.session.start(request()))
        runCurrent()

        fixture.signaling.mutableEvents.emit(CallSignalingEvent.RemoteEnded(started.callId))
        runCurrent()

        assertEquals(CallState.Ended, fixture.session.state.value.state)
        assertEquals(1, fixture.voice.leaves)
        assertEquals(0, fixture.signaling.ends)
    }

    @Test
    fun platform_start_failure_releases_created_signaling_session() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.platform.failStart = true

        assertIs<StartCallResult.Failed>(fixture.session.start(request()))

        assertEquals(CallState.Failed, fixture.session.state.value.state)
        assertEquals(1, fixture.signaling.ends)
        assertEquals(1, fixture.platform.endCount)
        assertEquals(0, fixture.voice.joinCount)
    }

    @Test
    fun foreground_incoming_offer_is_authoritative_and_accepts_one_media_session() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        fixture.signaling.mutableEvents.emit(
            CallSignalingEvent.IncomingOffered(
                CallId("incoming"),
                "nqrb",
                CallParticipant("caller", "Caller"),
            ),
        )
        runCurrent()

        assertEquals(CallDirection.Incoming, fixture.session.state.value.direction)
        assertEquals(CallState.Ringing, fixture.session.state.value.state)
        assertIs<StartCallResult.Started>(fixture.session.acceptIncoming())
        assertEquals(listOf("answer", "platform_start_incoming", "join"), fixture.operationOrder)
        assertEquals(1, fixture.platform.presentCount)
        assertEquals(1, fixture.platform.startCount)
        assertEquals(1, fixture.signaling.answers)
        assertEquals(1, fixture.voice.joinCount)
    }

    @Test
    fun answered_device_ignores_terminal_push_sent_to_other_devices() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        val callId = CallId("incoming")
        fixture.signaling.mutableEvents.emit(
            CallSignalingEvent.IncomingOffered(callId, "nqrb", CallParticipant("caller", "Caller")),
        )
        runCurrent()
        assertIs<StartCallResult.Started>(fixture.session.acceptIncoming())

        fixture.session.dismissIncoming(callId, CallTerminationReason.Cancelled)

        assertEquals(CallState.Connecting, fixture.session.state.value.state)
        assertEquals(0, fixture.platform.endCount)
        assertEquals(0, fixture.voice.leaves)
    }

    @Test
    fun sibling_device_clears_ringing_when_terminal_push_arrives() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        val callId = CallId("incoming")
        fixture.signaling.mutableEvents.emit(
            CallSignalingEvent.IncomingOffered(callId, "nqrb", CallParticipant("caller", "Caller")),
        )
        runCurrent()

        fixture.session.dismissIncoming(callId, CallTerminationReason.Cancelled)

        assertEquals(CallState.Cancelled, fixture.session.state.value.state)
        assertEquals(1, fixture.platform.endCount)
    }

    @Test
    fun telecom_registration_alone_does_not_end_incoming_business_call() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        fixture.signaling.mutableEvents.emit(
            CallSignalingEvent.IncomingOffered(CallId("incoming"), "nqrb", CallParticipant("caller", "Caller")),
        )
        runCurrent()

        assertEquals(CallState.Ringing, fixture.session.state.value.state)
        assertEquals(0, fixture.signaling.ends)
        assertEquals(0, fixture.platform.endCount)
    }

    @Test
    fun duplicate_delivery_for_same_incoming_call_is_idempotent() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        val offer = CallSignalingEvent.IncomingOffered(
            CallId("incoming"), "nqrb", CallParticipant("caller", "Caller"),
        )

        fixture.signaling.mutableEvents.emit(offer)
        runCurrent()
        fixture.signaling.mutableEvents.emit(offer)
        runCurrent()

        assertEquals(CallState.Ringing, fixture.session.state.value.state)
        assertEquals(1, fixture.platform.presentCount)
        assertEquals(0, fixture.platform.startCount)
        assertEquals(0, fixture.signaling.ends)
    }

    @Test
    fun incoming_reject_is_authoritative_idempotent_and_never_starts_media() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        val offer = CallSignalingEvent.IncomingOffered(
            CallId("incoming"), "nqrb", CallParticipant("caller", "Caller"),
        )
        fixture.signaling.mutableEvents.emit(offer)
        runCurrent()
        fixture.signaling.mutableEvents.emit(offer)
        runCurrent()

        fixture.session.rejectIncoming()
        fixture.session.rejectIncoming()
        fixture.signaling.mutableEvents.emit(CallSignalingEvent.Cancelled(offer.callId))
        runCurrent()

        assertEquals(CallState.Rejected, fixture.session.state.value.state)
        assertEquals(CallTerminationReason.Rejected, fixture.session.state.value.terminationReason)
        assertTrue(fixture.session.state.value.networkUsage.isFinal)
        assertEquals(1, fixture.signaling.rejects)
        assertEquals(0, fixture.signaling.ends)
        assertEquals(0, fixture.signaling.answers)
        assertEquals(0, fixture.voice.joinCount)
        assertEquals(0, fixture.voice.leaves)
        assertEquals(1, fixture.platform.presentCount)
        assertEquals(0, fixture.platform.startCount)
        assertEquals(listOf(CallTerminationReason.Rejected), fixture.platform.endReasons)
    }

    @Test
    fun system_reject_and_compose_decline_converge_on_one_authoritative_reject() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        fixture.signaling.mutableEvents.emit(
            CallSignalingEvent.IncomingOffered(
                CallId("incoming"), "nqrb", CallParticipant("caller", "Caller"),
            ),
        )
        runCurrent()

        fixture.platform.events.emit(CallPlatformAction.Reject)
        fixture.session.rejectIncoming()
        runCurrent()

        assertEquals(CallState.Rejected, fixture.session.state.value.state)
        assertEquals(CallTerminationReason.Rejected, fixture.session.state.value.terminationReason)
        assertEquals(1, fixture.signaling.rejects)
        assertEquals(0, fixture.signaling.ends)
        assertEquals(0, fixture.voice.joinCount)
        assertEquals(listOf(CallTerminationReason.Rejected), fixture.platform.endReasons)
    }

    @Test
    fun system_answer_action_accepts_incoming_call_and_starts_media_once() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        fixture.signaling.mutableEvents.emit(
            CallSignalingEvent.IncomingOffered(
                CallId("incoming"),
                "nqrb",
                CallParticipant("caller", "Caller"),
            ),
        )
        runCurrent()

        fixture.platform.events.emit(CallPlatformAction.Answer)
        runCurrent()

        assertEquals(CallState.Connecting, fixture.session.state.value.state)
        assertEquals(listOf("answer", "platform_start_incoming", "join"), fixture.operationOrder)
        assertEquals(1, fixture.platform.presentCount)
        assertEquals(1, fixture.platform.startCount)
        assertEquals(1, fixture.signaling.answers)
        assertEquals(1, fixture.voice.joinCount)
    }

    @Test
    fun caller_cancel_wins_once_when_it_races_a_local_reject() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        val callId = CallId("incoming")
        fixture.signaling.mutableEvents.emit(
            CallSignalingEvent.IncomingOffered(callId, "nqrb", CallParticipant("caller", "Caller")),
        )
        runCurrent()

        fixture.signaling.mutableEvents.emit(CallSignalingEvent.Cancelled(callId))
        runCurrent()
        fixture.session.rejectIncoming()

        assertEquals(CallState.Cancelled, fixture.session.state.value.state)
        assertEquals(CallTerminationReason.Cancelled, fixture.session.state.value.terminationReason)
        assertEquals(0, fixture.signaling.rejects)
        assertEquals(0, fixture.signaling.answers)
        assertEquals(0, fixture.voice.joinCount)
        assertEquals(1, fixture.voice.leaves)
        assertEquals(listOf(CallTerminationReason.Cancelled), fixture.platform.endReasons)
    }

    @Test
    fun different_incoming_call_is_rejected_while_one_is_ringing() = runTest {
        val fixture = fixture(backgroundScope)
        runCurrent()
        fixture.signaling.mutableEvents.emit(
            CallSignalingEvent.IncomingOffered(CallId("first"), "nqrb", CallParticipant("caller-a", "Caller A")),
        )
        runCurrent()
        fixture.signaling.mutableEvents.emit(
            CallSignalingEvent.IncomingOffered(CallId("second"), "nqrb", CallParticipant("caller-b", "Caller B")),
        )
        runCurrent()

        assertEquals(CallId("first"), fixture.session.state.value.callId)
        assertEquals(1, fixture.platform.presentCount)
        assertEquals(0, fixture.platform.startCount)
        assertEquals(1, fixture.signaling.ends)
    }

    @Test
    fun cancellation_during_start_cleans_up_the_returned_server_call_and_rethrows() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.signaling.startGate = CompletableDeferred()
        val job = launch { fixture.session.start(request()) }
        runCurrent()

        job.cancel()
        fixture.signaling.startGate!!.complete(StartedCall(CallId("late-call"), request().callee))
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, fixture.signaling.ends)
        assertEquals(CallState.Cancelled, fixture.session.state.value.state)
        assertEquals("call_start_cancelled", fixture.session.state.value.error)
    }

    @Test
    fun account_change_fences_a_late_start_reply_and_never_adopts_the_old_call() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.signaling.startGate = CompletableDeferred()
        val result = async { fixture.session.start(request()) }
        runCurrent()

        fixture.session.clearForAccountChange()
        fixture.signaling.startGate!!.complete(StartedCall(CallId("old-account-call"), request().callee))

        assertEquals(StartCallResult.Failed("call_start_cancelled"), result.await())
        assertEquals(CallState.Idle, fixture.session.state.value.state)
        assertEquals(1, fixture.signaling.ends)
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        nowEpochMillis: () -> Long = { 42L },
    ): Fixture {
        val operationOrder = mutableListOf<String>()
        val signaling = FakeSignaling(operationOrder)
        val voice = FakeVoice(operationOrder)
        val platform = FakePlatform(operationOrder)
        return Fixture(
            CallSessionController(scope, signaling, voice, platform, nowEpochMillis = nowEpochMillis),
            signaling,
            voice,
            platform,
            operationOrder,
        )
    }

    private fun request(member: String = "member-a") = OutgoingCallRequest(
        applicationContext = "nqrb",
        callee = CallParticipant(member, "Known NQRB user"),
    )

    private data class Fixture(
        val session: CallSessionController,
        val signaling: FakeSignaling,
        val voice: FakeVoice,
        val platform: FakePlatform,
        val operationOrder: List<String>,
    )

    private class FakeSignaling(private val operationOrder: MutableList<String>) : CallSignaling {
        val mutableEvents = MutableSharedFlow<CallSignalingEvent>(extraBufferCapacity = 4)
        override val events: kotlinx.coroutines.flow.Flow<CallSignalingEvent> = mutableEvents
        var starts = 0
        var ends = 0
        var answers = 0
        var rejects = 0
        var delivery: CallDeliveryStatus? = null
        var deliveryFailure: Throwable? = null
        var startGate: CompletableDeferred<StartedCall>? = null
        override suspend fun startOutgoing(request: OutgoingCallRequest): StartedCall {
            starts++
            return startGate?.await() ?: StartedCall(CallId("call-$starts"), request.callee)
        }
        override suspend fun answer(callId: CallId) {
            answers++
            operationOrder += "answer"
        }
        override suspend fun reject(callId: CallId) { rejects++ }
        override suspend fun deliveryStatus(callId: CallId): CallDeliveryStatus? {
            deliveryFailure?.let { throw it }
            return delivery
        }
        override suspend fun end(callId: CallId, reason: CallTerminationReason) { ends++ }
    }

    private class FakeVoice(private val operationOrder: MutableList<String>) : VoiceRoomController {
        private val mutable = MutableStateFlow(VoiceRoomSnapshot())
        override val snapshot = mutable
        var joinCount = 0
        var leaves = 0
        var interruptions = 0
        var recoveries = 0
        var leaveGate: CompletableDeferred<Unit>? = null
        val mutes = mutableListOf<Boolean>()
        override suspend fun join(roomId: String) {
            joinCount++
            operationOrder += "join"
        }
        override suspend fun leave() { leaves++; leaveGate?.await(); leaveGate = null }
        override suspend fun setMuted(muted: Boolean) { mutes += muted }
        override suspend fun signalingInterrupted() { interruptions++ }
        override suspend fun signalingRecovered() { recoveries++ }
        fun emit(
            state: VoiceRoomState,
            sent: Long = 0,
            received: Long = 0,
        ) {
            mutable.value = mutable.value.copy(
                state = state,
                stats = VoiceMediaStats(
                    outboundBytes = sent,
                    inboundBytes = received,
                    available = state == VoiceRoomState.Connected,
                ),
            )
        }
    }

    private class FakePlatform(private val operationOrder: MutableList<String>) : CallPlatformLifecycle {
        val events = MutableSharedFlow<CallPlatformAction>(extraBufferCapacity = 2)
        override val actions = events
        var activeCount = 0
        var endCount = 0
        var presentCount = 0
        var startCount = 0
        var failStart = false
        var appliedRoute: CallAudioRoute? = null
        val endReasons = mutableListOf<CallTerminationReason>()
        val ringbackEvents = mutableListOf<Boolean>()
        override fun setRingback(active: Boolean) { ringbackEvents += active }
        override suspend fun presentIncoming(callId: CallId, participant: CallParticipant) {
            presentCount++
        }
        override suspend fun start(callId: CallId, participant: CallParticipant, direction: CallDirection) {
            startCount++
            operationOrder += "platform_start_${direction.name.lowercase()}"
            if (failStart) error("platform unavailable")
        }
        override suspend fun markActive() { activeCount++ }
        override suspend fun requestRoute(route: CallAudioRoute) = appliedRoute ?: route
        override suspend fun end(reason: CallTerminationReason) {
            endCount++
            endReasons += reason
        }
    }
}
