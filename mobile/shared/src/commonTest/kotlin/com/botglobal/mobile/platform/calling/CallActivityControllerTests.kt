package com.botglobal.mobile.platform.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallActivityControllerTests {
    @Test
    fun history_exposes_ready_empty_detail_and_error_states() = runTest {
        val item = CallHistoryItem("call-1", "outgoing", "Remote", "completed", "2026-09-01T12:00:00Z", 60, 3_000)
        val detail = CallHistoryDetail("call-1", "outgoing", listOf("Remote"), "completed", "ended",
            "2026-09-01T12:00:00Z", null, null, 4, 60, 1_000, 2_000)
        val gateway = FakeGateway(historyItems = listOf(item), detail = detail)
        val controller = CallActivityController(gateway)

        controller.loadHistory()
        assertEquals(CallActivityLoadState.Ready, controller.state.value.historyState)
        assertEquals(listOf(item), controller.state.value.history)
        controller.loadDetail("call-1")
        assertEquals(detail, controller.state.value.selected)
        controller.clearDetail()
        assertNull(controller.state.value.selected)

        gateway.historyItems = emptyList()
        controller.loadHistory()
        assertEquals(CallActivityLoadState.Empty, controller.state.value.historyState)
        gateway.failHistory = true
        controller.loadHistory()
        assertEquals(CallActivityLoadState.Empty, controller.state.value.historyState)
        assertEquals(true, controller.state.value.historyRefreshFailed)

        val firstLoadFailure = CallActivityController(gateway)
        firstLoadFailure.loadHistory()
        assertEquals(CallActivityLoadState.Error, firstLoadFailure.state.value.historyState)
    }

    @Test
    fun history_refresh_keeps_existing_items_visible_while_loading_and_after_failure() = runTest {
        val cached = CallHistoryItem("cached", "outgoing", "Remote", "completed", "2026-09-01T12:00:00Z", 60, 100)
        val refresh = CompletableDeferred<CallHistoryPage>()
        var requests = 0
        val gateway = object : CallActivityGateway by FakeGateway() {
            override suspend fun history(page: Int, pageSize: Int, filter: CallHistoryFilter): CallHistoryPage {
                requests++
                return if (requests == 1) CallHistoryPage(listOf(cached), 1, pageSize, false) else refresh.await()
            }
        }
        val controller = CallActivityController(gateway)
        controller.loadHistory()

        backgroundScope.launch { controller.loadHistory() }
        runCurrent()

        assertEquals(CallActivityLoadState.Ready, controller.state.value.historyState)
        assertEquals(listOf(cached), controller.state.value.history)
        assertEquals(true, controller.state.value.historyRefreshing)

        refresh.completeExceptionally(IllegalStateException("temporarily unavailable"))
        runCurrent()

        assertEquals(CallActivityLoadState.Ready, controller.state.value.historyState)
        assertEquals(listOf(cached), controller.state.value.history)
        assertEquals(false, controller.state.value.historyRefreshing)
        assertEquals(true, controller.state.value.historyRefreshFailed)
    }

    @Test
    fun history_appends_next_page_without_replacing_newer_calls() = runTest {
        val first = CallHistoryItem("new", "outgoing", "Remote", "completed", "2026-09-02T12:00:00Z", 60, 100)
        val second = CallHistoryItem("old", "incoming", "Remote", "missed", "2026-09-01T12:00:00Z", null, null)
        val gateway = object : CallActivityGateway by FakeGateway() {
            override suspend fun history(page: Int, pageSize: Int, filter: CallHistoryFilter) =
                if (page == 1) CallHistoryPage(listOf(first), 1, pageSize, true)
                else CallHistoryPage(listOf(second), 2, pageSize, false)
        }
        val controller = CallActivityController(gateway)

        controller.loadHistory()
        controller.loadNextHistoryPage()

        assertEquals(listOf(first, second), controller.state.value.history)
        assertEquals(2, controller.state.value.historyPage)
        assertEquals(false, controller.state.value.historyHasMore)
    }

    @Test
    fun overlapping_filter_requests_ignore_stale_response() = runTest {
        val allResponse = CompletableDeferred<CallHistoryPage>()
        val missedResponse = CompletableDeferred<CallHistoryPage>()
        val gateway = object : CallActivityGateway by FakeGateway() {
            override suspend fun history(page: Int, pageSize: Int, filter: CallHistoryFilter) =
                when (filter) {
                    CallHistoryFilter.All -> allResponse.await()
                    CallHistoryFilter.Missed -> missedResponse.await()
                    else -> error("unexpected filter")
                }
        }
        val controller = CallActivityController(gateway)
        val stale = CallHistoryItem("all", "outgoing", "Old", "completed", "2026-09-01T12:00:00Z", 60, 100)
        val fresh = CallHistoryItem("missed", "incoming", "Fresh", "missed", "2026-09-02T12:00:00Z", null, null)

        backgroundScope.launch { controller.loadHistory(CallHistoryFilter.All) }
        runCurrent()
        backgroundScope.launch { controller.loadHistory(CallHistoryFilter.Missed) }
        runCurrent()
        missedResponse.complete(CallHistoryPage(listOf(fresh), 1, 20, false))
        runCurrent()
        allResponse.complete(CallHistoryPage(listOf(stale), 1, 20, false))
        runCurrent()

        assertEquals(CallHistoryFilter.Missed, controller.state.value.historyFilter)
        assertEquals(listOf(fresh), controller.state.value.history)
    }

    @Test
    fun overlapping_next_page_response_does_not_overwrite_new_filter() = runTest {
        val first = CallHistoryItem("first", "outgoing", "First", "completed", "2026-09-02T12:00:00Z", 60, 100)
        val stalePage = CallHistoryItem("stale-page", "outgoing", "Stale", "completed", "2026-09-01T12:00:00Z", 60, 100)
        val missed = CallHistoryItem("missed", "incoming", "Missed", "missed", "2026-09-03T12:00:00Z", null, null)
        val nextPageResponse = CompletableDeferred<CallHistoryPage>()
        val missedResponse = CompletableDeferred<CallHistoryPage>()
        val gateway = object : CallActivityGateway by FakeGateway() {
            override suspend fun history(page: Int, pageSize: Int, filter: CallHistoryFilter) =
                when {
                    page == 1 && filter == CallHistoryFilter.All -> CallHistoryPage(listOf(first), 1, pageSize, true)
                    page == 2 && filter == CallHistoryFilter.All -> nextPageResponse.await()
                    page == 1 && filter == CallHistoryFilter.Missed -> missedResponse.await()
                    else -> error("unexpected request")
                }
        }
        val controller = CallActivityController(gateway)

        controller.loadHistory(CallHistoryFilter.All)
        backgroundScope.launch { controller.loadNextHistoryPage() }
        runCurrent()
        backgroundScope.launch { controller.loadHistory(CallHistoryFilter.Missed) }
        runCurrent()
        missedResponse.complete(CallHistoryPage(listOf(missed), 1, 20, false))
        runCurrent()
        nextPageResponse.complete(CallHistoryPage(listOf(stalePage), 2, 20, false))
        runCurrent()

        assertEquals(CallHistoryFilter.Missed, controller.state.value.historyFilter)
        assertEquals(listOf(missed), controller.state.value.history)
        assertEquals(false, controller.state.value.historyHasMore)
    }

    @Test
    fun final_usage_is_durably_queued_retried_and_removed_only_after_acceptance() = runTest {
        val gateway = FakeGateway().apply { failFinalize = true }
        val pending = MemoryPendingStore()
        val controller = CallActivityController(gateway, pending)
        val usage = FinalCallUsage("call-1", 1_000, 2_000, 60)

        controller.submit(usage, "member-a")
        assertEquals(listOf(usage.copy(ownerMembershipId = "member-a")), pending.load())

        gateway.failFinalize = false
        controller.flushPending("member-a")
        assertEquals(emptyList(), pending.load())
        assertEquals(listOf(usage.copy(ownerMembershipId = "member-a"), usage.copy(ownerMembershipId = "member-a")), gateway.finalizeAttempts)
    }

    @Test
    fun queue_deduplicates_one_final_report_per_call() = runTest {
        val pending = MemoryPendingStore()
        val first = FinalCallUsage("call-1", 10, 20, 30)
        val replacement = FinalCallUsage("call-1", 99, 99, 99)

        pending.save(first)
        pending.save(replacement)

        assertEquals(listOf(replacement), pending.load())
    }

    @Test
    fun permanent_conflict_does_not_leave_an_endless_pending_retry() = runTest {
        val pending = MemoryPendingStore()
        val usage = FinalCallUsage("call-1", 10, 20, 30, "member-a")
        pending.save(usage)
        val gateway = object : CallActivityGateway by FakeGateway() {
            override suspend fun finalizeUsage(usage: FinalCallUsage) {
                throw CallActivityRequestException(409)
            }
        }

        CallActivityController(gateway, pending).flushPending("member-a")

        assertEquals(emptyList(), pending.load())
    }

    @Test
    fun pending_usage_is_never_submitted_under_a_different_restored_membership() = runTest {
        val pending = MemoryPendingStore()
        val report = FinalCallUsage("call-1", 10, 20, 30, "member-a")
        pending.save(report)
        val gateway = FakeGateway()

        CallActivityController(gateway, pending).flushPending("member-b")

        assertEquals(emptyList(), gateway.finalizeAttempts)
        assertEquals(listOf(report), pending.load())
    }

    @Test
    fun usage_reset_replaces_current_period_without_touching_history_state() = runTest {
        val oldPeriod = UsagePeriod("period-1", "2026-09-01T00:00:00Z", null, 100, 200, null, null)
        val newPeriod = UsagePeriod("period-2", "2026-09-02T00:00:00Z", null, 0, 0, null, null)
        val item = CallHistoryItem("call-1", "incoming", "Remote", "completed", "2026-09-01T12:00:00Z", 30, 300)
        val gateway = FakeGateway(historyItems = listOf(item), usage = oldPeriod, reset = newPeriod)
        val controller = CallActivityController(gateway)
        controller.loadHistory()
        controller.loadUsage()

        controller.resetUsage()

        assertEquals(newPeriod, controller.state.value.usage)
        assertEquals(listOf(item), controller.state.value.history)
    }

    @Test
    fun usage_refresh_keeps_existing_period_visible_while_loading_and_after_failure() = runTest {
        val cached = UsagePeriod("cached", "2026-09-01T00:00:00Z", null, 100, 200, null, null)
        val refresh = CompletableDeferred<UsagePeriod>()
        var requests = 0
        val gateway = object : CallActivityGateway by FakeGateway() {
            override suspend fun currentUsage(): UsagePeriod {
                requests++
                return if (requests == 1) cached else refresh.await()
            }
        }
        val controller = CallActivityController(gateway)
        controller.loadUsage()

        backgroundScope.launch { controller.loadUsage() }
        runCurrent()

        assertEquals(cached, controller.state.value.usage)
        assertEquals(true, controller.state.value.usageRefreshing)

        refresh.completeExceptionally(IllegalStateException("temporarily unavailable"))
        runCurrent()

        assertEquals(CallActivityLoadState.Ready, controller.state.value.usageState)
        assertEquals(cached, controller.state.value.usage)
        assertEquals(false, controller.state.value.usageRefreshing)
        assertEquals(true, controller.state.value.usageRefreshFailed)
    }

    @Test
    fun overlapping_usage_requests_ignore_the_stale_response() = runTest {
        val stale = CompletableDeferred<UsagePeriod>()
        val fresh = CompletableDeferred<UsagePeriod>()
        var requests = 0
        val gateway = object : CallActivityGateway by FakeGateway() {
            override suspend fun currentUsage(): UsagePeriod {
                requests++
                return if (requests == 1) stale.await() else fresh.await()
            }
        }
        val controller = CallActivityController(gateway)
        val stalePeriod = UsagePeriod("stale", "2026-09-01T00:00:00Z", null, 100, 200, null, null)
        val freshPeriod = UsagePeriod("fresh", "2026-09-02T00:00:00Z", null, 300, 400, null, null)

        backgroundScope.launch { controller.loadUsage() }
        runCurrent()
        backgroundScope.launch { controller.loadUsage() }
        runCurrent()
        fresh.complete(freshPeriod)
        runCurrent()
        stale.complete(stalePeriod)
        runCurrent()

        assertEquals(freshPeriod, controller.state.value.usage)
    }

    private class MemoryPendingStore : PendingCallUsageStore {
        private val reports = linkedMapOf<String, FinalCallUsage>()
        override suspend fun load() = reports.values.toList()
        override suspend fun save(usage: FinalCallUsage) { reports[usage.callId] = usage }
        override suspend fun remove(callId: String) { reports.remove(callId) }
    }

    private class FakeGateway(
        var historyItems: List<CallHistoryItem> = emptyList(),
        private val detail: CallHistoryDetail? = null,
        private val usage: UsagePeriod = UsagePeriod("period", "2026-09-01T00:00:00Z", null, 0, 0, null, null),
        private val reset: UsagePeriod = usage,
    ) : CallActivityGateway {
        var failHistory = false
        var failFinalize = false
        val finalizeAttempts = mutableListOf<FinalCallUsage>()
        override suspend fun history(page: Int, pageSize: Int, filter: CallHistoryFilter): CallHistoryPage {
            if (failHistory) error("history unavailable")
            return CallHistoryPage(historyItems, page, pageSize, false)
        }
        override suspend fun detail(callId: String) = detail
        override suspend fun finalizeUsage(usage: FinalCallUsage) {
            finalizeAttempts += usage
            if (failFinalize) error("temporarily unavailable")
        }
        override suspend fun currentUsage() = usage
        override suspend fun resetUsage() = reset
        override suspend fun scheduleUsageReset(schedule: UsageResetSchedule) = usage
    }
}
