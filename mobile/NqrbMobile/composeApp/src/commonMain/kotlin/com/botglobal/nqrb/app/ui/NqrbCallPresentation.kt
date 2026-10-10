package com.botglobal.nqrb.app.ui

import com.botglobal.mobile.platform.calling.CallDeliveryState
import com.botglobal.mobile.platform.calling.CallDirection
import com.botglobal.mobile.platform.calling.CallSessionSnapshot
import com.botglobal.mobile.platform.calling.CallState
import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallingParticipantAvailability
import com.botglobal.mobile.platform.presence.PresenceEvidence

data class NqrbCallPresentation(
    val status: String,
    val announcePolitely: Boolean = true,
    val terminal: Boolean = false,
)

fun nqrbCallPresentation(strings: NqrbStrings, call: CallSessionSnapshot): NqrbCallPresentation {
    val wasPresentedOrAnswered = call.delivery?.wasPresented == true ||
        call.delivery?.state in setOf(CallDeliveryState.Presented, CallDeliveryState.Answered)
    val wasConnected = call.activeSinceEpochMillis != null || call.networkUsage.connectedAtEpochMillis != null
    val status = when {
        call.state == CallState.Active -> strings.activeCall
        call.state == CallState.Reconnecting -> strings.reconnecting
        call.state == CallState.Answering -> strings.connecting
        call.direction == CallDirection.Incoming && call.state == CallState.Ringing -> strings.incomingCall
        call.direction == CallDirection.Incoming && call.state == CallState.Connecting -> strings.connecting
        call.state == CallState.Preparing -> strings.checkingAvailability
        call.delivery?.state == CallDeliveryState.Answered && call.state in setOf(CallState.Connecting, CallState.Ringing) ->
            strings.connecting
        call.direction == CallDirection.Outgoing && call.state in setOf(CallState.Connecting, CallState.Ringing) ->
            if (call.delivery?.state == CallDeliveryState.Presented) {
                strings.waitingAfterReceipt
            } else strings.tryingToReach
        call.state == CallState.Ending -> strings.endingCall
        call.state in setOf(CallState.Missed, CallState.Expired) || call.terminationReason == com.botglobal.mobile.platform.calling.CallTerminationReason.Missed ->
            if (wasPresentedOrAnswered) {
                strings.noAnswer
            } else strings.unableToReach
        call.direction == CallDirection.Outgoing && call.state == CallState.Ended && !wasPresentedOrAnswered && !wasConnected ->
            strings.unableToReach
        call.state == CallState.Failed && call.error == "call_peer_unavailable" -> strings.callUnavailable
        call.state == CallState.Failed -> strings.callFailed
        call.state in setOf(CallState.Rejected, CallState.Cancelled, CallState.Ended) -> strings.callEnded
        else -> strings.availabilityUnverified
    }
    return NqrbCallPresentation(
        status = status,
        terminal = call.state in setOf(
            CallState.Rejected,
            CallState.Cancelled,
            CallState.Missed,
            CallState.Expired,
            CallState.Ended,
            CallState.Failed,
        ),
    )
}

fun nqrbDirectoryAvailability(
    strings: NqrbStrings,
    participant: CallableParticipant,
    nowEpochMillis: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
): String {
    val observedAt = participant.presenceObservedAtEpochMillis
    val fresh = observedAt != null && observedAt >= nowEpochMillis - 40_000 && observedAt <= nowEpochMillis + 5_000
    return when {
    fresh && participant.presenceEvidence == PresenceEvidence.Connected -> strings.onlineNow
    fresh && participant.presenceEvidence == PresenceEvidence.Disconnected && participant.presenceFullyCovered == true ->
        strings.currentlyUnavailable
    participant.availability == CallingParticipantAvailability.Reachable -> strings.availableForCalls
    else -> strings.availabilityUnverified
    }
}
