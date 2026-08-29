package com.botglobal.familygames.app.realtime

import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.mobile.platform.realtime.NetworkAvailabilitySnapshot
import com.botglobal.mobile.platform.realtime.NetworkAvailabilityState
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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

internal data class GameRealtimeTransportConfiguration(
    val sessionId: String,
    val accessToken: suspend () -> String?,
    val generation: Long,
    val instance: Long,
)

internal interface GameRealtimeTransportListener {
    fun onEvent(name: String, snapshot: GameSessionSnapshot)
    fun onClosed()
}

internal interface GameRealtimeTransport {
    suspend fun connectAndRejoin()
    suspend fun rejoin()
    suspend fun dispose()
}

internal fun interface GameRealtimeTransportFactory {
    fun create(
        configuration: GameRealtimeTransportConfiguration,
        listener: GameRealtimeTransportListener,
    ): GameRealtimeTransport
}

internal fun interface RealtimeLifecycleLogger {
    fun log(message: String)
}

internal class ManagedGameRealtimeClient(
    private val ownerScope: CoroutineScope,
    private val transportFactory: GameRealtimeTransportFactory,
    private val logger: RealtimeLifecycleLogger = RealtimeLifecycleLogger {},
    private val reconnectBackoffMillis: List<Long> = DefaultReconnectBackoff,
) : GameRealtimeClient {
    private val mutableState = MutableStateFlow(RealtimeConnectionState.Disconnected)
    private val mutableEvents = MutableSharedFlow<GameRealtimeEvent>(extraBufferCapacity = 32)
    private val operationMutex = Mutex()
    private var activeTransport: ActiveTransport? = null
    private var activeSessionId: String? = null
    private var tokenProvider: (suspend () -> String?)? = null
    private var generation = 0L
    private var nextInstance = 0L
    private var reconnectJob: Job? = null
    private var networkObserverGeneration = Long.MIN_VALUE
    private var networkRevision = Long.MIN_VALUE
    private var networkState = NetworkAvailabilityState.Unknown

    override val connectionState: StateFlow<RealtimeConnectionState> = mutableState.asStateFlow()
    override val events: Flow<GameRealtimeEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(
        sessionId: String,
        source: RealtimeConnectSource,
        accessToken: suspend () -> String?,
    ) = operationMutex.withLock {
        val canCoalesce = activeSessionId == sessionId && mutableState.value in CoalescedStates
        val requestedGeneration = if (canCoalesce) generation else generation + 1
        logger.log(
            "realtime connect requested source=${source.logValue} " +
                "generation=$requestedGeneration state=${mutableState.value}",
        )
        if (canCoalesce) {
            tokenProvider = accessToken
            logger.log(
                "realtime connect coalesced source=${source.logValue} " +
                    "generation=$generation state=${mutableState.value}",
            )
            return@withLock
        }

        replaceTransportLocked(sessionId, accessToken, source)
    }

    override suspend fun stop() = operationMutex.withLock {
        val disposedGeneration = activeTransport?.configuration?.generation
        activeSessionId = null
        tokenProvider = null
        reconnectJob?.cancel()
        reconnectJob = null
        disposeActiveTransportLocked()
        mutableState.value = RealtimeConnectionState.Disconnected
        if (disposedGeneration != null) {
            logger.log(
                "transport disposed generation=$disposedGeneration reason=explicitStop " +
                    "state=${mutableState.value}",
            )
        }
    }

    override suspend fun rejoin() = operationMutex.withLock {
        activeTransport?.transport?.rejoin()
            ?: error("Realtime transport is disconnected.")
    }

    override suspend fun onNetworkAvailabilityChanged(snapshot: NetworkAvailabilitySnapshot) =
        operationMutex.withLock {
            if (!acceptNetworkSnapshot(snapshot)) {
                logger.log(
                    "connectivity stale ignored observerGeneration=${snapshot.observerGeneration} " +
                        "networkRevision=${snapshot.revision} state=${snapshot.state}",
                )
                return@withLock
            }

            val previousState = networkState
            networkObserverGeneration = snapshot.observerGeneration
            networkRevision = snapshot.revision
            networkState = snapshot.state
            logger.log(
                "connectivity ${snapshot.state.name.lowercase()} " +
                    "observerGeneration=${snapshot.observerGeneration} " +
                    "networkRevision=${snapshot.revision} generation=$generation " +
                    "state=${mutableState.value}",
            )
            if (previousState == snapshot.state) {
                logger.log(
                    "realtime recovery coalesced source=connectivityDuplicate " +
                        "generation=$generation state=${mutableState.value}",
                )
                return@withLock
            }

            when (snapshot.state) {
                NetworkAvailabilityState.Unavailable -> onNetworkUnavailableLocked()
                NetworkAvailabilityState.Available -> onNetworkAvailableLocked()
                NetworkAvailabilityState.Unknown -> Unit
            }
        }

    private suspend fun replaceTransportLocked(
        sessionId: String,
        accessToken: suspend () -> String?,
        source: RealtimeConnectSource,
    ) {
        val replacedGeneration = activeTransport?.configuration?.generation
        reconnectJob?.cancel()
        reconnectJob = null
        activeSessionId = null
        tokenProvider = null
        disposeActiveTransportLocked()
        if (replacedGeneration != null) {
            logger.log(
                "transport disposed generation=$replacedGeneration reason=replaced " +
                    "state=${mutableState.value}",
            )
        }

        generation++
        activeSessionId = sessionId
        tokenProvider = accessToken
        if (networkState == NetworkAvailabilityState.Unavailable) {
            mutableState.value = RealtimeConnectionState.Reconnecting
            logger.log(
                "realtime connect deferred source=${source.logValue} generation=$generation " +
                    "reason=networkUnavailable state=${mutableState.value}",
            )
            return
        }
        mutableState.value = RealtimeConnectionState.Connecting
        try {
            connectAttemptLocked(generation)
        } catch (error: Throwable) {
            if (isCurrentGeneration(generation)) {
                mutableState.value = RealtimeConnectionState.Failed
                scheduleReconnectLocked(generation)
                logger.log(
                    "transport connect failed source=${source.logValue} " +
                        "generation=$generation state=${mutableState.value}",
                )
            }
            throw error
        }
    }

    private suspend fun connectAttemptLocked(expectedGeneration: Long) {
        val sessionId = activeSessionId ?: return
        val provider = tokenProvider ?: return
        if (!isCurrentGeneration(expectedGeneration)) return
        val configuration = GameRealtimeTransportConfiguration(
            sessionId = sessionId,
            accessToken = provider,
            generation = expectedGeneration,
            instance = ++nextInstance,
        )
        val listener = listenerFor(configuration)
        val transport = transportFactory.create(configuration, listener)
        val active = ActiveTransport(configuration, transport)
        activeTransport = active
        try {
            transport.connectAndRejoin()
            if (activeTransport === active && isCurrentGeneration(expectedGeneration)) {
                mutableState.value = RealtimeConnectionState.Connected
                logger.log(
                    "transport connected generation=$expectedGeneration " +
                        "instance=${configuration.instance} state=${mutableState.value}",
                )
            } else {
                transport.dispose()
            }
        } catch (error: Throwable) {
            if (activeTransport === active) activeTransport = null
            transport.dispose()
            throw error
        }
    }

    private fun listenerFor(configuration: GameRealtimeTransportConfiguration) =
        object : GameRealtimeTransportListener {
            override fun onEvent(name: String, snapshot: GameSessionSnapshot) {
                ownerScope.launch {
                    operationMutex.withLock {
                        if (isCurrentTransport(configuration)) {
                            if (name == PresenceEventName) {
                                logger.log(
                                    "realtime presence received event=$name " +
                                        "generation=${configuration.generation} " +
                                        "instance=${configuration.instance} " +
                                        "sessionRevision=${snapshot.revision} state=${mutableState.value}",
                                )
                            }
                            mutableEvents.tryEmit(GameRealtimeEvent(name, snapshot))
                        } else {
                            logger.log(
                                "transport stale event ignored generation=${configuration.generation} " +
                                    "instance=${configuration.instance} event=$name " +
                                    "state=${mutableState.value}",
                            )
                        }
                    }
                }
            }

            override fun onClosed() {
                ownerScope.launch {
                    operationMutex.withLock {
                        if (!isCurrentTransport(configuration)) {
                            logger.log(
                                "transport stale close ignored generation=${configuration.generation} " +
                                    "instance=${configuration.instance} state=${mutableState.value}",
                            )
                            return@withLock
                        }
                        val closedTransport = activeTransport
                        activeTransport = null
                        mutableState.value = RealtimeConnectionState.Reconnecting
                        logger.log(
                            "transport reconnecting generation=${configuration.generation} " +
                                "instance=${configuration.instance} state=${mutableState.value}",
                        )
                        scheduleReconnectLocked(configuration.generation)
                        closedTransport?.transport?.dispose()
                    }
                }
            }
        }

    private fun scheduleReconnectLocked(expectedGeneration: Long) {
        if (
            !isCurrentGeneration(expectedGeneration) ||
            reconnectJob?.isActive == true ||
            networkState == NetworkAvailabilityState.Unavailable
        ) return
        reconnectJob = ownerScope.launch { reconnect(expectedGeneration) }
    }

    private suspend fun onNetworkUnavailableLocked() {
        if (activeSessionId == null) return
        reconnectJob?.cancel()
        reconnectJob = null
        val interrupted = activeTransport
        activeTransport = null
        mutableState.value = RealtimeConnectionState.Reconnecting
        logger.log(
            "realtime recovery requested source=networkUnavailable " +
                "generation=$generation state=${mutableState.value}",
        )
        if (interrupted != null) {
            interrupted.transport.dispose()
            logger.log(
                "transport disposed generation=${interrupted.configuration.generation} " +
                    "instance=${interrupted.configuration.instance} reason=networkUnavailable " +
                    "state=${mutableState.value}",
            )
        }
    }

    private fun onNetworkAvailableLocked() {
        if (activeSessionId == null) return
        if (
            mutableState.value in RecoverableNetworkStates &&
            activeTransport == null
        ) {
            mutableState.value = RealtimeConnectionState.Reconnecting
            logger.log(
                "realtime recovery requested source=networkAvailable " +
                    "generation=$generation state=${mutableState.value}",
            )
            scheduleReconnectLocked(generation)
        }
    }

    private suspend fun reconnect(expectedGeneration: Long) {
        val runningJob = currentCoroutineContext()[Job]
        try {
            reconnectBackoffMillis.forEachIndexed { index, backoffMillis ->
                if (backoffMillis > 0) delay(backoffMillis)
                val restored = operationMutex.withLock {
                    if (!isCurrentGeneration(expectedGeneration)) return
                    mutableState.value = RealtimeConnectionState.Reconnecting
                    logger.log(
                        "transport reconnect attempt=${index + 1} " +
                            "generation=$expectedGeneration state=${mutableState.value}",
                    )
                    runCatching { connectAttemptLocked(expectedGeneration) }.isSuccess &&
                        mutableState.value == RealtimeConnectionState.Connected
                }
                if (restored) return
            }
            operationMutex.withLock {
                if (isCurrentGeneration(expectedGeneration)) {
                    mutableState.value = RealtimeConnectionState.Failed
                    logger.log(
                        "transport reconnect exhausted generation=$expectedGeneration " +
                            "state=${mutableState.value}",
                    )
                }
            }
        } finally {
            operationMutex.withLock {
                if (reconnectJob === runningJob) reconnectJob = null
            }
        }
    }

    private suspend fun disposeActiveTransportLocked() {
        val current = activeTransport
        activeTransport = null
        current?.transport?.dispose()
    }

    private fun isCurrentGeneration(expectedGeneration: Long): Boolean =
        generation == expectedGeneration && activeSessionId != null

    private fun isCurrentTransport(configuration: GameRealtimeTransportConfiguration): Boolean =
        activeTransport?.configuration == configuration && isCurrentGeneration(configuration.generation)

    private fun acceptNetworkSnapshot(snapshot: NetworkAvailabilitySnapshot): Boolean =
        snapshot.observerGeneration > networkObserverGeneration ||
            snapshot.observerGeneration == networkObserverGeneration && snapshot.revision > networkRevision

    private data class ActiveTransport(
        val configuration: GameRealtimeTransportConfiguration,
        val transport: GameRealtimeTransport,
    )

    private companion object {
        val CoalescedStates = setOf(
            RealtimeConnectionState.Connecting,
            RealtimeConnectionState.Connected,
            RealtimeConnectionState.Reconnecting,
        )
        val RecoverableNetworkStates = setOf(
            RealtimeConnectionState.Disconnected,
            RealtimeConnectionState.Reconnecting,
            RealtimeConnectionState.Failed,
        )
        val DefaultReconnectBackoff = listOf(0L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        const val PresenceEventName = "PlayerConnectionChanged"
    }
}
