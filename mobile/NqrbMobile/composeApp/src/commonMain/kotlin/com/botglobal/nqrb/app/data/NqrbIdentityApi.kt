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
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlinx.coroutines.CancellationException

class NqrbIdentityApi(
    platformClient: HttpClient,
    private val apiBaseUrl: String,
    private val vault: SessionVault,
    private val diagnostic: (String) -> Unit = {},
) : FederatedIdentityGateway, NqrbAccountProfileGateway {
    private val restoreMutex = Mutex()
    private val client = platformClient.config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    override suspend fun restore(): MobileSession? = restoreMutex.withLock {
        val saved = vault.restore() ?: return@withLock null
        return@withLock try {
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NqrbIdentityNetworkException) {
            throw error
        } catch (error: Exception) {
            diagnostic("session restore failed: ${error::class.simpleName}")
            throw NqrbIdentityNetworkException()
        }
    }

    override suspend fun authenticate(credential: FederatedCredential): FederatedSignInResult = restoreMutex.withLock {
        if (credential.provider != FederatedIdentityProvider.Google) return@withLock FederatedSignInResult.Rejected
        return@withLock try {
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
        } catch (error: Exception) {
            diagnostic("Google sign-in request failed: ${error::class.simpleName}")
            FederatedSignInResult.NetworkFailure
        }
    }

    override suspend fun logout() = restoreMutex.withLock {
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

    override suspend fun load(session: MobileSession): NqrbAccountProfileResult {
        return try {
            val stored = vault.restore() ?: return NqrbAccountProfileResult.AuthenticationRequired
            if (stored.identity.membershipId != session.identity.membershipId) {
                return NqrbAccountProfileResult.AuthenticationRequired
            }
            suspend fun requestProfile(accessToken: String) = client.get(endpoint("/api/mobile/nqrb/identity/profile")) {
                accept(ContentType.Application.Json)
                bearerAuth(accessToken)
            }
            var response = requestProfile(stored.accessToken)
            if (response.status == HttpStatusCode.Unauthorized) {
                val refreshed = restore() ?: return NqrbAccountProfileResult.AuthenticationRequired
                if (refreshed.identity.membershipId != session.identity.membershipId) {
                    return NqrbAccountProfileResult.AuthenticationRequired
                }
                response = requestProfile(refreshed.accessToken)
            }
            when {
                response.status.value in 200..299 -> {
                    val profile = response.body<AccountProfileDto>()
                    NqrbAccountProfileResult.Available(
                        NqrbAccountProfile(profile.displayName, profile.email),
                    )
                }
                response.status == HttpStatusCode.Unauthorized ->
                    NqrbAccountProfileResult.AuthenticationRequired
                else -> NqrbAccountProfileResult.RetryableFailure
            }
        } catch (_: Exception) {
            NqrbAccountProfileResult.RetryableFailure
        }
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
private data class AccountProfileDto(
    val displayName: String,
    val email: String,
)

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
