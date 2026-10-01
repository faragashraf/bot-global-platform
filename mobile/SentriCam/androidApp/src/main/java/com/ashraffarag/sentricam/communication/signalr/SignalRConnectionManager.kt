package com.ashraffarag.sentricam.communication.signalr

import com.ashraffarag.sentricam.device.domain.DeviceSnapshot
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import com.ashraffarag.sentricam.live.capability.LiveSignalReceiver
import com.ashraffarag.sentricam.live.capability.LiveSignalingSender
import com.ashraffarag.sentricam.live.capability.NoOpLiveSignalReceiver
import com.ashraffarag.sentricam.live.domain.LiveIceCandidate
import com.ashraffarag.sentricam.live.domain.LiveSessionDescription
import com.ashraffarag.sentricam.live.domain.LiveSessionStatistics
import com.ashraffarag.sentricam.live.domain.LiveSessionStatusUpdate
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlSignalReceiver
import com.ashraffarag.sentricam.cameracontrol.capability.CameraControlSignalingSender
import com.ashraffarag.sentricam.cameracontrol.capability.NoOpCameraControlSignalReceiver
import com.ashraffarag.sentricam.cameracontrol.domain.CameraControlCommandResult

class SignalRConnectionManager(
    private val clientFactory: SignalRClientFactory,
    private val configurationProvider: SignalRConfigurationProvider,
    private val credentialProvider: SecureSignalRCredentialProvider,
    private val networkMonitor: SignalRNetworkMonitor,
    private val snapshots: StateFlow<DeviceSnapshot>,
    private val snapshotVersions: SnapshotVersionProvider,
    private val clock: SignalRClock,
    private val scope: CoroutineScope,
    private val policy: ConnectionPolicy = ConnectionPolicy(),
    private val connectionDelay: SignalRDelay = SignalRDelay { delay(it) },
    private val reconnectJitter: SignalRJitter = BoundedRandomSignalRJitter,
    private val logger: SignalRLogger = NoOpSignalRLogger,
    private val stateMachine: SignalRStateMachine = SignalRStateMachine(),
    private val heartbeatAcknowledged: (SignalRHeartbeatAcknowledgement, Long) -> Unit = { _, _ -> },
    private val pairingRevoked: (DevicePairingRevoked) -> Unit = {},
) : AutoCloseable, LiveSignalingSender, CameraControlSignalingSender {
    private val lock = Any()
    private var desiredRunning = false
    private var generation = 0L
    private var activeJob: Job? = null
    private var commandExecutor: SignalRCommandExecutor? = null
    private var liveReceiver: LiveSignalReceiver = NoOpLiveSignalReceiver
    private var cameraControlReceiver: CameraControlSignalReceiver = NoOpCameraControlSignalReceiver
    private var connectedClient: SignalRClient? = null
    private val outbound = Mutex()
    private var commandsInFlight = 0
    private var stopPending = false
    private val mutableMetrics = MutableStateFlow(SignalRConnectionMetrics())
    val state: StateFlow<SignalRState> = stateMachine.state
    val metrics: StateFlow<SignalRConnectionMetrics> = mutableMetrics.asStateFlow()

    fun configuredHubUrl(): String = configurationProvider.load().getOrNull()?.hubUrl.orEmpty()

    fun start(
        commandExecutor: SignalRCommandExecutor = SignalRCommandExecutor {
            throw SignalRTransportException(SignalRFailureCode.PROTOCOL_ERROR)
        },
        liveReceiver: LiveSignalReceiver = NoOpLiveSignalReceiver,
        cameraControlReceiver: CameraControlSignalReceiver = NoOpCameraControlSignalReceiver,
    ) {
        synchronized(lock) {
            if (desiredRunning && activeJob?.isActive == true) return
            desiredRunning = true
            stopPending = false
            this.commandExecutor = commandExecutor
            this.liveReceiver = liveReceiver
            this.cameraControlReceiver = cameraControlReceiver
            val runGeneration = ++generation
            stateMachine.transition(SignalRLifecycleEvent.Start())
            activeJob = scope.launch { runConnectionLoop(runGeneration, initialReconnectAttempts = 0) }
        }
    }

    fun forceReconnect() {
        synchronized(lock) {
            if (!desiredRunning) return
            val previous = activeJob
            val runGeneration = ++generation
            stateMachine.transition(
                SignalRLifecycleEvent.Lost(
                    SignalRFailureCode.CONNECTION_CLOSED.stableCode,
                    reconnectAttempts = 1,
                ),
            )
            activeJob = scope.launch {
                previous?.cancelAndJoin()
                if (isCurrent(runGeneration)) {
                    stateMachine.transition(SignalRLifecycleEvent.Start(1))
                    runConnectionLoop(runGeneration, initialReconnectAttempts = 1)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            desiredRunning = false
            commandExecutor = null
            liveReceiver.connectionLost()
            liveReceiver = NoOpLiveSignalReceiver
            cameraControlReceiver = NoOpCameraControlSignalReceiver
            connectedClient = null
            generation++
            if (commandsInFlight > 0) {
                stopPending = true
            } else {
                activeJob?.cancel()
                activeJob = null
            }
            mutableMetrics.value = mutableMetrics.value.copy(
                connectionId = null,
                reconnectAttempts = 0,
            )
            stateMachine.transition(SignalRLifecycleEvent.Stop)
        }
    }

    override fun close() = stop()

    private suspend fun runConnectionLoop(runGeneration: Long, initialReconnectAttempts: Int) {
        var reconnectAttempts = initialReconnectAttempts
        while (isCurrent(runGeneration)) {
            if (!awaitValidatedNetwork(runGeneration, reconnectAttempts)) return

            val configuration = configurationProvider.load().getOrElse { failure ->
                failPermanently(failure, reconnectAttempts)
                return
            }
            val credentials = credentialProvider.load().getOrElse { failure ->
                failPermanently(failure, reconnectAttempts)
                return
            }
            if (reconnectAttempts == 0) {
                stateMachine.transition(SignalRLifecycleEvent.Start())
            } else if (state.value !is SignalRState.Reconnecting) {
                stateMachine.transition(
                    SignalRLifecycleEvent.RetryScheduled(
                        reconnectAttempts,
                        clock.nowMillis(),
                        SignalRFailureCode.CONNECTION_CLOSED.stableCode,
                    ),
                )
            }

            val closed = CompletableDeferred<Throwable?>()
            val commands = Channel<RemoteDeviceCommand>(Channel.UNLIMITED)
            val client = clientFactory.create()
            val currentLiveReceiver = synchronized(lock) { liveReceiver }
            val currentCameraControlReceiver = synchronized(lock) { cameraControlReceiver }
            var connectionId: String? = null
            var clientDisposed = false
            try {
                val connection = client.connect(
                    configuration,
                    SignalRAccessTokenProvider { credentials.accessToken },
                    ConnectionLifecycle { cause -> closed.complete(cause) },
                    SignalRCommandReceiver { command -> commands.trySend(command) },
                    PairingRevocationReceiver(pairingRevoked),
                    currentLiveReceiver,
                    currentCameraControlReceiver,
                )
                synchronized(lock) { connectedClient = client }
                outbound.withLock {
                    client.reportLiveCapabilities(
                        currentLiveReceiver.capabilities(credentials.serverDeviceId),
                    )
                    client.reportCameraControlState(
                        currentCameraControlReceiver.state(credentials.serverDeviceId),
                    )
                }
                connectionId = connection.connectionId
                val connectedAt = clock.nowMillis()
                mutableMetrics.value = mutableMetrics.value.copy(
                    connectionId = connection.connectionId,
                    connectedAtMillis = connectedAt,
                    reconnectAttempts = reconnectAttempts,
                )
                stateMachine.transition(
                    SignalRLifecycleEvent.Connected(
                        connection.connectionId,
                        connectedAt,
                        reconnectAttempts,
                    ),
                )
                logger.connected(
                    URI(configuration.hubUrl).host,
                    connection.connectionId,
                    reconnectAttempts,
                )
                // A completed handshake and current capability/state publication is a recovery
                // boundary. Future outages start at the first bounded delay again.
                reconnectAttempts = 0
                val closedCause = runConnectedSession(
                    runGeneration,
                    client,
                    credentials.serverDeviceId,
                    connection.connectionId,
                    commands,
                    closed,
                )
                if (closedCause is SignalRTransportException) throw closedCause
                throw SignalRTransportException(
                    SignalRFailureCode.CONNECTION_CLOSED,
                    closedCause,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                currentLiveReceiver.connectionLost()
                val failure = exception.asTransportFailure()
                logger.disconnected(connectionId, failure)
                disposeFailedClient(client, connectionId, failure.failureCode.stableCode)
                clientDisposed = true
                if (!failure.failureCode.retryAllowed) {
                    failPermanently(failure, reconnectAttempts)
                    return
                }
                stateMachine.transition(
                    SignalRLifecycleEvent.Lost(failure.failureCode.stableCode, reconnectAttempts),
                )
                reconnectAttempts = if (reconnectAttempts == Int.MAX_VALUE) {
                    Int.MAX_VALUE
                } else {
                    reconnectAttempts + 1
                }
                mutableMetrics.value = mutableMetrics.value.copy(
                    connectionId = null,
                    reconnectAttempts = reconnectAttempts,
                )
                val baseDelay = policy.reconnectDelaysMillis[
                    (reconnectAttempts - 1).coerceAtMost(policy.reconnectDelaysMillis.lastIndex)
                ]
                val retryDelay = reconnectJitter.apply(baseDelay, reconnectAttempts)
                stateMachine.transition(
                    SignalRLifecycleEvent.RetryScheduled(
                        reconnectAttempts,
                        clock.nowMillis() + retryDelay,
                        failure.failureCode.stableCode,
                    ),
                )
                logger.reconnecting(
                    URI(configuration.hubUrl).host,
                    reconnectAttempts,
                    retryDelay,
                    failure.failureCode.stableCode,
                )
                waitForRetryOrValidatedNetwork(
                    runGeneration = runGeneration,
                    reconnectAttempts = reconnectAttempts,
                    retryDelay = retryDelay,
                )
            } finally {
                synchronized(lock) {
                    if (connectedClient === client) connectedClient = null
                }
                commands.close()
                if (!clientDisposed) {
                    runCatching { client.disconnect() }
                    runCatching { client.close() }
                }
            }
        }
    }

    private suspend fun disposeFailedClient(
        client: SignalRClient,
        connectionId: String?,
        reasonCode: String,
    ) {
        synchronized(lock) {
            if (connectedClient === client) connectedClient = null
        }
        // Abort first: a graceful stop can wait on the same broken socket that caused the retry.
        runCatching { client.close() }
        runCatching { client.disconnect() }
        logger.staleConnectionDisposed(connectionId, reasonCode)
    }

    private suspend fun runConnectedSession(
        runGeneration: Long,
        client: SignalRClient,
        serverDeviceId: String,
        connectionId: String,
        commands: Channel<RemoteDeviceCommand>,
        closed: CompletableDeferred<Throwable?>,
    ): Throwable? = coroutineScope {
        val heartbeatJob = launch {
            publishHeartbeats(runGeneration, client, serverDeviceId, connectionId, closed, outbound)
        }
        val transportPulseJob = launch {
            while (isCurrent(runGeneration) && networkMonitor.state.value.validated && !closed.isCompleted) {
                outbound.withLock { client.transportPulse() }
                withTimeoutOrNull(policy.transportPulseIntervalMillis) { closed.await() }
            }
        }
        val healthJob = launch {
            snapshots
                .map { it.subsystemHealth }
                .distinctUntilChanged()
                .collect { health ->
                    outbound.withLock {
                        client.reportOperationalHealth(
                            DeviceOperationalHealthReport(
                                serverDeviceId,
                                health,
                                Instant.ofEpochMilli(clock.nowMillis()).toString(),
                            ),
                        )
                    }
                }
        }
        val commandJob = launch {
            for (command in commands) {
                if (!isCurrent(runGeneration)) break
                if (command.deviceId != serverDeviceId) {
                    throw SignalRTransportException(SignalRFailureCode.CONTRACT_ERROR)
                }
                val executor = synchronized(lock) { commandExecutor }
                    ?: throw SignalRTransportException(SignalRFailureCode.CONTRACT_ERROR)
                synchronized(lock) { commandsInFlight++ }
                mutableMetrics.value = mutableMetrics.value.copy(commandsInFlight = commandsInFlight)
                try {
                    outbound.withLock {
                        client.completeCommand(executor.execute(command))
                    }
                } finally {
                    finishCommandDelivery()
                }
            }
        }
        val networkJob = launch {
            networkMonitor.state.first { !it.validated }
            closed.complete(SignalRTransportException(SignalRFailureCode.NETWORK_OFFLINE))
        }
        val cause = closed.await()
        heartbeatJob.cancelAndJoin()
        transportPulseJob.cancelAndJoin()
        healthJob.cancelAndJoin()
        commandJob.cancelAndJoin()
        networkJob.cancelAndJoin()
        cause
    }

    private fun finishCommandDelivery() {
        val jobToCancel = synchronized(lock) {
            commandsInFlight--
            mutableMetrics.value = mutableMetrics.value.copy(commandsInFlight = commandsInFlight)
            if (commandsInFlight == 0 && stopPending) {
                stopPending = false
                activeJob.also { activeJob = null }
            } else {
                null
            }
        }
        jobToCancel?.cancel()
    }

    private suspend fun publishHeartbeats(
        runGeneration: Long,
        client: SignalRClient,
        serverDeviceId: String,
        connectionId: String,
        closed: CompletableDeferred<Throwable?>,
        outbound: Mutex,
    ) {
        while (isCurrent(runGeneration) && networkMonitor.state.value.validated && !closed.isCompleted) {
            val (acknowledgement, version) = outbound.withLock {
                val now = clock.nowMillis()
                val nextVersion = snapshotVersions.next()
                val result = client.heartbeat(
                    SignalRHeartbeat(
                        deviceId = serverDeviceId,
                        connectionId = connectionId,
                        snapshotVersion = nextVersion,
                        timestamp = Instant.ofEpochMilli(now).toString(),
                        currentDeviceSnapshot = snapshots.value,
                    ),
                )
                val cameraControls = synchronized(lock) { cameraControlReceiver }
                client.reportCameraControlState(cameraControls.state(serverDeviceId))
                result to nextVersion
            }
            if (acknowledgement.deviceId != serverDeviceId
                || acknowledgement.connectionId != connectionId
                || acknowledgement.snapshotVersion != version
            ) {
                throw SignalRTransportException(SignalRFailureCode.PROTOCOL_ERROR)
            }
            val acknowledgedAt = clock.nowMillis()
            heartbeatAcknowledged(acknowledgement, acknowledgedAt)
            stateMachine.transition(SignalRLifecycleEvent.Heartbeat(acknowledgedAt))
            mutableMetrics.value = mutableMetrics.value.copy(lastHeartbeatAtMillis = acknowledgedAt)
            logger.heartbeat(connectionId, version)
            withTimeoutOrNull(policy.heartbeatIntervalMillis) { closed.await() }
        }
        if (!networkMonitor.state.value.validated) {
            throw SignalRTransportException(SignalRFailureCode.NETWORK_OFFLINE)
        }
    }

    private suspend fun awaitValidatedNetwork(
        runGeneration: Long,
        reconnectAttempts: Int,
        logImmediateWhenAlreadyValidated: Boolean = false,
    ): Boolean {
        if (networkMonitor.state.value.validated) {
            if (logImmediateWhenAlreadyValidated) {
                logger.immediateReconnectRequested(reconnectAttempts)
            }
            return true
        }
        stateMachine.transition(
            SignalRLifecycleEvent.Lost(
                SignalRFailureCode.NETWORK_OFFLINE.stableCode,
                reconnectAttempts,
            ),
        )
        networkMonitor.state.first { it.validated }
        if (!isCurrent(runGeneration)) return false
        logger.immediateReconnectRequested(reconnectAttempts)
        return true
    }

    private suspend fun waitForRetryOrValidatedNetwork(
        runGeneration: Long,
        reconnectAttempts: Int,
        retryDelay: Long,
    ) = coroutineScope {
        if (!networkMonitor.state.value.validated) {
            logger.pendingBackoffCancelled(reconnectAttempts, retryDelay)
            awaitValidatedNetwork(
                runGeneration,
                reconnectAttempts,
                logImmediateWhenAlreadyValidated = true,
            )
            return@coroutineScope
        }

        val backoff = async { connectionDelay.wait(retryDelay) }
        val networkLost = async { networkMonitor.state.first { !it.validated } }
        val lostDuringBackoff = select<Boolean> {
            backoff.onAwait { false }
            networkLost.onAwait { true }
        }
        if (lostDuringBackoff) {
            logger.pendingBackoffCancelled(reconnectAttempts, retryDelay)
            backoff.cancelAndJoin()
            awaitValidatedNetwork(
                runGeneration,
                reconnectAttempts,
                logImmediateWhenAlreadyValidated = true,
            )
        } else {
            networkLost.cancelAndJoin()
        }
    }

    private fun failPermanently(failure: Throwable, reconnectAttempts: Int) {
        val transport = failure.asTransportFailure()
        when (transport.failureCode) {
            SignalRFailureCode.AUTHENTICATION_FAILED,
            SignalRFailureCode.CREDENTIALS_MISSING,
            SignalRFailureCode.TOKEN_EXPIRED,
            -> stateMachine.transition(
                SignalRLifecycleEvent.AuthenticationRejected(
                    transport.failureCode.stableCode,
                    reconnectAttempts,
                ),
            )
            else -> stateMachine.transition(
                SignalRLifecycleEvent.Failed(
                    transport.failureCode.stableCode,
                    reconnectAttempts,
                ),
            )
        }
    }

    override suspend fun submitAnswer(answer: LiveSessionDescription) =
        withConnectedClient { it.submitLiveAnswer(answer) }

    override suspend fun submitCandidate(candidate: LiveIceCandidate) =
        withConnectedClient { it.submitLiveCandidate(candidate) }

    override suspend fun reportState(update: LiveSessionStatusUpdate) =
        withConnectedClient { it.reportLiveState(update) }

    override suspend fun reportStatistics(statistics: LiveSessionStatistics) =
        withConnectedClient { it.reportLiveStatistics(statistics) }

    override suspend fun reportCapabilities(
        capabilities: com.ashraffarag.sentricam.live.domain.LiveDeviceCapabilities,
    ) = withConnectedClient { it.reportLiveCapabilities(capabilities) }

    override suspend fun completeCameraControl(result: CameraControlCommandResult) =
        withConnectedClient { it.completeCameraControlCommand(result) }

    override suspend fun reportCameraControlState(
        report: com.ashraffarag.sentricam.cameracontrol.domain.CameraControlDeviceReport,
    ) = withConnectedClient { it.reportCameraControlState(report) }

    private suspend fun withConnectedClient(action: suspend (SignalRClient) -> Unit) {
        val client = synchronized(lock) { connectedClient }
            ?: throw SignalRTransportException(SignalRFailureCode.CONNECTION_CLOSED)
        outbound.withLock { action(client) }
    }

    private fun Throwable.asTransportFailure(): SignalRTransportException =
        this as? SignalRTransportException
            ?: SignalRTransportException(SignalRFailureCode.UNEXPECTED, this)

    private fun isCurrent(runGeneration: Long): Boolean = synchronized(lock) {
        desiredRunning && generation == runGeneration
    }
}
