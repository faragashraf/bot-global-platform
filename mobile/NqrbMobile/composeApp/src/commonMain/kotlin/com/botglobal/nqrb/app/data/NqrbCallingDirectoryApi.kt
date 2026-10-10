package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallingDirectory
import com.botglobal.mobile.platform.calling.CallingParticipantAvailability
import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.presence.PresenceEvidence
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class NqrbCallingDirectoryApi(
    platformClient: HttpClient,
    private val apiBaseUrl: String,
    private val sessionVault: SessionVault,
) : CallingDirectory {
    private val client = platformClient.config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    override suspend fun loadCallableParticipants(): List<CallableParticipant> {
        val session = sessionVault.restore()
            ?: throw NqrbCallingDirectoryAuthenticationException()
        val response = client.get(endpoint("/api/mobile/calling/participants?savedOnly=true")) {
            bearerAuth(session.accessToken)
            accept(ContentType.Application.Json)
        }
        if (response.status.value !in 200..299) {
            throw NqrbCallingDirectoryRequestException(response.status.value)
        }
        return response.body<List<CallableParticipantDto>>()
            .map { participant ->
                CallableParticipant(
                    membershipId = participant.membershipId,
                    displayName = participant.displayName,
                    availability = runCatching {
                        CallingParticipantAvailability.valueOf(participant.availability)
                    }.getOrDefault(CallingParticipantAvailability.Offline),
                    presenceEvidence = participant.presenceEvidence?.let { value ->
                        runCatching { PresenceEvidence.valueOf(value) }.getOrDefault(PresenceEvidence.Unknown)
                    } ?: PresenceEvidence.Unknown,
                    presenceObservedAtEpochMillis = participant.presenceObservedAtUtc?.let(::parseEpochMillis),
                    presenceFullyCovered = participant.presenceFullyCovered,
                )
            }
    }

    private fun endpoint(path: String) = apiBaseUrl.trimEnd('/') + path

    private fun parseEpochMillis(value: String): Long? = runCatching {
        kotlin.time.Instant.parse(value).toEpochMilliseconds()
    }.getOrNull()
}

class NqrbCallingDirectoryAuthenticationException : Exception()

class NqrbCallingDirectoryRequestException(val statusCode: Int) : Exception()

@Serializable
private data class CallableParticipantDto(
    val membershipId: String,
    val displayName: String,
    val availability: String = "Offline",
    val presenceEvidence: String? = null,
    val presenceObservedAtUtc: String? = null,
    val presenceFullyCovered: Boolean? = null,
)
