package com.ashraffarag.sentricam.device.registration

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class SentriCamApiClient(
    private val client: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson(),
) : DeviceRegistrationApi {
    override suspend fun register(
        serverBaseUrl: String,
        request: DeviceRegistrationRequest,
    ): RegistrationCallResult<DeviceRegistrationResponse> {
        val httpRequest = try {
            Request.Builder()
                .url(serverBaseUrl + REGISTRATION_PATH)
                .header("Accept", JSON_MEDIA_TYPE.toString())
                .post(gson.toJson(request).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } catch (exception: Exception) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl(exception))
        }
        return execute(httpRequest) { response ->
            if (!response.isSuccessful) return@execute httpFailure(response.code)
            val body = response.body?.string()
                ?: return@execute RegistrationCallResult.Failure(RegistrationFailure.SerializationFailure())
            try {
                RegistrationCallResult.Success(gson.fromJson(body, DeviceRegistrationResponse::class.java))
            } catch (exception: JsonParseException) {
                RegistrationCallResult.Failure(RegistrationFailure.SerializationFailure(exception))
            } catch (exception: RuntimeException) {
                RegistrationCallResult.Failure(RegistrationFailure.SerializationFailure(exception))
            }
        }
    }

    override suspend fun testConnection(serverBaseUrl: String): RegistrationCallResult<Unit> {
        val request = try {
            Request.Builder()
                .url(serverBaseUrl + HEALTH_PATH)
                .get()
                .build()
        } catch (exception: Exception) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl(exception))
        }
        return execute(request) { response ->
            if (response.isSuccessful) RegistrationCallResult.Success(Unit) else httpFailure(response.code)
        }
    }

    override suspend fun completePairing(
        serverBaseUrl: String,
        pairingCode: String,
        request: DeviceRegistrationRequest,
    ): RegistrationCallResult<DeviceRegistrationResponse> {
        val httpRequest = try {
            Request.Builder()
                .url(serverBaseUrl + PAIRING_PATH)
                .header("Accept", JSON_MEDIA_TYPE.toString())
                .post(
                    gson.toJson(HubPairingCompletionRequest(pairingCode, request))
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .build()
        } catch (exception: Exception) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl(exception))
        }
        return execute(httpRequest) { response ->
            if (!response.isSuccessful) return@execute httpFailure(response.code)
            val body = response.body?.string()
                ?: return@execute RegistrationCallResult.Failure(RegistrationFailure.SerializationFailure())
            try {
                RegistrationCallResult.Success(gson.fromJson(body, DeviceRegistrationResponse::class.java))
            } catch (exception: JsonParseException) {
                RegistrationCallResult.Failure(RegistrationFailure.SerializationFailure(exception))
            } catch (exception: RuntimeException) {
                RegistrationCallResult.Failure(RegistrationFailure.SerializationFailure(exception))
            }
        }
    }

    private suspend fun <T> execute(
        request: Request,
        map: (Response) -> RegistrationCallResult<T>,
    ): RegistrationCallResult<T> = try {
        await(client.newCall(request)).use(map)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: SocketTimeoutException) {
        RegistrationCallResult.Failure(RegistrationFailure.Timeout(exception))
    } catch (exception: IOException) {
        RegistrationCallResult.Failure(mapIoFailure(exception))
    } catch (exception: Exception) {
        RegistrationCallResult.Failure(RegistrationFailure.UnexpectedFailure(exception))
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(response))
                } else {
                    response.close()
                }
            }
        })
    }

    private fun mapIoFailure(exception: IOException): RegistrationFailure = when {
        exception.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
            RegistrationFailure.CleartextBlocked()
        exception is ConnectException || exception is UnknownHostException ->
            RegistrationFailure.ServerUnreachable(cause = exception)
        exception is InterruptedIOException -> RegistrationFailure.Timeout(exception)
        else -> RegistrationFailure.ServerUnreachable(cause = exception)
    }

    private fun <T> httpFailure(status: Int): RegistrationCallResult<T> = RegistrationCallResult.Failure(
        when (status) {
            400, 422 -> RegistrationFailure.ValidationRejected(status)
            401, 403 -> RegistrationFailure.Unauthorized(status)
            408 -> RegistrationFailure.Timeout()
            409 -> RegistrationFailure.Conflict(status)
            in 500..599 -> RegistrationFailure.ServerUnreachable(status)
            else -> RegistrationFailure.UnexpectedFailure()
        },
    )

    companion object {
        private const val REGISTRATION_PATH = "api/v1/devices/registrations"
        private const val HEALTH_PATH = "health"
        private const val PAIRING_PATH = "api/v1/hub/pairing-sessions/complete"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
