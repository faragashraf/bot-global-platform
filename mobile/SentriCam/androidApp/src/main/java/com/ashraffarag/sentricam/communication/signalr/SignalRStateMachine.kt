package com.ashraffarag.sentricam.communication.signalr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SignalRLifecycleEvent {
    data class Start(val reconnectAttempts: Int = 0) : SignalRLifecycleEvent
    data class Connected(
        val connectionId: String,
        val connectedAtMillis: Long,
        val reconnectAttempts: Int,
    ) : SignalRLifecycleEvent
    data class Heartbeat(val acknowledgedAtMillis: Long) : SignalRLifecycleEvent
    data class Lost(val reasonCode: String, val reconnectAttempts: Int) : SignalRLifecycleEvent
    data class RetryScheduled(
        val attempt: Int,
        val nextAttemptAtMillis: Long,
        val reasonCode: String,
    ) : SignalRLifecycleEvent
    data class AuthenticationRejected(val reasonCode: String, val attempts: Int) : SignalRLifecycleEvent
    data class RetriesExhausted(val reasonCode: String, val attempts: Int) : SignalRLifecycleEvent
    data class Failed(val reasonCode: String, val attempts: Int) : SignalRLifecycleEvent
    data object Stop : SignalRLifecycleEvent
}

class SignalRStateMachine(initial: SignalRState = SignalRState.Stopped) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<SignalRState> = mutableState.asStateFlow()

    @Synchronized
    fun transition(event: SignalRLifecycleEvent): SignalRState {
        val current = mutableState.value
        val next = reduce(current, event)
        mutableState.value = next
        return next
    }

    private fun reduce(current: SignalRState, event: SignalRLifecycleEvent): SignalRState = when (event) {
        SignalRLifecycleEvent.Stop -> SignalRState.Stopped
        is SignalRLifecycleEvent.Start -> when (current) {
            SignalRState.Stopped,
            is SignalRState.Disconnected,
            is SignalRState.AuthenticationFailed,
            is SignalRState.ServerUnavailable,
            is SignalRState.Error,
            -> SignalRState.Connecting(event.reconnectAttempts)
            is SignalRState.Connecting,
            is SignalRState.Connected,
            is SignalRState.Reconnecting,
            -> current
        }
        is SignalRLifecycleEvent.Connected -> when (current) {
            is SignalRState.Connecting,
            is SignalRState.Reconnecting,
            -> SignalRState.Connected(
                event.connectionId,
                event.connectedAtMillis,
                reconnectAttempts = event.reconnectAttempts,
            )
            else -> current
        }
        is SignalRLifecycleEvent.Heartbeat -> when (current) {
            is SignalRState.Connected -> current.copy(lastHeartbeatAtMillis = event.acknowledgedAtMillis)
            else -> current
        }
        is SignalRLifecycleEvent.Lost -> when (current) {
            is SignalRState.Connecting,
            is SignalRState.Connected,
            is SignalRState.Reconnecting,
            -> SignalRState.Disconnected(
                reasonCode = event.reasonCode,
                lastConnectedAtMillis = (current as? SignalRState.Connected)?.connectedAtMillis,
                reconnectAttempts = event.reconnectAttempts,
            )
            else -> current
        }
        is SignalRLifecycleEvent.RetryScheduled -> when (current) {
            is SignalRState.Disconnected,
            is SignalRState.Connecting,
            is SignalRState.Reconnecting,
            -> SignalRState.Reconnecting(
                event.attempt,
                event.nextAttemptAtMillis,
                event.reasonCode,
            )
            else -> current
        }
        is SignalRLifecycleEvent.AuthenticationRejected -> when (current) {
            is SignalRState.Connecting,
            is SignalRState.Reconnecting,
            is SignalRState.Disconnected,
            -> SignalRState.AuthenticationFailed(event.reasonCode, event.attempts)
            else -> current
        }
        is SignalRLifecycleEvent.RetriesExhausted -> when (current) {
            is SignalRState.Connecting,
            is SignalRState.Reconnecting,
            is SignalRState.Disconnected,
            -> SignalRState.ServerUnavailable(event.reasonCode, event.attempts)
            else -> current
        }
        is SignalRLifecycleEvent.Failed -> SignalRState.Error(event.reasonCode, event.attempts)
    }
}
