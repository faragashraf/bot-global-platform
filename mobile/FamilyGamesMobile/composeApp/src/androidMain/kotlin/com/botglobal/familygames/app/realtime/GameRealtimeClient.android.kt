package com.botglobal.familygames.app.realtime

import com.botglobal.familygames.app.data.FamilyGamesEnvironment
import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private class AndroidGameRealtimeClient(
    private val environment: FamilyGamesEnvironment,
) : GameRealtimeClient {
    private val mutableState = MutableStateFlow(RealtimeConnectionState.Disconnected)
    private val mutableEvents = MutableSharedFlow<GameRealtimeEvent>(extraBufferCapacity = 32)
    private var connection: HubConnection? = null
    private var activeSessionId: String? = null
    private var tokenProvider: (suspend () -> String?)? = null
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val connectionState: StateFlow<RealtimeConnectionState> = mutableState.asStateFlow()
    override val events: Flow<GameRealtimeEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(sessionId: String, accessToken: suspend () -> String?) {
        stop()
        activeSessionId = sessionId
        tokenProvider = accessToken
        mutableState.value = RealtimeConnectionState.Connecting
        val hub = HubConnectionBuilder
            .create(environment.gamesHubUrl)
            .withAccessTokenProvider(
                Single.defer {
                    val token = kotlinx.coroutines.runBlocking { tokenProvider?.invoke() }
                    if (token.isNullOrBlank()) Single.error(IllegalStateException("Mobile session is unavailable."))
                    else Single.just(token)
                },
            )
            .build()
        register(hub)
        connection = hub
        try {
            withContext(Dispatchers.IO) { hub.start().blockingAwait() }
            mutableState.value = RealtimeConnectionState.Connected
            rejoin()
        } catch (error: Throwable) {
            if (connection === hub) mutableState.value = RealtimeConnectionState.Failed
            throw error
        }
    }

    override suspend fun stop() {
        val current = connection
        connection = null
        activeSessionId = null
        tokenProvider = null
        if (current != null) {
            runCatching { withContext(Dispatchers.IO) { current.stop().blockingAwait() } }
        }
        mutableState.value = RealtimeConnectionState.Disconnected
    }

    override suspend fun rejoin() {
        val current = connection ?: return
        val sessionId = activeSessionId ?: return
        withContext(Dispatchers.IO) { current.invoke("Rejoin", sessionId).blockingAwait() }
    }

    private fun register(hub: HubConnection) {
        val eventNames = listOf(
            "SessionCreated",
            "PlayerJoined",
            "PlayerReady",
            "GameStarted",
            "GameStateUpdated",
            "MoveAccepted",
            "PlayerConnectionChanged",
            "GameCompleted",
            "RematchRequested",
            "RematchAccepted",
        )
        eventNames.forEach { eventName ->
            hub.on(
                eventName,
                { snapshot -> mutableEvents.tryEmit(GameRealtimeEvent(eventName, snapshot)) },
                GameSessionSnapshot::class.java,
            )
        }
        hub.onClosed {
            if (connection === hub && activeSessionId != null) {
                mutableState.value = RealtimeConnectionState.Reconnecting
                reconnectScope.launchReconnect(hub)
            } else {
                mutableState.value = RealtimeConnectionState.Disconnected
            }
        }
    }

    private fun CoroutineScope.launchReconnect(hub: HubConnection) =
        launch {
            repeat(6) { attempt ->
                if (connection !== hub || activeSessionId == null) return@launch
                delay((attempt + 1) * 1_000L)
                val restored = runCatching {
                    hub.start().blockingAwait()
                    val sessionId = activeSessionId ?: return@runCatching
                    hub.invoke("Rejoin", sessionId).blockingAwait()
                }.isSuccess
                if (restored) {
                    mutableState.value = RealtimeConnectionState.Connected
                    return@launch
                }
            }
            mutableState.value = RealtimeConnectionState.Failed
        }
}

actual fun createGameRealtimeClient(environment: FamilyGamesEnvironment): GameRealtimeClient =
    AndroidGameRealtimeClient(environment)
