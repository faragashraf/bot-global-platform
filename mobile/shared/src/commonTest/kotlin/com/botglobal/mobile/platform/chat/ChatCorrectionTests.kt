package com.botglobal.mobile.platform.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class ChatCorrectionTests {
    private val a = ChatAccountScope("app-a", "subject-a")
    private val b = ChatAccountScope("app-a", "subject-b")
    private val c = "thread"
    private fun draft(duration: Int = 2179) = ChatVoiceDraft("draft", duration, ChatAacFixture.length,
        owner = a, conversationId = c, sha256 = ChatAacFixture.hash)
    private fun message(n: Long, sender: String = "peer", client: String = "client-$n") =
        ChatMessage("message-$n", c, n, sender, client, "text", "text-$n", createdAtUtc = "2026-10-07T00:00:00Z")

    @Test fun obsoleteGenerationCannotDispatchSecondOldMessage() = runTest {
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val store = Store().apply { states[a] = ChatDurableState(textOutbox = listOf(PendingChatText(c, "one", "A1"), PendingChatText(c, "two", "A2"))) }
        val api = Api().apply { beforeSend = { entered.complete(Unit); gate.await() } }
        val controller = ChatController(api, store)
        val old = launch { controller.bind(a) }; entered.await()
        controller.bind(b); gate.complete(Unit); old.join()
        assertEquals(listOf("one"), api.sent.map { it.clientMessageId })
        assertEquals(b, controller.state.value.account)
        assertEquals(2, store.states.getValue(a).textOutbox.size)
    }

    @Test fun ownSendCannotSkipUnseenHistoryAndSenderKeysDoNotCollide() = runTest {
        val api = Api().apply { history += message(1, "peer", "same-key") }
        val controller = ChatController(api)
        controller.bind(a)
        api.history += message(2, "peer")
        controller.sendText(c, "own")
        api.history += message(4, a.subjectId, "same-key")
        controller.sync(c)
        assertEquals(listOf(1L, 2L, 3L, 4L), controller.state.value.messages[c]!!.map { it.sequence })
        api.history[0] = api.history[0].copy(deliveryState = "Updated")
        controller.sync(c)
        assertEquals("Updated", controller.state.value.messages[c]!!.first().deliveryState)
    }

    @Test fun catchupBeyondTenPagesResumesWithoutFalseCompletion() = runTest {
        val api = Api().apply { history += (1..601).map { message(it.toLong()) } }
        val store = Store(); val controller = ChatController(api, store)
        controller.bind(a)
        assertEquals(500, controller.state.value.messages[c]!!.size)
        assertTrue(controller.state.value.retryableFailure)
        controller.sync(c)
        assertEquals(601, controller.state.value.messages[c]!!.size)
        assertEquals(601L, store.states[a]!!.cursors[c])
    }

    @Test fun allConversationPagesAreRetained() = runTest {
        val api = Api().apply { rows = (1..75).map {
            ChatConversation("thread-$it", "peer-$it", "peer-$it", "Peer", 0, 0, updatedAtUtc = "2026-10-07T00:00:00Z")
        } }
        val controller = ChatController(api); controller.bind(a)
        assertEquals(75, controller.state.value.conversations.size)
        assertEquals(75, controller.state.value.conversations.map { it.conversationId }.distinct().size)
    }

    @Test fun nonzeroHistoricalPagesPreserveCanonicalRecencyAtEverySaveAndRestart() = runTest {
        val rows = (0..64).map { i ->
            ChatConversation("id-${i.toString().padStart(2, '0')}", "peer", null, null, 10L + i, 0,
                updatedAtUtc = "2026-10-${(28 - i / 3).toString().padStart(2, '0')}T${20 - i % 3}:00:00Z")
        }
        val api = Api().apply { this.rows = rows }; val disk = Store()
        val controller = ChatController(api, disk); controller.bind(a)
        fun ordered(state: ChatDurableState): List<String> = ChatSnapshot(account = a,
            conversations = state.conversations, messages = state.messages, activityOrders = state.activityOrders)
            .conversationActivity.map { it.conversation.conversationId }
        for (saved in disk.saves.filter { it.conversations.isNotEmpty() }) {
            assertEquals(rows.filter { row -> saved.conversations.any { it.conversationId == row.conversationId } }.map { it.conversationId }, ordered(saved))
            assertTrue(saved.activityOrders.values.all { it.ordinal == 0L })
        }
        assertTrue(disk.saves.any { it.conversations.size == 30 })
        assertTrue(disk.saves.any { it.conversations.size == 60 })
        assertEquals(rows.map { it.conversationId }, ordered(disk.states.getValue(a)))
        disk.states[a] = Json.decodeFromString(Json.encodeToString(disk.states.getValue(a)))
        val restarted = ChatController(api, disk); restarted.bindAuthenticated(a, resumeNetwork = false)
        assertEquals(rows.map { it.conversationId }, restarted.state.value.conversationActivity.map { it.conversation.conversationId })

        // Mix known advances with newly hydrated older rows. Canonical timestamps still win.
        val advanced = rows.last().copy(lastSequence = 100, updatedAtUtc = "2026-10-29T00:00:00Z")
        val newestHydrated = rows.first().copy(conversationId = "z-newest", updatedAtUtc = "2026-10-30T00:00:00Z")
        val olderHydrated = rows.first().copy(conversationId = "a-oldest", updatedAtUtc = "2026-10-01T00:00:00Z")
        api.rows = listOf(newestHydrated, advanced) + rows.dropLast(1) + olderHydrated
        restarted.sync()
        assertEquals(listOf("z-newest", advanced.conversationId), restarted.state.value.conversationActivity.take(2).map { it.conversation.conversationId })
        assertEquals("a-oldest", restarted.state.value.conversationActivity.last().conversation.conversationId)
        assertEquals(0L, disk.states.getValue(a).activityOrders.getValue("a-oldest").ordinal)
        assertTrue(disk.states.getValue(a).activityOrders.getValue(advanced.conversationId).ordinal > 0)
        // A stale overlapping historical page cannot lower already-known canonical recency.
        api.rows = rows
        restarted.sync()
        assertEquals(listOf("z-newest", advanced.conversationId), restarted.state.value.conversationActivity.take(2).map { it.conversation.conversationId })
        assertEquals(100L, restarted.state.value.conversations.single { it.conversationId == advanced.conversationId }.lastSequence)
        disk.states[a] = Json.decodeFromString(Json.encodeToString(disk.states.getValue(a)))
        val secondRestart = ChatController(api, disk); secondRestart.bindAuthenticated(a, resumeNetwork = false)
        assertEquals(restarted.state.value.conversationActivity, secondRestart.state.value.conversationActivity)
        restarted.bind(b)
        assertTrue(restarted.state.value.pendingActivity.isEmpty())
    }

    @Test fun canonicalTimestampTiesIgnoreHydrationAndLegacyObservationOrdinals() {
        val row = ChatConversation("z", "peer", null, null, 5, 0, updatedAtUtc = "2026-10-08T00:00:00Z")
        val snapshot = ChatSnapshot(account = a, conversations = listOf(row, row.copy(conversationId = "a")),
            activityOrders = mapOf("z" to ChatActivityOrder(5, 1), "a" to ChatActivityOrder(5, 99)))
        assertEquals(listOf("z", "a"), snapshot.conversationActivity.map { it.conversation.conversationId })
    }

    @Test fun unreadCountsAreSharedPerConversationAndCounterpart() {
        val bero = ChatConversation("bero-thread", "bero-subject", "bero", "Bero", 5, 1, updatedAtUtc = "2026-10-08T00:00:00Z")
        val other = ChatConversation("other-thread", "other-subject", "other", "Other", 3, 2, updatedAtUtc = "2026-10-08T00:01:00Z")
        val readLocally = ChatConversation("read-thread", "read-subject", "bero", "Bero", 7, 0, updatedAtUtc = "2026-10-08T00:02:00Z")
        val snapshot = ChatSnapshot(
            account = a,
            conversations = listOf(bero, other, readLocally),
            localReadWatermarks = mapOf("read-thread" to 7),
        )

        assertEquals(5, snapshot.totalUnreadCount)
        assertEquals(4, snapshot.unreadCountForCounterpart("bero"))
        assertEquals(1, snapshot.unreadCountForCounterpart("other"))
        assertFalse(snapshot.conversationActivity.single { it.conversation.conversationId == "read-thread" }.unread)
    }

    @Test fun legacyDurationMigrationIsAtomicAndCachedUploadIsNeverResent() = runTest {
        val old = PendingChatVoice(c, "voice", "draft", 2179, ChatAacFixture.length, ChatAacFixture.hash)
        val canonical = message(1, a.subjectId, "voice").copy(kind = "voice", text = null,
            voiceTransferId = "transfer", voiceSha256 = ChatAacFixture.hash, voiceLength = ChatAacFixture.length,
            voiceDurationMilliseconds = 2024)
        val initial = ChatDurableState(voiceOutbox = listOf(old), messages = mapOf(c to listOf(canonical)))
        val disk = Store().apply { states[a] = initial; rejectNormalization = true }
        val api = Api(); val voices = Voices()
        assertFailsWith<IllegalStateException> { ChatController(api, disk, voices).bindAuthenticated(a, resumeNetwork = false) }
        assertEquals(initial, disk.states[a]); assertFalse(voices.deleted); assertTrue(api.dispatchKinds.isEmpty())
        disk.rejectNormalization = false
        val restored = ChatController(api, disk, voices); restored.bindAuthenticated(a, resumeNetwork = false)
        val normalized = disk.states.getValue(a).voiceOutbox.single()
        assertEquals(2024, normalized.durationMilliseconds); assertTrue(normalized.encodedDurationVerified)
        assertEquals(ChatAacFixture.hash, normalized.sha256)
        assertEquals(1, restored.state.value.timeline(c).size)
        voices.reject = true; restored.flush()
        assertEquals(normalized, disk.states.getValue(a).voiceOutbox.single()); assertFalse(voices.deleted)
        disk.states[a] = Json.decodeFromString(Json.encodeToString(disk.states.getValue(a)))
        voices.reject = false
        val restarted = ChatController(api, disk, voices); restarted.bind(a)
        assertTrue(disk.states.getValue(a).voiceOutbox.isEmpty()); assertTrue(voices.deleted)
        assertEquals("voice-key", restarted.state.value.localVoiceKeys["transfer"])
        assertTrue(api.dispatchKinds.isEmpty()); assertEquals(0, api.acks)
    }

    @Test fun malformedOrMismatchedDraftsNeverGainQueueOwnershipOrUpload() = runTest {
        val api = Api(); val disk = Store(); val voices = Voices()
        val controller = ChatController(api, disk, voices); controller.bind(a)
        for (invalid in listOf(draft().copy(owner = b), draft().copy(owner = ChatAccountScope("other-app", a.subjectId)),
            draft().copy(conversationId = "other"), draft().copy(sha256 = "wrong"), draft().copy(length = 1))) {
            assertNull(controller.enqueueVoice(c, invalid))
        }
        voices.draftBytes = byteArrayOf(1, 2, 3)
        assertNull(controller.enqueueVoice(c, draft().copy(length = 3)))
        voices.draftBytes = ByteArray(ChatVoiceDuration.MaxBytes + 1)
        assertNull(controller.enqueueVoice(c, draft().copy(length = voices.draftBytes.size.toLong())))
        assertTrue(disk.states.getValue(a).voiceOutbox.isEmpty()); assertTrue(api.dispatchKinds.isEmpty())
        assertFalse(voices.deleted); assertEquals(0, api.acks)
    }

    @Test fun canonicalMediaMismatchCannotPublishOrResendCachedVoice() = runTest {
        val pending = PendingChatVoice(c, "voice", "draft", 2024, ChatAacFixture.length, ChatAacFixture.hash,
            enqueueOrdinal = 1, afterSequence = 0, encodedDurationVerified = true)
        val canonical = message(1, a.subjectId, "voice").copy(kind = "voice", text = null, voiceTransferId = "transfer",
            voiceSha256 = ChatAacFixture.hash, voiceLength = ChatAacFixture.length, voiceDurationMilliseconds = 2024)
        for (wrong in listOf(canonical.copy(voiceDurationMilliseconds = 2179), canonical.copy(voiceSha256 = "wrong"),
            canonical.copy(voiceLength = 1), canonical.copy(conversationId = "other"), canonical.copy(kind = "text"))) {
            val disk = Store().apply { states[a] = ChatDurableState(voiceOutbox = listOf(pending), messages = mapOf(c to listOf(wrong))) }
            val api = Api(); val voices = Voices(); val controller = ChatController(api, disk, voices)
            controller.bindAuthenticated(a, resumeNetwork = false); controller.flush()
            assertEquals(pending, disk.states.getValue(a).voiceOutbox.single())
            assertTrue(controller.state.value.localVoiceKeys.isEmpty()); assertFalse(voices.deleted)
            assertTrue(api.dispatchKinds.isEmpty()); assertEquals(0, api.acks)
        }
    }

    @Test fun normalizedDurationCorruptionAndTruncatedLegacyBytesRemainOwned() = runTest {
        for (alreadyMeasured in listOf(true, false)) {
            val queued = PendingChatVoice(c, "voice", "draft", 2179, ChatAacFixture.length, ChatAacFixture.hash,
                encodedDurationVerified = alreadyMeasured)
            val disk = Store().apply { states[a] = ChatDurableState(voiceOutbox = listOf(queued)) }
            val api = Api(); val voices = Voices()
            if (!alreadyMeasured) voices.draftBytes = ChatAacFixture.bytes.copyOf(100)
            val controller = ChatController(api, disk, voices); controller.bind(a)
            assertEquals(ChatVoiceFailure.MissingOrCorrupt, disk.states.getValue(a).voiceOutbox.single().failure)
            assertEquals(2179, disk.states.getValue(a).voiceOutbox.single().durationMilliseconds)
            assertFalse(voices.deleted); assertTrue(api.dispatchKinds.isEmpty()); assertEquals(0, api.acks)
        }
    }

    @Test fun lateContextCannotRebindAfterLogoutAndDisconnectFailureClearsAccount() = runTest {
        val gate = CompletableDeferred<Unit>(); val api = Api().apply { contextWait = gate }
        val realtime = object : ChatRealtime {
            override suspend fun connect(onHint: suspend (ChatMessageHint) -> Unit) = Unit
            override suspend fun disconnect() { error("synthetic disconnect failure") }
        }
        val controller = ChatController(api, realtime = realtime)
        val bind = async { controller.bindAuthenticated() }; runCurrent()
        controller.bind(null); gate.complete(Unit)
        assertFalse(bind.await()); assertNull(controller.state.value.account)
    }

    @Test fun measuredNormalUploadPublishesOnceAndLateDraftReadCannotCrossGeneration() = runTest {
        val api = Api(); val disk = Store(); val voices = Voices()
        val controller = ChatController(api, disk, voices); controller.bind(a)
        val id = controller.sendVoice(c, draft())!!
        assertEquals(2024, controller.state.value.messages.getValue(c).single().voiceDurationMilliseconds)
        assertEquals(id, controller.state.value.messages.getValue(c).single().clientMessageId)
        assertTrue(controller.state.value.pendingVoices.isEmpty()); assertTrue(voices.deleted)
        assertEquals(1, controller.state.value.localVoiceKeys.size); assertEquals(listOf("voice"), api.dispatchKinds)
        assertEquals(0, api.acks)

        val pending = PendingChatVoice(c, "late", "draft", 2024, ChatAacFixture.length, ChatAacFixture.hash,
            enqueueOrdinal = 1, afterSequence = 0, encodedDurationVerified = true)
        disk.states[a] = ChatDurableState(voiceOutbox = listOf(pending)); voices.deleted = false
        controller.bindAuthenticated(a, resumeNetwork = false)
        val gate = CompletableDeferred<Unit>(); voices.inspectWait = gate
        val late = launch { controller.flush() }; runCurrent()
        val rebind = launch { controller.bind(b) }; runCurrent()
        assertEquals(b, controller.state.value.account)
        gate.complete(Unit); late.join(); rebind.join()
        assertEquals(b, controller.state.value.account); assertTrue(controller.state.value.localVoiceKeys.isEmpty())
        assertEquals(pending, disk.states.getValue(a).voiceOutbox.single()); assertFalse(voices.deleted)
        assertEquals(listOf("voice"), api.dispatchKinds)
    }

    @Test fun offlineRestorationLoadsKnownAuthenticatedAccount() = runTest {
        val store = Store().apply { states[a] = ChatDurableState(messages = mapOf(c to listOf(message(1)))) }
        val api = Api().apply { offline = true }
        val controller = ChatController(api, store)
        assertFalse(controller.bindAuthenticated(a))
        assertEquals(a, controller.state.value.account)
        assertEquals(1, controller.state.value.messages[c]!!.size)
    }

    @Test fun olderInFlightReadCompletionRetainsNewerReceipt() = runTest {
        val first = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = Api().apply { beforeRead = { sequence -> if (sequence == 10L) { first.complete(Unit); release.await() }; ChatGatewayResult.Success(sequence) } }
        val store = Store(); val controller = ChatController(api, store); controller.bind(a)
        val older = launch { controller.markRead(c, 10) }; first.await()
        api.beforeRead = { sequence -> if (sequence == 10L) { release.await(); ChatGatewayResult.Success(sequence) } else ChatGatewayResult.RetryableFailure }
        val newer = launch { controller.markRead(c, 20) }; runCurrent()
        release.complete(Unit); older.join(); newer.join()
        assertEquals(20L, store.states[a]!!.readReceipts.single().sequence)
    }

    @Test fun stateSaveFailureKeepsVoiceDraftAndRetryPublishesExactlyOneLocalKey() = runTest {
        val store = Store().apply { states[a] = ChatDurableState(voiceOutbox = listOf(PendingChatVoice(c, "voice", "draft", 1000, ChatAacFixture.length))); rejectSent = true }
        val voices = Voices(); val api = Api()
        assertFailsWith<IllegalStateException> { ChatController(api, store, voices).bind(a) }
        assertFalse(voices.deleted)
        assertEquals(1, store.states[a]!!.voiceOutbox.size)
        store.rejectSent = false
        val restarted = ChatController(api, store, voices); restarted.bind(a)
        assertTrue(voices.deleted); assertTrue(store.states[a]!!.voiceOutbox.isEmpty())
        assertEquals("voice-key", store.states[a]!!.localVoiceKeys["transfer"])
    }

    @Test fun duplicateDownloadAndDiskRejectionNeverAckMissingCopy() = runTest {
        val api = Api(); val voices = Voices(); val store = Store()
        val controller = ChatController(api, store, voices, installationId = { "installation" }); controller.bind(a)
        val voice = message(1).copy(kind = "voice", voiceTransferId = "transfer", voiceLength = 3, voiceSha256 = "hash")
        voices.reject = true
        assertNull(controller.downloadVoice(voice)); assertEquals(0, api.acks)
        voices.reject = false
        val first = async { controller.downloadVoice(voice) }; val second = async { controller.downloadVoice(voice) }
        assertNotNull(first.await()); assertNotNull(second.await())
        assertEquals(1, store.states[a]!!.localVoiceKeys.size)
        assertTrue(api.acks >= 1)
        controller.deleteAccountData(a)
        assertNull(controller.state.value.account); assertNull(store.states[a]); assertNull(voices.bytes)
    }

    @Test fun mixedEnqueueOrderSurvivesOfflineBackRestartAndCanonicalSequence() = runTest {
        for (voiceFirst in listOf(true, false)) {
            val api = Api().apply { offline = true }; val disk = Store(); val voices = Voices()
            val controller = ChatController(api, disk, voices, creationTime = { "2026-10-08T10:00:00Z" })
            controller.bindAuthenticated(a)
            val draft = draft()
            if (voiceFirst) { controller.enqueueVoice(c, draft); controller.enqueueText(c, "after voice") }
            else { controller.enqueueText(c, "before voice"); controller.enqueueVoice(c, draft) }
            controller.selectConversation(null)
            val pending = controller.state.value.pendingActivity
            assertEquals(listOf(1L, 2L), pending.map { it.enqueueOrdinal })
            assertEquals(voiceFirst, pending.first().voice != null)
            // Real serialization boundary, not just reuse of an in-memory state object.
            disk.states[a] = Json.decodeFromString(Json.encodeToString(disk.states.getValue(a)))
            val restarted = ChatController(api, disk, voices)
            restarted.bindAuthenticated(a)
            assertEquals(pending, restarted.state.value.pendingActivity)
            api.offline = false; restarted.bind(a)
            assertEquals(if (voiceFirst) listOf("voice", "text") else listOf("text", "voice"), api.dispatchKinds)
            assertEquals(listOf(1L, 2L), restarted.state.value.messages.getValue(c).map { it.sequence })
            assertEquals(api.dispatchKinds, restarted.state.value.messages.getValue(c).map { it.kind })
        }
    }

    @Test fun legacyJsonNormalizationIsStableAndDoesNotInventTimestamps() = runTest {
        val legacy = Json.decodeFromString<ChatDurableState>("""{"textOutbox":[{"conversationId":"thread","clientMessageId":"t1","text":"one"},{"conversationId":"thread","clientMessageId":"t2","text":"two"}],"voiceOutbox":[{"conversationId":"thread","clientMessageId":"v1","draftToken":"draft","durationMilliseconds":1000,"length":3}]}""")
        val disk = Store().apply { states[a] = legacy }; val api = Api().apply { offline = true }
        val controller = ChatController(api, disk); controller.bindAuthenticated(a)
        val normalized = disk.states.getValue(a)
        assertEquals(listOf("t1", "t2", "v1"), controller.state.value.pendingActivity.map { it.clientMessageId })
        assertEquals(listOf(1L, 2L, 3L), controller.state.value.pendingActivity.map { it.enqueueOrdinal })
        assertTrue(controller.state.value.pendingActivity.all { it.createdAtUtc == null })
        ChatController(api, disk).bindAuthenticated(a)
        assertEquals(normalized, disk.states[a])
        controller.enqueueText(c, "new")
        assertEquals(4L, controller.state.value.pendingActivity.last().enqueueOrdinal)
    }

    @Test fun transientHeadBlocksOnlyItsConversationAcrossTypes() = runTest {
        for (voiceFirst in listOf(true, false)) {
            val api = Api().apply { offline = true }; val voices = Voices(); val controller = ChatController(api, Store(), voices)
            controller.bindAuthenticated(a)
            val draft = draft()
            if (voiceFirst) { controller.enqueueVoice(c, draft); controller.enqueueText(c, "later") }
            else { controller.enqueueText(c, "first"); controller.enqueueVoice(c, draft) }
            controller.enqueueText("unrelated", "independent")
            api.failConversation = c; api.offline = false; controller.bind(a)
            assertEquals(if (voiceFirst) listOf("voice", "text") else listOf("text", "text"), api.dispatchKinds)
            assertEquals(2, controller.state.value.pendingActivity.size)
            assertTrue(controller.state.value.pendingActivity.all { it.conversationId == c })
            api.failConversation = null; api.dispatchKinds.clear(); controller.flush()
            assertEquals(if (voiceFirst) listOf("voice", "text") else listOf("text", "voice"), api.dispatchKinds)
            assertTrue(controller.state.value.pendingActivity.isEmpty())
        }
    }

    @Test fun forbiddenTextRetainsContentAcrossRestartAndExplicitEditCreatesNewIdentity() = runTest {
        val api = Api().apply { textFailure = ChatGatewayResult.Forbidden }; val disk = Store()
        val controller = ChatController(api, disk); controller.bind(a)
        val id = controller.sendText(c, "keep my text")!!
        assertEquals(ChatTextFailure.Forbidden, disk.states.getValue(a).textOutbox.single().failure)
        api.offline = true
        val restarted = ChatController(api, disk); restarted.bindAuthenticated(a)
        assertEquals("keep my text", restarted.state.value.pendingTexts.single().text)
        restarted.flush(); assertEquals(1, api.sent.size)
        assertTrue(restarted.editFailedText(id, "edited text"))
        val edited = restarted.state.value.pendingTexts.single()
        assertNotEquals(id, edited.clientMessageId); assertNull(edited.failure)
        assertEquals("edited text", edited.text)
        assertTrue(edited.enqueueOrdinal > 1)
        assertFalse(restarted.editFailedText(id, "again"))
    }

    @Test fun ambiguousSuccessThenConflictReconcilesOnlyExactCanonicalIdentityAndContent() = runTest {
        val api = Api(); val disk = Store(); val controller = ChatController(api, disk); controller.bind(a)
        api.ambiguousSuccess = true
        val id = controller.sendText(c, "preserve me")!!
        assertEquals(id, controller.state.value.pendingTexts.single().clientMessageId)
        api.ambiguousSuccess = false; api.textFailure = ChatGatewayResult.Conflict
        controller.flush()
        assertTrue(controller.state.value.pendingTexts.isEmpty())
        assertEquals(1, controller.state.value.messages.getValue(c).count { it.clientMessageId == id })
        val conflict = controller.sendText(c, "different")!!
        assertEquals(ChatTextFailure.Conflict, controller.state.value.pendingTexts.single().failure)
        assertFalse(controller.editFailedText(conflict, "do not duplicate an ambiguous send"))
        api.history += message(99, "peer", conflict).copy(text = "different")
        controller.reconcileFailedText(conflict)
        assertEquals(conflict, controller.state.value.pendingTexts.single().clientMessageId)
        val restarted = ChatController(api, disk); restarted.bindAuthenticated(a, resumeNetwork = false)
        assertEquals("different", restarted.state.value.pendingTexts.single().text)
        restarted.removeFailedText(conflict)
        assertTrue(restarted.state.value.pendingTexts.isEmpty())
    }

    @Test fun offlineReadAndPendingPreviewSurviveRestartAndReadAckWithoutInventingPeerReceipts() = runTest {
        val row = ChatConversation(c, "peer", null, "Peer", 7, 0, 2, "2026-10-07T00:00:00Z")
        val disk = Store().apply { states[a] = ChatDurableState(conversations = listOf(row, row.copy(conversationId = "newer", updatedAtUtc = "2026-10-08T00:00:00Z"))) }
        val api = Api().apply { offline = true }
        val controller = ChatController(api, disk); controller.bindAuthenticated(a)
        controller.markRead(c, 7); controller.enqueueText(c, "offline preview"); controller.selectConversation(null)
        val restarted = ChatController(api, disk); restarted.bindAuthenticated(a)
        val activity = restarted.state.value.conversationActivity.first()
        assertEquals(c, activity.conversation.conversationId); assertFalse(activity.unread)
        assertEquals("offline preview", activity.pending?.text?.text)
        assertEquals(2L, activity.conversation.counterpartLastReadSequence)
        api.offline = false; api.rows = listOf(row); restarted.bind(a)
        assertTrue(disk.states.getValue(a).readReceipts.isEmpty())
        assertFalse(restarted.state.value.conversationActivity.first { it.conversation.conversationId == c }.unread)
        assertEquals(2L, restarted.state.value.conversations.first { it.conversationId == c }.counterpartLastReadSequence)
    }

    @Test fun concurrentVoiceDownloadPublishesLoadingBeforeIoAndSharesOneTransfer() = runTest {
        val gate = CompletableDeferred<Unit>(); val api = Api().apply { downloadWait = gate }
        val controller = ChatController(api, Store(), Voices(), installationId = { "installation" }); controller.bind(a)
        val voice = message(1).copy(kind = "voice", voiceTransferId = "transfer", voiceSha256 = "hash", voiceLength = 3)
        val first = async { controller.downloadVoice(voice) }; runCurrent()
        assertEquals(ChatVoiceLoadState.Loading, controller.state.value.voiceLoads["transfer"])
        val second = async { controller.downloadVoice(voice) }; runCurrent()
        assertEquals(1, api.downloadCalls); assertEquals(0, api.acks)
        gate.complete(Unit)
        assertEquals(first.await(), second.await()); assertEquals(1, api.downloadCalls)
        assertEquals(ChatVoiceLoadState.Ready, controller.state.value.voiceLoads["transfer"])
        assertEquals(1, api.acks)
    }

    @Test fun delayedDownloadAndRejectedTextCannotMutateReboundSameAccount() = runTest {
        val api = Api(); val disk = Store(); val voices = Voices()
        val controller = ChatController(api, disk, voices, installationId = { "installation" }); controller.bind(a)
        val gate = CompletableDeferred<Unit>(); api.downloadWait = gate
        val voice = message(1).copy(kind = "voice", voiceTransferId = "transfer", voiceSha256 = "hash", voiceLength = 3)
        val download = async { controller.downloadVoice(voice) }; runCurrent()
        val generation = controller.state.value.accountGeneration
        controller.bind(null); controller.bind(a); gate.complete(Unit)
        assertNull(download.await()); assertTrue(controller.state.value.accountGeneration > generation)
        assertTrue(controller.state.value.localVoiceKeys.isEmpty()); assertEquals(0, api.acks)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforeSend = { entered.complete(Unit); release.await() }; api.textFailure = ChatGatewayResult.Forbidden
        val send = launch { controller.sendText(c, "account fenced") }; entered.await()
        controller.bind(b); release.complete(Unit); send.join()
        assertTrue(controller.state.value.pendingTexts.isEmpty())
        assertEquals("account fenced", disk.states.getValue(a).textOutbox.single().text)
        assertNull(disk.states.getValue(a).textOutbox.single().failure)
    }

    @Test fun retainedFailureStaysBeforeNewMixedCanonicalHistoryAcrossReconnectAndRestart() = runTest {
        for (voiceFirst in listOf(true, false)) {
            val api = Api().apply { textFailure = ChatGatewayResult.Forbidden }
            val disk = Store(); val voices = Voices()
            // Deliberately ahead of server time: ordering must not compare these clocks.
            val controller = ChatController(api, disk, voices, creationTime = { "2099-01-01T00:00:00Z" })
            controller.bind(a)
            val failed = controller.sendText(c, "older retained failure")!!
            val draft = draft()
            if (voiceFirst) { controller.enqueueVoice(c, draft); controller.enqueueText(c, "newer text") }
            else { controller.enqueueText(c, "newer text"); controller.enqueueVoice(c, draft) }
            val beforeKeys = controller.state.value.timeline(c).map { it.key }
            api.textFailure = null; controller.flush(); controller.sync(c)
            fun assertOrder(snapshot: ChatSnapshot) {
                val timeline = snapshot.timeline(c)
                assertEquals(beforeKeys, timeline.take(3).map { it.key })
                assertEquals(failed, timeline.first().pending?.clientMessageId)
                assertEquals(if (voiceFirst) listOf("voice", "text") else listOf("text", "voice"), timeline.drop(1).take(2).map { it.message?.kind })
                assertNull(snapshot.conversationActivity.single().pending)
            }
            assertOrder(controller.state.value)
            disk.states[a] = Json.decodeFromString(Json.encodeToString(disk.states.getValue(a)))
            val restarted = ChatController(api, disk, voices); restarted.bind(a); assertOrder(restarted.state.value)
            api.history += message(3).copy(text = "new incoming", createdAtUtc = "2026-10-08T11:00:00Z")
            restarted.sync(c)
            assertOrder(restarted.state.value)
            val activity = restarted.state.value.conversationActivity.single()
            assertEquals("new incoming", activity.latestMessage?.text)
            assertEquals("2026-10-08T11:00:00Z", activity.updatedAtUtc)
            assertTrue(activity.unread)
            assertEquals(1, disk.states.getValue(a).textOutbox.size)
            assertEquals(1, disk.states.getValue(a).activityOrders.size)
        }
    }

    @Test fun anchorsSurvivePartialHistoryAndDoNotAccumulateSentIdentityMetadata() = runTest {
        val api = Api(); val disk = Store(); val controller = ChatController(api, disk)
        controller.bind(a); controller.sendText(c, "first canonical")
        api.textFailure = ChatGatewayResult.Forbidden
        val failed = controller.sendText(c, "failure after first")!!
        api.textFailure = null; controller.sendText(c, "next canonical")
        val saved = disk.states.getValue(a)
        assertEquals(1L, saved.textOutbox.single().afterSequence)
        assertEquals(2L, saved.textOutbox.single().beforeSequence)
        // A partial local history still retains the exact lower and upper bounds.
        disk.states[a] = Json.decodeFromString(Json.encodeToString(saved.copy(messages = emptyMap())))
        val restarted = ChatController(api, disk); restarted.bindAuthenticated(a, resumeNetwork = false)
        assertEquals(failed, restarted.state.value.timeline(c).single().pending?.clientMessageId)
        restarted.sync(c)
        assertEquals(listOf("first canonical", "failure after first", "next canonical"), restarted.state.value.timeline(c).map { it.message?.text ?: it.pending?.text?.text })
        restarted.removeFailedText(failed)
        assertTrue(disk.states.getValue(a).textOutbox.isEmpty())
        assertEquals(1, disk.states.getValue(a).activityOrders.size)
    }

    @Test fun newerIncomingConversationOutranksOldFailureWithoutUsingDeviceClock() = runTest {
        val row = ChatConversation(c, "peer", null, "Peer", 0, 0, updatedAtUtc = "2026-10-07T00:00:00Z")
        val api = Api().apply { rows = listOf(row, row.copy(conversationId = "other")); textFailure = ChatGatewayResult.Forbidden }
        val controller = ChatController(api, Store(), creationTime = { "2099-01-01T00:00:00Z" })
        controller.bind(a); controller.sendText(c, "old failure")
        assertEquals(c, controller.state.value.conversationActivity.first().conversation.conversationId)
        api.history += message(1).copy(conversationId = "other", text = "incoming", createdAtUtc = "2026-10-08T00:00:00Z")
        api.rows = listOf(row, row.copy(conversationId = "other", lastSequence = 1, updatedAtUtc = "2026-10-08T00:00:00Z"))
        controller.sync("other")
        assertEquals("other", controller.state.value.conversationActivity.first().conversation.conversationId)
        assertEquals("incoming", controller.state.value.conversationActivity.first().latestMessage?.text)
        // Summary can arrive before its message page. Never present the old failed send as newest.
        api.rows = listOf(row.copy(lastSequence = 2, updatedAtUtc = "2026-10-09T00:00:00Z"),
            row.copy(conversationId = "other", lastSequence = 1, updatedAtUtc = "2026-10-08T00:00:00Z"))
        controller.sync(c)
        assertNull(controller.state.value.conversationActivity.first().pending)
    }

    @Test fun lostVoiceResponseCoalescesWhileFailedPublicationRetainsBytesThenRestartPublishesWithoutResend() = runTest {
        val disk = Store(); val voices = Voices(); val api = Api().apply { ambiguousVoice = true }
        val controller = ChatController(api, disk, voices); controller.bind(a)
        val id = controller.sendVoice(c, draft())!!
        assertEquals(ChatAacFixture.duration, disk.states.getValue(a).voiceOutbox.single().durationMilliseconds)
        assertTrue(disk.states.getValue(a).voiceOutbox.single().encodedDurationVerified)
        val key = controller.state.value.timeline(c).single().key
        controller.sync(c); controller.sync(c)
        val row = controller.state.value.timeline(c).single()
        assertEquals(key, row.key); assertEquals(id, row.message?.clientMessageId); assertEquals(id, row.pending?.clientMessageId)
        assertFalse(voices.deleted); assertEquals(1, disk.states.getValue(a).voiceOutbox.size)
        disk.rejectSent = true
        assertFailsWith<IllegalStateException> { controller.flush() }
        assertFalse(voices.deleted); assertEquals(1, disk.states.getValue(a).voiceOutbox.size)
        assertEquals(key, controller.state.value.timeline(c).single().key)
        assertEquals(0, api.acks); assertEquals(0, api.downloadCalls)
        disk.states[a] = Json.decodeFromString(Json.encodeToString(disk.states.getValue(a)))
        val restarted = ChatController(api, disk, voices)
        restarted.bindAuthenticated(a, resumeNetwork = false)
        assertNotNull(restarted.state.value.timeline(c).single().pending)
        disk.rejectSent = false; restarted.flush()
        assertEquals(key, restarted.state.value.timeline(c).single().key)
        assertNull(restarted.state.value.timeline(c).single().pending)
        assertEquals("voice-key", disk.states.getValue(a).localVoiceKeys["transfer"])
        assertTrue(voices.deleted); assertEquals(listOf("voice"), api.dispatchKinds)
        assertEquals(0, api.acks)
        restarted.bind(b)
        assertTrue(restarted.state.value.pendingVoices.isEmpty())
        assertTrue(restarted.state.value.timeline(c).none { it.key == key })
    }

    @Test fun permanentlyRejectedVoiceDoesNotBlockLaterQueuedText() = runTest {
        val disk = Store(); val voices = Voices()
        val api = Api().apply { voiceFailure = ChatGatewayResult.Conflict }
        val controller = ChatController(api, disk, voices)
        controller.bind(a)

        val voiceId = controller.enqueueVoice(c, draft())!!
        val textId = controller.enqueueText(c, "text after rejected voice")!!
        controller.flush()

        val state = disk.states.getValue(a)
        assertEquals(ChatVoiceFailure.Rejected, state.voiceOutbox.single { it.clientMessageId == voiceId }.failure)
        assertTrue(state.textOutbox.none { it.clientMessageId == textId })
        assertEquals(listOf("voice", "text"), api.dispatchKinds)
        assertEquals("text after rejected voice", api.history.single { it.clientMessageId == textId }.text)
    }

    @Test fun coalescingRejectsWrongSenderConversationKindContentAndAccount() {
        val voice = PendingChatVoice(c, "same", "draft", 1000, 3, "hash", enqueueOrdinal = 1, afterSequence = 0, encodedDurationVerified = true)
        val canonical = message(1, a.subjectId, "same").copy(kind = "voice", text = null,
            voiceTransferId = "transfer", voiceLength = 3, voiceSha256 = "hash", voiceDurationMilliseconds = 1000)
        val snapshot = ChatSnapshot(account = a, pendingVoices = listOf(voice))
        val key = snapshot.timeline(c).single().key
        val variants = listOf(canonical.copy(senderSubjectId = "peer"), canonical.copy(conversationId = "other"),
            canonical.copy(kind = "text", text = "different"), canonical.copy(voiceSha256 = "wrong"),
            canonical.copy(voiceLength = 4), canonical.copy(voiceDurationMilliseconds = 2000))
        for (wrong in variants) {
            val rows = snapshot.copy(messages = mapOf(c to listOf(wrong))).timeline(c)
            assertEquals(1, rows.count { it.key == key })
            assertNull(rows.single { it.key == key }.message)
            assertEquals(rows.size, rows.map { it.key }.distinct().size)
        }
        assertEquals(2, snapshot.copy(account = b, messages = mapOf(c to listOf(canonical))).timeline(c).size)
        val text = PendingChatText(c, "same", "expected", 1)
        val textRows = snapshot.copy(pendingVoices = emptyList(), pendingTexts = listOf(text),
            messages = mapOf(c to listOf(message(1, a.subjectId, "same").copy(text = "different")))).timeline(c)
        assertEquals(2, textRows.size); assertEquals(2, textRows.map { it.key }.distinct().size)
        assertEquals(2, snapshot.copy(pendingVoices = listOf(voice.copy(sha256 = null)), messages = mapOf(c to listOf(canonical))).timeline(c).size)
    }

    @Test fun legacyUnknownChronologyUsesGapZeroAndDuplicateCanonicalIdentityDoesNotConsumePending() {
        val legacy = ChatDurableState(textOutbox = listOf(PendingChatText(c, "legacy", "saved")),
            messages = mapOf(c to listOf(message(1)))).normalizePendingOrder()
        assertNull(legacy.textOutbox.single().createdAtUtc)
        assertEquals(0L, legacy.textOutbox.single().afterSequence)
        val snapshot = ChatSnapshot(account = a, messages = legacy.messages, pendingTexts = legacy.textOutbox)
        assertEquals("legacy", snapshot.timeline(c).first().pending?.clientMessageId)
        val matched = message(1, a.subjectId, "legacy").copy(text = "saved")
        val ambiguous = snapshot.copy(messages = mapOf(c to listOf(matched, matched.copy(messageId = "duplicate", sequence = 2))))
        val rows = ambiguous.timeline(c)
        assertEquals(3, rows.size); assertEquals(3, rows.map { it.key }.distinct().size)
        assertEquals(1, rows.count { it.pending != null && it.message == null })
    }

    private inner class Api : ChatGateway {
        var rows: List<ChatConversation>? = null
        val history = mutableListOf<ChatMessage>(); val sent = mutableListOf<PendingChatText>(); var acks = 0
        var beforeSend: suspend () -> Unit = {}; var contextWait: CompletableDeferred<Unit>? = null; var offline = false
        val dispatchKinds = mutableListOf<String>()
        var failConversation: String? = null
        var textFailure: ChatGatewayResult<ChatMessage>? = null
        var voiceFailure: ChatGatewayResult<ChatMessage>? = null
        var ambiguousSuccess = false
        var ambiguousVoice = false
        var downloadWait: CompletableDeferred<Unit>? = null; var downloadCalls = 0
        var beforeRead: suspend (Long) -> ChatGatewayResult<Long> = { ChatGatewayResult.Success(it) }
        override suspend fun context(): ChatGatewayResult<ChatAccountScope> { contextWait?.await(); return if (offline) ChatGatewayResult.RetryableFailure else ChatGatewayResult.Success(a) }
        override suspend fun direct(reference: String) = ChatGatewayResult.RetryableFailure
        override suspend fun conversations() = ChatGatewayResult.Success(ChatPage(listOf(ChatConversation(c, "peer", "peer", "Peer", history.size.toLong(), 0, updatedAtUtc = "2026-10-07T00:00:00Z")), false))
        override suspend fun conversations(cursor: String?): ChatGatewayResult<ChatPage<ChatConversation>> {
            val all = rows ?: return conversations()
            val start = cursor?.toInt() ?: 0; val page = all.drop(start).take(30); val more = start + page.size < all.size
            return ChatGatewayResult.Success(ChatPage(page, more, nextConversationCursor = if (more) (start + page.size).toString() else null))
        }
        override suspend fun messages(conversationId: String, afterSequence: Long): ChatGatewayResult<ChatPage<ChatMessage>> {
            if (offline) return ChatGatewayResult.RetryableFailure
            val remaining = history.filter { it.conversationId == conversationId && it.sequence > afterSequence }; val page = remaining.take(50)
            return ChatGatewayResult.Success(ChatPage(page, remaining.size > 50, page.lastOrNull()?.sequence))
        }
        override suspend fun sendText(message: PendingChatText): ChatGatewayResult<ChatMessage> {
            sent += message; dispatchKinds += "text"; beforeSend()
            if (message.conversationId == failConversation) return ChatGatewayResult.RetryableFailure
            textFailure?.let { return it }
            val sentMessage = message((history.maxOfOrNull { it.sequence } ?: 0) + 1, a.subjectId).copy(clientMessageId = message.clientMessageId, text = message.text, conversationId = message.conversationId)
            history += sentMessage; return if (ambiguousSuccess) ChatGatewayResult.RetryableFailure else ChatGatewayResult.Success(sentMessage)
        }
        override suspend fun sendVoice(conversationId: String, clientMessageId: String, draft: ChatVoiceDraft, bytes: ByteArray): ChatGatewayResult<ChatMessage> {
            dispatchKinds += "voice"
            if (conversationId == failConversation) return ChatGatewayResult.RetryableFailure
            voiceFailure?.let { return it }
            // Backend fixture oracle, independent of the supplied draft/timer.
            assertContentEquals(ChatAacFixture.bytes, bytes)
            val result = message((history.maxOfOrNull { it.sequence } ?: 0) + 1, a.subjectId, clientMessageId).copy(conversationId = conversationId, kind = "voice", text = null, voiceTransferId = "transfer", voiceSha256 = ChatAacFixture.hash, voiceLength = ChatAacFixture.length, voiceDurationMilliseconds = 2024)
            history += result; return if (ambiguousVoice) ChatGatewayResult.RetryableFailure else ChatGatewayResult.Success(result)
        }
        override suspend fun downloadVoice(transferId: String, expectedLength: Long): ChatGatewayResult<ByteArray> {
            downloadCalls++; downloadWait?.await(); return ChatGatewayResult.Success(byteArrayOf(1, 2, 3))
        }
        override suspend fun acknowledgeVoice(ack: PendingChatVoiceAck): ChatGatewayResult<Unit> { acks++; return ChatGatewayResult.Success(Unit) }
        override suspend fun read(conversationId: String, sequence: Long) = beforeRead(sequence)
    }
    private class Store : ChatDurableStore {
        val states = mutableMapOf<ChatAccountScope, ChatDurableState>(); var rejectSent = false
        var rejectNormalization = false
        val saves = mutableListOf<ChatDurableState>()
        override suspend fun load(scope: ChatAccountScope) = states[scope] ?: ChatDurableState()
        override suspend fun save(scope: ChatAccountScope, state: ChatDurableState) {
            if ((rejectSent && state.voiceOutbox.isEmpty()) || (rejectNormalization && state.voiceOutbox.any { it.encodedDurationVerified })) error("synthetic disk failure")
            states[scope] = state; saves += state
        }
        override suspend fun deleteAccount(scope: ChatAccountScope) { states.remove(scope) }
    }
    private class Voices : ChatVoiceStore {
        var bytes: ByteArray? = null; var deleted = false; var reject = false
        var draftBytes = ChatAacFixture.bytes
        var inspectWait: CompletableDeferred<Unit>? = null
        override suspend fun readDraft(draft: ChatVoiceDraft) = if (deleted || draft.length != draftBytes.size.toLong() ||
            (draft.sha256 != null && draft.sha256 != ChatAacFixture.hash)) null else draftBytes
        override suspend fun inspectDraft(draft: ChatVoiceDraft): ChatDraftRead {
            inspectWait?.await()
            return readDraft(draft)?.let { ChatDraftRead.Available(it, ChatAacFixture.hash) } ?: ChatDraftRead.MissingOrCorrupt
        }
        override suspend fun promoteDraft(scope: ChatAccountScope, transferId: String, expectedSha256: String, expectedLength: Long, draft: ChatVoiceDraft): StoredChatVoice? {
            if (reject || draft.owner != scope || expectedSha256 != ChatAacFixture.hash || expectedLength != ChatAacFixture.length) return null
            bytes = readDraft(draft) ?: return null
            return StoredChatVoice("voice-key", ChatAacFixture.length, ChatAacFixture.hash)
        }
        override suspend fun deleteDraft(draft: ChatVoiceDraft) { deleted = true }
        override suspend fun storeVerified(scope: ChatAccountScope, transferId: String, expectedSha256: String, expectedLength: Long, content: ByteArray): StoredChatVoice? { if (reject) return null; bytes = content; return StoredChatVoice("voice-key", 3, "hash") }
        override suspend fun read(scope: ChatAccountScope, localKey: String) = bytes
        override suspend fun deleteAccount(scope: ChatAccountScope) { bytes = null }
    }
}
