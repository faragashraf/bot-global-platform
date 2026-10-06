package com.enpo.connect.app.notifications

import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialAvailability
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.PushMessageHandler
import com.botglobal.mobile.platform.preferences.PreferenceStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes delivery with unpair cleanup and persists the block across process restarts. */
class EnpoPairedPushGate(
    private val preferences: PreferenceStore,
    private val credentialVault: MobileDeviceCredentialVault,
    private val delegate: PushMessageHandler,
) : PushMessageHandler {
    private val mutex = Mutex()

    override suspend fun onMessage(message: PushMessage) = mutex.withLock {
        if (isBlocked()) return@withLock
        if (credentialVault.availability() != MobileDeviceCredentialAvailability.Available) return@withLock
        delegate.onMessage(message)
    }

    suspend fun block() = mutex.withLock {
        preferences.putBoolean(BLOCKED_KEY, true)
    }

    suspend fun resume() = mutex.withLock {
        preferences.putBoolean(BLOCKED_KEY, false)
    }

    fun isBlocked(): Boolean = preferences.boolean(BLOCKED_KEY) == true

    private companion object {
        const val BLOCKED_KEY = "enpo_push_blocked_after_unpair"
    }
}
