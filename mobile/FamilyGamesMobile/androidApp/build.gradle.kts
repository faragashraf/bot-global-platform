import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.familyGamesMobile.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.compose.foundation)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.zxing.core.api23)
    implementation(libs.zxing.android.embedded) {
        isTransitive = false
    }
}

android {
    namespace = "com.botglobal.familygames"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.botglobal.familygames"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        val invitationLinkBase = providers.gradleProperty("familyGamesInvitationLinkBase").orNull
            ?: "familygames://invite"
        buildConfigField("String", "INVITATION_LINK_BASE", "\"$invitationLinkBase\"")
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            val debugUrl = providers.gradleProperty("familyGamesDebugApiBaseUrl").orNull
                ?: "http://10.0.2.2:5062"
            buildConfigField("String", "API_BASE_URL", "\"$debugUrl\"")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val configuredUrl = providers.gradleProperty("familyGamesApiBaseUrl").orNull
                ?: "https://configure-family-games-api.invalid"
            buildConfigField("String", "API_BASE_URL", "\"$configuredUrl\"")
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
