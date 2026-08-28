package com.botglobal.familygames.app.realtime

import com.botglobal.familygames.app.data.FamilyGamesEnvironment
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private class IosRealtimeBoundary : GameRealtimeClient {
    override val connectionState: StateFlow<RealtimeConnectionState> =
        MutableStateFlow(RealtimeConnectionState.Unavailable)
    override val events: Flow<GameRealtimeEvent> = MutableSharedFlow()
    override suspend fun start(sessionId: String, accessToken: suspend () -> String?) = Unit
    override suspend fun stop() = Unit
    override suspend fun rejoin() = Unit
}

actual fun createGameRealtimeClient(environment: FamilyGamesEnvironment): GameRealtimeClient = IosRealtimeBoundary()
