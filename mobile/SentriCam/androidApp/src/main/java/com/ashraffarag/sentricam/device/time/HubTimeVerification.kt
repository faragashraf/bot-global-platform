package com.ashraffarag.sentricam.device.time

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class HubTimeVerificationCredentials(
    val hubBaseUrl: String,
    val accessToken: String,
)

data class HubTimeResponse(
    val serverUtcNow: String?,
    val serverUtcOffsetMinutes: Int?,
    val serverTimeZoneId: String?,
)

sealed interface HubTimeVerificationResult {
    data class Success(
        val response: HubTimeResponse,
        val requestStartedAtMillis: Long,
        val responseReceivedAtMillis: Long,
    ) : HubTimeVerificationResult

    data class Failure(
        val reason: HubTimeVerificationFailureReason,
        val technicalCause: Throwable? = null,
    ) : HubTimeVerificationResult
}

fun interface HubTimeVerificationApi {
    suspend fun verify(credentials: HubTimeVerificationCredentials): HubTimeVerificationResult
}

class HubTimeVerificationHttpClient(
    private val client: OkHttpClient,
    private val gson: Gson = Gson(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : HubTimeVerificationApi {
    override suspend fun verify(credentials: HubTimeVerificationCredentials): HubTimeVerificationResult {
        val request = try {
            Request.Builder()
                .url(credentials.hubBaseUrl + TIME_PATH)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .get()
                .build()
        } catch (exception: Exception) {
            return HubTimeVerificationResult.Failure(
                HubTimeVerificationFailureReason.ENDPOINT_UNAVAILABLE,
                exception,
            )
        }
        val startedAt = nowMillis()
        return try {
            await(client.newCall(request)).use { response ->
                val receivedAt = nowMillis()
                when {
                    response.code == 401 || response.code == 403 -> HubTimeVerificationResult.Failure(
                        HubTimeVerificationFailureReason.AUTHORIZATION_FAILURE,
                    )
                    response.code == 404 || response.code == 405 -> HubTimeVerificationResult.Failure(
                        HubTimeVerificationFailureReason.ENDPOINT_UNAVAILABLE,
                    )
                    response.code == 408 -> HubTimeVerificationResult.Failure(
                        HubTimeVerificationFailureReason.TIMEOUT,
                    )
                    !response.isSuccessful -> HubTimeVerificationResult.Failure(
                        HubTimeVerificationFailureReason.HUB_UNREACHABLE,
                    )
                    else -> parseResponse(response, startedAt, receivedAt)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SocketTimeoutException) {
            HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.TIMEOUT, exception)
        } catch (exception: InterruptedIOException) {
            HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.TIMEOUT, exception)
        } catch (exception: ConnectException) {
            HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.HUB_UNREACHABLE, exception)
        } catch (exception: UnknownHostException) {
            HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.HUB_UNREACHABLE, exception)
        } catch (exception: IOException) {
            HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.HUB_UNREACHABLE, exception)
        }
    }

    private fun parseResponse(
        response: Response,
        startedAt: Long,
        receivedAt: Long,
    ): HubTimeVerificationResult {
        val body = response.body?.string()
            ?: return HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.PARSE_FAILURE)
        return try {
            val value = gson.fromJson(body, HubTimeResponse::class.java)
            if (value.serverUtcNow.isNullOrBlank() || value.serverUtcOffsetMinutes == null || value.serverTimeZoneId.isNullOrBlank()) {
                HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.PARSE_FAILURE)
            } else {
                HubTimeVerificationResult.Success(value, startedAt, receivedAt)
            }
        } catch (exception: JsonParseException) {
            HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.PARSE_FAILURE, exception)
        } catch (exception: RuntimeException) {
            HubTimeVerificationResult.Failure(HubTimeVerificationFailureReason.PARSE_FAILURE, exception)
        }
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

    private companion object {
        const val TIME_PATH = "api/v1/device/time"
    }
}

interface HubTimeVerificationLogger {
    fun received(response: HubTimeResponse)
    fun succeeded(validation: DeviceTimeValidation)
    fun failed(reason: HubTimeVerificationFailureReason, cause: Throwable?)
}

object NoOpHubTimeVerificationLogger : HubTimeVerificationLogger {
    override fun received(response: HubTimeResponse) = Unit
    override fun succeeded(validation: DeviceTimeValidation) = Unit
    override fun failed(reason: HubTimeVerificationFailureReason, cause: Throwable?) = Unit
}

class HubTimeVerificationCoordinator(
    private val api: HubTimeVerificationApi,
    private val credentials: () -> HubTimeVerificationCredentials?,
    private val validation: DeviceTimeValidationController,
    private val logger: HubTimeVerificationLogger = NoOpHubTimeVerificationLogger,
) {
    private val gate = Mutex()

    suspend fun verify(): DeviceTimeValidation {
        if (!gate.tryLock()) return validation.state.value
        validation.verifying()
        try {
            val currentCredentials = credentials()
            if (currentCredentials == null) {
                return unavailable(HubTimeVerificationFailureReason.AUTHORIZATION_FAILURE)
            }
            return when (val result = api.verify(currentCredentials)) {
                is HubTimeVerificationResult.Failure -> unavailable(result.reason, result.technicalCause)
                is HubTimeVerificationResult.Success -> {
                    logger.received(result.response)
                    val roundTrip = (result.responseReceivedAtMillis - result.requestStartedAtMillis).coerceAtLeast(0L)
                    val localMidpoint = result.requestStartedAtMillis + roundTrip / 2
                    validation.update(
                        result.response.serverUtcNow,
                        result.response.serverUtcOffsetMinutes,
                        result.response.serverTimeZoneId,
                        localMidpoint,
                        roundTrip,
                    )
                    validation.state.value.also(logger::succeeded)
                }
            }
        } catch (exception: CancellationException) {
            validation.verificationInterrupted()
            throw exception
        } finally {
            gate.unlock()
        }
    }

    private fun unavailable(
        reason: HubTimeVerificationFailureReason,
        cause: Throwable? = null,
    ): DeviceTimeValidation {
        validation.unavailable(reason)
        logger.failed(reason, cause)
        return validation.state.value
    }
}
