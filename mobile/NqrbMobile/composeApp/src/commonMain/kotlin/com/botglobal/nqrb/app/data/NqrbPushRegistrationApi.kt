package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.PushDestination
import com.botglobal.mobile.platform.notifications.PushDeviceInstallation
import com.botglobal.mobile.platform.notifications.PushRegistration
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

class NqrbPushRegistrationApi(
    platformClient: HttpClient,
    private val apiBaseUrl: String,
    private val sessionVault: SessionVault,
    private val deviceCredentialVault: MobileDeviceCredentialVault,
    private val installation: PushDeviceInstallation,
) : PushRegistration {
    private val client = platformClient.config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    override suspend fun register(destination: PushDestination): PushRegistrationOutcome {
        if (destination.provider != FCM_PROVIDER) return PushRegistrationOutcome.Rejected
        return try {
            val session = sessionVault.restore()
                ?: return PushRegistrationOutcome.AuthenticationRequired
            var credential = deviceCredentialVault.restore()
                ?: when (val enrollment = enroll(session.accessToken)) {
                    is EnrollmentResult.Succeeded -> enrollment.credential
                    EnrollmentResult.AuthenticationRequired -> return PushRegistrationOutcome.AuthenticationRequired
                    EnrollmentResult.Rejected -> return PushRegistrationOutcome.Rejected
                    EnrollmentResult.RetryableFailure -> return PushRegistrationOutcome.RetryableFailure
                }

            var result = registerDestination(credential, destination)
            if (result == PushRegistrationOutcome.AuthenticationRequired) {
                deviceCredentialVault.clear()
                credential = when (val enrollment = enroll(session.accessToken)) {
                    is EnrollmentResult.Succeeded -> enrollment.credential
                    EnrollmentResult.AuthenticationRequired -> return PushRegistrationOutcome.AuthenticationRequired
                    EnrollmentResult.Rejected -> return PushRegistrationOutcome.Rejected
                    EnrollmentResult.RetryableFailure -> return PushRegistrationOutcome.RetryableFailure
                }
                result = registerDestination(credential, destination)
            }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PushRegistrationOutcome.RetryableFailure
        }
    }

    override suspend fun unregister(): PushRegistrationOutcome {
        val credential = deviceCredentialVault.restore()
            ?: return PushRegistrationOutcome.Unregistered
        return try {
            val response = client.post(endpoint("/api/mobile/devices/unpair")) {
                header(HttpHeaders.Authorization, "Device ${credential.credential}")
            }
            when {
                response.status == HttpStatusCode.NoContent -> PushRegistrationOutcome.Unregistered
                response.status == HttpStatusCode.Unauthorized -> PushRegistrationOutcome.Unregistered
                response.status.value in 400..499 -> PushRegistrationOutcome.Rejected
                else -> PushRegistrationOutcome.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PushRegistrationOutcome.RetryableFailure
        } finally {
            deviceCredentialVault.clear()
        }
    }

    private suspend fun enroll(accessToken: String): EnrollmentResult {
        val response = client.put(endpoint("/api/mobile/devices/enrollment")) {
            jsonRequest()
            bearerAuth(accessToken)
            setBody(
                EnrollDeviceRequest(
                    installation.installationId,
                    installation.platform,
                    installation.deviceName,
                    installation.appVersion,
                ),
            )
        }
        return when {
            response.status.value in 200..299 -> {
                val enrolled = response.body<EnrolledDeviceResponse>()
                val credential = MobileDeviceCredential(
                    enrolled.deviceId,
                    enrolled.credential,
                )
                deviceCredentialVault.save(credential)
                EnrollmentResult.Succeeded(credential)
            }
            response.status == HttpStatusCode.Unauthorized -> EnrollmentResult.AuthenticationRequired
            response.status == HttpStatusCode.Forbidden -> EnrollmentResult.Rejected
            response.status.value in 400..499 && response.status != HttpStatusCode.Conflict ->
                EnrollmentResult.Rejected
            else -> EnrollmentResult.RetryableFailure
        }
    }

    private suspend fun registerDestination(
        credential: MobileDeviceCredential,
        destination: PushDestination,
    ): PushRegistrationOutcome {
        val response = client.put(endpoint("/api/mobile/devices/push-registration")) {
            jsonRequest()
            header(HttpHeaders.Authorization, "Device ${credential.credential}")
            setBody(
                RegisterPushRequest(
                    destination.provider,
                    destination.identifier.value,
                ),
            )
        }
        return when {
            response.status.value in 200..299 -> PushRegistrationOutcome.Registered
            response.status == HttpStatusCode.Unauthorized -> PushRegistrationOutcome.AuthenticationRequired
            response.status.value in 400..499 -> PushRegistrationOutcome.Rejected
            else -> PushRegistrationOutcome.RetryableFailure
        }
    }

    private fun endpoint(path: String) = apiBaseUrl.trimEnd('/') + path

    private fun io.ktor.client.request.HttpRequestBuilder.jsonRequest() {
        contentType(ContentType.Application.Json)
        accept(ContentType.Application.Json)
    }

    private sealed interface EnrollmentResult {
        data class Succeeded(val credential: MobileDeviceCredential) : EnrollmentResult
        data object AuthenticationRequired : EnrollmentResult
        data object Rejected : EnrollmentResult
        data object RetryableFailure : EnrollmentResult
    }

    private companion object {
        const val FCM_PROVIDER = "fcm"
    }
}

@Serializable
private data class EnrollDeviceRequest(
    val installationId: String,
    val platform: String,
    val deviceName: String?,
    val appVersion: String?,
)

@Serializable
private data class EnrolledDeviceResponse(
    val deviceId: String,
    val credential: String,
)

@Serializable
private data class RegisterPushRequest(
    val provider: String,
    val registrationToken: String,
)
