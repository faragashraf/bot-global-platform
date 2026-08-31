package com.botglobal.mobile.platform.calling

import com.botglobal.mobile.platform.voice.VoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot
import com.botglobal.mobile.platform.voice.VoiceRoomState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
        assertEquals(1, fixture.voice.leaves)
        assertEquals(1, fixture.platform.endCount)
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
        assertEquals(1, fixture.voice.joinCount)
    }

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val signaling = FakeSignaling()
        val voice = FakeVoice()
        val platform = FakePlatform()
        return Fixture(
            CallSessionController(scope, signaling, voice, platform, nowEpochMillis = { 42L }),
            signaling,
            voice,
            platform,
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
    )

    private class FakeSignaling : CallSignaling {
        val mutableEvents = MutableSharedFlow<CallSignalingEvent>(extraBufferCapacity = 4)
        override val events: kotlinx.coroutines.flow.Flow<CallSignalingEvent> = mutableEvents
        var starts = 0
        var ends = 0
        override suspend fun startOutgoing(request: OutgoingCallRequest): StartedCall {
            starts++
            return StartedCall(CallId("call-$starts"), request.callee)
        }
        override suspend fun end(callId: CallId, reason: CallTerminationReason) { ends++ }
    }

    private class FakeVoice : VoiceRoomController {
        private val mutable = MutableStateFlow(VoiceRoomSnapshot())
        override val snapshot = mutable
        var joinCount = 0
        var leaves = 0
        var interruptions = 0
        var recoveries = 0
        val mutes = mutableListOf<Boolean>()
        override suspend fun join(roomId: String) { joinCount++ }
        override suspend fun leave() { leaves++ }
        override suspend fun setMuted(muted: Boolean) { mutes += muted }
        override suspend fun signalingInterrupted() { interruptions++ }
        override suspend fun signalingRecovered() { recoveries++ }
        fun emit(state: VoiceRoomState) { mutable.value = mutable.value.copy(state = state) }
    }

    private class FakePlatform : CallPlatformLifecycle {
        val events = MutableSharedFlow<CallPlatformAction>(extraBufferCapacity = 2)
        override val actions = events
        var activeCount = 0
        var endCount = 0
        var failStart = false
        override suspend fun start(callId: CallId, participant: CallParticipant, direction: CallDirection) {
            if (failStart) error("platform unavailable")
        }
        override suspend fun markActive() { activeCount++ }
        override suspend fun requestRoute(route: CallAudioRoute) = route
        override suspend fun end(reason: CallTerminationReason) { endCount++ }
    }
}
