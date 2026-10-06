package com.enpo.connect.app.pairing

import com.botglobal.mobile.platform.notifications.InMemoryMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.PushMessageHandler
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import com.enpo.connect.app.notifications.EnpoPairedPushGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class EnpoDeviceUnpairCoordinatorTests {
    @Test
    fun confirmedServerRevocationClearsLocalDataAndCredential() = runTest {
        val vault = InMemoryMobileDeviceCredentialVault(MobileDeviceCredential("device", "secret"))
        var clearedLocalData = false
        val coordinator = EnpoDeviceUnpairCoordinator(
            client = EnpoDeviceUnpairClient { EnpoDeviceUnpairResponse.Revoked },
            credentialVault = vault,
            clearLocalData = { clearedLocalData = true },
        )

        assertEquals(EnpoDeviceUnpairState.Completed, coordinator.unpair())
        assertEquals(true, clearedLocalData)
        assertNull(vault.restore())
    }

    @Test
    fun serviceFailureKeepsCredentialAndPushBlockedUntilRetry() = runTest {
        val credential = MobileDeviceCredential("device", "secret")
        val vault = InMemoryMobileDeviceCredentialVault(credential)
        var clearedLocalData = false
        var pushBlocked = false
        val coordinator = EnpoDeviceUnpairCoordinator(
            client = EnpoDeviceUnpairClient { EnpoDeviceUnpairResponse.Unavailable },
            credentialVault = vault,
            clearLocalData = { clearedLocalData = true },
            blockNotifications = { pushBlocked = true },
        )

        assertEquals(EnpoDeviceUnpairState.ServiceUnavailable, coordinator.unpair())
        assertEquals(false, clearedLocalData)
        assertEquals(true, pushBlocked)
        assertEquals(credential, vault.restore())
    }

    @Test
    fun cancelledRevocationKeepsPushBlockedUntilOutcomeIsConfirmed() = runTest {
        val credential = MobileDeviceCredential("device", "secret")
        val vault = InMemoryMobileDeviceCredentialVault(credential)
        val revokeStarted = CompletableDeferred<Unit>()
        val releaseRevoke = CompletableDeferred<Unit>()
        var pushBlocked = false
        val coordinator = EnpoDeviceUnpairCoordinator(
            client = EnpoDeviceUnpairClient {
                revokeStarted.complete(Unit)
                releaseRevoke.await()
                EnpoDeviceUnpairResponse.Revoked
            },
            credentialVault = vault,
            blockNotifications = { pushBlocked = true },
        )

        val unpair = async { coordinator.unpair() }
        revokeStarted.await()
        unpair.cancelAndJoin()

        assertEquals(true, pushBlocked)
        assertEquals(credential, vault.restore())
    }

    @Test
    fun pushStaysBlockedAfterServerRevocationAndLocalCleanup() = runTest {
        val vault = InMemoryMobileDeviceCredentialVault(MobileDeviceCredential("device", "secret"))
        var pushBlocked = false
        val coordinator = EnpoDeviceUnpairCoordinator(
            client = EnpoDeviceUnpairClient {
                assertEquals(true, pushBlocked)
                EnpoDeviceUnpairResponse.Revoked
            },
            credentialVault = vault,
            blockNotifications = { pushBlocked = true },
        )

        assertEquals(EnpoDeviceUnpairState.Completed, coordinator.unpair())
        assertEquals(true, pushBlocked)
    }

    @Test
    fun alreadyInvalidCredentialCanBeRemovedLocally() = runTest {
        val vault = InMemoryMobileDeviceCredentialVault(MobileDeviceCredential("device", "secret"))
        val coordinator = EnpoDeviceUnpairCoordinator(
            client = EnpoDeviceUnpairClient { EnpoDeviceUnpairResponse.AlreadyInvalid },
            credentialVault = vault,
        )

        assertEquals(EnpoDeviceUnpairState.Completed, coordinator.unpair())
        assertNull(vault.restore())
    }

    @Test
    fun localCleanupFailureKeepsCredentialSoTheUserCanRetry() = runTest {
        val credential = MobileDeviceCredential("device", "secret")
        val vault = InMemoryMobileDeviceCredentialVault(credential)
        var attempts = 0
        val coordinator = EnpoDeviceUnpairCoordinator(
            client = EnpoDeviceUnpairClient { EnpoDeviceUnpairResponse.Revoked },
            credentialVault = vault,
            clearLocalData = {
                attempts++
                if (attempts == 1) error("Synthetic local cleanup failure")
            },
        )

        assertEquals(EnpoDeviceUnpairState.LocalCleanupFailed, coordinator.unpair())
        assertEquals(credential, vault.restore())
        assertEquals(EnpoDeviceUnpairState.Completed, coordinator.unpair())
        assertNull(vault.restore())
    }

    @Test
    fun restartFinishesRevokedUnpairBeforeAnyQueuedPushCanBeDelivered() = runTest {
        val vault = InMemoryMobileDeviceCredentialVault(MobileDeviceCredential("device", "secret"))
        val preferences = InMemoryPreferenceStore()
        var delivered = 0
        val firstGate = EnpoPairedPushGate(preferences, vault, PushMessageHandler { delivered++ })
        val firstAttempt = EnpoDeviceUnpairCoordinator(
            client = EnpoDeviceUnpairClient { EnpoDeviceUnpairResponse.Revoked },
            credentialVault = vault,
            clearLocalData = { error("Synthetic interrupted cleanup") },
            blockNotifications = firstGate::block,
        )
        assertEquals(EnpoDeviceUnpairState.LocalCleanupFailed, firstAttempt.unpair())

        val restartedGate = EnpoPairedPushGate(preferences, vault, PushMessageHandler { delivered++ })
        restartedGate.onMessage(PushMessage(null, emptyMap(), 0, 60))
        assertEquals(0, delivered)
        assertEquals(true, restartedGate.isBlocked())

        val resumedAttempt = EnpoDeviceUnpairCoordinator(
            client = EnpoDeviceUnpairClient { EnpoDeviceUnpairResponse.AlreadyInvalid },
            credentialVault = vault,
            blockNotifications = restartedGate::block,
        )
        assertEquals(EnpoDeviceUnpairState.Completed, resumedAttempt.unpair())
        assertNull(vault.restore())
        assertEquals(true, restartedGate.isBlocked())
    }
}
