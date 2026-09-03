import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        getByName("release") {
            isMinifyEnabled = false
            if (enpoSigningConfigured) {
                signingConfig = signingConfigs.getByName("legacyRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

tasks.register("verifyEnpoSlice1Identity") {
    group = "verification"
    description = "Verifies the immutable ENPO package and API floor for migration Slice 1."
    doLast {
        check(android.defaultConfig.applicationId == "com.enpo.connect")
        check(android.defaultConfig.minSdk == 23)
    }
}

tasks.named("preBuild").configure {
    dependsOn("verifyEnpoSlice1Identity")
}
