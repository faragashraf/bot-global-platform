import java.util.Properties
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(readBytes())
    .joinToString("") { byte -> "%02x".format(byte) }

val enpoProductionPublicBaseUrl = "https://bgapi.challengershoes.com"
val enpoDebugPublicBaseUrl = providers.gradleProperty("enpoDebugPublicBaseUrl")
    .orElse(providers.environmentVariable("ENPO_DEBUG_PUBLIC_BASE_URL"))
    .getOrElse(enpoProductionPublicBaseUrl)

val legacySigningFile = file(
    System.getProperty("user.home") + "/.android/enpo-connect/signing.properties",
)
val enpoSigningProperties = Properties().apply {
    if (legacySigningFile.exists()) {
        legacySigningFile.inputStream().use(::load)
    }
}
val enpoStoreFile = enpoSigningProperties.getProperty("storeFile")?.takeIf(String::isNotBlank)
val enpoStorePassword = enpoSigningProperties.getProperty("storePassword")?.takeIf(String::isNotBlank)
val enpoKeyAlias = enpoSigningProperties.getProperty("keyAlias")?.takeIf(String::isNotBlank)
val enpoKeyPassword = enpoSigningProperties.getProperty("keyPassword")?.takeIf(String::isNotBlank)
val enpoSigningValues = listOf(enpoStoreFile, enpoStorePassword, enpoKeyAlias, enpoKeyPassword)
val enpoSigningConfigured = enpoSigningValues.all { it != null }

check(enpoSigningValues.none { it != null } || enpoSigningConfigured) {
    "ENPO release signing is partially configured. Provide all values in the external signing file."
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
}

dependencies {
    implementation(projects.enpoConnectMobile.composeApp)
    implementation(projects.firebaseMessaging)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.zxing.core.api23)
    implementation(libs.zxing.android.embedded) {
        isTransitive = false
    }
}

android {
    namespace = "com.enpo.connect"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.enpo.connect"
        minSdk = 23
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.0.2"
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    signingConfigs {
        if (enpoSigningConfigured) {
            create("legacyRelease") {
                storeFile = file(enpoStoreFile!!)
                storePassword = enpoStorePassword
                keyAlias = enpoKeyAlias
                keyPassword = enpoKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            buildConfigField("String", "PUBLIC_BASE_URL", enpoDebugPublicBaseUrl.asBuildConfigString())
            buildConfigField("String", "NETWORK_ENVIRONMENT", "development".asBuildConfigString())
        }
        getByName("release") {
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            buildConfigField("String", "PUBLIC_BASE_URL", enpoProductionPublicBaseUrl.asBuildConfigString())
            buildConfigField("String", "NETWORK_ENVIRONMENT", "production".asBuildConfigString())
            if (enpoSigningConfigured) {
                signingConfig = signingConfigs.getByName("legacyRelease")
            }
        }
    }

    buildFeatures.buildConfig = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

tasks.register("verifyEnpoMigrationIdentity") {
    group = "verification"
    description = "Verifies the immutable ENPO package and API floor during migration."
    doLast {
        check(android.defaultConfig.applicationId == "com.enpo.connect")
        check(android.defaultConfig.minSdk == 23)
    }
}

tasks.register("verifyEnpoFirebaseConfiguration") {
    group = "verification"
    description = "Requires the locally or CI-provisioned ENPO Firebase configuration."
    doLast {
        val configuration = file("google-services.json")
        check(configuration.exists()) {
            "Provision EnpoConnectMobile/androidApp/google-services.json from the ENPO Firebase project."
        }
        val configurationText = configuration.readText()
        check("\"package_name\": \"com.enpo.connect\"" in configurationText) {
            "The provisioned Firebase configuration does not target com.enpo.connect."
        }
        check("com.botglobal.nqrb" !in configurationText) {
            "The ENPO Android module must not use the NQRB Firebase configuration."
        }
    }
}

tasks.register("verifyEnpoProductBranding") {
    group = "verification"
    description = "Verifies the authoritative ENPO launcher and product artwork."
    doLast {
        val expectedAssets = mapOf(
            file("src/main/res/drawable/connect_launcher_mark.png") to
                "51a9586ee94592a95ea95405d97ea7c49d4e6f6b50af557e99151cc365516fb6",
            file("../composeApp/src/commonMain/composeResources/drawable/connect_logo_dark.png") to
                "8a8e3ac2f7cbc8302c926665d66c7e711f5202dcd9673c7ad61c353688ff7b45",
            file("../composeApp/src/commonMain/composeResources/drawable/connect_logo_light.png") to
                "05468c71bf0b04ad303c0359666155341e844084aa11ee44a19fd4ee439fe0d3",
            file("../composeApp/src/commonMain/composeResources/drawable/organization_logo_dark.png") to
                "8ce02296b054a855f399b42afbe8d03b1726f59e1eecd74247ccc705e0797c33",
            file("../composeApp/src/commonMain/composeResources/drawable/organization_logo_light.png") to
                "fdf874799bc43e24881994db6db932db8457ce7c3eae150c7e77622d6fbcc946",
            file("../composeApp/src/commonMain/composeResources/drawable/splash_cinematic_background.png") to
                "c6ffe94b366977223b27d3ec06701aab99f051ce4fbeba21067053c7ec6869c1",
        )
        expectedAssets.forEach { (asset, expectedHash) ->
            check(asset.exists() && asset.sha256() == expectedHash) {
                "ENPO product artwork is missing or differs from the authoritative legacy asset: ${asset.name}"
            }
        }
        val manifest = file("src/main/AndroidManifest.xml").readText()
        check("android:icon=\"@mipmap/ic_launcher\"" in manifest)
        check("android:roundIcon=\"@mipmap/ic_launcher_round\"" in manifest)
    }
}

tasks.named("preBuild").configure {
    dependsOn("verifyEnpoMigrationIdentity")
    dependsOn("verifyEnpoFirebaseConfiguration")
    dependsOn("verifyEnpoProductBranding")
}
