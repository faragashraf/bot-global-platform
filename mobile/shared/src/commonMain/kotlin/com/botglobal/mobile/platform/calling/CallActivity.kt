package com.botglobal.mobile.platform.calling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallActivityLoadState { Idle, Loading, Ready, Empty, Error }

data class CallHistoryItem(
    val callId: String,
    val direction: String,
    val participantDisplayName: String,
    val outcome: String?,
    val startedAtUtc: String,
    val connectedDurationSeconds: Long?,
    val totalBytes: Long?,
)

data class CallHistoryPage(
    val items: List<CallHistoryItem>,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
)

data class CallHistoryDetail(
    val callId: String,
    val direction: String,
    val participantDisplayNames: List<String>,
    val outcome: String?,
    val endReason: String?,
    val startedAtUtc: String,
    val answeredAtUtc: String?,
    val endedAtUtc: String?,
    val ringingDurationSeconds: Long?,
    val connectedDurationSeconds: Long?,
    val bytesSent: Long?,
    val bytesReceived: Long?,
) { val totalBytes: Long? get() = bytesSent?.plus(bytesReceived ?: 0) }

data class UsagePeriod(
    val periodId: String,
    val startedAtUtc: String,
    val endedAtUtc: String?,
    val bytesSent: Long,
    val bytesReceived: Long,
    val scheduledResetAtUtc: String?,
    val scheduledTimeZoneId: String?,
) { val totalBytes: Long get() = bytesSent + bytesReceived }

data class UsageResetSchedule(val localDateTime: String, val timeZoneId: String)

data class FinalCallUsage(
    val callId: String,
    val bytesSent: Long,
    val bytesReceived: Long,
    val connectedDurationSeconds: Long,
    val ownerMembershipId: String? = null,
)

interface CallActivityGateway {
    suspend fun history(page: Int = 1, pageSize: Int = 20): CallHistoryPage
    suspend fun detail(callId: String): CallHistoryDetail?
    suspend fun finalizeUsage(usage: FinalCallUsage)
    suspend fun currentUsage(): UsagePeriod
    suspend fun resetUsage(): UsagePeriod
    suspend fun scheduleUsageReset(schedule: UsageResetSchedule): UsagePeriod
}

class CallActivityRequestException(val statusCode: Int) : Exception("Call activity request failed with status $statusCode")

object UnavailableCallActivityGateway : CallActivityGateway {
    override suspend fun history(page: Int, pageSize: Int) = CallHistoryPage(emptyList(), page, pageSize, false)
    override suspend fun detail(callId: String): CallHistoryDetail? = null
    override suspend fun finalizeUsage(usage: FinalCallUsage) = Unit
    override suspend fun currentUsage(): UsagePeriod = error("Call activity is unavailable")
    override suspend fun resetUsage(): UsagePeriod = error("Call activity is unavailable")
    override suspend fun scheduleUsageReset(schedule: UsageResetSchedule): UsagePeriod = error("Call activity is unavailable")
}

interface PendingCallUsageStore {
    suspend fun load(): List<FinalCallUsage>
    suspend fun save(usage: FinalCallUsage)
    suspend fun remove(callId: String)
}

object UnavailablePendingCallUsageStore : PendingCallUsageStore {
    override suspend fun load() = emptyList<FinalCallUsage>()
    override suspend fun save(usage: FinalCallUsage) = Unit
    override suspend fun remove(callId: String) = Unit
}

data class CallActivitySnapshot(
    val historyState: CallActivityLoadState = CallActivityLoadState.Idle,
    val history: List<CallHistoryItem> = emptyList(),
    val historyPage: Int = 0,
    val historyHasMore: Boolean = false,
    val selected: CallHistoryDetail? = null,
    val usageState: CallActivityLoadState = CallActivityLoadState.Idle,
    val usage: UsagePeriod? = null,
)

class CallActivityController(
    private val gateway: CallActivityGateway,
    private val pending: PendingCallUsageStore = UnavailablePendingCallUsageStore,
) {
    private val mutableState = MutableStateFlow(CallActivitySnapshot())
    val state = mutableState.asStateFlow()

    suspend fun loadHistory() {
        mutableState.value = mutableState.value.copy(historyState = CallActivityLoadState.Loading)
        runCatching { gateway.history() }.fold(
            onSuccess = { page -> mutableState.value = mutableState.value.copy(
                historyState = if (page.items.isEmpty()) CallActivityLoadState.Empty else CallActivityLoadState.Ready,
                history = page.items, historyPage = page.page, historyHasMore = page.hasMore) },
            onFailure = { mutableState.value = mutableState.value.copy(historyState = CallActivityLoadState.Error) },
        )
    }
    suspend fun loadNextHistoryPage() {
        val current = mutableState.value
        if (current.historyState != CallActivityLoadState.Ready || !current.historyHasMore) return
        runCatching { gateway.history(current.historyPage + 1) }.onSuccess { page ->
            mutableState.value = current.copy(history = current.history + page.items,
                historyPage = page.page, historyHasMore = page.hasMore)
        }
    }
    suspend fun loadDetail(callId: String) {
        mutableState.value = mutableState.value.copy(selected = runCatching { gateway.detail(callId) }.getOrNull())
    }
    fun clearDetail() { mutableState.value = mutableState.value.copy(selected = null) }
    suspend fun loadUsage() {
        mutableState.value = mutableState.value.copy(usageState = CallActivityLoadState.Loading)
        runCatching { gateway.currentUsage() }.fold(
            onSuccess = { mutableState.value = mutableState.value.copy(usageState = CallActivityLoadState.Ready, usage = it) },
            onFailure = { mutableState.value = mutableState.value.copy(usageState = CallActivityLoadState.Error) },
        )
    }
    suspend fun resetUsage() {
        runCatching { gateway.resetUsage() }.onSuccess { mutableState.value = mutableState.value.copy(usageState = CallActivityLoadState.Ready, usage = it) }
    }
    suspend fun scheduleUsageReset(schedule: UsageResetSchedule) {
        runCatching { gateway.scheduleUsageReset(schedule) }.onSuccess {
            mutableState.value = mutableState.value.copy(usageState = CallActivityLoadState.Ready, usage = it)
        }
    }
    suspend fun submit(usage: FinalCallUsage, ownerMembershipId: String) {
        pending.save(usage.copy(ownerMembershipId = ownerMembershipId))
        flushPending(ownerMembershipId)
    }
    suspend fun flushPending(ownerMembershipId: String) {
        pending.load().filter { it.ownerMembershipId == ownerMembershipId }.forEach { report ->
            try {
                gateway.finalizeUsage(report)
                pending.remove(report.callId)
            } catch (error: CallActivityRequestException) {
                if (error.statusCode in setOf(400, 403, 404, 409)) pending.remove(report.callId)
            } catch (_: Exception) {
                // The durable outbox keeps retryable transport/server failures for the next authenticated startup.
            }
        }
    }
}
