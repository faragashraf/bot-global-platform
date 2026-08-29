package com.botglobal.lamma.app.realtime

import android.util.Log
import com.botglobal.lamma.app.data.FamilyGamesEnvironment
import com.botglobal.lamma.app.data.GameSessionSnapshot
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.Subscription
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private class SignalRGameRealtimeTransport(
    environment: FamilyGamesEnvironment,
    private val configuration: GameRealtimeTransportConfiguration,
    private val listener: GameRealtimeTransportListener,
) : GameRealtimeTransport {
    private val subscriptions = mutableListOf<Subscription>()
    private val hub: HubConnection = HubConnectionBuilder
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
        .also { connection ->
            connection.setKeepAliveInterval(KeepAliveIntervalMillis)
            connection.setServerTimeout(ServerTimeoutMillis)
            register(connection)
        }

    override suspend fun connectAndRejoin() = withContext(Dispatchers.IO) {
        hub.start()
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingAwait()
        hub.invoke("Rejoin", configuration.sessionId)
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingAwait()
    }

    override suspend fun rejoin() = withContext(Dispatchers.IO) {
        hub.invoke("Rejoin", configuration.sessionId)
            .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
            .blockingAwait()
    }

    override suspend fun dispose() {
        subscriptions.forEach(Subscription::unsubscribe)
        subscriptions.clear()
        runCatching {
            withContext(Dispatchers.IO) {
                hub.stop()
                    .timeout(OperationTimeoutSeconds, TimeUnit.SECONDS)
                    .blockingAwait()
            }
        }
    }

    private fun register(connection: HubConnection) {
        EventNames.forEach { eventName ->
            subscriptions += connection.on(
                eventName,
                { snapshot ->
                    if (snapshot.sessionId == configuration.sessionId) {
                        listener.onEvent(eventName, snapshot)
                    }
                },
                GameSessionSnapshot::class.java,
            )
        }
        connection.onClosed { listener.onClosed() }
    }

    private companion object {
        const val OperationTimeoutSeconds = 4L
        const val KeepAliveIntervalMillis = 5_000L
        // SignalR's server heartbeat defaults to 15 seconds; retain its 30-second client timeout.
        const val ServerTimeoutMillis = 30_000L
        val EventNames = listOf(
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
    }
}

private object AndroidGameRealtimeClients {
    private val lock = Any()
    private val clients = mutableMapOf<String, GameRealtimeClient>()

    fun get(environment: FamilyGamesEnvironment): GameRealtimeClient = synchronized(lock) {
        clients.getOrPut(environment.gamesHubUrl) {
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ManagedGameRealtimeClient(
                ownerScope = ownerScope,
                transportFactory = GameRealtimeTransportFactory { configuration, listener ->
                    SignalRGameRealtimeTransport(environment, configuration, listener)
                },
                logger = RealtimeLifecycleLogger { message -> Log.i(LogTag, message) },
            )
        }
    }

    private const val LogTag = "LammaRealtime"
}

actual fun createGameRealtimeClient(environment: FamilyGamesEnvironment): GameRealtimeClient =
    AndroidGameRealtimeClients.get(environment)
