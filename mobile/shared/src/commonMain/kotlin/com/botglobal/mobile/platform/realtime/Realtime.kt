package com.botglobal.mobile.platform.realtime

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.runningFold

enum class RealtimeConnectionState { Disconnected, Connecting, Connected, Reconnecting, Failed, Unavailable }

interface RealtimeLifecycle {
    val state: RealtimeConnectionState
    suspend fun connect()
    suspend fun disconnect()
    suspend fun onForeground()
    suspend fun onBackground()
}

data class VersionedEvent<T>(val version: Long, val payload: T)

object StaleEventGuard {
    fun <T> accept(currentVersion: Long, event: VersionedEvent<T>): T? =
        event.payload.takeIf { event.version >= currentVersion }
}

enum class NetworkAvailabilityState { Unknown, Available, Unavailable }

data class NetworkAvailabilitySnapshot(
    val state: NetworkAvailabilityState,
    val observerGeneration: Long,
    val revision: Long,
)

interface NetworkAvailability {
    val changes: Flow<NetworkAvailabilitySnapshot>
}

object UnavailableNetworkAvailability : NetworkAvailability {
    override val changes: Flow<NetworkAvailabilitySnapshot> = emptyFlow()
}

@OptIn(FlowPreview::class)
fun stabilizedNetworkAvailability(
    rawChanges: Flow<NetworkAvailabilityState>,
    observerGeneration: Long,
    unavailableConfirmationMillis: Long,
): Flow<NetworkAvailabilitySnapshot> = rawChanges
    .distinctUntilChanged()
    .debounce { state ->
        if (state == NetworkAvailabilityState.Unavailable) unavailableConfirmationMillis else 0L
    }
    .distinctUntilChanged()
    .runningFold(
        NetworkAvailabilitySnapshot(
            state = NetworkAvailabilityState.Unknown,
            observerGeneration = observerGeneration,
            revision = 0,
        ),
    ) { previous, state ->
        NetworkAvailabilitySnapshot(
            state = state,
            observerGeneration = observerGeneration,
            revision = previous.revision + 1,
        )
    }
    .drop(1)
