import java.util.Properties
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val nqrbSigningFile = file(System.getProperty("user.home") + "/.android/nqrb/signing.properties")
val nqrbSigningProperties = Properties().apply {
    if (nqrbSigningFile.exists()) {
        nqrbSigningFile.inputStream().use(::load)
    }
}

fun releaseSetting(gradlePropertyName: String, environmentName: String, signingPropertyName: String): String? =
    providers.gradleProperty(gradlePropertyName)
        .orElse(providers.environmentVariable(environmentName))
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: nqrbSigningProperties.getProperty(signingPropertyName)?.trim()?.takeIf(String::isNotEmpty)

val googleServerClientId = providers.gradleProperty("nqrbGoogleServerClientId")
    .orElse(providers.environmentVariable("NQRB_GOOGLE_SERVER_CLIENT_ID"))
    .getOrElse("470964330068-4q9469h04hb9e41j0r8ijhfqphpdmc8k.apps.googleusercontent.com")
val uploadStoreFile = releaseSetting("nqrbUploadStoreFile", "NQRB_UPLOAD_STORE_FILE", "storeFile")
val uploadStorePassword = releaseSetting("nqrbUploadStorePassword", "NQRB_UPLOAD_STORE_PASSWORD", "storePassword")
val uploadKeyAlias = releaseSetting("nqrbUploadKeyAlias", "NQRB_UPLOAD_KEY_ALIAS", "keyAlias")
val uploadKeyPassword = releaseSetting("nqrbUploadKeyPassword", "NQRB_UPLOAD_KEY_PASSWORD", "keyPassword")
val uploadSigningValues = listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
val uploadSigningConfigured = uploadSigningValues.all { it != null }
val nqrbVersionCode = providers.gradleProperty("nqrbVersionCode").map(String::toInt).getOrElse(12)
val nqrbVersionName = providers.gradleProperty("nqrbVersionName").getOrElse("0.2.7")
val nqrbPresenceEnabled = providers.gradleProperty("nqrbPresenceEnabled").map(String::toBoolean).getOrElse(false)
val nqrbPresenceProjectId = providers.gradleProperty("nqrbPresenceProjectId").getOrElse("")
val nqrbPresenceDatabaseNamespace = providers.gradleProperty("nqrbPresenceDatabaseNamespace").getOrElse("")
val nqrbPresenceDatabaseUrl = providers.gradleProperty("nqrbPresenceDatabaseUrl").getOrElse("")
val nqrbPresenceDatabaseHost = providers.gradleProperty("nqrbPresenceDatabaseHost").getOrElse("")
val nqrbPresenceApiKey = providers.gradleProperty("nqrbPresenceApiKey").getOrElse("")
val nqrbPresenceApplicationId = providers.gradleProperty("nqrbPresenceApplicationId").getOrElse("")
val releaseTaskPrefixes = listOf("assemble", "bundle", "install", "package", "publish", "sign", "upload")

if (uploadSigningValues.any { it != null } && !uploadSigningConfigured) {
    throw GradleException("NQRB upload signing is partially configured. Provide all four NQRB upload signing values.")
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.kotlinxSerialization)
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

fun String.requiresNqrbReleaseSigning(): Boolean =
    contains("Release", ignoreCase = true) &&
        releaseTaskPrefixes.any { startsWith(it, ignoreCase = true) }

val validateNqrbReleaseSigningConfig = tasks.register("validateNqrbReleaseSigningConfig") {
    group = "verification"
    description = "Requires upload signing before producing an NQRB release artifact."
    inputs.property("uploadSigningConfigured", uploadSigningConfigured)
    inputs.property("signingFilePath", nqrbSigningFile.absolutePath)
    doLast {
        if (inputs.properties["uploadSigningConfigured"] != true) {
            throw GradleException(
                "NQRB release signing requires nqrbUploadStoreFile/NQRB_UPLOAD_STORE_FILE, " +
                    "nqrbUploadStorePassword/NQRB_UPLOAD_STORE_PASSWORD, " +
                    "nqrbUploadKeyAlias/NQRB_UPLOAD_KEY_ALIAS, and " +
                    "nqrbUploadKeyPassword/NQRB_UPLOAD_KEY_PASSWORD, or all four values in " +
                    "${inputs.properties["signingFilePath"]}.",
            )
        }
    }
}

tasks.configureEach {
    if (name.requiresNqrbReleaseSigning()) {
        dependsOn(validateNqrbReleaseSigningConfig)
    }
}

dependencies {
    implementation(projects.nqrbMobile.composeApp)
    implementation(projects.firebaseMessaging)
    implementation(projects.firebasePresence)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.telecom)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
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
        versionCode = nqrbVersionCode
        versionName = nqrbVersionName
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", googleServerClientId.asBuildConfigString())
        buildConfigField("boolean", "PRESENCE_ENABLED", nqrbPresenceEnabled.toString())
        buildConfigField("String", "PRESENCE_PROJECT_ID", nqrbPresenceProjectId.asBuildConfigString())
        buildConfigField("String", "PRESENCE_DATABASE_NAMESPACE", nqrbPresenceDatabaseNamespace.asBuildConfigString())
        buildConfigField("String", "PRESENCE_DATABASE_URL", nqrbPresenceDatabaseUrl.asBuildConfigString())
        buildConfigField("String", "PRESENCE_DATABASE_HOST", nqrbPresenceDatabaseHost.asBuildConfigString())
        buildConfigField("String", "PRESENCE_API_KEY", nqrbPresenceApiKey.asBuildConfigString())
        buildConfigField("String", "PRESENCE_APPLICATION_ID", nqrbPresenceApplicationId.asBuildConfigString())
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
            val apiUrl = providers.gradleProperty("nqrbDebugApiBaseUrl").getOrElse("http://10.0.2.2:5062")
            buildConfigField("String", "API_BASE_URL", apiUrl.asBuildConfigString())
        }
        create("canary") {
            initWith(getByName("debug"))
            versionNameSuffix = "-canary"
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            matchingFallbacks += listOf("debug")
            val apiUrl = providers.gradleProperty("nqrbCanaryApiBaseUrl")
                .orElse(providers.environmentVariable("NQRB_CANARY_API_BASE_URL"))
                .getOrElse("https://api.botglobalservice.com")
            buildConfigField("String", "API_BASE_URL", apiUrl.asBuildConfigString())
        }
        getByName("release") {
            isMinifyEnabled = false
            if (uploadSigningConfigured) {
                signingConfig = signingConfigs.getByName("upload")
            }
            buildConfigField(
                "String",
                "API_BASE_URL",
                "https://api.botglobalservice.com".asBuildConfigString(),
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
