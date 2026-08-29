package com.botglobal.lamma.app.realtime

import com.botglobal.lamma.app.data.FamilyGamesEnvironment
import com.botglobal.lamma.app.data.GameSessionSnapshot
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import com.botglobal.mobile.platform.realtime.NetworkAvailabilitySnapshot
import com.botglobal.mobile.platform.voice.VoiceSignalingTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import com.botglobal.mobile.platform.voice.VoiceIceConfiguration
import com.botglobal.mobile.platform.voice.VoiceJoinResult
import com.botglobal.mobile.platform.voice.VoiceSignal
import com.botglobal.mobile.platform.voice.VoiceConsentResult
import com.botglobal.mobile.platform.voice.VoiceConsentAuthoritativeState
import com.botglobal.mobile.platform.voice.VoiceConsentSignal
import com.botglobal.mobile.platform.voice.VoiceConsentSignalingTransport

data class GameRealtimeEvent(
    val name: String,
    val snapshot: GameSessionSnapshot,
)

interface GameRealtimeClient : VoiceSignalingTransport, VoiceConsentSignalingTransport {
    val connectionState: StateFlow<RealtimeConnectionState>
    val events: Flow<GameRealtimeEvent>
    override val signals: Flow<VoiceSignal> get() = emptyFlow()
    override val consentSignals: Flow<VoiceConsentSignal> get() = emptyFlow()
    override suspend fun iceConfiguration(roomId: String): VoiceIceConfiguration = error("Voice unavailable")
    override suspend fun join(roomId: String, generation: Long): VoiceJoinResult = error("Voice unavailable")
    override suspend fun leave(roomId: String, generation: Long) = Unit
    override suspend fun offer(roomId: String, generation: Long, sessionDescription: String) = Unit
    override suspend fun answer(roomId: String, generation: Long, sessionDescription: String) = Unit
    override suspend fun iceCandidate(roomId: String, generation: Long, candidate: String, sdpMid: String?, sdpMLineIndex: Int) = Unit
    override suspend fun muted(roomId: String, generation: Long, muted: Boolean) = Unit
    override suspend fun requestVoice(roomId: String, matchNumber: Int): VoiceConsentResult = error("Voice unavailable")
    override suspend fun voiceConsentState(roomId: String, matchNumber: Int): VoiceConsentAuthoritativeState =
        VoiceConsentAuthoritativeState(false, roomId, matchNumber, "", "", "", "", com.botglobal.mobile.platform.voice.VoiceConsentState.Idle)
    override suspend fun acceptVoice(roomId: String, matchNumber: Int, requestId: String) = Unit
    override suspend fun declineVoice(roomId: String, matchNumber: Int, requestId: String) = Unit
    override suspend fun cancelVoiceRequest(roomId: String, matchNumber: Int, requestId: String) = Unit
    override suspend fun voiceUnavailable(roomId: String, matchNumber: Int, requestId: String, reason: String) = Unit
    override suspend fun endVoice(roomId: String, matchNumber: Int, requestId: String) = Unit
    suspend fun start(
        sessionId: String,
        source: RealtimeConnectSource,
        accessToken: suspend () -> String?,
    )
    suspend fun stop()
    suspend fun rejoin()
    suspend fun onNetworkAvailabilityChanged(snapshot: NetworkAvailabilitySnapshot) = Unit
}

enum class RealtimeConnectSource(val logValue: String) {
    AppStart("appStart"),
    SessionCreated("sessionCreated"),
    SessionJoined("sessionJoined"),
    InvitationResolved("invitationResolved"),
    Foreground("foreground"),
    NetworkAvailable("networkAvailable"),
    ManualRetry("manualRetry"),
}

expect fun createGameRealtimeClient(environment: FamilyGamesEnvironment): GameRealtimeClient
