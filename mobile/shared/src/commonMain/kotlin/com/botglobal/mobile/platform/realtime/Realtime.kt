package com.botglobal.mobile.platform.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

interface NetworkAvailability {
    val changes: Flow<Boolean>
}

object UnavailableNetworkAvailability : NetworkAvailability {
    override val changes: Flow<Boolean> = emptyFlow()
}
