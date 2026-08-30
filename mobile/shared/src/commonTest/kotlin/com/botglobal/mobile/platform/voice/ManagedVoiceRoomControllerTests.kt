package com.botglobal.mobile.platform.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedVoiceRoomControllerTests {
    @Test
    fun join_is_idempotent_and_deterministic_initiator_creates_one_offer() = runTest {
        val signaling = FakeSignaling(isInitiator = true, peerPresent = true)
        val factory = FakeMediaFactory()
        val controller = ManagedVoiceRoomController(backgroundScope, signaling, factory)

        controller.join("room")
        controller.join("room")
        advanceUntilIdle()

        assertEquals(1, signaling.joinCount)
        assertEquals(1, signaling.offers.size)
        assertEquals(VoiceRoomState.Negotiating, controller.snapshot.value.state)
    }

    @Test
    fun mute_disables_track_without_replacing_peer_and_unmute_restores_it() = runTest {
        val signaling = FakeSignaling()
        val factory = FakeMediaFactory()
        val controller = ManagedVoiceRoomController(backgroundScope, signaling, factory)
        controller.join("room")
        val peer = factory.peers.single()

        controller.setMuted(true)
        controller.setMuted(false)

        assertEquals(listOf(true, false), peer.muteChanges)
        assertEquals(1, factory.peers.size)
        assertFalse(controller.snapshot.value.muted)
    }

    @Test
    fun stale_signal_generation_cannot_mutate_current_peer() = runTest {
        val signaling = FakeSignaling()
        val factory = FakeMediaFactory()
        val controller = ManagedVoiceRoomController(backgroundScope, signaling, factory)
        controller.join("room")
        val generation = controller.snapshot.value.generation

        signaling.events.emit(VoiceSignal.Offer("room", generation - 1, "peer", 1, "stale"))
        advanceUntilIdle()

        assertTrue(factory.peers.single().remoteOffers.isEmpty())
        assertEquals(VoiceRoomState.WaitingForPeer, controller.snapshot.value.state)
    }

    @Test
    fun interruption_disposes_old_peer_and_recovery_creates_one_new_generation() = runTest {
        val signaling = FakeSignaling()
        val factory = FakeMediaFactory()
        val controller = ManagedVoiceRoomController(backgroundScope, signaling, factory)
        controller.join("room")
        val original = factory.peers.single()

        controller.signalingInterrupted()
        controller.signalingInterrupted()
        controller.signalingRecovered()

        assertTrue(original.closed)
        assertEquals(2, signaling.joinCount)
        assertEquals(2, factory.peers.size)
        assertEquals(VoiceRoomState.WaitingForPeer, controller.snapshot.value.state)
    }

    @Test
    fun leave_stops_media_and_is_idempotent() = runTest {
        val signaling = FakeSignaling()
        val factory = FakeMediaFactory()
        val controller = ManagedVoiceRoomController(backgroundScope, signaling, factory)
        controller.join("room")
        val peer = factory.peers.single()

        controller.leave()
        controller.leave()

        assertTrue(peer.closed)
        assertEquals(1, signaling.leaveCount)
        assertEquals(VoiceRoomState.Idle, controller.snapshot.value.state)
    }

    @Test
    fun self_addressed_offer_and_ice_candidate_are_rejected_before_media_peer() = runTest {
        val signaling = FakeSignaling(peerPresent = true)
        val factory = FakeMediaFactory()
        val controller = ManagedVoiceRoomController(backgroundScope, signaling, factory)
        controller.join("room")
        val generation = controller.snapshot.value.generation
        val peer = factory.peers.single()

        signaling.events.emit(VoiceSignal.Offer("room", generation, "local", generation, "self-offer", "local-connection", "local-connection"))
        signaling.events.emit(VoiceSignal.IceCandidate("room", generation, "local", generation, "self-candidate", null, 0, "local-connection", "local-connection"))
        advanceUntilIdle()

        assertTrue(peer.remoteOffers.isEmpty())
        assertTrue(peer.remoteCandidates.isEmpty())
    }

    private class FakeSignaling(
        private val isInitiator: Boolean = false,
        private val peerPresent: Boolean = false,
    ) : VoiceSignalingTransport {
        val events = MutableSharedFlow<VoiceSignal>(extraBufferCapacity = 10)
        override val signals = events
        var joinCount = 0
        var leaveCount = 0
        val offers = mutableListOf<String>()
        override suspend fun iceConfiguration(roomId: String) = VoiceIceConfiguration(emptyList(), "2099-01-01T00:00:00Z")
        override suspend fun join(roomId: String, generation: Long): VoiceJoinResult {
            joinCount++
            return VoiceJoinResult(
                roomId, generation, "local", isInitiator, peerPresent,
                connectionId = "local-connection",
                peerParticipantId = if (peerPresent) "remote" else null,
                peerConnectionId = if (peerPresent) "remote-connection" else null,
            )
        }
        override suspend fun leave(roomId: String, generation: Long) { leaveCount++ }
        override suspend fun offer(roomId: String, generation: Long, sessionDescription: String) { offers += sessionDescription }
        override suspend fun answer(roomId: String, generation: Long, sessionDescription: String) = Unit
        override suspend fun iceCandidate(roomId: String, generation: Long, candidate: String, sdpMid: String?, sdpMLineIndex: Int) = Unit
        override suspend fun muted(roomId: String, generation: Long, muted: Boolean) = Unit
    }

    private class FakeMediaFactory : VoiceMediaPeerFactory {
        val peers = mutableListOf<FakeMediaPeer>()
        override fun create(configuration: VoiceIceConfiguration, generation: Long, listener: VoiceMediaPeerListener): VoiceMediaPeer =
            FakeMediaPeer().also(peers::add)
    }

    private class FakeMediaPeer : VoiceMediaPeer {
        val muteChanges = mutableListOf<Boolean>()
        val remoteOffers = mutableListOf<String>()
        val remoteCandidates = mutableListOf<String>()
        var closed = false
        override suspend fun createOffer() = "offer"
        override suspend fun acceptOfferAndCreateAnswer(sessionDescription: String): String {
            remoteOffers += sessionDescription
            return "answer"
        }
        override suspend fun acceptAnswer(sessionDescription: String) = Unit
        override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) { remoteCandidates += candidate }
        override fun setMuted(muted: Boolean) { muteChanges += muted }
        override suspend fun close() { closed = true }
    }
}
