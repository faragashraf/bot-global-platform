package com.ashraffarag.sentricam.communication.signalr

import com.ashraffarag.sentricam.device.domain.DeviceSnapshot
import com.ashraffarag.sentricam.device.recovery.OperationalHealthState
import com.ashraffarag.sentricam.device.recovery.OperationalLifecycleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalRConnectionManagerTest {
    @Test
    fun connectsWithStoredJwtAndPublishesImmediateHeartbeat() = runBlocking {
        val client = FakeSignalRClient("connection-1")
        val fixture = fixture(scope = this, clientFactory = SignalRClientFactory { client })

        fixture.manager.start()
        val connected = fixture.manager.awaitState<SignalRState.Connected>()
        withTimeout(1_000L) { fixture.manager.metrics.first { it.lastHeartbeatAtMillis != null } }

        assertEquals("connection-1", connected.connectionId)
        assertEquals(SIGNALR_TOKEN, client.observedToken)
        assertEquals(SIGNALR_SERVER_DEVICE_ID, client.lastHeartbeat?.deviceId)
        assertEquals(signalRTestSnapshot(), client.lastHeartbeat?.currentDeviceSnapshot)
        fixture.manager.stop()
    }

    @Test
    fun duplicateStartDoesNotOpenASecondConnection() = runBlocking {
        var clientsCreated = 0
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory {
                clientsCreated++
                FakeSignalRClient("connection-$clientsCreated")
            },
        )

        fixture.manager.start()
        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Connected>()

        assertEquals(1, clientsCreated)
        fixture.manager.stop()
    }

    @Test
    fun closedConnectionReconnectsWithFreshClientAndReusesSecureJwt() = runBlocking {
        val first = FakeSignalRClient("connection-1")
        val second = FakeSignalRClient("connection-2")
        val clients = ArrayDeque(listOf(first, second))
        val fixture = fixture(scope = this, clientFactory = SignalRClientFactory { clients.removeFirst() })
        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Connected>()

        first.closeFromServer()
        val reconnected = withTimeout(1_000L) {
            fixture.manager.state.first {
                it is SignalRState.Connected && it.connectionId == "connection-2"
            } as SignalRState.Connected
        }

        assertEquals(1, reconnected.reconnectAttempts)
        assertEquals(SIGNALR_TOKEN, second.observedToken)
        assertTrue(first.disconnectCalls > 0)
        assertTrue(first.closeCalls > 0)
        fixture.manager.stop()
    }

    @Test
    fun retryableProtocolFailureDisposesPartialConnectionAndRecoversAutomatically() = runBlocking {
        val first = FakeSignalRClient("connection-1")
        val second = FakeSignalRClient("connection-2")
        val clients = ArrayDeque(listOf(first, second))
        val fixture = fixture(scope = this, clientFactory = SignalRClientFactory { clients.removeFirst() })
        fixture.manager.start()
        fixture.manager.awaitConnected("connection-1")

        first.closeFromServer(SignalRTransportException(SignalRFailureCode.PROTOCOL_ERROR))
        val recovered = fixture.manager.awaitConnected("connection-2")

        assertEquals(1, recovered.reconnectAttempts)
        assertTrue(first.closeCalls > 0)
        assertTrue(first.disconnectCalls > 0)
        fixture.manager.stop()
    }

    @Test
    fun successfulRecoveryResetsBackoffForTheNextIndependentOutage() = runBlocking {
        val first = FakeSignalRClient("connection-1")
        val second = FakeSignalRClient("connection-2")
        val third = FakeSignalRClient("connection-3")
        val clients = ArrayDeque(listOf(first, second, third))
        val delays = mutableListOf<Long>()
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory { clients.removeFirst() },
            connectionDelay = SignalRDelay(delays::add),
        )
        fixture.manager.start()
        fixture.manager.awaitConnected("connection-1")

        first.closeFromServer()
        fixture.manager.awaitConnected("connection-2")
        second.closeFromServer()
        fixture.manager.awaitConnected("connection-3")

        assertEquals(listOf(1L, 1L), delays)
        fixture.manager.stop()
    }

    @Test
    fun recoveredHealthIsPublishedEvenWhenItChangesAsTheConnectedSessionStarts() = runBlocking {
        val client = FakeSignalRClient("connection-1")
        val base = signalRTestSnapshot()
        val snapshots = MutableStateFlow(
            base.copy(
                subsystemHealth = base.subsystemHealth.copy(
                    overall = OperationalHealthState.RECOVERING,
                    realtime = base.subsystemHealth.realtime.copy(
                        lifecycle = OperationalLifecycleState.RECOVERING,
                        health = OperationalHealthState.RECOVERING,
                        recoveryReason = "network_offline",
                    ),
                ),
            ),
        )
        client.onOperationalHealthReport = {
            if (client.operationalHealthReports.size == 1) {
                val recovering = snapshots.value.subsystemHealth
                snapshots.value = snapshots.value.copy(
                    subsystemHealth = recovering.copy(
                        overall = OperationalHealthState.HEALTHY,
                        realtime = recovering.realtime.copy(
                            lifecycle = OperationalLifecycleState.RUNNING,
                            health = OperationalHealthState.HEALTHY,
                            recoveryReason = null,
                        ),
                    ),
                )
            }
        }
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory { client },
            snapshots = snapshots,
        )

        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Connected>()
        withTimeout(1_000L) {
            while (client.operationalHealthReports.lastOrNull()?.health?.realtime?.health
                != OperationalHealthState.HEALTHY
            ) kotlinx.coroutines.yield()
        }

        assertEquals(2, client.operationalHealthReports.size)
        assertEquals(
            OperationalHealthState.HEALTHY,
            client.operationalHealthReports.last().health.realtime.health,
        )
        fixture.manager.stop()
    }

    @Test
    fun offlineStartWaitsAndRecoversWhenNetworkReturns() = runBlocking {
        val network = FakeSignalRNetworkMonitor(false)
        val client = FakeSignalRClient("connection-online")
        val fixture = fixture(
            scope = this,
            network = network,
            clientFactory = SignalRClientFactory { client },
        )

        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Disconnected>()
        assertEquals(0, client.connectCalls)

        network.validated()
        assertEquals(
            "connection-online",
            fixture.manager.awaitState<SignalRState.Connected>().connectionId,
        )
        fixture.manager.stop()
    }

    @Test
    fun networkLossReconnectsWhileMonitoringIsStopped() = runBlocking {
        val network = FakeSignalRNetworkMonitor(true)
        val first = FakeSignalRClient("connection-1")
        val second = FakeSignalRClient("connection-2")
        val clients = ArrayDeque(listOf(first, second))
        val fixture = fixture(
            scope = this,
            network = network,
            clientFactory = SignalRClientFactory { clients.removeFirst() },
            heartbeatIntervalMillis = 1L,
        )

        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Connected>()
        network.unavailable()
        fixture.manager.awaitState<SignalRState.Disconnected>()
        assertEquals(1, first.connectCalls)
        assertEquals(0, second.connectCalls)

        network.validated()
        val recovered = withTimeout(1_000L) {
            fixture.manager.state.first {
                it is SignalRState.Connected && it.connectionId == "connection-2"
            } as SignalRState.Connected
        }

        assertEquals("connection-2", recovered.connectionId)
        assertTrue(first.disconnectCalls > 0)
        fixture.manager.stop()
    }

    @Test
    fun availableButUnvalidatedNetworkPausesConnectionAttempts() = runBlocking {
        val network = FakeSignalRNetworkMonitor(false).apply { availableNotValidated() }
        val client = FakeSignalRClient("connection-validated")
        val fixture = fixture(
            scope = this,
            network = network,
            clientFactory = SignalRClientFactory { client },
        )

        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Disconnected>()
        assertEquals(0, client.connectCalls)

        network.validated()
        assertEquals("connection-validated", fixture.manager.awaitState<SignalRState.Connected>().connectionId)
        fixture.manager.stop()
    }

    @Test
    fun validatedNetworkReturnCancelsPendingLongBackoffAndReconnectsImmediately() = runBlocking {
        val network = FakeSignalRNetworkMonitor(true)
        val delay = BlockingSignalRDelay()
        val logger = RecordingSignalRLogger()
        var clientsCreated = 0
        val fixture = fixture(
            scope = this,
            network = network,
            connectionDelay = delay,
            logger = logger,
            clientFactory = SignalRClientFactory {
                clientsCreated++
                if (clientsCreated == 1) {
                    FakeSignalRClient(
                        "server-unavailable",
                        SignalRTransportException(SignalRFailureCode.SERVER_UNAVAILABLE),
                    )
                } else {
                    FakeSignalRClient("connection-after-validation")
                }
            },
        )

        fixture.manager.start()
        withTimeout(1_000L) { delay.started.await() }
        network.availableNotValidated()
        withTimeout(1_000L) { delay.cancelled.await() }
        assertEquals(1, clientsCreated)

        network.validated()
        val connected = fixture.manager.awaitState<SignalRState.Connected>()

        assertEquals("connection-after-validation", connected.connectionId)
        assertEquals(listOf(1L), logger.cancelledBackoffs)
        assertEquals(listOf(1), logger.immediateReconnectAttempts)
        fixture.manager.stop()
    }

    @Test
    fun repeatedWifiTogglesReconnectOncePerValidatedReturnWithoutParallelAttempts() = runBlocking {
        val network = FakeSignalRNetworkMonitor(true)
        var clientsCreated = 0
        var activeConnects = 0
        var maximumActiveConnects = 0
        val fixture = fixture(
            scope = this,
            network = network,
            heartbeatIntervalMillis = 1L,
            clientFactory = SignalRClientFactory {
                clientsCreated++
                object : FakeSignalRClient("connection-$clientsCreated") {
                    override suspend fun connect(
                        configuration: SignalRConfiguration,
                        accessTokenProvider: SignalRAccessTokenProvider,
                        lifecycle: ConnectionLifecycle,
                        commandReceiver: SignalRCommandReceiver,
                        pairingRevocationReceiver: PairingRevocationReceiver,
                        liveReceiver: com.ashraffarag.sentricam.live.capability.LiveSignalReceiver,
                        cameraControlReceiver: com.ashraffarag.sentricam.cameracontrol.capability.CameraControlSignalReceiver,
                    ): SignalRConnection {
                        activeConnects++
                        maximumActiveConnects = maxOf(maximumActiveConnects, activeConnects)
                        return try {
                            super.connect(
                                configuration,
                                accessTokenProvider,
                                lifecycle,
                                commandReceiver,
                                pairingRevocationReceiver,
                                liveReceiver,
                                cameraControlReceiver,
                            )
                        } finally {
                            activeConnects--
                        }
                    }
                }
            },
        )

        fixture.manager.start()
        fixture.manager.awaitConnected("connection-1")
        repeat(2) { index ->
            network.unavailable()
            fixture.manager.awaitState<SignalRState.Disconnected>()
            network.availableNotValidated()
            kotlinx.coroutines.yield()
            assertEquals(index + 1, clientsCreated)
            network.validated()
            fixture.manager.awaitConnected("connection-${index + 2}")
        }

        assertEquals(3, clientsCreated)
        assertEquals(1, maximumActiveConnects)
        fixture.manager.stop()
    }

    @Test
    fun stopAndRestartAcrossBackgroundPreservesPairingCredentials() = runBlocking {
        val network = FakeSignalRNetworkMonitor(true)
        val store = FakeSignalRCredentialStore()
        val first = FakeSignalRClient("foreground-1")
        val second = FakeSignalRClient("foreground-2")
        val clients = ArrayDeque(listOf(first, second))
        val fixture = fixture(
            scope = this,
            network = network,
            store = store,
            clientFactory = SignalRClientFactory { clients.removeFirst() },
        )
        val pairedCredentials = store.value

        fixture.manager.start()
        fixture.manager.awaitConnected("foreground-1")
        fixture.manager.stop()
        network.unavailable()
        network.validated()
        assertEquals(1, first.connectCalls)

        fixture.manager.start()
        fixture.manager.awaitConnected("foreground-2")

        assertSame(pairedCredentials, store.value)
        assertEquals(SIGNALR_TOKEN, second.observedToken)
        fixture.manager.stop()
    }

    @Test
    fun missingAndExpiredCredentialsStopWithoutRetry() = runBlocking {
        val missingStore = FakeSignalRCredentialStore().apply { value = null }
        val expiredStore = FakeSignalRCredentialStore(expiration = SIGNALR_NOW)
        val missing = fixture(scope = this, store = missingStore)
        val expired = fixture(scope = this, store = expiredStore)

        missing.manager.start()
        expired.manager.start()

        val missingState = missing.manager.awaitState<SignalRState.AuthenticationFailed>()
        val expiredState = expired.manager.awaitState<SignalRState.AuthenticationFailed>()
        assertEquals(SignalRFailureCode.CREDENTIALS_MISSING.stableCode, missingState.reasonCode)
        assertEquals(SignalRFailureCode.TOKEN_EXPIRED.stableCode, expiredState.reasonCode)
    }

    @Test
    fun authenticationRejectionStopsWithoutRetry() = runBlocking {
        var clientsCreated = 0
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory {
                clientsCreated++
                FakeSignalRClient(
                    "unauthorized",
                    SignalRTransportException(SignalRFailureCode.AUTHENTICATION_FAILED),
                )
            },
        )

        fixture.manager.start()
        val rejected = fixture.manager.awaitState<SignalRState.AuthenticationFailed>()

        assertEquals(SignalRFailureCode.AUTHENTICATION_FAILED.stableCode, rejected.reasonCode)
        assertEquals(1, clientsCreated)
    }

    @Test
    fun pairingRevocationIsDeliveredToTheAuthoritativeRuntime() = runBlocking {
        val client = FakeSignalRClient("connection-1")
        var revoked: DevicePairingRevoked? = null
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory { client },
            pairingRevoked = { revoked = it },
        )
        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Connected>()

        client.revokePairing()

        assertEquals(SIGNALR_SERVER_DEVICE_ID, revoked?.deviceId)
        assertEquals("administrator_removed", revoked?.reason)
        fixture.manager.stop()
    }

    @Test
    fun unavailableServerKeepsRetryingWithCappedExponentialBackoff() = runBlocking {
        var clientsCreated = 0
        val delays = mutableListOf<Long>()
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory {
                clientsCreated++
                if (clientsCreated <= 7) {
                    FakeSignalRClient(
                        "failed-$clientsCreated",
                        SignalRTransportException(SignalRFailureCode.SERVER_UNAVAILABLE),
                    )
                } else {
                    FakeSignalRClient("recovered")
                }
            },
            connectionDelay = SignalRDelay(delays::add),
        )

        fixture.manager.start()
        val connected = fixture.manager.awaitState<SignalRState.Connected>()

        assertEquals("recovered", connected.connectionId)
        assertEquals(7, connected.reconnectAttempts)
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 16L, 16L), delays)
        assertEquals(8, clientsCreated)
        fixture.manager.stop()
    }

    @Test
    fun stopCancelsConnectionAndDisposesClient() = runBlocking {
        val client = FakeSignalRClient("connection-1")
        val fixture = fixture(scope = this, clientFactory = SignalRClientFactory { client })
        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Connected>()

        fixture.manager.stop()
        fixture.manager.stop()
        withTimeout(1_000L) { while (client.closeCalls == 0) kotlinx.coroutines.yield() }

        assertSame(SignalRState.Stopped, fixture.manager.state.value)
        assertEquals(1, client.disconnectCalls)
        assertEquals(1, client.closeCalls)
    }

    @Test
    fun receivedCommandIsExecutedOnceAndResultReturnsToServer() = runBlocking {
        val client = FakeSignalRClient("connection-1")
        val fixture = fixture(scope = this, clientFactory = SignalRClientFactory { client })
        val command = RemoteDeviceCommand(
            commandId = "dcbdcc2e-3c35-4d4b-bfdb-8750885a4e73",
            deviceId = SIGNALR_SERVER_DEVICE_ID,
            commandType = 6,
            correlationId = "dashboard-ping",
            requestedAtUtc = "2026-08-01T00:00:00Z",
        )
        var executions = 0
        fixture.manager.start(SignalRCommandExecutor {
            executions++
            RemoteDeviceCommandResult(
                it.commandId,
                it.deviceId,
                1,
                "pong",
                null,
                "2026-08-01T00:00:01Z",
            )
        })
        fixture.manager.awaitState<SignalRState.Connected>()

        client.receive(command)
        withTimeout(1_000L) { while (client.completedCommands.isEmpty()) kotlinx.coroutines.yield() }

        assertEquals(1, executions)
        assertEquals("pong", client.completedCommands.single().resultCode)
        fixture.manager.stop()
    }

    @Test
    fun monitoringStopCommandLeavesConnectionAndHeartbeatRunning() = runBlocking {
        val client = FakeSignalRClient("connection-1")
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory { client },
            heartbeatIntervalMillis = 1L,
        )
        fixture.manager.start(SignalRCommandExecutor {
            client.outboundEvents.clear()
            RemoteDeviceCommandResult(
                it.commandId,
                it.deviceId,
                1,
                "accepted",
                null,
                "2026-08-01T00:00:01Z",
            )
        })
        fixture.manager.awaitState<SignalRState.Connected>()

        client.receive(
            RemoteDeviceCommand(
                "e5802301-18d6-4395-9397-094127bd9a62",
                SIGNALR_SERVER_DEVICE_ID,
                2,
                "stop-monitoring",
                "2026-08-01T00:00:00Z",
            ),
        )
        withTimeout(1_000L) { while (client.completedCommands.isEmpty()) kotlinx.coroutines.yield() }
        val heartbeatCountAfterStop = client.heartbeatCalls
        withTimeout(1_000L) {
            while (client.heartbeatCalls < heartbeatCountAfterStop + 2) kotlinx.coroutines.yield()
        }

        assertEquals("accepted", client.completedCommands.single().resultCode)
        assertEquals(listOf("command", "heartbeat"), client.outboundEvents.take(2))
        assertTrue(fixture.manager.state.value is SignalRState.Connected)
        assertEquals(0, client.disconnectCalls)
        fixture.manager.stop()
    }

    @Test
    fun cameraCapabilityAndTelemetryReportRefreshesWithHeartbeatCadence() = runBlocking {
        val client = FakeSignalRClient("connection-1")
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory { client },
            heartbeatIntervalMillis = 1L,
        )
        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Connected>()

        withTimeout(1_000L) { while (client.cameraControlReportCalls < 3) kotlinx.coroutines.yield() }

        assertTrue(client.heartbeatCalls >= 2)
        assertTrue(client.cameraControlReportCalls >= 3)
        fixture.manager.stop()
    }

    @Test
    fun connectedSessionPublishesLightweightTransportPulsesIndependentlyOfHeartbeats() = runBlocking {
        val client = FakeSignalRClient("connection-1")
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory { client },
            transportPulseIntervalMillis = 1L,
        )
        fixture.manager.start()
        fixture.manager.awaitState<SignalRState.Connected>()

        withTimeout(1_000L) { while (client.transportPulseCalls < 3) kotlinx.coroutines.yield() }

        assertTrue(client.transportPulseCalls >= 3)
        assertEquals(1, client.heartbeatCalls)
        fixture.manager.stop()
    }

    @Test
    fun retryablePulseAbortDisposesStaleClientThenReconnectsAndAcknowledgesHubTime() = runBlocking {
        val events = mutableListOf<String>()
        val acknowledgements = mutableListOf<String?>()
        val first = FakeSignalRClient("connection-1").apply {
            transportPulseFailure = SignalRTransportException(
                SignalRFailureCode.CONNECTION_CLOSED,
                RuntimeException("Software caused connection abort"),
            )
            onClose = { events += "first-closed" }
        }
        val second = FakeSignalRClient("connection-2")
        val clients = ArrayDeque(listOf(first, second))
        val fixture = fixture(
            scope = this,
            clientFactory = SignalRClientFactory {
                events += "create-${if (clients.size == 2) "first" else "second"}"
                clients.removeFirst()
            },
            connectionDelay = SignalRDelay { events += "backoff" },
            transportPulseIntervalMillis = 1L,
            heartbeatAcknowledged = { acknowledgement, _ ->
                acknowledgements += acknowledgement.connectionId
            },
        )

        fixture.manager.start()
        fixture.manager.awaitConnected("connection-2")
        withTimeout(1_000L) {
            while ("connection-2" !in acknowledgements) kotlinx.coroutines.yield()
        }

        assertTrue(events.indexOf("first-closed") < events.indexOf("backoff"))
        assertTrue(events.indexOf("first-closed") < events.indexOf("create-second"))
        assertEquals(1, first.closeCalls)
        assertEquals(1, first.disconnectCalls)
        assertEquals(SIGNALR_TOKEN, second.observedToken)
        assertTrue(fixture.manager.state.value is SignalRState.Connected)
        fixture.manager.stop()
    }

    @Test
    fun forceReconnectIsIgnoredWhileConnectivityRuntimeHasStoppedManager() = runBlocking {
        val client = FakeSignalRClient("connection-1")
        val fixture = fixture(scope = this, clientFactory = SignalRClientFactory { client })

        fixture.manager.forceReconnect()

        assertSame(SignalRState.Stopped, fixture.manager.state.value)
        assertEquals(0, client.connectCalls)
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        clientFactory: SignalRClientFactory = SignalRClientFactory { FakeSignalRClient("connection") },
        store: FakeSignalRCredentialStore = FakeSignalRCredentialStore(),
        network: FakeSignalRNetworkMonitor = FakeSignalRNetworkMonitor(true),
        connectionDelay: SignalRDelay = SignalRDelay { },
        heartbeatIntervalMillis: Long = 60_000L,
        transportPulseIntervalMillis: Long = 60_000L,
        pairingRevoked: (DevicePairingRevoked) -> Unit = {},
        snapshots: MutableStateFlow<DeviceSnapshot> = MutableStateFlow(signalRTestSnapshot()),
        logger: SignalRLogger = NoOpSignalRLogger,
        heartbeatAcknowledged: (SignalRHeartbeatAcknowledgement, Long) -> Unit = { _, _ -> },
    ): Fixture {
        val clock = FakeSignalRClock()
        var snapshotVersion = 0L
        val manager = SignalRConnectionManager(
            clientFactory = clientFactory,
            configurationProvider = SignalRConfigurationProvider(FakeSignalRServerConfiguration()),
            credentialProvider = SecureSignalRCredentialProvider(store, clock),
            networkMonitor = network,
            snapshots = snapshots,
            snapshotVersions = SnapshotVersionProvider { ++snapshotVersion },
            clock = clock,
            scope = scope,
            policy = ConnectionPolicy(
                heartbeatIntervalMillis = heartbeatIntervalMillis,
                transportPulseIntervalMillis = transportPulseIntervalMillis,
                reconnectDelaysMillis = listOf(1L, 2L, 4L, 8L, 16L),
            ),
            connectionDelay = connectionDelay,
            reconnectJitter = SignalRJitter { baseDelayMillis, _ -> baseDelayMillis },
            pairingRevoked = pairingRevoked,
            logger = logger,
            heartbeatAcknowledged = heartbeatAcknowledged,
        )
        return Fixture(manager)
    }

    private suspend inline fun <reified T : SignalRState> SignalRConnectionManager.awaitState(): T =
        withTimeout(1_000L) { state.first { it is T } as T }

    private suspend fun SignalRConnectionManager.awaitConnected(connectionId: String): SignalRState.Connected =
        withTimeout(1_000L) {
            state.first { it is SignalRState.Connected && it.connectionId == connectionId } as SignalRState.Connected
        }

    private data class Fixture(val manager: SignalRConnectionManager)

    private class BlockingSignalRDelay : SignalRDelay {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun wait(delayMillis: Long) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
    }

    private class RecordingSignalRLogger : SignalRLogger {
        val cancelledBackoffs = mutableListOf<Long>()
        val immediateReconnectAttempts = mutableListOf<Int>()

        override fun connected(serverHost: String, connectionId: String, reconnectAttempts: Int) = Unit
        override fun disconnected(connectionId: String?, failure: SignalRTransportException) = Unit
        override fun reconnecting(serverHost: String, attempt: Int, delayMillis: Long, reasonCode: String) = Unit
        override fun heartbeat(connectionId: String, snapshotVersion: Long) = Unit
        override fun pendingBackoffCancelled(attempt: Int, delayMillis: Long) {
            cancelledBackoffs += delayMillis
        }
        override fun immediateReconnectRequested(attempt: Int) {
            immediateReconnectAttempts += attempt
        }
    }
}
