package com.botglobal.familygames.app.realtime

import com.botglobal.familygames.app.data.GameSessionSnapshot
import com.botglobal.familygames.app.data.PlayerSnapshot
import com.botglobal.familygames.app.data.RulesetSnapshot
import com.botglobal.mobile.platform.realtime.RealtimeConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedGameRealtimeClientTests {
    @Test
    fun concurrent_connect_calls_create_one_generation_and_one_effective_start() = runTest {
        val factory = RecordingTransportFactory(blockFirstConnect = true)
        val client = client(factory)

        val requests = List(8) {
            async { client.start(SessionId, RealtimeConnectSource.AppStart) { "access" } }
        }
        runCurrent()

        assertEquals(1, factory.transports.size)
        assertEquals(1, factory.transports.single().connectCalls)
        factory.releaseFirstConnect.complete(Unit)
        requests.forEach { it.await() }

        assertEquals(1, factory.transports.size)
        assertEquals(RealtimeConnectionState.Connected, client.connectionState.value)
    }

    @Test
    fun duplicate_session_restore_callbacks_coalesce_while_connected() = runTest {
        val factory = RecordingTransportFactory()
        val client = client(factory)

        client.start(SessionId, RealtimeConnectSource.AppStart) { "access" }
        client.start(SessionId, RealtimeConnectSource.AppStart) { "access" }

        assertEquals(1, factory.transports.size)
        assertEquals(1, factory.transports.single().connectCalls)
    }

    @Test
    fun navigation_recreation_reuses_the_shared_realtime_owner() = runTest {
        val factory = RecordingTransportFactory()
        val processOwner = client(factory)

        val firstPresentation = processOwner
        firstPresentation.start(SessionId, RealtimeConnectSource.SessionJoined) { "access" }
        val recreatedPresentation = processOwner
        recreatedPresentation.start(SessionId, RealtimeConnectSource.AppStart) { "access" }

        assertEquals(1, factory.transports.size)
        assertEquals(1, factory.transports.single().connectCalls)
    }

    @Test
    fun foreground_request_while_reconnecting_does_not_create_competing_generation() = runTest {
        val factory = RecordingTransportFactory(blockReconnect = true)
        val client = client(factory)
        client.start(SessionId, RealtimeConnectSource.AppStart) { "access" }

        factory.transports.single().close()
        runCurrent()
        assertEquals(RealtimeConnectionState.Reconnecting, client.connectionState.value)
        assertEquals(2, factory.transports.size)

        val foregroundRequest = async {
            client.start(SessionId, RealtimeConnectSource.Foreground) { "access" }
        }
        runCurrent()
        assertEquals(2, factory.transports.size)
        assertEquals(1, factory.distinctGenerations())

        factory.releaseReconnect.complete(Unit)
        foregroundRequest.await()
        advanceUntilIdle()
        assertEquals(RealtimeConnectionState.Connected, client.connectionState.value)
    }

    @Test
    fun reconnect_has_one_subscription_path_and_old_transport_events_are_ignored() = runTest {
        val factory = RecordingTransportFactory()
        val client = client(factory)
        val events = mutableListOf<GameRealtimeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.events.toList(events) }
        client.start(SessionId, RealtimeConnectSource.AppStart) { "access" }
        val old = factory.transports.single()

        old.close()
        advanceUntilIdle()
        val current = factory.transports.last()
        old.emit("PlayerConnectionChanged", snapshot(revision = 1))
        current.emit("PlayerConnectionChanged", snapshot(revision = 2))
        advanceUntilIdle()

        assertEquals(1, events.size)
        assertEquals(2, events.single().snapshot.revision)
        assertTrue(old.disposed)
        assertEquals(1, current.registrationCount)
    }

    @Test
    fun stale_generation_callback_cannot_mutate_newer_connected_generation() = runTest {
        val factory = RecordingTransportFactory()
        val client = client(factory)
        client.start(SessionId, RealtimeConnectSource.AppStart) { "access" }
        val old = factory.transports.single()

        client.stop()
        client.start(SessionId, RealtimeConnectSource.AppStart) { "access" }
        val currentGeneration = factory.transports.last().configuration.generation
        old.close()
        advanceUntilIdle()

        assertEquals(RealtimeConnectionState.Connected, client.connectionState.value)
        assertEquals(2, currentGeneration)
        assertEquals(2, factory.transports.size)
    }

    @Test
    fun stop_disposes_transport_and_prevents_orphan_callbacks_and_events() = runTest {
        val factory = RecordingTransportFactory()
        val client = client(factory)
        val events = mutableListOf<GameRealtimeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.events.toList(events) }
        client.start(SessionId, RealtimeConnectSource.AppStart) { "access" }
        val stopped = factory.transports.single()

        client.stop()
        stopped.emit("GameStateUpdated", snapshot(revision = 4))
        stopped.close()
        advanceUntilIdle()

        assertTrue(stopped.disposed)
        assertFalse(stopped.subscriptionsActive)
        assertTrue(events.isEmpty())
        assertEquals(RealtimeConnectionState.Disconnected, client.connectionState.value)
        assertEquals(1, factory.transports.size)
    }

    private fun kotlinx.coroutines.test.TestScope.client(factory: RecordingTransportFactory) =
        ManagedGameRealtimeClient(
            ownerScope = this,
            transportFactory = factory,
            reconnectBackoffMillis = listOf(0L),
        )

    private class RecordingTransportFactory(
        private val blockFirstConnect: Boolean = false,
        private val blockReconnect: Boolean = false,
    ) : GameRealtimeTransportFactory {
        val transports = mutableListOf<RecordingTransport>()
        val releaseFirstConnect = CompletableDeferred<Unit>()
        val releaseReconnect = CompletableDeferred<Unit>()

        override fun create(
            configuration: GameRealtimeTransportConfiguration,
            listener: GameRealtimeTransportListener,
        ): GameRealtimeTransport = RecordingTransport(configuration, listener).also { transport ->
            transports += transport
            transport.connectGate = when {
                blockFirstConnect && transports.size == 1 -> releaseFirstConnect
                blockReconnect && transports.size > 1 -> releaseReconnect
                else -> null
            }
        }

        fun distinctGenerations(): Int = transports.map { it.configuration.generation }.distinct().size
    }

    private class RecordingTransport(
        val configuration: GameRealtimeTransportConfiguration,
        private val listener: GameRealtimeTransportListener,
    ) : GameRealtimeTransport {
        var connectGate: CompletableDeferred<Unit>? = null
        var connectCalls = 0
        var disposed = false
        var subscriptionsActive = true
        val registrationCount = 1

        override suspend fun connectAndRejoin() {
            connectCalls++
            connectGate?.await()
        }

        override suspend fun rejoin() = Unit

        override suspend fun dispose() {
            disposed = true
            subscriptionsActive = false
        }

        fun close() = listener.onClosed()

        fun emit(name: String, snapshot: GameSessionSnapshot) = listener.onEvent(name, snapshot)
    }

    private companion object {
        const val SessionId = "session-1"

        fun snapshot(revision: Long) = GameSessionSnapshot(
            sessionId = SessionId,
            joinCode = "ABC123",
            gameType = "xo",
            status = "started",
            matchNumber = 1,
            ruleset = RulesetSnapshot("classic-3x3", 3, 3, 2, null, true, false),
            players = listOf(
                PlayerSnapshot("member-1", "A", 0, "x", true, true),
                PlayerSnapshot("member-2", "B", 1, "o", true, true),
            ),
            board = List(9) { "" },
            version = revision,
            activePlayerMembershipId = "member-1",
            matchStatus = "inprogress",
            lastActivityAtUtc = "2099-01-01T00:00:00Z",
            revision = revision,
        )
    }
}
