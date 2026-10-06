package com.enpo.connect.app.notifications

import com.botglobal.mobile.platform.notifications.InMemoryMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.PushMessageHandler
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class EnpoPairedPushGateTests {
    private val message = PushMessage(null, emptyMap(), 0, 60)

    @Test
    fun lateDeliveryCannotRepopulateNotificationsAfterUnpair() = runTest {
        val preferences = InMemoryPreferenceStore()
        val vault = InMemoryMobileDeviceCredentialVault(MobileDeviceCredential("device", "secret"))
        var delivered = 0
        val gate = EnpoPairedPushGate(preferences, vault, PushMessageHandler { delivered++ })

        gate.onMessage(message)
        gate.block()
        gate.onMessage(message)
        vault.clear()
        gate.onMessage(message)
        val recreatedGate = EnpoPairedPushGate(preferences, vault, PushMessageHandler { delivered++ })
        recreatedGate.onMessage(message)

        assertEquals(1, delivered)
        assertTrue(recreatedGate.isBlocked())
        vault.save(MobileDeviceCredential("new-device", "new-secret"))
        recreatedGate.resume()
        recreatedGate.onMessage(message)
        assertEquals(2, delivered)
    }

    @Test
    fun unpairWaitsForInFlightDeliveryBeforeTheCallerClearsNotifications() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = EnpoPairedPushGate(
            InMemoryPreferenceStore(),
            InMemoryMobileDeviceCredentialVault(MobileDeviceCredential("device", "secret")),
            PushMessageHandler {
                entered.complete(Unit)
                release.await()
            },
        )
        val delivery = async { gate.onMessage(message) }
        entered.await()
        val block = async { gate.block() }
        assertFalse(block.isCompleted)
        release.complete(Unit)
        delivery.await()
        block.await()
        assertTrue(gate.isBlocked())
    }

    @Test
    fun restartKeepsBlockedPushEvenWhenRevokedCredentialStillExists() = runTest {
        val preferences = InMemoryPreferenceStore()
        val vault = InMemoryMobileDeviceCredentialVault(MobileDeviceCredential("device", "secret"))
        var delivered = 0
        val original = EnpoPairedPushGate(preferences, vault, PushMessageHandler { delivered++ })
        original.block()

        val restarted = EnpoPairedPushGate(preferences, vault, PushMessageHandler { delivered++ })
        restarted.onMessage(message)
        assertTrue(restarted.isBlocked())
        assertEquals(0, delivered)

        restarted.block()
        vault.clear()
        val unpairedRestart = EnpoPairedPushGate(preferences, vault, PushMessageHandler { delivered++ })
        unpairedRestart.onMessage(message)
        assertTrue(unpairedRestart.isBlocked())
        assertEquals(0, delivered)
    }
}
