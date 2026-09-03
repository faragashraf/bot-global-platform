package com.botglobal.mobile.platform.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class MobileDeviceCredentialVaultTests {
    @Test
    fun emptyVaultReportsAbsenceWithoutExposingValues() = runTest {
        val vault = InMemoryMobileDeviceCredentialVault()

        assertNull(vault.restore())
        assertEquals(MobileDeviceCredentialAvailability.Absent, vault.availability())
    }

    @Test
    fun storedCredentialReportsPresenceAndDiagnosticTextIsRedacted() = runTest {
        val credential = MobileDeviceCredential("device-value", "credential-value")
        val vault = InMemoryMobileDeviceCredentialVault(credential)

        assertEquals(MobileDeviceCredentialAvailability.Available, vault.availability())
        assertEquals(credential, vault.restore())
        assertFalse("device-value" in credential.toString())
        assertFalse("credential-value" in credential.toString())
        assertEquals(
            "MobileDeviceCredential(deviceId=<redacted>, credential=<redacted>)",
            credential.toString(),
        )
    }
}
