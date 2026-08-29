package com.botglobal.mobile.platform.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoiceConsentState {
    Idle, Requesting, IncomingRequest, Accepted, Declined, TimedOut, Cancelled,
    Joining, Connected, Muted, Reconnecting, Unavailable, Ended,
}

data class VoiceConsentSnapshot(
    val state: VoiceConsentState = VoiceConsentState.Idle,
    val roomId: String? = null,
    val matchNumber: Int? = null,
    val requestId: String? = null,
    val requesterMembershipId: String? = null,
    val recipientMembershipId: String? = null,
    val expiresAtUtc: String? = null,
    val reason: String? = null,
)

data class VoiceConsentResult(
    val roomId: String,
    val matchNumber: Int,
    val requestId: String,
    val requesterMembershipId: String,
    val recipientMembershipId: String,
    val expiresAtUtc: String,
    val created: Boolean,
)

sealed interface VoiceConsentSignal {
    val roomId: String
    val matchNumber: Int
    val requestId: String
    val requesterMembershipId: String
    val requesterConnectionId: String
    val recipientMembershipId: String
    val recipientConnectionId: String
    val expiresAtUtc: String
    val reason: String?

    data class Requested(override val roomId: String, override val matchNumber: Int, override val requestId: String,
        override val requesterMembershipId: String, override val requesterConnectionId: String,
        override val recipientMembershipId: String, override val recipientConnectionId: String,
        override val expiresAtUtc: String, override val reason: String? = null) : VoiceConsentSignal
    data class Accepted(override val roomId: String, override val matchNumber: Int, override val requestId: String,
        override val requesterMembershipId: String, override val requesterConnectionId: String,
        override val recipientMembershipId: String, override val recipientConnectionId: String,
        override val expiresAtUtc: String, override val reason: String? = null) : VoiceConsentSignal
    data class Declined(override val roomId: String, override val matchNumber: Int, override val requestId: String,
        override val requesterMembershipId: String, override val requesterConnectionId: String,
        override val recipientMembershipId: String, override val recipientConnectionId: String,
        override val expiresAtUtc: String, override val reason: String? = null) : VoiceConsentSignal
    data class Cancelled(override val roomId: String, override val matchNumber: Int, override val requestId: String,
        override val requesterMembershipId: String, override val requesterConnectionId: String,
        override val recipientMembershipId: String, override val recipientConnectionId: String,
        override val expiresAtUtc: String, override val reason: String? = null) : VoiceConsentSignal
    data class TimedOut(override val roomId: String, override val matchNumber: Int, override val requestId: String,
        override val requesterMembershipId: String, override val requesterConnectionId: String,
        override val recipientMembershipId: String, override val recipientConnectionId: String,
        override val expiresAtUtc: String, override val reason: String? = null) : VoiceConsentSignal
    data class Unavailable(override val roomId: String, override val matchNumber: Int, override val requestId: String,
        override val requesterMembershipId: String, override val requesterConnectionId: String,
        override val recipientMembershipId: String, override val recipientConnectionId: String,
        override val expiresAtUtc: String, override val reason: String? = null) : VoiceConsentSignal
    data class Ended(override val roomId: String, override val matchNumber: Int, override val requestId: String,
        override val requesterMembershipId: String, override val requesterConnectionId: String,
        override val recipientMembershipId: String, override val recipientConnectionId: String,
        override val expiresAtUtc: String, override val reason: String? = null) : VoiceConsentSignal
}

interface VoiceConsentSignalingTransport {
    val consentSignals: Flow<VoiceConsentSignal>
    suspend fun requestVoice(roomId: String, matchNumber: Int): VoiceConsentResult
    suspend fun acceptVoice(roomId: String, matchNumber: Int, requestId: String)
    suspend fun declineVoice(roomId: String, matchNumber: Int, requestId: String)
    suspend fun cancelVoiceRequest(roomId: String, matchNumber: Int, requestId: String)
    suspend fun voiceUnavailable(roomId: String, matchNumber: Int, requestId: String, reason: String)
    suspend fun endVoice(roomId: String, matchNumber: Int, requestId: String)
}

class ManagedVoiceConsentController(
    scope: CoroutineScope,
    private val signaling: VoiceConsentSignalingTransport,
    private val logger: (String) -> Unit = {},
) {
    private val mutableSnapshot = MutableStateFlow(VoiceConsentSnapshot())
    val snapshot: StateFlow<VoiceConsentSnapshot> = mutableSnapshot.asStateFlow()

    init {
        // Subscribe synchronously so a realtime event delivered immediately after
        // coordinator construction cannot be lost before the collector starts.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { signaling.consentSignals.collect(::onSignal) }
    }

    fun bind(roomId: String, matchNumber: Int) {
        val current = mutableSnapshot.value
        if (current.roomId == roomId && current.matchNumber == matchNumber) return
        mutableSnapshot.value = VoiceConsentSnapshot(roomId = roomId, matchNumber = matchNumber)
    }

    suspend fun request() {
        val current = mutableSnapshot.value
        if (current.state == VoiceConsentState.Requesting) return
        val room = requireNotNull(current.roomId)
        val match = requireNotNull(current.matchNumber)
        mutableSnapshot.value = current.copy(state = VoiceConsentState.Requesting, reason = null)
        try {
            val result = signaling.requestVoice(room, match)
            if (!isCurrent(result.roomId, result.matchNumber)) return
            mutableSnapshot.value = mutableSnapshot.value.copy(
                state = VoiceConsentState.Requesting,
                requestId = result.requestId,
                requesterMembershipId = result.requesterMembershipId,
                recipientMembershipId = result.recipientMembershipId,
                expiresAtUtc = result.expiresAtUtc,
            )
        } catch (error: Throwable) {
            logger("voice consent request failed type=${error::class.simpleName}")
            mutableSnapshot.value = current.copy(state = VoiceConsentState.Unavailable, reason = "voice_request_failed")
        }
    }

    suspend fun accept() = act(VoiceConsentState.IncomingRequest, VoiceConsentState.Accepted) { room, match, request -> signaling.acceptVoice(room, match, request) }
    suspend fun decline() = act(VoiceConsentState.IncomingRequest, VoiceConsentState.Declined) { room, match, request -> signaling.declineVoice(room, match, request) }
    suspend fun cancel() = act(VoiceConsentState.Requesting, VoiceConsentState.Cancelled) { room, match, request -> signaling.cancelVoiceRequest(room, match, request) }

    suspend fun unavailable(reason: String) {
        val current = mutableSnapshot.value
        val room = current.roomId ?: return
        val match = current.matchNumber ?: return
        val request = current.requestId ?: return
        runCatching { signaling.voiceUnavailable(room, match, request, reason) }
        mutableSnapshot.value = current.copy(state = VoiceConsentState.Unavailable, reason = reason)
    }

    fun mediaStateChanged(media: VoiceRoomSnapshot) {
        val current = mutableSnapshot.value
        if (current.state !in setOf(VoiceConsentState.Accepted, VoiceConsentState.Joining,
                VoiceConsentState.Connected, VoiceConsentState.Muted, VoiceConsentState.Reconnecting)) return
        val state = when (media.state) {
            VoiceRoomState.Joining, VoiceRoomState.WaitingForPeer, VoiceRoomState.Negotiating -> VoiceConsentState.Joining
            VoiceRoomState.Connected -> if (media.muted) VoiceConsentState.Muted else VoiceConsentState.Connected
            VoiceRoomState.Reconnecting -> VoiceConsentState.Reconnecting
            VoiceRoomState.Failed, VoiceRoomState.Unavailable -> VoiceConsentState.Unavailable
            VoiceRoomState.Idle, VoiceRoomState.PermissionRequired -> current.state
        }
        mutableSnapshot.value = current.copy(state = state, reason = media.error ?: current.reason)
    }

    suspend fun end() {
        val current = mutableSnapshot.value
        if (current.state in setOf(VoiceConsentState.Accepted, VoiceConsentState.Joining,
                VoiceConsentState.Connected, VoiceConsentState.Muted, VoiceConsentState.Reconnecting) &&
            current.roomId != null && current.matchNumber != null && current.requestId != null) {
            runCatching { signaling.endVoice(current.roomId, current.matchNumber, current.requestId) }
        }
        mutableSnapshot.value = current.copy(state = VoiceConsentState.Ended)
    }

    private suspend fun act(required: VoiceConsentState, completed: VoiceConsentState, operation: suspend (String, Int, String) -> Unit) {
        val current = mutableSnapshot.value
        if (current.state != required) return
        operation(requireNotNull(current.roomId), requireNotNull(current.matchNumber), requireNotNull(current.requestId))
        if (mutableSnapshot.value.requestId == current.requestId) mutableSnapshot.value = current.copy(state = completed)
    }

    private fun onSignal(signal: VoiceConsentSignal) {
        if (!isCurrent(signal.roomId, signal.matchNumber)) {
            logger("stale voice consent ignored request=${signal.requestId} match=${signal.matchNumber}")
            return
        }
        val current = mutableSnapshot.value
        val mayStartNewRequest = signal is VoiceConsentSignal.Requested && current.state in setOf(
            VoiceConsentState.Idle, VoiceConsentState.Declined, VoiceConsentState.TimedOut,
            VoiceConsentState.Cancelled, VoiceConsentState.Unavailable, VoiceConsentState.Ended,
        )
        if (current.requestId != null && current.requestId != signal.requestId && !mayStartNewRequest) {
            logger("conflicting voice consent ignored request=${signal.requestId}")
            return
        }
        val state = when (signal) {
            is VoiceConsentSignal.Requested -> VoiceConsentState.IncomingRequest
            is VoiceConsentSignal.Accepted -> VoiceConsentState.Accepted
            is VoiceConsentSignal.Declined -> VoiceConsentState.Declined
            is VoiceConsentSignal.Cancelled -> VoiceConsentState.Cancelled
            is VoiceConsentSignal.TimedOut -> VoiceConsentState.TimedOut
            is VoiceConsentSignal.Unavailable -> VoiceConsentState.Unavailable
            is VoiceConsentSignal.Ended -> VoiceConsentState.Ended
        }
        mutableSnapshot.value = current.copy(
            state = state,
            requestId = signal.requestId,
            requesterMembershipId = signal.requesterMembershipId,
            recipientMembershipId = signal.recipientMembershipId,
            expiresAtUtc = signal.expiresAtUtc,
            reason = signal.reason,
        )
    }

    private fun isCurrent(roomId: String, matchNumber: Int) =
        mutableSnapshot.value.roomId == roomId && mutableSnapshot.value.matchNumber == matchNumber
}
