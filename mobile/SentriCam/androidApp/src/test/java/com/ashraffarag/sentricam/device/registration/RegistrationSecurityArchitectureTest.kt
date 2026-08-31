package com.ashraffarag.sentricam.device.registration

import com.ashraffarag.sentricam.device.registration.android.AndroidKeystoreSecretCipher
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationSecurityArchitectureTest {
    @Test
    fun androidNetworkLayerContainsNoUiDependency() {
        val forbidden = listOf("android.view", "android.app.Activity", "androidx.fragment", "androidx.compose")
        val dependencyNames = listOf(
            SentriCamApiClient::class.java,
            DeviceRegistrationRepository::class.java,
            DeviceRegistrationCoordinator::class.java,
        ).flatMap { type ->
            type.declaredFields.map { it.type.name } + type.declaredMethods.map { it.returnType.name }
        }

        assertFalse(dependencyNames.any { name -> forbidden.any(name::contains) })
    }

    @Test
    fun api23SecureStorageUsesAndroidKeystoreWithoutUiOrLegacyCrypto() {
        val dependencyNames = AndroidKeystoreSecretCipher::class.java.declaredFields.map { it.type.name }

        assertFalse(dependencyNames.any { "android.view" in it || "android.app.Activity" in it })
        assertFalse(dependencyNames.any { "androidx.security.crypto" in it })
    }

    @Test
    fun settingsAndSafeLoggerNeverReferenceRawCredentialFields() {
        val settings = source("src/main/java/com/ashraffarag/sentricam/settings/android/AppSettingsActivity.kt")
        val logger = source(
            "src/main/java/com/ashraffarag/sentricam/device/registration/android/AndroidRegistrationLogger.kt",
        )

        assertFalse("Settings references raw access token", Regex("accessToken(?!Expires)").containsMatchIn(settings))
        assertFalse("Settings references refresh token", settings.contains("refreshToken"))
        assertFalse("Settings references authorization header", settings.contains("Authorization"))
        listOf("accessToken", "refreshToken", "Authorization").forEach { secretName ->
            assertFalse("Logger references $secretName", logger.contains(secretName))
        }
    }

    @Test
    fun cleartextIsDebugOnlyAndSignalRUsesTheSupportedTransportDependency() {
        val mainManifest = source("src/main/AndroidManifest.xml")
        val debugManifest = source("src/debug/AndroidManifest.xml")
        val dependencies = source("../../gradle/libs.versions.toml")

        assertTrue(mainManifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(debugManifest.contains("android:usesCleartextTraffic=\"true\""))
        assertTrue(dependencies.contains("com.microsoft.signalr"))
        assertFalse(dependencies.contains("hubconnection", ignoreCase = true))
    }

    @Test
    fun localCaddyCaTrustIsGeneratedForDebugOnly() {
        val build = source("build.gradle.kts")
        val debugManifest = source("src/debug/AndroidManifest.xml")
        val debugNetworkSecurity = source("src/debug/res/xml/network_security_config.xml")
        val mainManifest = source("src/main/AndroidManifest.xml")

        assertTrue(build.contains("applicationIdSuffix = \".debug\""))
        assertTrue(build.contains("prepareSentriCamLocalCa"))
        assertTrue(build.contains("sourceSets.getByName(\"debug\")"))
        assertTrue(debugManifest.contains("@xml/network_security_config"))
        assertTrue(debugNetworkSecurity.contains("<certificates src=\"system\" />"))
        assertTrue(debugNetworkSecurity.contains("<certificates src=\"@raw/sentricam_local_ca\" />"))
        assertFalse(debugNetworkSecurity.contains("<certificates src=\"user\" />"))
        assertFalse(mainManifest.contains("android:networkSecurityConfig"))
        assertFalse(mainManifest.contains("sentricam_local_ca"))
    }

    @Test
    fun localCaPreparationUsesOnlyValidatedPublicCertificateMaterial() {
        val script = source("../scripts/android/prepare-local-hub-ca.sh")

        assertTrue(script.contains("SENTRICAM_CADDY_ROOT_CA"))
        assertTrue(script.contains("CA:TRUE"))
        assertTrue(script.contains("PRIVATE KEY"))
        assertTrue(script.contains("-outform DER"))
        assertFalse(script.contains("root.key"))
    }

    @Test
    fun credentialPreferencesAreExcludedFromBackupAndApi23RemainsMinimum() {
        val backup = source("src/main/res/xml/backup_rules.xml")
        val extraction = source("src/main/res/xml/data_extraction_rules.xml")
        val build = source("build.gradle.kts")

        assertTrue(backup.contains("device_registration_credentials.xml"))
        assertTrue(extraction.contains("device_registration_credentials.xml"))
        assertTrue(build.contains("minSdk = 23"))
    }

    private fun source(relativePath: String): String = File(relativePath).readText()
}
