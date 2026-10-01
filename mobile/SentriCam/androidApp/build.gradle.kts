plugins {
    alias(libs.plugins.androidApplication)
}

abstract class ValidateSentriCamReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val missingPropertyNames: ListProperty<String>

    @get:Internal
    abstract val configuredStoreFile: RegularFileProperty

    @TaskAction
    fun validateSigning() {
        val missingProperties = missingPropertyNames.get()

        if (missingProperties.isNotEmpty()) {
            throw GradleException(
                "Release signing requires these Gradle properties: ${missingProperties.joinToString()}",
            )
        }

        if (configuredStoreFile.orNull?.asFile?.isFile != true) {
            throw GradleException("The configured release signing keystore file does not exist.")
        }
    }
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val debugServerUrl = providers.gradleProperty("sentricam.debugServerUrl")
    .orElse("http://10.0.2.2:5173/")
val releaseServerUrl = providers.gradleProperty("sentricam.releaseServerUrl")
    .orElse("")
val localCaddyRootCa = providers.gradleProperty("sentricam.caddyRootCa")
    .orElse(providers.environmentVariable("SENTRICAM_CADDY_ROOT_CA"))
val localCaResourceDirectory = layout.buildDirectory.dir(
    "generated/sentricamLocalCa/debug/res",
).get()
val localCaResourceFile = localCaResourceDirectory.file("raw/sentricam_local_ca.der")
val localCaPreparationScript = project.projectDir.parentFile.resolve(
    "scripts/android/prepare-local-hub-ca.sh",
)
val localCaPreparationTestScript = project.projectDir.parentFile.resolve(
    "scripts/android/test-prepare-local-hub-ca.sh",
)

val prepareSentriCamLocalCa by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Validates and generates the Debug-only SentriCam Caddy Root CA resource."
    val command = mutableListOf(
        localCaPreparationScript.absolutePath,
        "--output",
        localCaResourceFile.asFile.absolutePath,
    )
    localCaddyRootCa.orNull?.takeIf(String::isNotBlank)?.let {
        command += listOf("--ca", it)
    }
    commandLine(command)
    outputs.file(localCaResourceFile)
    outputs.upToDateWhen { false }
}

tasks.register<Exec>("testSentriCamLocalCaPreparation") {
    group = "verification"
    description = "Exercises missing, invalid, private-key, and valid local CA preparation paths."
    commandLine(localCaPreparationTestScript.absolutePath)
}

val releaseSigningPropertyNames = listOf(
    "SENTRICAM_UPLOAD_STORE_FILE",
    "SENTRICAM_UPLOAD_STORE_PASSWORD",
    "SENTRICAM_UPLOAD_KEY_ALIAS",
    "SENTRICAM_UPLOAD_KEY_PASSWORD",
)
val releaseSigningProperties = releaseSigningPropertyNames.associateWith { propertyName ->
    providers.gradleProperty(propertyName).orNull
}
val releaseSigningTaskPrefixes = listOf("assemble", "bundle", "install", "package", "publish", "sign", "upload")
val releaseSigningStoreFile = releaseSigningProperties["SENTRICAM_UPLOAD_STORE_FILE"]
    ?.takeIf(String::isNotBlank)
    ?.let(::file)

fun String.requiresSentriCamReleaseSigning(): Boolean {
    if (equals("signingReport", ignoreCase = true)) return true
    return contains("Release", ignoreCase = true) &&
        releaseSigningTaskPrefixes.any { startsWith(it, ignoreCase = true) }
}

val validateSentriCamReleaseSigning = tasks.register<ValidateSentriCamReleaseSigningTask>(
    "validateSentriCamReleaseSigning",
) {
    group = "verification"
    description = "Validates SentriCam release signing credentials before a signing task runs."
    missingPropertyNames.set(
        releaseSigningProperties.filterValues { it.isNullOrBlank() }.keys,
    )
    releaseSigningStoreFile?.let(configuredStoreFile::set)
}

android {
    namespace = "com.ashraffarag.sentricam"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ashraffarag.sentricam"
        minSdk = 23
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            releaseSigningProperties["SENTRICAM_UPLOAD_STORE_FILE"]
                ?.takeIf { it.isNotBlank() }
                ?.let { storeFile = file(it) }
            storePassword = releaseSigningProperties["SENTRICAM_UPLOAD_STORE_PASSWORD"]
                ?.takeIf { it.isNotBlank() }
            keyAlias = releaseSigningProperties["SENTRICAM_UPLOAD_KEY_ALIAS"]
                ?.takeIf { it.isNotBlank() }
            keyPassword = releaseSigningProperties["SENTRICAM_UPLOAD_KEY_PASSWORD"]
                ?.takeIf { it.isNotBlank() }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "DEFAULT_SERVER_BASE_URL", debugServerUrl.get().asBuildConfigString())
        }
        release {
            buildConfigField("String", "DEFAULT_SERVER_BASE_URL", releaseServerUrl.get().asBuildConfigString())
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    sourceSets.getByName("debug").res.srcDir(localCaResourceDirectory.asFile)
}

tasks.configureEach {
    if (name == "preDebugBuild") {
        dependsOn(prepareSentriCamLocalCa)
    }
    if (name.requiresSentriCamReleaseSigning()) {
        dependsOn(validateSentriCamReleaseSigning)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.effects)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.signalr)
    implementation(libs.sentricam.webrtc.android)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
