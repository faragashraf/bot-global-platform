package com.botglobal.familygames.app.data

import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
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
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.botglobal.mobile.platform.update.AppVersionPolicy
import io.ktor.client.request.parameter

interface FamilyGamesGateway {
    suspend fun versionPolicy(currentVersion: String, platform: String): AppVersionPolicy
    suspend fun restore(): MobileSession?
    suspend fun continueAsGuest(displayName: String): MobileSession
    suspend fun login(userNameOrEmail: String, password: String): MobileSession
    suspend fun register(request: RegistrationRequest): MobileSession
    suspend fun logout()
    suspend fun activeSession(): GameSessionSnapshot?
    suspend fun createSession(rulesetKey: String): GameSessionSnapshot
    suspend fun joinSession(code: String): GameSessionSnapshot
    suspend fun createInvitation(sessionId: String): GameInvitation
    suspend fun resolveInvitation(token: String): GameSessionSnapshot
    suspend fun ready(sessionId: String): GameSessionSnapshot
    suspend fun rejoin(sessionId: String): GameSessionSnapshot
    suspend fun move(request: MoveRequest): GameSessionSnapshot
    suspend fun requestRematch(sessionId: String): GameSessionSnapshot
    suspend fun acceptRematch(sessionId: String): GameSessionSnapshot
}

class FamilyGamesApi(
    platformClient: HttpClient,
    private val baseUrl: String,
    private val vault: SessionVault,
) : FamilyGamesGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = platformClient.config {
        install(ContentNegotiation) { json(json) }
    }

    override suspend fun versionPolicy(currentVersion: String, platform: String): AppVersionPolicy =
        client.get("$baseUrl/api/mobile/family-games/version-policy") {
            accept(ContentType.Application.Json)
            parameter("platform", platform)
            parameter("currentVersion", currentVersion)
        }.expect<AppVersionPolicyDto>().toDomain()

    override suspend fun restore(): MobileSession? = vault.restore()

    override suspend fun continueAsGuest(displayName: String): MobileSession =
        save(client.post("$baseUrl/api/mobile/family-games/identity/guest") {
            jsonRequest()
            setBody(GuestRequest(displayName))
        }.expect())

    override suspend fun login(userNameOrEmail: String, password: String): MobileSession =
        save(client.post("$baseUrl/api/mobile/family-games/identity/login") {
            jsonRequest()
            setBody(LoginRequest(userNameOrEmail, password))
        }.expect())

    override suspend fun register(request: RegistrationRequest): MobileSession =
        save(client.post("$baseUrl/api/mobile/family-games/identity/register") {
            jsonRequest()
            setBody(request)
        }.expect())

    override suspend fun logout() {
        runCatching { authorizedPost("/api/mobile/family-games/identity/logout") }
        vault.clear()
    }

    override suspend fun activeSession(): GameSessionSnapshot? =
        try {
            authorizedGet("/api/games/sessions/active").expect()
        } catch (error: ApiException) {
            if (error.status == 404) null else throw error
        }

    override suspend fun createSession(rulesetKey: String): GameSessionSnapshot =
        authorizedPost("/api/games/sessions", CreateSessionRequest(rulesetKey)).expect()

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

    override suspend fun requestRematch(sessionId: String): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/$sessionId/rematch/request").expect()

    override suspend fun acceptRematch(sessionId: String): GameSessionSnapshot =
        authorizedPost("/api/games/sessions/$sessionId/rematch/accept").expect()

    private suspend fun save(dto: MobileSessionDto): MobileSession =
        dto.toDomain().also { vault.save(it) }

    private suspend fun authorizedGet(path: String): HttpResponse =
        withRefresh { access -> client.get("$baseUrl$path") { authorize(access) } }

    private suspend fun authorizedPost(path: String): HttpResponse =
        withRefresh { access ->
            client.post("$baseUrl$path") {
                authorize(access)
            }
        }

    private suspend inline fun <reified T> authorizedPost(path: String, body: T): HttpResponse =
        withRefresh { access ->
            client.post("$baseUrl$path") {
                authorize(access)
                setBody(body)
            }
        }

    private suspend fun withRefresh(block: suspend (String) -> HttpResponse): HttpResponse {
        val session = vault.restore() ?: throw ApiException("session_missing", 401, "No mobile session is available.")
        val initial = block(session.accessToken)
        if (initial.status != HttpStatusCode.Unauthorized) return initial
        val refreshed = client.post("$baseUrl/api/mobile/family-games/identity/refresh") {
            jsonRequest()
            setBody(RefreshRequest(session.refreshToken))
        }.expect<MobileSessionDto>()
        val domain = save(refreshed)
        return block(domain.accessToken)
    }

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
