package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.FederatedCredential
import com.botglobal.mobile.platform.identity.FederatedIdentityGateway
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.FederatedSignInResult
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NqrbIdentityApi(
    platformClient: HttpClient,
    private val apiBaseUrl: String,
    private val vault: SessionVault,
) : FederatedIdentityGateway {
    private val restoreMutex = Mutex()
    private val client = platformClient.config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    override suspend fun restore(): MobileSession? = restoreMutex.withLock {
        val saved = vault.restore() ?: return null
        return try {
            val refreshed = client.post(endpoint("/api/mobile/nqrb/identity/refresh")) {
                jsonRequest()
                setBody(RefreshRequest(saved.refreshToken))
            }
            if (refreshed.status == HttpStatusCode.Unauthorized) {
                vault.clear()
                null
            } else if (refreshed.status.value in 200..299) {
                refreshed.body<MobileSessionDto>().toDomain().also { vault.save(it) }
            } else {
                throw NqrbIdentityNetworkException()
            }
        } catch (error: NqrbIdentityNetworkException) {
            throw error
        } catch (_: Exception) {
            throw NqrbIdentityNetworkException()
        }
    }

    override suspend fun authenticate(credential: FederatedCredential): FederatedSignInResult {
        if (credential.provider != FederatedIdentityProvider.Google) return FederatedSignInResult.Rejected
        return try {
            val response = client.post(endpoint("/api/mobile/nqrb/identity/federated")) {
                jsonRequest()
                setBody(FederatedRequest("google", credential.value))
            }
            when {
                response.status.value in 200..299 -> {
                    val session = response.body<MobileSessionDto>().toDomain()
                    vault.save(session)
                    FederatedSignInResult.Authenticated(session)
                }
                response.status == HttpStatusCode.ServiceUnavailable -> FederatedSignInResult.ConfigurationMissing
                response.status == HttpStatusCode.BadRequest -> FederatedSignInResult.Rejected
                response.status == HttpStatusCode.Conflict -> FederatedSignInResult.AccountLinkRequired
                else -> FederatedSignInResult.Failed
            }
        } catch (_: Exception) {
            FederatedSignInResult.NetworkFailure
        }
    }

    override suspend fun logout() {
        val session = vault.restore()
        if (session != null) {
            runCatching {
                client.post(endpoint("/api/mobile/nqrb/identity/logout")) {
                    jsonRequest()
                    bearerAuth(session.accessToken)
                }
            }
        }
        vault.clear()
    }

    private fun endpoint(path: String) = apiBaseUrl.trimEnd('/') + path

    private fun io.ktor.client.request.HttpRequestBuilder.jsonRequest() {
        contentType(ContentType.Application.Json)
        accept(ContentType.Application.Json)
    }
}

class NqrbIdentityNetworkException : Exception()

@Serializable
private data class FederatedRequest(val provider: String, val idToken: String)

@Serializable
private data class RefreshRequest(val refreshToken: String)

@Serializable
private data class IdentityDto(
    val membershipId: String,
    val subjectId: String,
    val displayName: String,
    val isGuest: Boolean,
    val applicationKey: String,
)

@Serializable
private data class MobileSessionDto(
    val accessToken: String,
    val accessExpiresAtUtc: String,
    val refreshToken: String,
    val refreshExpiresAtUtc: String,
    val identity: IdentityDto,
) {
    fun toDomain() = MobileSession(
        accessToken,
        accessExpiresAtUtc,
        refreshToken,
        refreshExpiresAtUtc,
        ApplicationIdentity(
            identity.membershipId,
            identity.subjectId,
            identity.displayName,
            if (identity.isGuest) IdentityKind.Guest else IdentityKind.Registered,
            identity.applicationKey,
        ),
    )
}

expect fun createNqrbHttpClient(): HttpClient
