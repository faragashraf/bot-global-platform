package com.botglobal.lamma.app.data

import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.identity.FederatedCredential
import com.botglobal.mobile.platform.identity.FederatedCredentialType
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.invitations.GameInvitation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.botglobal.mobile.platform.update.AppVersionPolicy
import io.ktor.client.request.parameter

enum class AccountDeletionAcceptance { Completed, Pending }

interface FamilyGamesGateway {
    suspend fun versionPolicy(currentVersion: String, platform: String): AppVersionPolicy
    suspend fun restore(): MobileSession?
    suspend fun continueAsGuest(displayName: String): MobileSession
    suspend fun authenticateFederated(credential: FederatedCredential): MobileSession
    suspend fun login(userNameOrEmail: String, password: String): MobileSession
    suspend fun register(request: RegistrationRequest): MobileSession
    suspend fun updateProfile(displayName: String): MobileSession
    suspend fun deleteAccount(): AccountDeletionAcceptance
    suspend fun clearLocalSession()
    suspend fun logout()
    suspend fun activeSession(): GameSessionSnapshot?
    suspend fun createSession(rulesetKey: String): GameSessionSnapshot
    suspend fun createAutobusSession(
        rounds: Int,
        seconds: Int,
        difficulty: String,
        categories: List<String>,
    ): GameSessionSnapshot
    suspend fun joinSession(code: String): GameSessionSnapshot
    suspend fun createInvitation(sessionId: String): GameInvitation
    suspend fun resolveInvitation(token: String): GameSessionSnapshot
    suspend fun ready(sessionId: String): GameSessionSnapshot
    suspend fun rejoin(sessionId: String): GameSessionSnapshot
    suspend fun move(request: MoveRequest): GameSessionSnapshot
    suspend fun submitAutobusAnswers(request: AutobusSubmitAnswersRequest): GameSessionSnapshot
    suspend fun finishAutobusRound(request: AutobusFinishRoundRequest): GameSessionSnapshot
    suspend fun revealAutobus(request: AutobusRevealRequest): GameSessionSnapshot
    suspend fun voteAutobus(request: AutobusVoteRequest): GameSessionSnapshot
    suspend fun requestRematch(sessionId: String): GameSessionSnapshot
    suspend fun acceptRematch(sessionId: String): GameSessionSnapshot
}

class FamilyGamesApi(
    platformClient: HttpClient,
    private val environment: FamilyGamesEnvironment,
    private val vault: SessionVault,
) : FamilyGamesGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionMutex = Mutex()
    private var sessionGeneration = 0L
    private val client = platformClient.config {
        install(ContentNegotiation) { json(json) }
    }

    override suspend fun versionPolicy(currentVersion: String, platform: String): AppVersionPolicy =
        client.get(environment.endpoint("/api/mobile/family-games/version-policy")) {
            accept(ContentType.Application.Json)
            parameter("platform", platform)
            parameter("currentVersion", currentVersion)
        }.expect<AppVersionPolicyDto>().toDomain()

    override suspend fun restore(): MobileSession? {
        val snapshot = sessionSnapshot() ?: return null
        val identity = try {
            withRefresh { access ->
                client.get(environment.endpoint("/api/mobile/family-games/identity/me")) {
                    authorize(access)
                }
            }.expect<IdentityDto>()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            if (error.code == "session_expired") return null
            if (error.code == "session_superseded") throw error
            return snapshot.session
        } catch (_: Throwable) {
            return snapshot.session
        }
        val current = sessionSnapshot() ?: return null
        if (current.generation != snapshot.generation || !current.session.sameAccountAs(snapshot.session)) {
            throw supersededSession()
        }
        return saveIfCurrent(
            current,
            current.session.copy(identity = identity.toDomain(current.session.identity.kind)),
        )
    }

    override suspend fun continueAsGuest(displayName: String): MobileSession =
        saveNewSession(beginSessionReplacement(), client.post(environment.endpoint("/api/mobile/family-games/identity/guest")) {
            jsonRequest()
            setBody(GuestRequest(displayName))
        }.expect())

    override suspend fun authenticateFederated(credential: FederatedCredential): MobileSession {
        require(credential.provider == FederatedIdentityProvider.Google) { "provider_not_supported" }
        require(credential.type == FederatedCredentialType.IdToken) { "credential_type_not_supported" }
        return saveNewSession(beginSessionReplacement(), client.post(environment.endpoint("/api/mobile/family-games/identity/federated")) {
            jsonRequest()
            setBody(FederatedIdentityRequest("google", credential.value))
        }.expect())
    }

    override suspend fun login(userNameOrEmail: String, password: String): MobileSession =
        saveNewSession(beginSessionReplacement(), client.post(environment.endpoint("/api/mobile/family-games/identity/login")) {
            jsonRequest()
            setBody(LoginRequest(userNameOrEmail, password))
        }.expect())

    override suspend fun register(request: RegistrationRequest): MobileSession =
        saveNewSession(beginSessionReplacement(), client.post(environment.endpoint("/api/mobile/family-games/identity/register")) {
            jsonRequest()
            setBody(request)
        }.expect())

    override suspend fun updateProfile(displayName: String): MobileSession {
        val snapshot = sessionSnapshot() ?: throw ApiException("session_missing", 401, "No mobile session is available.")
        val response = withRefresh { access ->
            client.patch(environment.endpoint("/api/mobile/family-games/identity/profile")) {
                jsonRequest()
                authorize(access)
                setBody(ProfileUpdateRequest(displayName))
            }
        }.expect<IdentityDto>()
        val current = sessionSnapshot() ?: throw ApiException("session_missing", 401, "No mobile session is available.")
        if (!current.session.sameAccountAs(snapshot.session)) throw supersededSession()
        return saveIfCurrent(
            current,
            current.session.copy(identity = response.toDomain(current.session.identity.kind)),
        )
    }

    override suspend fun logout() {
        val retired = retireLocalSession()
        if (retired != null) {
            try {
                client.post(environment.endpoint("/api/mobile/family-games/identity/logout")) {
                    authorize(retired.accessToken)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) { }
        }
    }

    override suspend fun clearLocalSession() {
        retireLocalSession()
    }

    override suspend fun deleteAccount(): AccountDeletionAcceptance {
        val response = withRefresh { access ->
            client.delete(environment.endpoint("/api/mobile/family-games/account")) { authorize(access) }
        }
        return when (response.status) {
            HttpStatusCode.NoContent -> AccountDeletionAcceptance.Completed
            HttpStatusCode.Accepted -> AccountDeletionAcceptance.Pending
            else -> throw response.toApiException()
        }
    }

    override suspend fun activeSession(): GameSessionSnapshot? =
        try {
            authorizedGet("/api/games/sessions/active").expect()
        } catch (error: ApiException) {
            if (error.status == 404) null else throw error
        }

    override suspend fun createSession(rulesetKey: String): GameSessionSnapshot =
        authorizedPost("/api/games/sessions", CreateSessionRequest(rulesetKey)).expect()

    override suspend fun createAutobusSession(
        rounds: Int,
        seconds: Int,
        difficulty: String,
        categories: List<String>,
    ): GameSessionSnapshot =
        authorizedPost(
            "/api/games/sessions",
            CreateSessionRequest(
                rulesetKey = "autobus-${rounds}x$seconds-$difficulty",
                gameType = "autobus",
                roundCount = rounds,
                roundSeconds = seconds,
                difficulty = difficulty,
                categories = categories,
            ),
        ).expect()

    override suspend fun joinSession(code: String): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/join", JoinSessionRequest(code.trim().uppercase())).expect()

    override suspend fun createInvitation(sessionId: String): GameInvitation =
        authorizedPost("/api/games/sessions/$sessionId/invitations")
            .expect<GameInvitationDto>()
            .toDomain()

    override suspend fun resolveInvitation(token: String): GameSessionSnapshot =
        authorizedPost(
            "/api/games/invitations/resolve",
            ResolveInvitationRequest(token.trim()),
        ).expect<ResolvedGameInvitationDto>().session

    override suspend fun ready(sessionId: String): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/$sessionId/ready").expect()

    override suspend fun rejoin(sessionId: String): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/$sessionId/rejoin").expect()

    override suspend fun move(request: MoveRequest): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/${request.sessionId}/moves", request).expect()

    override suspend fun submitAutobusAnswers(request: AutobusSubmitAnswersRequest): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/${request.sessionId}/autobus/answers", request).expect()

    override suspend fun finishAutobusRound(request: AutobusFinishRoundRequest): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/${request.sessionId}/autobus/finish", request).expect()

    override suspend fun revealAutobus(request: AutobusRevealRequest): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/${request.sessionId}/autobus/reveal", request).expect()

    override suspend fun voteAutobus(request: AutobusVoteRequest): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/${request.sessionId}/autobus/votes", request).expect()

    override suspend fun requestRematch(sessionId: String): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/$sessionId/rematch/request").expect()

    override suspend fun acceptRematch(sessionId: String): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/$sessionId/rematch/accept").expect()

    private suspend fun authorizedGet(path: String): HttpResponse =
        withRefresh { access -> client.get(environment.endpoint(path)) { authorize(access) } }

    private suspend fun authorizedPost(path: String): HttpResponse =
        withRefresh { access ->
            client.post(environment.endpoint(path)) {
                authorize(access)
            }
        }

    private suspend inline fun <reified T> authorizedPost(path: String, body: T): HttpResponse =
        withRefresh { access ->
            client.post(environment.endpoint(path)) {
                authorize(access)
                setBody(body)
            }
        }

    private suspend fun withRefresh(block: suspend (String) -> HttpResponse): HttpResponse {
        val snapshot = sessionSnapshot() ?: throw ApiException("session_missing", 401, "No mobile session is available.")
        val initial = block(snapshot.session.accessToken)
        if (initial.status != HttpStatusCode.Unauthorized) return initial
        val refreshed = client.post(environment.endpoint("/api/mobile/family-games/identity/refresh")) {
            jsonRequest()
            setBody(RefreshRequest(snapshot.session.refreshToken))
        }
        if (refreshed.status == HttpStatusCode.Unauthorized) {
            if (!expireIfCurrent(snapshot)) throw supersededSession()
            throw ApiException("session_expired", 401, "The mobile session has expired.")
        }
        val refreshedSession = refreshed.expect<MobileSessionDto>()
        val domain = saveIfCurrent(snapshot, refreshedSession.toDomain())
        return block(domain.accessToken)
    }

    private suspend fun expireIfCurrent(snapshot: SessionSnapshot): Boolean =
        sessionMutex.withLock {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val current = vault.restore() ?: return@withLock false
            if (sessionGeneration != snapshot.generation ||
                !current.sameAccountAs(snapshot.session) ||
                !current.sameCredentialsAs(snapshot.session)
            ) {
                return@withLock false
            }
            sessionGeneration++
            vault.clear()
            true
        }

    private suspend fun sessionSnapshot(): SessionSnapshot? =
        sessionMutex.withLock {
            vault.restore()?.let { SessionSnapshot(it, sessionGeneration) }
        }

    private suspend fun beginSessionReplacement(): Long =
        sessionMutex.withLock {
            ++sessionGeneration
        }

    private suspend fun saveNewSession(generation: Long, dto: MobileSessionDto): MobileSession =
        dto.toDomain().also { session ->
            sessionMutex.withLock {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (sessionGeneration != generation) throw supersededSession()
                vault.save(session)
            }
        }

    private suspend fun saveIfCurrent(snapshot: SessionSnapshot, session: MobileSession): MobileSession =
        sessionMutex.withLock {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val current = vault.restore() ?: throw ApiException("session_missing", 401, "No mobile session is available.")
            if (sessionGeneration != snapshot.generation ||
                !current.sameAccountAs(snapshot.session) ||
                !current.sameCredentialsAs(snapshot.session)
            ) {
                throw supersededSession()
            }
            vault.save(session)
            session
        }

    private suspend fun retireLocalSession(): MobileSession? =
        sessionMutex.withLock {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            sessionGeneration++
            vault.restore().also { vault.clear() }
        }

    private fun MobileSession.sameAccountAs(other: MobileSession): Boolean =
        identity.membershipId == other.identity.membershipId &&
            identity.applicationKey == other.identity.applicationKey

    private fun MobileSession.sameCredentialsAs(other: MobileSession): Boolean =
        accessToken == other.accessToken && refreshToken == other.refreshToken

    private fun supersededSession(): ApiException =
        ApiException("session_superseded", 409, "The mobile session changed while the request was in flight.")

    private data class SessionSnapshot(
        val session: MobileSession,
        val generation: Long,
    )

    private fun HttpRequestBuilder.jsonRequest() {
        contentType(ContentType.Application.Json)
        accept(ContentType.Application.Json)
    }

    private fun HttpRequestBuilder.authorize(accessToken: String) {
        jsonRequest()
        bearerAuth(accessToken)
    }

    private suspend inline fun <reified T> HttpResponse.expect(): T {
        if (status.value in 200..299) return body()
        throw toApiException()
    }

    private suspend fun HttpResponse.toApiException(): ApiException {
        val text = bodyAsText()
        val problem = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = problem?.get("code")?.jsonPrimitive?.content
            ?: problem?.get("title")?.jsonPrimitive?.content
            ?: "request_failed"
        val detail = problem?.get("detail")?.jsonPrimitive?.content ?: "The request could not be completed."
        return ApiException(code, status.value, detail)
    }
}

expect fun createPlatformHttpClient(): HttpClient
