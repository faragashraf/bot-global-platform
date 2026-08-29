package com.botglobal.familygames.app.realtime

import com.botglobal.familygames.app.data.FamilyGamesEnvironment
import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class GameRealtimeEvent(
    val name: String,
    val snapshot: GameSessionSnapshot,
)

interface GameRealtimeClient {
    val connectionState: StateFlow<RealtimeConnectionState>
    val events: Flow<GameRealtimeEvent>
    suspend fun start(
        sessionId: String,
        source: RealtimeConnectSource,
        accessToken: suspend () -> String?,
    )
    suspend fun stop()
    suspend fun rejoin()
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
