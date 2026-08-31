import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val googleServerClientId = providers.gradleProperty("nqrbGoogleServerClientId")
    .orElse(providers.environmentVariable("NQRB_GOOGLE_SERVER_CLIENT_ID"))
    .getOrElse("")
val callTargetMembershipId = providers.gradleProperty("nqrbCallTargetMembershipId")
    .orElse(providers.environmentVariable("NQRB_CALL_TARGET_MEMBERSHIP_ID"))
    .getOrElse("")
val callTargetDisplayName = providers.gradleProperty("nqrbCallTargetDisplayName")
    .orElse(providers.environmentVariable("NQRB_CALL_TARGET_DISPLAY_NAME"))
    .getOrElse("")

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.nqrbMobile.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.telecom)
    implementation(libs.compose.uiToolingPreview)
    implementation("com.microsoft.signalr:signalr:7.0.0")
}

android {
    namespace = "com.botglobal.nqrb"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.botglobal.nqrb"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", googleServerClientId.asBuildConfigString())
        buildConfigField("String", "CALL_TARGET_MEMBERSHIP_ID", callTargetMembershipId.asBuildConfigString())
        buildConfigField("String", "CALL_TARGET_DISPLAY_NAME", callTargetDisplayName.asBuildConfigString())
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            val apiUrl = providers.gradleProperty("nqrbDebugApiBaseUrl").getOrElse("http://10.0.2.2:5062")
            buildConfigField("String", "API_BASE_URL", apiUrl.asBuildConfigString())
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "API_BASE_URL",
                "https://bgapi.challengershoes.com".asBuildConfigString(),
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}
