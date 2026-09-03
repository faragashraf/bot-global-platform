package com.enpo.connect.app.state

import com.botglobal.mobile.platform.device.InstallationId
import com.botglobal.mobile.platform.device.InstallationIdentity
import com.botglobal.mobile.platform.device.PreferenceInstallationIdStore
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialAvailability
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import com.botglobal.mobile.platform.preferences.InMemoryPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class EnpoDeviceInfrastructureTests {
    @Test
    fun legacyInstallationIdIsPreservedBeforeDeviceStateInspection() = runTest {
        val preferences = InMemoryPreferenceStore(
            mapOf(EnpoLegacyStorageCompatibility.InstallationIdKey to "existing-installation"),
        )
        var generations = 0
        val infrastructure = PlatformEnpoDeviceInfrastructure(
            InstallationIdentity(
                PreferenceInstallationIdStore(
                    preferences,
                    EnpoLegacyStorageCompatibility.InstallationIdKey,
                ),
            ) {
                generations += 1
                InstallationId("generated-installation")
            },
            AvailabilityOnlyVault(MobileDeviceCredentialAvailability.Absent),
        )

        assertEquals(EnpoDeviceBootstrapResult.Unpaired, infrastructure.inspect())
        assertEquals(0, generations)
        assertEquals(
            "existing-installation",
            preferences.string(EnpoLegacyStorageCompatibility.InstallationIdKey),
        )
    }

    @Test
    fun missingInstallationIdIsGeneratedOnce() = runTest {
        val preferences = InMemoryPreferenceStore()
        var generations = 0
        val infrastructure = PlatformEnpoDeviceInfrastructure(
            InstallationIdentity(
                PreferenceInstallationIdStore(
                    preferences,
                    EnpoLegacyStorageCompatibility.InstallationIdKey,
                ),
            ) {
                generations += 1
                InstallationId("generated-$generations")
            },
            AvailabilityOnlyVault(MobileDeviceCredentialAvailability.Absent),
        )

        infrastructure.inspect()
        infrastructure.inspect()

        assertEquals(1, generations)
        assertEquals(
            "generated-1",
            preferences.string(EnpoLegacyStorageCompatibility.InstallationIdKey),
        )
    }

    @Test
    fun availableAndUnreadableCredentialsHaveDistinctBootstrapStates() = runTest {
        suspend fun inspect(availability: MobileDeviceCredentialAvailability) =
            PlatformEnpoDeviceInfrastructure(
                InstallationIdentity(PreferenceInstallationIdStore(InMemoryPreferenceStore(), "id")) {
                    InstallationId("installation")
                },
                AvailabilityOnlyVault(availability),
            ).inspect()

        assertEquals(
            EnpoDeviceBootstrapResult.DeviceCredentialAvailable,
            inspect(MobileDeviceCredentialAvailability.Available),
        )
        assertEquals(
            EnpoDeviceBootstrapResult.CredentialUnreadable,
            inspect(MobileDeviceCredentialAvailability.Unreadable),
        )
    }

    private class AvailabilityOnlyVault(
        private val availability: MobileDeviceCredentialAvailability,
    ) : MobileDeviceCredentialVault {
        override suspend fun restore() = error("Raw credentials must not be restored by bootstrap.")
        override suspend fun save(credential: com.botglobal.mobile.platform.notifications.MobileDeviceCredential) =
            error("Pairing writes are deferred.")
        override suspend fun clear() = error("Unpair is deferred.")
        override suspend fun availability(): MobileDeviceCredentialAvailability = availability
    }
}
