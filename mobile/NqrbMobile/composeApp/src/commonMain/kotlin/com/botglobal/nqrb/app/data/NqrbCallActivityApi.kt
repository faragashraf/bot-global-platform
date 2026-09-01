package com.botglobal.nqrb.app.data

import com.botglobal.mobile.platform.calling.*
import com.botglobal.mobile.platform.identity.SessionVault
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class NqrbCallActivityApi(
    platformClient: HttpClient,
    private val apiBaseUrl: String,
    private val sessionVault: SessionVault,
) : CallActivityGateway {
    private val client = platformClient.config { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    override suspend fun history(page: Int, pageSize: Int): CallHistoryPage = authorizedGet<HistoryPageDto>(
        "/api/mobile/calling/history?page=$page&pageSize=$pageSize").toDomain()
    override suspend fun detail(callId: String): CallHistoryDetail? = authorizedGet<HistoryDetailDto>(
        "/api/mobile/calling/history/$callId").toDomain()
    override suspend fun finalizeUsage(usage: FinalCallUsage) {
        authorizedRequest<FinalizeUsageResponseDto, FinalizeUsageDto>(
            "/api/mobile/calling/history/${usage.callId}/usage",
            "PUT",
            FinalizeUsageDto(usage.bytesSent, usage.bytesReceived, usage.connectedDurationSeconds),
        )
    }
    override suspend fun currentUsage(): UsagePeriod = authorizedGet<UsagePeriodDto>("/api/mobile/calling/usage/current").toDomain()
    override suspend fun resetUsage(): UsagePeriod = authorizedRequest<UsagePeriodDto, EmptyBody>(
        "/api/mobile/calling/usage/reset", "POST", EmptyBody()).toDomain()
    override suspend fun scheduleUsageReset(schedule: UsageResetSchedule): UsagePeriod =
        authorizedRequest<UsagePeriodDto, ScheduleUsageResetDto>("/api/mobile/calling/usage/reset-schedule", "PUT",
            ScheduleUsageResetDto(schedule.localDateTime, schedule.timeZoneId)).toDomain()

    private suspend inline fun <reified T> authorizedGet(path: String): T {
        val session = sessionVault.restore() ?: error("call_activity_authentication_required")
        val response = client.get(apiBaseUrl.trimEnd('/') + path) { bearerAuth(session.accessToken) }
        if (response.status.value !in 200..299) throw CallActivityRequestException(response.status.value)
        return response.body()
    }
    private suspend inline fun <reified T, reified B> authorizedRequest(path: String, method: String, body: B): T {
        val session = sessionVault.restore() ?: error("call_activity_authentication_required")
        val response = client.request(apiBaseUrl.trimEnd('/') + path) {
            this.method = io.ktor.http.HttpMethod.parse(method); bearerAuth(session.accessToken); contentType(ContentType.Application.Json); setBody(body)
        }
        if (response.status.value !in 200..299) throw CallActivityRequestException(response.status.value)
        return response.body()
    }
}

@Serializable private data class HistoryPageDto(val items: List<HistoryItemDto>, val page: Int, val pageSize: Int, val hasMore: Boolean) {
    fun toDomain() = CallHistoryPage(items.map(HistoryItemDto::toDomain), page, pageSize, hasMore)
}
@Serializable private data class HistoryItemDto(val callId: String, val direction: String, val participantDisplayName: String,
    val outcome: String? = null, val startedAtUtc: String, val connectedDurationSeconds: Long? = null, val totalBytes: Long? = null) {
    fun toDomain() = CallHistoryItem(callId, direction, participantDisplayName, outcome, startedAtUtc, connectedDurationSeconds, totalBytes)
}
@Serializable private data class HistoryDetailDto(val callId: String, val direction: String, val participantDisplayNames: List<String>,
    val outcome: String? = null, val endReason: String? = null, val startedAtUtc: String, val answeredAtUtc: String? = null,
    val endedAtUtc: String? = null, val ringingDurationSeconds: Long? = null, val connectedDurationSeconds: Long? = null,
    val bytesSent: Long? = null, val bytesReceived: Long? = null) {
    fun toDomain() = CallHistoryDetail(callId, direction, participantDisplayNames, outcome, endReason, startedAtUtc,
        answeredAtUtc, endedAtUtc, ringingDurationSeconds, connectedDurationSeconds, bytesSent, bytesReceived)
}
@Serializable private data class UsagePeriodDto(val periodId: String, val startedAtUtc: String, val endedAtUtc: String? = null,
    val bytesSent: Long, val bytesReceived: Long, val scheduledResetAtUtc: String? = null, val scheduledTimeZoneId: String? = null) {
    fun toDomain() = UsagePeriod(periodId, startedAtUtc, endedAtUtc, bytesSent, bytesReceived, scheduledResetAtUtc, scheduledTimeZoneId)
}
@Serializable private data class FinalizeUsageDto(val bytesSent: Long, val bytesReceived: Long, val connectedDurationSeconds: Long)
@Serializable private data class ScheduleUsageResetDto(val localDateTime: String, val timeZoneId: String)
@Serializable private data class FinalizeUsageResponseDto(val accepted: Boolean = true)
@Serializable private class EmptyBody
