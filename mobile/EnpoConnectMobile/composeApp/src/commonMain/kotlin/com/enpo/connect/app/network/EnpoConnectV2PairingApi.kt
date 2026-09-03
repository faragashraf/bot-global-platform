package com.enpo.connect.app.network

import com.botglobal.mobile.platform.device.InstallationIdentity
import com.botglobal.mobile.platform.networking.ApiErrorKind
import com.botglobal.mobile.platform.networking.apiErrorFromHttpStatus
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.enpo.connect.app.pairing.ConnectPairingToken
import com.enpo.connect.app.pairing.EnpoPairingClaimResult
import com.enpo.connect.app.pairing.EnpoPairingClient
import com.enpo.connect.app.pairing.EnpoPairingDeviceInfo
import com.enpo.connect.app.pairing.EnpoPairingError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class EnpoConnectV2PairingApi(
    private val client: HttpClient,
    private val configuration: EnpoNetworkConfiguration,
    private val installationIdentity: InstallationIdentity,
    private val deviceInfo: EnpoPairingDeviceInfo,
) : EnpoPairingClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun claim(token: ConnectPairingToken): EnpoPairingClaimResult {
        val installationId = installationIdentity.getOrCreate()
        return try {
            val response = client.post(
                configuration.endpoint(EnpoPublicServiceRoute.PairingClaim),
            ) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("pairingToken", token.value)
                        put(
                            "device",
                            buildJsonObject {
                                put("platform", deviceInfo.platform)
                                put("installationId", installationId.value)
                                deviceInfo.deviceName?.let { put("deviceName", it) }
                                deviceInfo.appVersion?.let { put("appVersion", it) }
                            },
                        )
                    }.toString(),
                )
            }
            val statusCode = response.status.value
            if (statusCode !in 200..299) {
                EnpoPairingClaimResult.Failure(mapHttpError(statusCode), statusCode)
            } else {
                parseSuccess(response.bodyAsText())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            EnpoPairingClaimResult.Failure(EnpoPairingError.Timeout)
        } catch (_: ConnectTimeoutException) {
            EnpoPairingClaimResult.Failure(EnpoPairingError.Timeout)
        } catch (_: Throwable) {
            EnpoPairingClaimResult.Failure(EnpoPairingError.NetworkUnavailable)
        }
    }

    private fun parseSuccess(body: String): EnpoPairingClaimResult = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        val device = root["device"]?.jsonObject
        val deviceId = device?.get("deviceId")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val credential = device?.get("credential")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        if (!status.equals("completed", ignoreCase = true) || deviceId == null || credential == null) {
            return EnpoPairingClaimResult.Failure(EnpoPairingError.Unknown)
        }
        EnpoPairingClaimResult.Success(MobileDeviceCredential(deviceId, credential))
    }.getOrElse {
        EnpoPairingClaimResult.Failure(EnpoPairingError.Unknown)
    }

    private fun mapHttpError(statusCode: Int): EnpoPairingError {
        if (statusCode == 400) return EnpoPairingError.InvalidExpiredOrAlreadyUsed
        return when (apiErrorFromHttpStatus(statusCode)?.kind) {
            ApiErrorKind.Timeout -> EnpoPairingError.Timeout
            ApiErrorKind.Unauthorized -> EnpoPairingError.Unauthorized
            ApiErrorKind.Forbidden -> EnpoPairingError.Forbidden
            ApiErrorKind.Unavailable -> EnpoPairingError.ServerUnavailable
            ApiErrorKind.Server -> EnpoPairingError.ServerError
            ApiErrorKind.Transport -> EnpoPairingError.NetworkUnavailable
            ApiErrorKind.Validation,
            ApiErrorKind.NotFound,
            ApiErrorKind.Conflict,
            ApiErrorKind.Unknown,
            null,
            -> EnpoPairingError.Unknown
        }
    }
}
