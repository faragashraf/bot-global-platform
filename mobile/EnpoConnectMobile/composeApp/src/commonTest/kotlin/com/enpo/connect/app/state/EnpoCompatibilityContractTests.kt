package com.enpo.connect.app.state

import com.enpo.connect.app.ui.enpoStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnpoCompatibilityContractTests {
    @Test
    fun releaseIdentityRemainsTheInstalledEnpoApplication() {
        assertEquals("com.enpo.connect", EnpoReleaseIdentity.ApplicationId)
        assertEquals(23, EnpoReleaseIdentity.MinimumAndroidSdk)
    }

    @Test
    fun legacyStorageNamesRemainStableWithoutReadingCredentials() {
        assertEquals("enpo_connect_preferences", EnpoLegacyStorageCompatibility.ApplicationPreferencesFile)
        assertEquals("preferred_language", EnpoLegacyStorageCompatibility.LanguagePreferenceKey)
        assertEquals("preferred_theme", EnpoLegacyStorageCompatibility.ThemePreferenceKey)
        assertEquals("app_protection_enabled", EnpoLegacyStorageCompatibility.AppProtectionPreferenceKey)
        assertEquals("notifications_enabled", EnpoLegacyStorageCompatibility.NotificationsEnabledPreferenceKey)
        assertEquals("notification_sound", EnpoLegacyStorageCompatibility.NotificationSoundPreferenceKey)
        assertEquals(
            "device_notification_sound_uri",
            EnpoLegacyStorageCompatibility.DeviceNotificationSoundUriPreferenceKey,
        )
        assertEquals("enpo_connect_installation", EnpoLegacyStorageCompatibility.InstallationPreferencesFile)
        assertEquals("installation_id", EnpoLegacyStorageCompatibility.InstallationIdKey)
        assertEquals("enpo_connect_mobile_device", EnpoLegacyStorageCompatibility.DevicePreferencesFile)
        assertEquals("device_id", EnpoLegacyStorageCompatibility.DeviceIdKey)
        assertEquals("device_credential", EnpoLegacyStorageCompatibility.DeviceCredentialPayloadKey)
        assertEquals("device_credential_iv", EnpoLegacyStorageCompatibility.DeviceCredentialIvKey)
        assertEquals("enpo_connect_mobile_device_key", EnpoLegacyStorageCompatibility.AndroidKeystoreAlias)
    }

    @Test
    fun sliceFourEnablesPushAfterPairingAndKeepsUiBootstrapOffline() {
        assertTrue(EnpoMigrationBoundaries.FirebaseEnabled)
        assertTrue(EnpoMigrationBoundaries.NotificationsEnabled)
        assertTrue(EnpoMigrationBoundaries.PairingEnabled)
        assertFalse(EnpoMigrationBoundaries.NetworkCallsDuringUiBootstrap)
    }

    @Test
    fun enpoCopyContainsNoSiblingProductBranding() {
        val copy = enpoStrings("ar").allValues() + enpoStrings("en").allValues()
        val normalized = copy.joinToString(" ").lowercase()

        assertFalse("nqrb" in normalized)
        assertFalse("نقرب" in normalized)
        assertFalse("lamma" in normalized)
        assertFalse("لمة" in normalized)
    }
}
