package com.botglobal.mobile.platform.calling

import com.botglobal.mobile.platform.voice.VoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot
import com.botglobal.mobile.platform.voice.VoiceRoomState
import com.botglobal.mobile.platform.voice.VoiceMediaStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        assertEquals(CallAudioRoute.Speaker, fixture.session.state.value.media.route)
        assertEquals(1, fixture.voice.interruptions)
        assertEquals(1, fixture.voice.recoveries)
        assertEquals(1, fixture.voice.leaves)
        assertEquals(1, fixture.platform.endCount)
        assertEquals(CallState.Ended, fixture.session.state.value.state)
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
        assertEquals(listOf("answer", "join"), fixture.operationOrder)
        assertEquals(1, fixture.signaling.answers)
        assertEquals(1, fixture.voice.joinCount)
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
        assertEquals(1, fixture.platform.startCount)
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
        assertEquals(1, fixture.platform.startCount)
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
        assertEquals(1, fixture.platform.startCount)
        assertEquals(1, fixture.signaling.ends)
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        nowEpochMillis: () -> Long = { 42L },
    ): Fixture {
        val operationOrder = mutableListOf<String>()
        val signaling = FakeSignaling(operationOrder)
        val voice = FakeVoice(operationOrder)
        val platform = FakePlatform()
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
        override suspend fun startOutgoing(request: OutgoingCallRequest): StartedCall {
            starts++
            return StartedCall(CallId("call-$starts"), request.callee)
        }
        override suspend fun answer(callId: CallId) {
            answers++
            operationOrder += "answer"
        }
        override suspend fun reject(callId: CallId) { rejects++ }
        override suspend fun end(callId: CallId, reason: CallTerminationReason) { ends++ }
    }

    private class FakeVoice(private val operationOrder: MutableList<String>) : VoiceRoomController {
        private val mutable = MutableStateFlow(VoiceRoomSnapshot())
        override val snapshot = mutable
        var joinCount = 0
        var leaves = 0
        var interruptions = 0
        var recoveries = 0
        val mutes = mutableListOf<Boolean>()
        override suspend fun join(roomId: String) {
            joinCount++
            operationOrder += "join"
        }
        override suspend fun leave() { leaves++ }
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

    private class FakePlatform : CallPlatformLifecycle {
        val events = MutableSharedFlow<CallPlatformAction>(extraBufferCapacity = 2)
        override val actions = events
        var activeCount = 0
        var endCount = 0
        var startCount = 0
        var failStart = false
        val endReasons = mutableListOf<CallTerminationReason>()
        override suspend fun start(callId: CallId, participant: CallParticipant, direction: CallDirection) {
            startCount++
            if (failStart) error("platform unavailable")
        }
        override suspend fun markActive() { activeCount++ }
        override suspend fun requestRoute(route: CallAudioRoute) = route
        override suspend fun end(reason: CallTerminationReason) {
            endCount++
            endReasons += reason
        }
    }
}
