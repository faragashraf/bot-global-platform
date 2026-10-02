package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLQueryComponent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class NqrbContact(
    val membershipId: String,
    val displayName: String,
    val nickname: String? = null,
)

data class NqrbBlockedAccount(val membershipId: String, val displayName: String)

sealed interface NqrbBlockListResult {
    data class Available(val accounts: List<NqrbBlockedAccount>) : NqrbBlockListResult
    data object Failed : NqrbBlockListResult
}

data class NqrbContactPage(
    val items: List<NqrbContact>,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
)

data class NqrbContactInvite(
    val code: String,
    val shareLink: String,
    val expiresAtUtc: String,
)

data class NqrbGuestCallInvite(
    val inviteId: String,
    val shareLink: String,
    val expiresAtUtc: String,
)

data class NqrbContactInvitePreview(
    val issuerDisplayName: String,
)

sealed interface NqrbContactBookResult {
    data class Available(val page: NqrbContactPage) : NqrbContactBookResult
    data object AuthenticationRequired : NqrbContactBookResult
    data object RetryableFailure : NqrbContactBookResult
}

sealed interface NqrbContactMutationResult {
    data class Saved(val contact: NqrbContact) : NqrbContactMutationResult
    data object Removed : NqrbContactMutationResult
    data object Unavailable : NqrbContactMutationResult
    data object AuthenticationRequired : NqrbContactMutationResult
    data object RetryableFailure : NqrbContactMutationResult
}

sealed interface NqrbContactLookupResult {
    data class Found(val contact: NqrbContact) : NqrbContactLookupResult
    data object Unavailable : NqrbContactLookupResult
    data object AuthenticationRequired : NqrbContactLookupResult
    data object RetryableFailure : NqrbContactLookupResult
}

sealed interface NqrbContactInviteCreateResult {
    data class Created(val invite: NqrbContactInvite) : NqrbContactInviteCreateResult
    data object AuthenticationRequired : NqrbContactInviteCreateResult
    data object RetryableFailure : NqrbContactInviteCreateResult
}

sealed interface NqrbGuestCallInviteCreateResult {
    data class Created(val invite: NqrbGuestCallInvite) : NqrbGuestCallInviteCreateResult
    data object AuthenticationRequired : NqrbGuestCallInviteCreateResult
    data object RetryableFailure : NqrbGuestCallInviteCreateResult
}

sealed interface NqrbGuestCallInviteRevokeResult {
    data object Revoked : NqrbGuestCallInviteRevokeResult
    data object AuthenticationRequired : NqrbGuestCallInviteRevokeResult
    data object RetryableFailure : NqrbGuestCallInviteRevokeResult
}

sealed interface NqrbContactInvitePreviewResult {
    data class Available(val preview: NqrbContactInvitePreview) : NqrbContactInvitePreviewResult
    data object Invalid : NqrbContactInvitePreviewResult
    data object Expired : NqrbContactInvitePreviewResult
    data object SelfInvite : NqrbContactInvitePreviewResult
    data object AlreadyClaimed : NqrbContactInvitePreviewResult
    data object AuthenticationRequired : NqrbContactInvitePreviewResult
    data object RetryableFailure : NqrbContactInvitePreviewResult
}

sealed interface NqrbContactInviteAcceptResult {
    data class Accepted(val issuer: NqrbContact) : NqrbContactInviteAcceptResult
    data object Invalid : NqrbContactInviteAcceptResult
    data object Expired : NqrbContactInviteAcceptResult
    data object SelfInvite : NqrbContactInviteAcceptResult
    data object AlreadyClaimed : NqrbContactInviteAcceptResult
    data object AuthenticationRequired : NqrbContactInviteAcceptResult
    data object RetryableFailure : NqrbContactInviteAcceptResult
}

interface NqrbContactBookGateway {
    suspend fun listBlockedAccounts(session: MobileSession): NqrbBlockListResult = NqrbBlockListResult.Failed
    suspend fun blockAccount(session: MobileSession, membershipId: String): Boolean = false
    suspend fun unblockAccount(session: MobileSession, membershipId: String): Boolean = false
    suspend fun listContacts(session: MobileSession, page: Int = 1): NqrbContactBookResult
    suspend fun findContact(session: MobileSession, membershipId: String): NqrbContactLookupResult =
        NqrbContactLookupResult.RetryableFailure
    suspend fun searchUsers(session: MobileSession, query: String, page: Int = 1): NqrbContactBookResult
    suspend fun addContact(session: MobileSession, membershipId: String): NqrbContactMutationResult
    suspend fun addContactFromCallHistory(session: MobileSession, callId: String): NqrbContactMutationResult
    suspend fun removeContact(session: MobileSession, membershipId: String): NqrbContactMutationResult
    suspend fun updateContactNickname(session: MobileSession, membershipId: String, nickname: String?): NqrbContactMutationResult
    suspend fun createInvite(session: MobileSession): NqrbContactInviteCreateResult
    suspend fun createGuestCallInvite(session: MobileSession): NqrbGuestCallInviteCreateResult
    suspend fun revokeGuestCallInvite(session: MobileSession, inviteId: String): NqrbGuestCallInviteRevokeResult
    suspend fun previewInvite(session: MobileSession, code: String): NqrbContactInvitePreviewResult
    suspend fun acceptInvite(session: MobileSession, code: String): NqrbContactInviteAcceptResult
}

object UnavailableNqrbContactBookGateway : NqrbContactBookGateway {
    override suspend fun listContacts(session: MobileSession, page: Int) = NqrbContactBookResult.RetryableFailure
    override suspend fun searchUsers(session: MobileSession, query: String, page: Int) = NqrbContactBookResult.RetryableFailure
    override suspend fun addContact(session: MobileSession, membershipId: String) = NqrbContactMutationResult.RetryableFailure
    override suspend fun addContactFromCallHistory(session: MobileSession, callId: String) = NqrbContactMutationResult.RetryableFailure
    override suspend fun removeContact(session: MobileSession, membershipId: String) = NqrbContactMutationResult.RetryableFailure
    override suspend fun updateContactNickname(session: MobileSession, membershipId: String, nickname: String?) =
        NqrbContactMutationResult.RetryableFailure
    override suspend fun createInvite(session: MobileSession) = NqrbContactInviteCreateResult.RetryableFailure
    override suspend fun createGuestCallInvite(session: MobileSession) = NqrbGuestCallInviteCreateResult.RetryableFailure
    override suspend fun revokeGuestCallInvite(session: MobileSession, inviteId: String) = NqrbGuestCallInviteRevokeResult.RetryableFailure
    override suspend fun previewInvite(session: MobileSession, code: String) = NqrbContactInvitePreviewResult.RetryableFailure
    override suspend fun acceptInvite(session: MobileSession, code: String) = NqrbContactInviteAcceptResult.RetryableFailure
}

class NqrbContactBookApi(
    platformClient: HttpClient,
    private val apiBaseUrl: String,
    private val sessionVault: SessionVault,
) : NqrbContactBookGateway {
    private val client = platformClient.config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    override suspend fun listBlockedAccounts(session: MobileSession): NqrbBlockListResult {
        val current = currentSessionFor(session) ?: return NqrbBlockListResult.Failed
        return try {
            val response = client.get(endpoint("/api/mobile/nqrb/blocked-accounts")) {
                bearerAuth(current.accessToken)
                accept(ContentType.Application.Json)
            }
            if (response.status.value in 200..299) {
                NqrbBlockListResult.Available(response.body<List<NqrbBlockedAccountDto>>().map { it.toModel() })
            } else NqrbBlockListResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbBlockListResult.Failed
        }
    }

    override suspend fun blockAccount(session: MobileSession, membershipId: String): Boolean =
        mutateBlock(session, membershipId, block = true)

    override suspend fun unblockAccount(session: MobileSession, membershipId: String): Boolean =
        mutateBlock(session, membershipId, block = false)

    private suspend fun mutateBlock(session: MobileSession, membershipId: String, block: Boolean): Boolean {
        val current = currentSessionFor(session) ?: return false
        return try {
            val url = endpoint("/api/mobile/nqrb/blocked-accounts/${membershipId.encodeURLQueryComponent()}")
            val response = if (block) client.post(url) { bearerAuth(current.accessToken) }
                else client.delete(url) { bearerAuth(current.accessToken) }
            response.status.value in 200..299
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun findContact(session: MobileSession, membershipId: String): NqrbContactLookupResult {
        val current = currentSessionFor(session) ?: return NqrbContactLookupResult.AuthenticationRequired
        return try {
            val response = client.get(endpoint("/api/mobile/nqrb/contacts/${membershipId.encodeURLQueryComponent()}")) {
                bearerAuth(current.accessToken)
                accept(ContentType.Application.Json)
            }
            when (response.status.value) {
                in 200..299 -> NqrbContactLookupResult.Found(response.body<NqrbContactDto>().toModel())
                401 -> NqrbContactLookupResult.AuthenticationRequired
                404 -> NqrbContactLookupResult.Unavailable
                else -> NqrbContactLookupResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbContactLookupResult.RetryableFailure
        }
    }

    override suspend fun listContacts(session: MobileSession, page: Int): NqrbContactBookResult =
        loadPage(session, "/api/mobile/nqrb/contacts?page=$page")

    override suspend fun searchUsers(session: MobileSession, query: String, page: Int): NqrbContactBookResult =
        loadPage(session, "/api/mobile/nqrb/users/search?query=${query.encodeURLQueryComponent()}&page=$page")

    override suspend fun addContact(
        session: MobileSession,
        membershipId: String,
    ): NqrbContactMutationResult {
        return postContact(session, "/api/mobile/nqrb/contacts/${membershipId.encodeURLQueryComponent()}")
    }

    override suspend fun addContactFromCallHistory(
        session: MobileSession,
        callId: String,
    ): NqrbContactMutationResult {
        return postContact(session, "/api/mobile/nqrb/contacts/from-history/${callId.encodeURLQueryComponent()}")
    }

    private suspend fun postContact(
        session: MobileSession,
        path: String,
    ): NqrbContactMutationResult {
        val current = currentSessionFor(session) ?: return NqrbContactMutationResult.AuthenticationRequired
        return try {
            val response = client.post(endpoint(path)) {
                bearerAuth(current.accessToken)
                accept(ContentType.Application.Json)
            }
            when (response.status.value) {
                in 200..299 -> NqrbContactMutationResult.Saved(response.body<NqrbContactDto>().toModel())
                401 -> NqrbContactMutationResult.AuthenticationRequired
                404 -> NqrbContactMutationResult.Unavailable
                else -> NqrbContactMutationResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbContactMutationResult.RetryableFailure
        }
    }

    override suspend fun removeContact(
        session: MobileSession,
        membershipId: String,
    ): NqrbContactMutationResult {
        val current = currentSessionFor(session) ?: return NqrbContactMutationResult.AuthenticationRequired
        return try {
            val response = client.delete(endpoint("/api/mobile/nqrb/contacts/${membershipId.encodeURLQueryComponent()}")) {
                bearerAuth(current.accessToken)
            }
            when (response.status.value) {
                in 200..299 -> NqrbContactMutationResult.Removed
                401 -> NqrbContactMutationResult.AuthenticationRequired
                else -> NqrbContactMutationResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbContactMutationResult.RetryableFailure
        }
    }

    override suspend fun updateContactNickname(
        session: MobileSession,
        membershipId: String,
        nickname: String?,
    ): NqrbContactMutationResult {
        val current = currentSessionFor(session) ?: return NqrbContactMutationResult.AuthenticationRequired
        return try {
            val response = client.put(endpoint("/api/mobile/nqrb/contacts/${membershipId.encodeURLQueryComponent()}/nickname")) {
                bearerAuth(current.accessToken)
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(NqrbNicknameRequest(nickname))
            }
            when (response.status.value) {
                in 200..299 -> NqrbContactMutationResult.Saved(response.body<NqrbContactDto>().toModel())
                401 -> NqrbContactMutationResult.AuthenticationRequired
                404 -> NqrbContactMutationResult.Unavailable
                else -> NqrbContactMutationResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbContactMutationResult.RetryableFailure
        }
    }

    override suspend fun createInvite(session: MobileSession): NqrbContactInviteCreateResult {
        val current = currentSessionFor(session) ?: return NqrbContactInviteCreateResult.AuthenticationRequired
        return try {
            val response = client.post(endpoint("/api/mobile/nqrb/contact-invites")) {
                bearerAuth(current.accessToken)
                accept(ContentType.Application.Json)
            }
            when (response.status.value) {
                in 200..299 -> NqrbContactInviteCreateResult.Created(response.body<NqrbContactInviteDto>().toModel())
                401 -> NqrbContactInviteCreateResult.AuthenticationRequired
                else -> NqrbContactInviteCreateResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbContactInviteCreateResult.RetryableFailure
        }
    }

    override suspend fun createGuestCallInvite(session: MobileSession): NqrbGuestCallInviteCreateResult {
        val current = currentSessionFor(session) ?: return NqrbGuestCallInviteCreateResult.AuthenticationRequired
        return try {
            val response = client.post(endpoint("/api/mobile/nqrb/guest-call-invites")) {
                bearerAuth(current.accessToken)
                accept(ContentType.Application.Json)
            }
            when (response.status.value) {
                in 200..299 -> NqrbGuestCallInviteCreateResult.Created(response.body<NqrbGuestCallInviteDto>().toModel())
                401 -> NqrbGuestCallInviteCreateResult.AuthenticationRequired
                else -> NqrbGuestCallInviteCreateResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbGuestCallInviteCreateResult.RetryableFailure
        }
    }

    override suspend fun revokeGuestCallInvite(
        session: MobileSession,
        inviteId: String,
    ): NqrbGuestCallInviteRevokeResult {
        val current = currentSessionFor(session) ?: return NqrbGuestCallInviteRevokeResult.AuthenticationRequired
        return try {
            val response = client.delete(endpoint("/api/mobile/nqrb/guest-call-invites/${inviteId.encodeURLQueryComponent()}")) {
                bearerAuth(current.accessToken)
            }
            when (response.status.value) {
                in 200..299 -> NqrbGuestCallInviteRevokeResult.Revoked
                401 -> NqrbGuestCallInviteRevokeResult.AuthenticationRequired
                else -> NqrbGuestCallInviteRevokeResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbGuestCallInviteRevokeResult.RetryableFailure
        }
    }

    override suspend fun previewInvite(
        session: MobileSession,
        code: String,
    ): NqrbContactInvitePreviewResult {
        val current = currentSessionFor(session) ?: return NqrbContactInvitePreviewResult.AuthenticationRequired
        return try {
            val response = client.get(endpoint("/api/mobile/nqrb/contact-invites/${code.encodeURLQueryComponent()}/preview")) {
                bearerAuth(current.accessToken)
                accept(ContentType.Application.Json)
            }
            when (response.status.value) {
                in 200..299 -> NqrbContactInvitePreviewResult.Available(response.body<NqrbContactInvitePreviewDto>().toModel())
                400 -> NqrbContactInvitePreviewResult.SelfInvite
                401 -> NqrbContactInvitePreviewResult.AuthenticationRequired
                404 -> NqrbContactInvitePreviewResult.Invalid
                409 -> NqrbContactInvitePreviewResult.AlreadyClaimed
                410 -> NqrbContactInvitePreviewResult.Expired
                else -> NqrbContactInvitePreviewResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbContactInvitePreviewResult.RetryableFailure
        }
    }

    override suspend fun acceptInvite(
        session: MobileSession,
        code: String,
    ): NqrbContactInviteAcceptResult {
        val current = currentSessionFor(session) ?: return NqrbContactInviteAcceptResult.AuthenticationRequired
        return try {
            val response = client.post(endpoint("/api/mobile/nqrb/contact-invites/${code.encodeURLQueryComponent()}/accept")) {
                bearerAuth(current.accessToken)
                accept(ContentType.Application.Json)
            }
            when (response.status.value) {
                in 200..299 -> NqrbContactInviteAcceptResult.Accepted(response.body<NqrbContactDto>().toModel())
                400 -> NqrbContactInviteAcceptResult.SelfInvite
                401 -> NqrbContactInviteAcceptResult.AuthenticationRequired
                404 -> NqrbContactInviteAcceptResult.Invalid
                409 -> NqrbContactInviteAcceptResult.AlreadyClaimed
                410 -> NqrbContactInviteAcceptResult.Expired
                else -> NqrbContactInviteAcceptResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbContactInviteAcceptResult.RetryableFailure
        }
    }

    private suspend fun loadPage(session: MobileSession, path: String): NqrbContactBookResult {
        val current = currentSessionFor(session) ?: return NqrbContactBookResult.AuthenticationRequired
        return try {
            val response = client.get(endpoint(path)) {
                bearerAuth(current.accessToken)
                accept(ContentType.Application.Json)
            }
            when (response.status.value) {
                in 200..299 -> NqrbContactBookResult.Available(response.body<NqrbContactPageDto>().toModel())
                401 -> NqrbContactBookResult.AuthenticationRequired
                else -> NqrbContactBookResult.RetryableFailure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NqrbContactBookResult.RetryableFailure
        }
    }

    private suspend fun currentSessionFor(expected: MobileSession): MobileSession? =
        sessionVault.restore()?.takeIf {
            it.identity.membershipId == expected.identity.membershipId &&
                it.identity.applicationKey == expected.identity.applicationKey
        }

    private fun endpoint(path: String) = apiBaseUrl.trimEnd('/') + path
}

@Serializable
private data class NqrbContactDto(
    val membershipId: String,
    val displayName: String,
    val nickname: String? = null,
) {
    fun toModel() = NqrbContact(membershipId, displayName, nickname)
}

@Serializable
private data class NqrbBlockedAccountDto(val membershipId: String, val displayName: String) {
    fun toModel() = NqrbBlockedAccount(membershipId, displayName)
}

@Serializable
private data class NqrbNicknameRequest(val nickname: String?)

@Serializable
private data class NqrbContactPageDto(
    val items: List<NqrbContactDto> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val hasMore: Boolean = false,
) {
    fun toModel() = NqrbContactPage(items.map { it.toModel() }, page, pageSize, hasMore)
}

@Serializable
private data class NqrbContactInviteDto(
    val code: String,
    val shareLink: String,
    val expiresAtUtc: String,
) {
    fun toModel() = NqrbContactInvite(code, shareLink, expiresAtUtc)
}

@Serializable
private data class NqrbGuestCallInviteDto(
    val inviteId: String,
    val shareLink: String,
    val expiresAtUtc: String,
) {
    fun toModel() = NqrbGuestCallInvite(inviteId, shareLink, expiresAtUtc)
}

@Serializable
private data class NqrbContactInvitePreviewDto(
    val issuerDisplayName: String,
) {
    fun toModel() = NqrbContactInvitePreview(issuerDisplayName)
}
