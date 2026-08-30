import java.net.URI
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

fun releaseSetting(gradlePropertyName: String, environmentName: String): String? =
    providers.gradleProperty(gradlePropertyName)
        .orElse(providers.environmentVariable(environmentName))
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)

fun requirePublicHttpsEndpoint(value: String) {
    val uri = runCatching { URI(value) }.getOrElse {
        throw GradleException("Release API base URL must be a valid public HTTPS URL.")
    }
    val host = uri.host?.lowercase()
        ?: throw GradleException("Release API base URL must include a valid host.")
    val octets = host.split('.').mapNotNull(String::toIntOrNull)
    val isPrivateIpv4 = octets.size == 4 && (
        octets[0] == 10 ||
            octets[0] == 127 ||
            octets[0] == 192 && octets[1] == 168 ||
            octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 169 && octets[1] == 254
        )
    val isDevelopmentHost = host == "localhost" ||
        host == "10.0.2.2" ||
        host.endsWith(".local") ||
        isPrivateIpv4
    if (uri.scheme != "https" || isDevelopmentHost) {
        throw GradleException("Release API configuration must use a public HTTPS endpoint.")
    }
}

val releaseApiBaseUrl = "https://bgapi.challengershoes.com".also(::requirePublicHttpsEndpoint)
val uploadStoreFile = releaseSetting("familyGamesUploadStoreFile", "LAMMA_UPLOAD_STORE_FILE")
val uploadStorePassword = releaseSetting("familyGamesUploadStorePassword", "LAMMA_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = releaseSetting("familyGamesUploadKeyAlias", "LAMMA_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = releaseSetting("familyGamesUploadKeyPassword", "LAMMA_UPLOAD_KEY_PASSWORD")
val uploadSigningValues = listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
val uploadSigningConfigured = uploadSigningValues.all { it != null }

if (uploadSigningValues.any { it != null } && !uploadSigningConfigured) {
    throw GradleException("Upload signing is partially configured. Provide all four Lamma upload signing values.")
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
    namespace = "com.botglobal.lamma"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.botglobal.lamma"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.lamma.versionCode.get().toInt()
        versionName = libs.versions.lamma.versionName.get()
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        val invitationLinkBase = providers.gradleProperty("familyGamesInvitationLinkBase").orNull
            ?: "familygames://invite"
        buildConfigField("String", "INVITATION_LINK_BASE", "\"$invitationLinkBase\"")
        buildConfigField("String", "VOICE_ICE_POLICY", "\"all\"")
    }

    signingConfigs {
        if (uploadSigningConfigured) {
            create("upload") {
                storeFile = rootProject.file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            val debugUrl = providers.gradleProperty("familyGamesDebugApiBaseUrl").orNull
                ?: "http://10.0.2.2:5062"
            buildConfigField("String", "API_BASE_URL", "\"$debugUrl\"")
            val voiceIcePolicy = providers.gradleProperty("familyGamesDebugVoiceIcePolicy").orNull ?: "all"
            if (voiceIcePolicy !in setOf("all", "relay")) {
                throw GradleException("familyGamesDebugVoiceIcePolicy must be 'all' or 'relay'.")
            }
            buildConfigField("String", "VOICE_ICE_POLICY", "\"$voiceIcePolicy\"")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (uploadSigningConfigured) {
                signingConfig = signingConfigs.getByName("upload")
            }
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
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
