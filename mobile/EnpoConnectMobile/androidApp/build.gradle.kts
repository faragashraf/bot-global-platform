import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

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
}

dependencies {
    implementation(projects.enpoConnectMobile.composeApp)
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

tasks.named("preBuild").configure {
    dependsOn("verifyEnpoMigrationIdentity")
}
