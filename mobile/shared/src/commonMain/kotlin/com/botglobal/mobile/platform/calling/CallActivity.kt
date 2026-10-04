package com.botglobal.mobile.platform.calling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallActivityLoadState { Idle, Loading, Ready, Empty, Error }

enum class CallHistoryFilter { All, Missed, Incoming, Outgoing }

data class CallHistoryItem(
    val callId: String,
    val direction: String,
    val participantDisplayName: String,
    val outcome: String?,
    val startedAtUtc: String,
    val connectedDurationSeconds: Long?,
    val totalBytes: Long?,
    val isGuestCall: Boolean = false,
    val isSavedContact: Boolean? = null,
    val counterpartMembershipId: String? = null,
    val canRedial: Boolean = false,
    val canAddContact: Boolean = false,
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
    val isGuestCall: Boolean = false,
    val isSavedContact: Boolean? = null,
    val counterpartMembershipId: String? = null,
    val canRedial: Boolean = false,
    val canAddContact: Boolean = false,
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
    suspend fun history(page: Int = 1, pageSize: Int = 20, filter: CallHistoryFilter = CallHistoryFilter.All): CallHistoryPage
    suspend fun detail(callId: String): CallHistoryDetail?
    suspend fun finalizeUsage(usage: FinalCallUsage)
    suspend fun currentUsage(): UsagePeriod
    suspend fun resetUsage(): UsagePeriod
    suspend fun scheduleUsageReset(schedule: UsageResetSchedule): UsagePeriod
}

class CallActivityRequestException(val statusCode: Int) : Exception("Call activity request failed with status $statusCode")

object UnavailableCallActivityGateway : CallActivityGateway {
    override suspend fun history(page: Int, pageSize: Int, filter: CallHistoryFilter) = CallHistoryPage(emptyList(), page, pageSize, false)
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
    val historyRefreshing: Boolean = false,
    val historyRefreshFailed: Boolean = false,
    val history: List<CallHistoryItem> = emptyList(),
    val historyPage: Int = 0,
    val historyHasMore: Boolean = false,
    val historyFilter: CallHistoryFilter = CallHistoryFilter.All,
    val selected: CallHistoryDetail? = null,
    val usageState: CallActivityLoadState = CallActivityLoadState.Idle,
    val usageRefreshing: Boolean = false,
    val usageRefreshFailed: Boolean = false,
    val usage: UsagePeriod? = null,
)

class CallActivityController(
    private val gateway: CallActivityGateway,
    private val pending: PendingCallUsageStore = UnavailablePendingCallUsageStore,
) {
    private val mutableState = MutableStateFlow(CallActivitySnapshot())
    val state = mutableState.asStateFlow()
    private var historyRequestGeneration = 0L
    private var usageRequestGeneration = 0L

    suspend fun loadHistory(filter: CallHistoryFilter = mutableState.value.historyFilter) {
        val requestGeneration = ++historyRequestGeneration
        val previous = mutableState.value
        val canRefreshInBackground = filter == previous.historyFilter &&
            previous.historyState in setOf(CallActivityLoadState.Ready, CallActivityLoadState.Empty)
        mutableState.value = if (canRefreshInBackground) {
            previous.copy(historyRefreshing = true, historyRefreshFailed = false)
        } else {
            previous.copy(
                historyState = CallActivityLoadState.Loading,
                historyRefreshing = false,
                historyRefreshFailed = false,
                history = emptyList(),
                historyPage = 0,
                historyHasMore = false,
                historyFilter = filter,
            )
        }
        runCatching { gateway.history(filter = filter) }.fold(
            onSuccess = { page ->
                if (requestGeneration == historyRequestGeneration) {
                    mutableState.value = mutableState.value.copy(
                        historyState = if (page.items.isEmpty()) CallActivityLoadState.Empty else CallActivityLoadState.Ready,
                        historyRefreshing = false,
                        historyRefreshFailed = false,
                        history = page.items, historyPage = page.page, historyHasMore = page.hasMore,
                    )
                }
            },
            onFailure = {
                if (requestGeneration == historyRequestGeneration) {
                    mutableState.value = if (canRefreshInBackground) {
                        mutableState.value.copy(historyRefreshing = false, historyRefreshFailed = true)
                    } else {
                        mutableState.value.copy(
                            historyState = CallActivityLoadState.Error,
                            historyRefreshing = false,
                        )
                    }
                }
            },
        )
    }
    suspend fun loadNextHistoryPage() {
        val current = mutableState.value
        if (current.historyState != CallActivityLoadState.Ready || !current.historyHasMore) return
        val requestGeneration = ++historyRequestGeneration
        runCatching { gateway.history(current.historyPage + 1, filter = current.historyFilter) }.onSuccess { page ->
            if (requestGeneration == historyRequestGeneration) {
                mutableState.value = current.copy(
                    history = current.history + page.items,
                    historyPage = page.page,
                    historyHasMore = page.hasMore,
                )
            }
        }
    }
    suspend fun loadDetail(callId: String) {
        mutableState.value = mutableState.value.copy(selected = runCatching { gateway.detail(callId) }.getOrNull())
    }
    fun clearDetail() { mutableState.value = mutableState.value.copy(selected = null) }
    fun clear() {
        historyRequestGeneration++
        usageRequestGeneration++
        mutableState.value = CallActivitySnapshot()
    }
    suspend fun loadUsage() {
        val requestGeneration = ++usageRequestGeneration
        val previous = mutableState.value
        val canRefreshInBackground = previous.usageState == CallActivityLoadState.Ready && previous.usage != null
        mutableState.value = if (canRefreshInBackground) {
            previous.copy(usageRefreshing = true, usageRefreshFailed = false)
        } else {
            previous.copy(
                usageState = CallActivityLoadState.Loading,
                usageRefreshing = false,
                usageRefreshFailed = false,
            )
        }
        runCatching { gateway.currentUsage() }.fold(
            onSuccess = {
                if (requestGeneration == usageRequestGeneration) {
                    mutableState.value = mutableState.value.copy(
                        usageState = CallActivityLoadState.Ready,
                        usageRefreshing = false,
                        usageRefreshFailed = false,
                        usage = it,
                    )
                }
            },
            onFailure = {
                if (requestGeneration == usageRequestGeneration) {
                    mutableState.value = if (canRefreshInBackground) {
                        mutableState.value.copy(usageRefreshing = false, usageRefreshFailed = true)
                    } else {
                        mutableState.value.copy(
                            usageState = CallActivityLoadState.Error,
                            usageRefreshing = false,
                        )
                    }
                }
            },
        )
    }
    suspend fun resetUsage() {
        val requestGeneration = ++usageRequestGeneration
        runCatching { gateway.resetUsage() }.onSuccess {
            if (requestGeneration == usageRequestGeneration) {
                mutableState.value = mutableState.value.copy(usageState = CallActivityLoadState.Ready, usage = it)
            }
        }
    }
    suspend fun scheduleUsageReset(schedule: UsageResetSchedule) {
        val requestGeneration = ++usageRequestGeneration
        runCatching { gateway.scheduleUsageReset(schedule) }.onSuccess {
            if (requestGeneration == usageRequestGeneration) {
                mutableState.value = mutableState.value.copy(usageState = CallActivityLoadState.Ready, usage = it)
            }
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
