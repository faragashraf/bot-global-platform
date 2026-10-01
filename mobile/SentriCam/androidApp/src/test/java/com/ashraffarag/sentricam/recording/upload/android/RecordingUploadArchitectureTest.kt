package com.ashraffarag.sentricam.recording.upload.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingUploadArchitectureTest {
    private val sourceRoot = File("src/main/java/com/ashraffarag/sentricam")

    @Test
    fun workerUsesPersistentNetworkConstrainedWorkAndNeverDeletesRecording() {
        val worker = File(sourceRoot, "recording/upload/android/RecordingUploadWorker.kt").readText()
        val scheduler = File(sourceRoot, "recording/upload/android/WorkManagerRecordingUploadScheduler.kt").readText()

        assertTrue(worker.contains("CoroutineWorker"))
        assertTrue(scheduler.contains("NetworkType.CONNECTED"))
        assertTrue(scheduler.contains("BackoffPolicy.EXPONENTIAL"))
        assertFalse(worker.contains("file.delete("))
        assertFalse(worker.contains("File.delete("))
    }

    @Test
    fun uploadLayerHasNoUiDependencyAndSupportsApi23ProjectMinimum() {
        val uploadSources = File(sourceRoot, "recording/upload").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val build = File("build.gradle.kts").readText()

        assertFalse(uploadSources.contains("android.app.Activity"))
        assertFalse(uploadSources.contains("android.view.View"))
        assertTrue(build.contains("minSdk = 23"))
    }

    @Test
    fun uploadLoggingNeverIncludesCredentialsOrEnablesHttpBodyLogging() {
        val worker = File(sourceRoot, "recording/upload/android/RecordingUploadWorker.kt").readText()
        val apiClient = File(sourceRoot, "recording/upload/android/RecordingUploadApiClient.kt").readText()
        val logStatements = worker.lineSequence()
            .filter { it.contains("Log.") }
            .joinToString("\n")

        listOf("token", "authorization", "credential", "header").forEach { secret ->
            assertFalse(
                "Recording upload logs reference $secret",
                logStatements.contains(secret, ignoreCase = true),
            )
        }
        assertFalse(apiClient.contains("HttpLoggingInterceptor"))
        assertFalse(apiClient.contains("BODY"))
    }

    @Test
    fun workerEmitsStableLifecycleDiagnosticsAndUsesRegisteredServerIdentity() {
        val worker = File(sourceRoot, "recording/upload/android/RecordingUploadWorker.kt").readText()
        val registration = File(
            sourceRoot,
            "device/registration/DeviceRegistrationCoordinator.kt",
        ).readText()

        listOf(
            "worker_started",
            "upload_attempt",
            "server_accepted",
            "upload_succeeded",
            "upload_failed",
            "retry_scheduled",
        ).forEach { event -> assertTrue("Missing diagnostic $event", worker.contains(event)) }
        assertTrue(worker.contains("deviceCredentials.details.serverDeviceId"))
        assertTrue(registration.contains("refreshExpiredCredentials"))
        assertTrue(registration.contains("accessTokenExpiresAtMillis"))
    }
}
