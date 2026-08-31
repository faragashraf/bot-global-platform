package com.ashraffarag.sentricam.recording.upload.android

import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadCallResult
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadFailure
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadItem
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingUploadApiClientTest {
    @Test
    fun uploadUsesDeviceBearerMultipartChecksumAndReportsProgress() = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("sentricam-upload", ".mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val checksum = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"recording":{"recordingId":"server-recording","checksumSha256":"$checksum"},"duplicate":false}""",
                    ),
            )
            server.start()
            val client = RecordingUploadApiClient(OkHttpClient())
            val item = item(file, checksum)

            assertEquals(
                RecordingUploadCallResult.NotFound,
                client.findExisting(server.url("/").toString(), "secret-token", item.clientRecordingId, checksum),
            )
            val progress = mutableListOf<Int>()
            val result = client.upload(server.url("/").toString(), "secret-token", item, file, progress::add)

            val uploaded = result as RecordingUploadCallResult.Uploaded
            assertEquals("server-recording", uploaded.receipt.serverRecordingId)
            assertEquals(100, progress.last())
            val head = server.takeRequest()
            val post = server.takeRequest()
            assertEquals("HEAD", head.method)
            assertEquals("POST", post.method)
            assertEquals("Bearer secret-token", post.getHeader("Authorization"))
            val body = post.body.readUtf8()
            assertTrue(body.contains("segment-1"))
            assertTrue(body.contains(checksum))
            assertFalse(body.contains("accessToken"))
        } finally {
            server.shutdown()
            file.delete()
        }
    }

    @Test
    fun preflightReceiptAvoidsUploadingAnAlreadyAcceptedRecording() = runBlocking {
        val server = MockWebServer()
        val checksum = "a".repeat(64)
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("X-SentriCam-Recording-Id", "existing")
                    .setHeader("X-SentriCam-Checksum-SHA256", checksum),
            )
            server.start()

            val result = RecordingUploadApiClient(OkHttpClient()).findExisting(
                server.url("/").toString(),
                "secret-token",
                "segment-1",
                checksum,
            )

            assertTrue((result as RecordingUploadCallResult.Uploaded).receipt.duplicate)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun serverFailureRemainsRetryableAndChecksumMismatchIsRejected() = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("sentricam-upload", ".mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val checksum = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"recording":{"recordingId":"server-recording","checksumSha256":"${"b".repeat(64)}"},"duplicate":false}""",
                    ),
            )
            server.start()
            val client = RecordingUploadApiClient(OkHttpClient())

            val unavailable = client.upload(server.url("/").toString(), "secret-token", item(file, checksum), file) {}
            val retryFailure = (unavailable as RecordingUploadCallResult.Failed).failure
            assertTrue(retryFailure is RecordingUploadFailure.Network)
            assertTrue(retryFailure.retryAllowed)

            val mismatched = client.upload(server.url("/").toString(), "secret-token", item(file, checksum), file) {}
            assertTrue((mismatched as RecordingUploadCallResult.Failed).failure is RecordingUploadFailure.Conflict)
        } finally {
            server.shutdown()
            file.delete()
        }
    }

    private fun item(file: File, checksum: String) = RecordingUploadItem(
        clientRecordingId = "segment-1",
        sessionId = "session-1",
        absolutePath = file.absolutePath,
        fileName = file.name,
        durationMillis = 1_000,
        sizeBytes = file.length(),
        createdAtMillis = 1_700_000_000_000,
        motion = false,
        manual = true,
        checksumSha256 = checksum,
    )
}
