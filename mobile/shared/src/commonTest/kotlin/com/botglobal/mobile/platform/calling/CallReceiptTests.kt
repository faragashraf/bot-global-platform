package com.botglobal.mobile.platform.calling

import com.botglobal.mobile.platform.voice.VoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceRoomSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class CallReceiptTests {
    @Test
    fun receipt_is_sent_only_after_successful_incoming_presentation() = runTest {
        val signaling = RecordingSignaling()
        val platform = RecordingPlatform(signaling.order)
        val controller = CallSessionController(backgroundScope, signaling, NoopVoice(), platform)
        runCurrent()

        signaling.eventsFlow.emit(offer("shown"))
        runCurrent()

        assertEquals(listOf("present:shown"), signaling.order)
        controller.confirmIncomingPresented(CallId("shown"))

        assertEquals(listOf("present:shown", "receipt:shown"), signaling.order)
        assertEquals(CallState.Ringing, controller.state.value.state)
    }

    @Test
    fun failed_presentation_never_fakes_a_receipt() = runTest {
        val signaling = RecordingSignaling()
        val platform = RecordingPlatform(signaling.order, failPresentation = true)
        val controller = CallSessionController(backgroundScope, signaling, NoopVoice(), platform)
        runCurrent()

        signaling.eventsFlow.emit(offer("failed"))
        runCurrent()

        assertEquals(listOf("present:failed"), signaling.order)
        assertEquals(CallState.Failed, controller.state.value.state)
    }

    @Test
    fun caller_queries_delivery_after_binding_the_start_reply_to_recover_a_lost_event() = runTest {
        val signaling = RecordingSignaling().apply {
            queriedDelivery = CallDeliveryStatus(
                CallId("outgoing"), CallDeliveryState.Presented, revision = 4, updatedAtEpochMillis = 12,
            )
        }
        val controller = CallSessionController(backgroundScope, signaling, NoopVoice(), RecordingPlatform(signaling.order))

        assertIs<StartCallResult.Started>(controller.start(
            OutgoingCallRequest("nqrb", CallParticipant("callee", "Callee")),
        ))

        assertEquals(CallId("outgoing"), controller.state.value.callId)
        assertEquals(CallDeliveryState.Presented, controller.state.value.delivery?.state)
        assertEquals(4, controller.state.value.delivery?.revision)
    }

    private fun offer(id: String) = CallSignalingEvent.IncomingOffered(
        CallId(id), "nqrb", CallParticipant("caller", "Caller"),
    )

    private class RecordingSignaling : CallSignaling {
        val eventsFlow = MutableSharedFlow<CallSignalingEvent>(extraBufferCapacity = 4)
        override val events = eventsFlow
        val order = mutableListOf<String>()
        var queriedDelivery: CallDeliveryStatus? = null

        override suspend fun startOutgoing(request: OutgoingCallRequest) =
            StartedCall(CallId("outgoing"), request.callee)
        override suspend fun confirmIncomingReceipt(callId: CallId) { order += "receipt:${callId.value}" }
        override suspend fun deliveryStatus(callId: CallId) = queriedDelivery
        override suspend fun end(callId: CallId, reason: CallTerminationReason) = Unit
    }

    private class RecordingPlatform(
        private val order: MutableList<String>,
        private val failPresentation: Boolean = false,
    ) : CallPlatformLifecycle {
        override suspend fun presentIncoming(callId: CallId, participant: CallParticipant) {
            order += "present:${callId.value}"
            if (failPresentation) error("synthetic presentation failure")
        }
        override suspend fun start(callId: CallId, participant: CallParticipant, direction: CallDirection) = Unit
        override suspend fun markActive() = Unit
        override suspend fun requestRoute(route: CallAudioRoute) = route
        override suspend fun end(reason: CallTerminationReason) = Unit
    }

    private class NoopVoice : VoiceRoomController {
        override val snapshot = MutableStateFlow(VoiceRoomSnapshot())
        override suspend fun join(roomId: String) = Unit
        override suspend fun leave() = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun signalingInterrupted() = Unit
        override suspend fun signalingRecovered() = Unit
    }
}
