import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.botglobal.mobile.platform.presence.firebase"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = maxOf(24, libs.versions.android.minSdk.get().toInt()) }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin { compilerOptions.jvmTarget.set(JvmTarget.JVM_11) }

dependencies {
    api(projects.shared)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
