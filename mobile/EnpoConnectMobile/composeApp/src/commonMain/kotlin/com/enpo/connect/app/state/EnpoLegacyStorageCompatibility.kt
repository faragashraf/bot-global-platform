package com.enpo.connect.app.state

/**
 * Names owned by the installed legacy application. Keeping them here prevents
 * accidental storage migration while pairing remains outside Slice 1.
 * This descriptor never reads or exposes credential values.
 */
object EnpoLegacyStorageCompatibility {
    const val ApplicationPreferencesFile = "enpo_connect_preferences"
    const val LanguagePreferenceKey = "preferred_language"
    const val ThemePreferenceKey = "preferred_theme"
    const val AppProtectionPreferenceKey = "app_protection_enabled"

    const val InstallationPreferencesFile = "enpo_connect_installation"
    const val InstallationIdKey = "installation_id"

    const val DevicePreferencesFile = "enpo_connect_mobile_device"
    const val DeviceIdKey = "device_id"
    const val DeviceCredentialPayloadKey = "device_credential"
    const val DeviceCredentialIvKey = "device_credential_iv"
    const val AndroidKeystoreAlias = "enpo_connect_mobile_device_key"
}

object EnpoReleaseIdentity {
    const val ApplicationId = "com.enpo.connect"
    const val MinimumAndroidSdk = 23
}

object EnpoSlice1Boundaries {
    const val FirebaseEnabled = false
    const val PairingEnabled = false
    const val NotificationsEnabled = false
    const val BackendAccessEnabled = false
}
