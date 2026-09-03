package com.enpo.connect.app.network

import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.PushDestination
import com.botglobal.mobile.platform.notifications.PushRegistration
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class EnpoPushRegistrationApi(
    platformClient: HttpClient,
    private val configuration: EnpoNetworkConfiguration,
    private val credentialVault: MobileDeviceCredentialVault,
) : PushRegistration {
    private val client = platformClient.config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    override suspend fun register(destination: PushDestination): PushRegistrationOutcome {
        if (destination.provider != FCM_PROVIDER) return PushRegistrationOutcome.Rejected
        val credential = credentialVault.restore()
            ?: return PushRegistrationOutcome.AuthenticationRequired
        return try {
            val response = client.put(
                configuration.endpoint(EnpoPublicServiceRoute.PushRegistration),
            ) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Device ${credential.credential}")
                setBody(RegisterPushRequest(destination.provider, destination.identifier.value))
            }
            when {
                response.status.value in 200..299 -> PushRegistrationOutcome.Registered
                response.status == HttpStatusCode.Unauthorized ->
                    PushRegistrationOutcome.AuthenticationRequired
                response.status.value in 400..499 -> PushRegistrationOutcome.Rejected
                else -> PushRegistrationOutcome.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PushRegistrationOutcome.RetryableFailure
        }
    }

    // Full unpair remains outside Slice 4; registration invalidation is server-owned on revoke.
    override suspend fun unregister(): PushRegistrationOutcome =
        PushRegistrationOutcome.Unregistered

    private companion object {
        const val FCM_PROVIDER = "fcm"
    }
}

@Serializable
private data class RegisterPushRequest(
    val provider: String,
    val registrationToken: String,
) {
    override fun toString(): String =
        "RegisterPushRequest(provider=$provider,registrationToken=<redacted>)"
}
