package com.botglobal.familygames.app.realtime

import android.util.Log
import com.botglobal.familygames.app.data.FamilyGamesEnvironment
import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private class AndroidGameRealtimeClient(
    private val environment: FamilyGamesEnvironment,
) : GameRealtimeClient {
    private val mutableState = MutableStateFlow(RealtimeConnectionState.Disconnected)
    private val mutableEvents = MutableSharedFlow<GameRealtimeEvent>(extraBufferCapacity = 32)
    private var connection: HubConnection? = null
    private var activeSessionId: String? = null
    private var tokenProvider: (suspend () -> String?)? = null
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private var connectionGeneration = 0L
    private var reconnectJob: Job? = null

    override val connectionState: StateFlow<RealtimeConnectionState> = mutableState.asStateFlow()
    override val events: Flow<GameRealtimeEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(sessionId: String, accessToken: suspend () -> String?) =
        operationMutex.withLock {
            stopLocked()
            val configuration = synchronized(stateLock) {
                connectionGeneration++
                activeSessionId = sessionId
                tokenProvider = accessToken
                ConnectionConfiguration(sessionId, accessToken, connectionGeneration)
            }
            mutableState.value = RealtimeConnectionState.Connecting
            try {
                connectFresh(configuration)
            } catch (error: Throwable) {
                if (isCurrent(configuration.generation)) {
                    mutableState.value = RealtimeConnectionState.Failed
                    scheduleReconnect(configuration.generation)
                }
                throw error
            }
        }

    override suspend fun stop() = operationMutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        val current = synchronized(stateLock) {
            connectionGeneration++
            reconnectJob?.cancel()
            reconnectJob = null
            connection.also {
                connection = null
                activeSessionId = null
                tokenProvider = null
            }
        }
        if (current != null) {
            runCatching { withContext(Dispatchers.IO) { current.stop().blockingAwait() } }
        }
        mutableState.value = RealtimeConnectionState.Disconnected
    }

    override suspend fun rejoin() {
        val current = synchronized(stateLock) {
            val hub = connection ?: error("Realtime transport is disconnected.")
            val sessionId = activeSessionId ?: error("Realtime session is unavailable.")
            hub to sessionId
        }
        withContext(Dispatchers.IO) {
            current.first
                .invoke("Rejoin", current.second)
                .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
                .blockingAwait()
        }
    }

    private suspend fun connectFresh(configuration: ConnectionConfiguration) {
        val hub = buildHub(configuration)
        register(hub, configuration)
        val accepted = synchronized(stateLock) {
            if (!isCurrentLocked(configuration.generation)) {
                false
            } else {
                connection = hub
                true
            }
        }
        if (!accepted) {
            runCatching { withContext(Dispatchers.IO) { hub.stop().blockingAwait() } }
            return
        }

        try {
            withContext(Dispatchers.IO) {
                hub.start()
                    .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
                    .blockingAwait()
                hub.invoke("Rejoin", configuration.sessionId)
                    .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
                    .blockingAwait()
            }
            if (isCurrentHub(hub, configuration.generation)) {
                mutableState.value = RealtimeConnectionState.Connected
                Log.i(LogTag, "transport connected generation=${configuration.generation}")
            } else {
                runCatching { withContext(Dispatchers.IO) { hub.stop().blockingAwait() } }
            }
        } catch (error: Throwable) {
            synchronized(stateLock) {
                if (connection === hub) connection = null
            }
            runCatching { withContext(Dispatchers.IO) { hub.stop().blockingAwait() } }
            throw error
        }
    }

    private fun buildHub(configuration: ConnectionConfiguration): HubConnection =
        HubConnectionBuilder
            .create(environment.gamesHubUrl)
            .withAccessTokenProvider(
                Single.defer {
                    val token = kotlinx.coroutines.runBlocking { configuration.accessToken() }
                    if (token.isNullOrBlank()) {
                        Single.error(IllegalStateException("Mobile session is unavailable."))
                    } else {
                        Single.just(token)
                    }
                },
            )
            .build()
            .also { hub ->
                hub.setKeepAliveInterval(KeepAliveIntervalMillis)
                hub.setServerTimeout(ServerTimeoutMillis)
            }

    private fun register(hub: HubConnection, configuration: ConnectionConfiguration) {
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
                { snapshot ->
                    if (isCurrentHub(hub, configuration.generation) &&
                        snapshot.sessionId == configuration.sessionId
                    ) {
                        mutableEvents.tryEmit(GameRealtimeEvent(eventName, snapshot))
                    }
                },
                GameSessionSnapshot::class.java,
            )
        }
        hub.onClosed {
            val shouldReconnect = synchronized(stateLock) {
                if (connection === hub && isCurrentLocked(configuration.generation)) {
                    connection = null
                    true
                } else {
                    false
                }
            }
            if (shouldReconnect) {
                mutableState.value = RealtimeConnectionState.Reconnecting
                Log.i(LogTag, "transport interrupted generation=${configuration.generation}")
                scheduleReconnect(configuration.generation)
            }
        }
    }

    private fun scheduleReconnect(generation: Long) {
        synchronized(stateLock) {
            if (!isCurrentLocked(generation) || reconnectJob?.isActive == true) return
            reconnectJob = reconnectScope.launch {
                reconnect(generation)
            }
        }
    }

    private suspend fun reconnect(generation: Long) {
        try {
            ReconnectBackoff.forEachIndexed { index, backoffMillis ->
                if (backoffMillis > 0) delay(backoffMillis)
                val configuration = synchronized(stateLock) {
                    if (!isCurrentLocked(generation)) return
                    val sessionId = activeSessionId ?: return
                    val provider = tokenProvider ?: return
                    ConnectionConfiguration(sessionId, provider, generation)
                }
                Log.i(LogTag, "transport reconnect attempt=${index + 1} generation=$generation")
                val restored = runCatching {
                    operationMutex.withLock {
                        if (!isCurrent(generation)) return@withLock
                        connectFresh(configuration)
                    }
                }.isSuccess && mutableState.value == RealtimeConnectionState.Connected
                if (restored) return
            }
            if (isCurrent(generation)) {
                mutableState.value = RealtimeConnectionState.Failed
                Log.w(LogTag, "transport reconnect exhausted generation=$generation")
            }
        } finally {
            synchronized(stateLock) {
                if (isCurrentLocked(generation)) reconnectJob = null
            }
        }
    }

    private fun isCurrent(generation: Long): Boolean =
        synchronized(stateLock) { isCurrentLocked(generation) }

    private fun isCurrentLocked(generation: Long): Boolean =
        connectionGeneration == generation && activeSessionId != null

    private fun isCurrentHub(hub: HubConnection, generation: Long): Boolean =
        synchronized(stateLock) {
            connection === hub && isCurrentLocked(generation)
        }

    private data class ConnectionConfiguration(
        val sessionId: String,
        val accessToken: suspend () -> String?,
        val generation: Long,
    )

    private companion object {
        const val LogTag = "LammaRealtime"
        const val OperationTimeoutSeconds = 4L
        const val KeepAliveIntervalMillis = 5_000L
        const val ServerTimeoutMillis = 15_000L
        val ReconnectBackoff = listOf(0L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}

actual fun createGameRealtimeClient(environment: FamilyGamesEnvironment): GameRealtimeClient =
    AndroidGameRealtimeClient(environment)
