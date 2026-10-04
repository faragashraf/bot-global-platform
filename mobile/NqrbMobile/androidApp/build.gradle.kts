import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val googleServerClientId = providers.gradleProperty("nqrbGoogleServerClientId")
    .orElse(providers.environmentVariable("NQRB_GOOGLE_SERVER_CLIENT_ID"))
    .getOrElse("235804274047-bb8tmlbfttdm8irq9hl8br4mac1su1pu.apps.googleusercontent.com")

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
}

val validateNqrbGoogleSignInConfig = tasks.register("validateNqrbGoogleSignInConfig") {
    val configuredGoogleServerClientId = googleServerClientId
    doLast {
        require(configuredGoogleServerClientId.isNotBlank()) {
            "NQRB Android sign-in requires nqrbGoogleServerClientId (or NQRB_GOOGLE_SERVER_CLIENT_ID) matching the backend ServerClientId."
        }
    }
}

tasks.named("preBuild") {
    dependsOn(validateNqrbGoogleSignInConfig)
}

dependencies {
    implementation(projects.nqrbMobile.composeApp)
    implementation(projects.firebaseMessaging)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.telecom)
    implementation(libs.compose.uiToolingPreview)
    implementation("com.microsoft.signalr:signalr:7.0.0")
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

android {
    namespace = "com.botglobal.nqrb"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.botglobal.nqrb"
        minSdk = maxOf(24, libs.versions.android.minSdk.get().toInt())
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 7
        versionName = "0.2.3"
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", googleServerClientId.asBuildConfigString())
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            val apiUrl = providers.gradleProperty("nqrbDebugApiBaseUrl").getOrElse("http://10.0.2.2:5062")
            buildConfigField("String", "API_BASE_URL", apiUrl.asBuildConfigString())
        }
        create("canary") {
            initWith(getByName("debug"))
            versionNameSuffix = "-canary"
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            matchingFallbacks += listOf("debug")
            val apiUrl = providers.gradleProperty("nqrbCanaryApiBaseUrl")
                .orElse(providers.environmentVariable("NQRB_CANARY_API_BASE_URL"))
                .getOrElse("http://134.209.241.35:18080/backend")
            buildConfigField("String", "API_BASE_URL", apiUrl.asBuildConfigString())
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "API_BASE_URL",
                "https://botglobalservice.com/backend".asBuildConfigString(),
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
