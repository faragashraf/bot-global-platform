package com.botglobal.mobile.platform.presence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface PresenceGateway {
    val enabled: Boolean
    val events: Flow<PresenceGatewayEvent> get() = emptyFlow()
    suspend fun activate(account: PresenceAccount, generation: Long): PresenceLease
    suspend fun suspend(lease: PresenceLease, generation: Long)
    suspend fun resume(lease: PresenceLease, generation: Long): PresenceLease
    suspend fun deactivate(lease: PresenceLease, generation: Long, invalidate: Boolean)
}

object DisabledPresenceGateway : PresenceGateway {
    override val enabled = false
    override suspend fun activate(account: PresenceAccount, generation: Long): PresenceLease =
        error("Presence is disabled.")
    override suspend fun suspend(lease: PresenceLease, generation: Long) = Unit
    override suspend fun resume(lease: PresenceLease, generation: Long) = lease
    override suspend fun deactivate(lease: PresenceLease, generation: Long, invalidate: Boolean) = Unit
}
