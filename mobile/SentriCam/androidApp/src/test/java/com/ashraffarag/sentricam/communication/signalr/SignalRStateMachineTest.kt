package com.ashraffarag.sentricam.communication.signalr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalRStateMachineTest {
    @Test
    fun transportLivenessIsFasterThanPresenceHeartbeatPolicy() {
        val configuration = SignalRConfiguration("https://hub.local/hubs/device")
        val policy = ConnectionPolicy()

        assertEquals(15_000L, configuration.keepAliveIntervalMillis)
        assertEquals(30_000L, configuration.serverTimeoutMillis)
        assertEquals(15_000L, policy.heartbeatIntervalMillis)
        assertEquals(1_000L, policy.transportPulseIntervalMillis)
    }

    @Test
    fun connectHeartbeatReconnectAndStopUseExplicitTransitions() {
        val machine = SignalRStateMachine()

        assertTrue(machine.transition(SignalRLifecycleEvent.Start()) is SignalRState.Connecting)
        assertTrue(
            machine.transition(SignalRLifecycleEvent.Connected("connection-1", 10L, 0))
                is SignalRState.Connected,
        )
        val heartbeat = machine.transition(SignalRLifecycleEvent.Heartbeat(20L))
        assertEquals(20L, (heartbeat as SignalRState.Connected).lastHeartbeatAtMillis)
        assertTrue(
            machine.transition(SignalRLifecycleEvent.Lost("network_offline", 0))
                is SignalRState.Disconnected,
        )
        assertTrue(
            machine.transition(SignalRLifecycleEvent.RetryScheduled(1, 30L, "network_offline"))
                is SignalRState.Reconnecting,
        )
        val reconnected = machine.transition(SignalRLifecycleEvent.Connected("connection-2", 40L, 1))
        assertEquals("connection-2", (reconnected as SignalRState.Connected).connectionId)
        assertSame(SignalRState.Stopped, machine.transition(SignalRLifecycleEvent.Stop))
    }

    @Test
    fun staleConnectedEventCannotEscapeStoppedState() {
        val machine = SignalRStateMachine()

        val state = machine.transition(SignalRLifecycleEvent.Connected("stale", 1L, 0))

        assertSame(SignalRState.Stopped, state)
    }

    @Test
    fun authenticationAndRetryExhaustionHaveDedicatedStates() {
        val auth = SignalRStateMachine().apply { transition(SignalRLifecycleEvent.Start()) }
        val unavailable = SignalRStateMachine().apply { transition(SignalRLifecycleEvent.Start()) }

        assertTrue(
            auth.transition(SignalRLifecycleEvent.AuthenticationRejected("token_expired", 0))
                is SignalRState.AuthenticationFailed,
        )
        assertTrue(
            unavailable.transition(SignalRLifecycleEvent.RetriesExhausted("server_unavailable", 5))
                is SignalRState.ServerUnavailable,
        )
    }
}
