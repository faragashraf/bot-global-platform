package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.FederatedCredential
import com.botglobal.mobile.platform.identity.FederatedIdentityGateway
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.FederatedSignInResult
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.update.AppVersionPolicy
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.ktor.client.plugins.timeout

/** Nqrb-only availability. CachedLocal never represents an authenticated network session. */
sealed interface NqrbSessionAvailability {
    data object Unavailable : NqrbSessionAvailability
    data class CachedLocal(val session: MobileSession) : NqrbSessionAvailability
    data class Online(val session: MobileSession) : NqrbSessionAvailability
}

interface NqrbSessionAvailabilitySource {
    val availability: StateFlow<NqrbSessionAvailability>
    suspend fun refreshAuthority(): MobileSession?
}

internal fun MobileSession.chatIdentityKey() = "${identity.applicationKey}\n${identity.membershipId}\n${identity.subjectId}"

class NqrbIdentityApi(
    platformClient: HttpClient,
    private val apiBaseUrl: String,
    private val vault: SessionVault,
    private val diagnostic: (String) -> Unit = {},
) : FederatedIdentityGateway, NqrbAccountProfileGateway, NqrbUpdatePolicyGateway, NqrbSessionAvailabilitySource {
    private val restoreMutex = Mutex()
    private val mutableAvailability = MutableStateFlow<NqrbSessionAvailability>(NqrbSessionAvailability.Unavailable)
    override val availability = mutableAvailability.asStateFlow()
    override suspend fun refreshAuthority() = restore()
    private val client = platformClient.config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    override suspend fun restore(): MobileSession? = restoreMutex.withLock {
        val saved = vault.restore() ?: run {
            mutableAvailability.value = NqrbSessionAvailability.Unavailable
            return@withLock null
        }
        if (saved.identity.applicationKey != "nqrb" || saved.identity.membershipId.isBlank() || saved.identity.subjectId.isBlank()) {
            vault.clear(); mutableAvailability.value = NqrbSessionAvailability.Unavailable
            return@withLock null
        }
        // Fence old API/socket credentials before the authoritative refresh can rotate them.
        mutableAvailability.value = NqrbSessionAvailability.CachedLocal(saved)
        return@withLock try {
            val refreshed = client.post(endpoint("/api/mobile/nqrb/identity/refresh")) {
                timeout { requestTimeoutMillis = 5_000; connectTimeoutMillis = 3_000; socketTimeoutMillis = 5_000 }
                jsonRequest()
                setBody(RefreshRequest(saved.refreshToken))
            }
            val current = vault.restore()
            if (current?.chatIdentityKey() != saved.chatIdentityKey() || current.refreshToken != saved.refreshToken) {
                mutableAvailability.value = NqrbSessionAvailability.Unavailable
                return@withLock null // A late refresh cannot resurrect logout or overwrite another account.
            }
            if (refreshed.status == HttpStatusCode.Unauthorized || refreshed.status == HttpStatusCode.Forbidden) {
                vault.clear()
                mutableAvailability.value = NqrbSessionAvailability.Unavailable
                null
            } else if (refreshed.status.value in 200..299) {
                val renewed = try { refreshed.body<MobileSessionDto>().toDomain() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableAvailability.value = NqrbSessionAvailability.Unavailable
                    throw NqrbIdentityNetworkException()
                }
                renewed.also {
                    if (it.chatIdentityKey() != saved.chatIdentityKey()) {
                        vault.clear()
                        mutableAvailability.value = NqrbSessionAvailability.Unavailable
                        throw NqrbIdentityNetworkException()
                    }
                    vault.save(it)
                    mutableAvailability.value = NqrbSessionAvailability.Online(it)
                }
            } else {
                if (refreshed.status.value !in 500..599 && refreshed.status != HttpStatusCode.TooManyRequests)
                    mutableAvailability.value = NqrbSessionAvailability.Unavailable
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
                    if (session.identity.applicationKey != "nqrb") return@withLock FederatedSignInResult.Rejected
                    vault.save(session)
                    mutableAvailability.value = NqrbSessionAvailability.Online(session)
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
        val wasOnline = mutableAvailability.value is NqrbSessionAvailability.Online
        mutableAvailability.value = NqrbSessionAvailability.Unavailable
        vault.clear()
        if (session != null && wasOnline) {
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
            if (stored.chatIdentityKey() != session.chatIdentityKey()) {
                return NqrbAccountProfileResult.AuthenticationRequired
            }
            suspend fun requestProfile(accessToken: String) = client.get(endpoint("/api/mobile/nqrb/identity/profile")) {
                accept(ContentType.Application.Json)
                bearerAuth(accessToken)
            }
            var response = requestProfile(stored.accessToken)
            if (response.status == HttpStatusCode.Unauthorized) {
                val refreshed = restore() ?: return NqrbAccountProfileResult.AuthenticationRequired
                if (refreshed.chatIdentityKey() != session.chatIdentityKey()) {
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
                response.status == HttpStatusCode.Unauthorized -> {
                    restoreMutex.withLock {
                        if (vault.restore()?.identity?.membershipId == session.identity.membershipId) {
                            vault.clear()
                            mutableAvailability.value = NqrbSessionAvailability.Unavailable
                        }
                    }
                    NqrbAccountProfileResult.AuthenticationRequired
                }
                else -> NqrbAccountProfileResult.RetryableFailure
            }
        } catch (_: Exception) {
            NqrbAccountProfileResult.RetryableFailure
        }
    }

    override suspend fun versionPolicy(currentVersion: String, platform: String): AppVersionPolicy =
        client.get(endpoint("/api/mobile/apps/nqrb/version-policy")) {
            accept(ContentType.Application.Json)
            parameter("platform", platform)
            parameter("currentVersion", currentVersion)
        }.body<NqrbVersionPolicyDto>().toDomain()

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
private data class NqrbVersionPolicyDto(
    val currentVersion: String,
    val latestVersion: String,
    val minimumSupportedVersion: String,
    val message: String? = null,
    val storeDestination: String? = null,
) {
    fun toDomain() = AppVersionPolicy(
        currentVersion,
        latestVersion,
        minimumSupportedVersion,
        message,
        storeDestination,
    )
}

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
