package com.ashraffarag.sentricam.recording.upload.android

import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadCallResult
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadFailure
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadItem
import com.ashraffarag.sentricam.recording.upload.domain.RecordingUploadReceipt
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink

class RecordingUploadApiClient(
    private val client: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson(),
) {
    suspend fun findExisting(
        serverBaseUrl: String,
        accessToken: String,
        clientRecordingId: String,
        expectedChecksum: String,
    ): RecordingUploadCallResult {
        val url = serverBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments(UPLOAD_PATH)
            .addPathSegment(clientRecordingId)
            .build()
        val request = Request.Builder()
            .url(url)
            .header(AUTHORIZATION, "Bearer $accessToken")
            .head()
            .build()
        return execute(request) { response ->
            when {
                response.code == 404 -> RecordingUploadCallResult.NotFound
                response.code == 401 || response.code == 403 ->
                    RecordingUploadCallResult.Failed(RecordingUploadFailure.Unauthorized)
                !response.isSuccessful -> httpFailure(response.code)
                else -> {
                    val recordingId = response.header(RECORDING_ID_HEADER)
                    val checksum = response.header(CHECKSUM_HEADER)
                    if (recordingId.isNullOrBlank() || checksum.isNullOrBlank() ||
                        !checksum.equals(expectedChecksum, ignoreCase = true)
                    ) {
                        RecordingUploadCallResult.Failed(RecordingUploadFailure.Conflict)
                    } else {
                        RecordingUploadCallResult.Uploaded(
                            RecordingUploadReceipt(recordingId, checksum.lowercase(), duplicate = true),
                        )
                    }
                }
            }
        }
    }

    suspend fun upload(
        serverBaseUrl: String,
        accessToken: String,
        item: RecordingUploadItem,
        file: File,
        onProgress: (Int) -> Unit,
    ): RecordingUploadCallResult {
        val checksum = requireNotNull(item.checksumSha256)
        val fileBody = ProgressFileRequestBody(file, VIDEO_MEDIA_TYPE, onProgress)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("ClientRecordingId", item.clientRecordingId)
            .addFormDataPart("SessionId", item.sessionId)
            .addFormDataPart("DurationMilliseconds", item.durationMillis.toString())
            .addFormDataPart("SizeBytes", item.sizeBytes.toString())
            .addFormDataPart("CreatedUtc", Instant.ofEpochMilli(item.createdAtMillis).toString())
            .addFormDataPart("Motion", item.motion.toString())
            .addFormDataPart("Manual", item.manual.toString())
            .addFormDataPart("ChecksumSha256", checksum)
            .addFormDataPart("File", item.fileName, fileBody)
            .build()
        val url = serverBaseUrl.toHttpUrl().newBuilder().addPathSegments(UPLOAD_PATH).build()
        val request = Request.Builder()
            .url(url)
            .header(AUTHORIZATION, "Bearer $accessToken")
            .header("Idempotency-Key", item.clientRecordingId)
            .header(CHECKSUM_HEADER, checksum)
            .post(multipart)
            .build()
        return execute(request) { response ->
            if (!response.isSuccessful) return@execute httpFailure(response.code)
            val body = response.body?.string()
                ?: return@execute RecordingUploadCallResult.Failed(RecordingUploadFailure.Unexpected())
            try {
                val payload = gson.fromJson(body, UploadResponse::class.java)
                val recording = payload?.recording
                if (recording?.recordingId.isNullOrBlank() || recording?.checksumSha256.isNullOrBlank()) {
                    RecordingUploadCallResult.Failed(RecordingUploadFailure.Unexpected())
                } else if (!recording.checksumSha256.equals(checksum, ignoreCase = true)) {
                    RecordingUploadCallResult.Failed(RecordingUploadFailure.Conflict)
                } else {
                    RecordingUploadCallResult.Uploaded(
                        RecordingUploadReceipt(
                            requireNotNull(recording).recordingId,
                            recording.checksumSha256.lowercase(),
                            payload.duplicate,
                        ),
                    )
                }
            } catch (exception: JsonParseException) {
                RecordingUploadCallResult.Failed(RecordingUploadFailure.Unexpected(exception))
            } catch (exception: RuntimeException) {
                RecordingUploadCallResult.Failed(RecordingUploadFailure.Unexpected(exception))
            }
        }
    }

    private suspend fun execute(
        request: Request,
        map: (Response) -> RecordingUploadCallResult,
    ): RecordingUploadCallResult = try {
        await(client.newCall(request)).use(map)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: SocketTimeoutException) {
        RecordingUploadCallResult.Failed(RecordingUploadFailure.Timeout(exception))
    } catch (exception: IOException) {
        val failure = when (exception) {
            is ConnectException, is UnknownHostException -> RecordingUploadFailure.Network(exception)
            is InterruptedIOException -> RecordingUploadFailure.Timeout(exception)
            else -> RecordingUploadFailure.Network(exception)
        }
        RecordingUploadCallResult.Failed(failure)
    } catch (exception: Exception) {
        RecordingUploadCallResult.Failed(RecordingUploadFailure.Unexpected(exception))
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resumeWith(Result.success(response)) else response.close()
            }
        })
    }

    private fun httpFailure(status: Int): RecordingUploadCallResult.Failed = RecordingUploadCallResult.Failed(
        when (status) {
            401, 403 -> RecordingUploadFailure.Unauthorized
            409 -> RecordingUploadFailure.Conflict
            400, 413, 422 -> RecordingUploadFailure.Rejected
            408 -> RecordingUploadFailure.Timeout()
            in 500..599 -> RecordingUploadFailure.Network()
            else -> RecordingUploadFailure.Unexpected()
        },
    )

    private data class UploadResponse(val recording: ServerRecording?, val duplicate: Boolean)
    private data class ServerRecording(val recordingId: String, val checksumSha256: String)

    private class ProgressFileRequestBody(
        private val file: File,
        private val mediaType: okhttp3.MediaType,
        private val onProgress: (Int) -> Unit,
    ) : RequestBody() {
        override fun contentType() = mediaType
        override fun contentLength() = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength().coerceAtLeast(1L)
            var written = 0L
            var lastProgress = -1
            file.inputStream().buffered(128 * 1024).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    sink.write(buffer, 0, count)
                    written += count
                    val progress = ((written * 100L) / total).toInt().coerceIn(0, 100)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        onProgress(progress)
                    }
                }
            }
        }
    }

    companion object {
        private const val UPLOAD_PATH = "api/v1/recordings/uploads"
        private const val AUTHORIZATION = "Authorization"
        private const val RECORDING_ID_HEADER = "X-SentriCam-Recording-Id"
        private const val CHECKSUM_HEADER = "X-SentriCam-Checksum-SHA256"
        private val VIDEO_MEDIA_TYPE = "video/mp4".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.MINUTES)
            .callTimeout(35, TimeUnit.MINUTES)
            .build()
    }
}
