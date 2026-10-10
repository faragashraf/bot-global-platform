package com.botglobal.mobile.platform.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

class ChatController(
    private val gateway: ChatGateway = UnavailableChatGateway,
    private val durableStore: ChatDurableStore = UnavailableChatDurableStore,
    private val voiceStore: ChatVoiceStore = UnavailableChatVoiceStore,
    private val realtime: ChatRealtime = UnavailableChatRealtime,
    private val installationId: suspend () -> String? = { null },
    private val authenticatedIdentityKey: suspend () -> String? = { null },
    private val ids: ChatIdGenerator = ChatIdGenerator { Random.nextBytes(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') } },
    private val creationTime: () -> String? = { null },
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(ChatSnapshot())
    val state = mutableState.asStateFlow()
    private var durable = ChatDurableState()
    private var committed = durable
    private var generation = 0L
    private var sessionGateway: ChatGateway = UnavailableChatGateway
    private var onlineEnabled = false
    private var sessionJob: Job = SupervisorJob()
    private val flushLock = Mutex()
    private val syncLock = Mutex()
    private val bindLock = Mutex()
    private val ackLock = Mutex()
    private val downloads = mutableMapOf<Pair<Long, String>, CompletableDeferred<StoredChatVoice?>>()

    suspend fun bindAuthenticated(knownScope: ChatAccountScope? = null, resumeNetwork: Boolean = true): Boolean {
        val ticket = clearBinding()
        val captured = gateway.snapshot()
        val identityKey = authenticatedIdentityKey()
        if (generation != ticket) return false
        val restored = knownScope ?: identityKey?.let { durableStore.rememberedScope(it) }
        if (restored != null) install(restored, ticket, UnavailableChatGateway)
        val scope = when (val context = captured.context()) {
            is ChatGatewayResult.Success -> context.value
            ChatGatewayResult.RetryableFailure -> return false
            else -> { if (generation == ticket) clearBinding(); return false }
        }
        if (restored != null && restored != scope) { if (generation == ticket) clearBinding(); return false }
        if (!install(scope, ticket, captured)) return false
        lock.withLock { if (generation == ticket && identityKey != null) durableStore.rememberScope(identityKey, scope) }
        if (resumeNetwork) resume(ticket)
        return true
    }

    suspend fun resumeOnline() { resume(generation) }

    suspend fun bind(scope: ChatAccountScope?) {
        val ticket = clearBinding()
        if (scope != null && install(scope, ticket, gateway.snapshot())) resume(ticket)
    }

    /** Restores a previously bound identity for local access only. No API or socket work. */
    suspend fun bindLocal(identityKey: String): Boolean {
        val ticket = clearBinding()
        val scope = durableStore.rememberedScope(identityKey) ?: return false
        return install(scope, ticket, UnavailableChatGateway)
    }

    private suspend fun clearBinding(): Long = bindLock.withLock {
        val ticket = lock.withLock {
            sessionJob.cancel()
            sessionJob = SupervisorJob()
            generation += 1
            durable = ChatDurableState(); committed = durable
            sessionGateway = UnavailableChatGateway
            onlineEnabled = false
            mutableState.value = ChatSnapshot(accountGeneration = generation)
            generation
        }
        try { realtime.disconnect() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        ticket
    }

    private suspend fun install(scope: ChatAccountScope, ticket: Long, captured: ChatGateway): Boolean = lock.withLock {
        if (generation != ticket) return@withLock false
        durable = durableStore.load(scope); committed = durable
        val ordered = durable.normalizePendingOrder()
        // One atomic durable migration before any canonical coalescing or network recovery.
        // Unavailable bytes remain queue-owned; flush reports their typed failure when online.
        val normalized = ordered.copy(voiceOutbox = ordered.voiceOutbox.map { pending ->
            if (pending.encodedDurationVerified) pending else {
                val draft = pending.draft(scope)
                val local = voiceStore.inspectDraft(draft) as? ChatDraftRead.Available
                local?.let { verifiedDraft(draft, it) }?.let {
                    pending.copy(durationMilliseconds = it.durationMilliseconds, sha256 = it.sha256, encodedDurationVerified = true)
                } ?: pending
            }
        })
        if (normalized != durable) { durableStore.save(scope, normalized); durable = normalized; committed = normalized }
        sessionGateway = captured
        onlineEnabled = captured !== UnavailableChatGateway
        publish(scope, ChatLoadState.Idle)
        true
    }

    private suspend fun resume(ticket: Long) {
        if (!onlineEnabled || generation != ticket) return
        // Realtime is optional; canonical sync must work while the socket is unavailable.
        bindLock.withLock {
            if (generation != ticket) return
            try { realtime.connect({ hint ->
                val account = state.value.account
                if (generation == ticket && hint.applicationId == account?.applicationId) sync(hint.conversationId, ticket)
            }, { if (generation == ticket) { flush(ticket); sync(expectedGeneration = ticket) } }) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        }
        flush(ticket)
        sync(expectedGeneration = ticket)
    }

    suspend fun createDirect(reference: String): ChatConversation? {
        val (scope, requestGeneration) = active() ?: return null
        return when (val result = request(scope, requestGeneration) { direct(reference.trim()) }) {
            is ChatGatewayResult.Success -> lock.withLock {
                if (!isCurrent(scope, requestGeneration)) return@withLock null
                durable = durable.copy(conversations = mergeConversations(durable.conversations, listOf(result.value)))
                persistAndPublish(scope, ChatLoadState.Ready)
                result.value
            }
            else -> { markFailure(scope, requestGeneration); null }
        }
    }

    suspend fun selectConversation(conversationId: String?) {
        lock.withLock { mutableState.value = mutableState.value.copy(selectedConversationId = conversationId) }
    }

    suspend fun sendText(conversationId: String, text: String, expectedAccount: ChatAccountScope? = null,
        replyTo: ChatMessage? = null): String? {
        val (scope, ticket) = active() ?: return null
        val id = enqueueText(conversationId, text, expectedAccount ?: scope, replyTo) ?: return null
        flush(ticket)
        return id
    }

    suspend fun enqueueText(conversationId: String, text: String, expectedAccount: ChatAccountScope? = null,
        replyTo: ChatMessage? = null): String? {
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.length > 4000) return null
        val (scope, requestGeneration) = active() ?: return null
        if (expectedAccount != null && expectedAccount != scope) return null
        if (replyTo != null && replyTo.conversationId != conversationId) return null
        return lock.withLock {
            if (!isCurrent(scope, requestGeneration)) return null
            val reply = replyTo?.takeIf { durable.messages[conversationId].orEmpty().any { known -> known.messageId == it.messageId } }
            val pending = PendingChatText(conversationId, ids.next(), normalized, durable.nextEnqueueOrdinal, creationTime(),
                afterSequence = durable.knownSequence(conversationId),
                replyToMessageId = reply?.messageId,
                replyToSenderSubjectId = reply?.senderSubjectId,
                replyToKind = reply?.kind,
                replyToText = reply?.text,
                replyToVoiceDurationMilliseconds = reply?.voiceDurationMilliseconds)
            durable = durable.copy(textOutbox = durable.textOutbox + pending, nextEnqueueOrdinal = durable.nextEnqueueOrdinal + 1)
            persistAndPublish(scope)
            pending.clientMessageId
        }
    }

    suspend fun sendVoice(conversationId: String, draft: ChatVoiceDraft): String? {
        val (_, ticket) = active() ?: return null
        val id = enqueueVoice(conversationId, draft) ?: return null
        flush(ticket)
        return id
    }

    /** Success is the durable ownership transfer. Network completion is independent. */
    suspend fun enqueueVoice(conversationId: String, draft: ChatVoiceDraft): String? {
        if (draft.durationMilliseconds !in 1..300_000 || draft.length !in 1..(10L * 1024 * 1024)) return null
        val (scope, requestGeneration) = active() ?: return null
        if (draft.owner != null && (draft.owner != scope || draft.conversationId != conversationId)) return null
        return lock.withLock {
            if (!isCurrent(scope, requestGeneration)) return null
            durable.voiceOutbox.firstOrNull { it.draftToken == draft.token }?.let { return it.clientMessageId }
            val local = voiceStore.inspectDraft(draft) as? ChatDraftRead.Available ?: return null
            val measured = verifiedDraft(draft, local) ?: return null
            val pending = PendingChatVoice(conversationId, ids.next(), draft.token, measured.durationMilliseconds, draft.length, measured.sha256,
                enqueueOrdinal = durable.nextEnqueueOrdinal, createdAtUtc = creationTime(), afterSequence = durable.knownSequence(conversationId),
                encodedDurationVerified = true)
            durable = durable.copy(voiceOutbox = durable.voiceOutbox + pending, nextEnqueueOrdinal = durable.nextEnqueueOrdinal + 1)
            persistAndPublish(scope)
            pending.clientMessageId
        }
    }

    suspend fun downloadVoice(message: ChatMessage): StoredChatVoice? {
        val transferId = message.voiceTransferId ?: return null
        val hash = message.voiceSha256 ?: return null
        val length = message.voiceLength ?: return null
        val (scope, requestGeneration) = active() ?: return null
        val operationKey = requestGeneration to transferId
        var owner = false
        val operation = lock.withLock {
            if (!isCurrent(scope, requestGeneration)) return null
            durable.localVoiceKeys[transferId]?.let { key ->
                if (voiceStore.read(scope, key)?.size?.toLong() == length) return StoredChatVoice(key, length, hash)
            }
            downloads[operationKey] ?: CompletableDeferred<StoredChatVoice?>().also {
                downloads[operationKey] = it; owner = true
                mutableState.value = mutableState.value.copy(voiceLoads = mutableState.value.voiceLoads + (transferId to ChatVoiceLoadState.Loading))
            }
        }
        if (!owner) return operation.await().takeIf { isCurrent(scope, requestGeneration) }
        var resultVoice: StoredChatVoice? = null
        var loadState = ChatVoiceLoadState.RetryableFailure
        try {
        val bytes = when (val result = request(scope, requestGeneration) { downloadVoice(transferId, length) }) {
            is ChatGatewayResult.Success -> result.value
            ChatGatewayResult.Forbidden, ChatGatewayResult.Conflict -> { loadState = ChatVoiceLoadState.Unavailable; return null }
            else -> return null
        }
        val stored = lock.withLock {
            if (!isCurrent(scope, requestGeneration)) return null
            val stored = voiceStore.storeVerified(scope, transferId, hash, length, bytes) ?: return null
            val deviceId = installationId()?.takeIf { it.isNotBlank() }
            val ack = deviceId?.let { PendingChatVoiceAck(transferId, it, hash, length) }
            durable = durable.copy(
                voiceAcks = if (ack == null) durable.voiceAcks else (durable.voiceAcks + ack).distinctBy { it.transferId },
                localVoiceKeys = durable.localVoiceKeys + (transferId to stored.localKey),
            )
            persistAndPublish(scope)
            stored
        }
        resultVoice = stored
        loadState = ChatVoiceLoadState.Ready
        flushVoiceAcks(scope, requestGeneration)
        return stored.takeIf { isCurrent(scope, requestGeneration) }
        } finally {
            withContext(NonCancellable) { lock.withLock {
                downloads.remove(operationKey)
                if (isCurrent(scope, requestGeneration)) mutableState.value = mutableState.value.copy(
                    voiceLoads = mutableState.value.voiceLoads + (transferId to loadState))
                operation.complete(resultVoice.takeIf { isCurrent(scope, requestGeneration) })
            } }
        }
    }

    suspend fun markRead(conversationId: String, sequence: Long, expectedAccount: ChatAccountScope? = null) {
        val (scope, requestGeneration) = active() ?: return
        if (expectedAccount != null && expectedAccount != scope) return
        lock.withLock {
            if (!isCurrent(scope, requestGeneration)) return
            val existing = durable.readReceipts.firstOrNull { it.conversationId == conversationId }?.sequence ?: 0
            durable = durable.copy(readReceipts = durable.readReceipts.filterNot { it.conversationId == conversationId } +
                PendingChatReadReceipt(conversationId, maxOf(existing, sequence)),
                localReadWatermarks = durable.localReadWatermarks + (conversationId to maxOf(sequence, durable.localReadWatermarks[conversationId] ?: 0)))
            persistAndPublish(scope)
        }
        flush(requestGeneration)
    }

    suspend fun sync(conversationId: String? = null, expectedGeneration: Long? = null) {
        if (!onlineEnabled) return
        val (scope, requestGeneration) = active() ?: return
        if (expectedGeneration != null && requestGeneration != expectedGeneration) return
        syncLock.withLock {
        if (!isCurrent(scope, requestGeneration)) return
        lock.withLock { mutableState.value = mutableState.value.copy(syncing = true, retryableFailure = false) }
        if (conversationId == null) {
            syncConversations(scope, requestGeneration)
            val ids = lock.withLock { if (isCurrent(scope, requestGeneration)) durable.conversations.map { it.conversationId } else emptyList() }
            for (id in ids) syncMessages(scope, requestGeneration, id)
        } else {
            syncConversations(scope, requestGeneration)
            syncMessages(scope, requestGeneration, conversationId)
            refreshMetadata(scope, requestGeneration, conversationId)
        }
        lock.withLock { if (isCurrent(scope, requestGeneration)) mutableState.value = mutableState.value.copy(syncing = false) }
        }
    }

    suspend fun flush(expectedGeneration: Long? = null) {
        if (!onlineEnabled) return
        val (scope, requestGeneration) = active() ?: return
        if (expectedGeneration != null && expectedGeneration != requestGeneration) return
        flushLock.withLock {
        if (!isCurrent(scope, requestGeneration)) return
        val queue = lock.withLock { if (isCurrent(scope, requestGeneration)) pendingChatActivity(durable.textOutbox, durable.voiceOutbox) else emptyList() }
        val blocked = mutableSetOf<String>()
        for (activity in queue) {
            if (!isCurrent(scope, requestGeneration)) return
            if (activity.conversationId in blocked) continue
            val text = activity.text
            if (text != null) {
                if (text.failure != null) continue
                when (val result = request(scope, requestGeneration) { sendText(text) }) {
                    is ChatGatewayResult.Success -> acceptSent(scope, requestGeneration, result.value, text.clientMessageId, voice = false)
                    ChatGatewayResult.Conflict -> {
                        if (!reconcileText(scope, requestGeneration, text)) failText(scope, requestGeneration, text.clientMessageId, ChatTextFailure.Conflict)
                    }
                    ChatGatewayResult.Forbidden -> failText(scope, requestGeneration, text.clientMessageId, ChatTextFailure.Forbidden)
                    ChatGatewayResult.AuthenticationRequired, ChatGatewayResult.RetryableFailure -> {
                        blocked += text.conversationId; markFailure(scope, requestGeneration)
                    }
                }
                continue
            }
            var pending = requireNotNull(activity.voice)
            var draft = pending.draft(scope)
            val local = when (val read = voiceStore.inspectDraft(draft)) {
                is ChatDraftRead.Available -> read
                ChatDraftRead.MissingOrCorrupt -> { failVoice(scope, requestGeneration, pending.clientMessageId, ChatVoiceFailure.MissingOrCorrupt); continue }
                ChatDraftRead.TemporaryFailure -> { blocked += pending.conversationId; markFailure(scope, requestGeneration); continue }
            }
            val measured = verifiedDraft(draft, local)
            if (measured == null || (pending.encodedDurationVerified &&
                    (measured.durationMilliseconds != pending.durationMilliseconds || measured.sha256 != pending.sha256))) {
                failVoice(scope, requestGeneration, pending.clientMessageId, ChatVoiceFailure.MissingOrCorrupt); continue
            }
            if (!pending.encodedDurationVerified) {
                val normalized = pending.copy(durationMilliseconds = measured.durationMilliseconds, sha256 = measured.sha256, encodedDurationVerified = true)
                lock.withLock {
                    if (!isCurrent(scope, requestGeneration)) return
                    durable = durable.copy(voiceOutbox = durable.voiceOutbox.map { if (it == pending) normalized else it })
                    persistAndPublish(scope) // Failure rolls back; no upload, publication or deletion follows.
                }
                pending = normalized
            }
            draft = pending.draft(scope)
            val candidates = lock.withLock {
                if (isCurrent(scope, requestGeneration)) durable.messages[pending.conversationId].orEmpty()
                    .filter { it.clientMessageId == pending.clientMessageId && it.senderSubjectId == scope.subjectId } else emptyList()
            }
            val canonical = candidates.singleOrNull()?.takeIf { ChatPendingActivity(voice = pending).matches(scope, it) }
            if (candidates.isNotEmpty() && canonical == null) { blocked += pending.conversationId; markFailure(scope, requestGeneration); continue }
            // A proven upload is not resent. Publication still owns the draft and may be retried.
            if (pending.failure != null && canonical == null) continue
            val bytes = local.bytes
            when (val result = canonical?.let { ChatGatewayResult.Success(it) }
                ?: request(scope, requestGeneration) { sendVoice(pending.conversationId, pending.clientMessageId, draft, bytes) }) {
                is ChatGatewayResult.Success -> {
                    val message = result.value
                    val compatible = ChatPendingActivity(voice = pending).matches(scope, message)
                    if (!compatible) { blocked += pending.conversationId; markFailure(scope, requestGeneration); continue }
                    val transferId = message.voiceTransferId
                    val hash = message.voiceSha256
                    val length = message.voiceLength
                    val stored = lock.withLock {
                        if (transferId != null && hash != null && length != null && isCurrent(scope, requestGeneration))
                            voiceStore.promoteDraft(scope, transferId, hash, length, draft) else null
                    }
                    if (stored == null) { blocked += pending.conversationId; markFailure(scope, requestGeneration) }
                    else {
                        acceptSent(scope, requestGeneration, message, pending.clientMessageId, voice = true, localVoice = stored)
                        lock.withLock { if (isCurrent(scope, requestGeneration)) voiceStore.deleteDraft(draft) }
                    }
                }
                ChatGatewayResult.Conflict, ChatGatewayResult.Forbidden -> failVoice(scope, requestGeneration, pending.clientMessageId, ChatVoiceFailure.Rejected)
                ChatGatewayResult.AuthenticationRequired, ChatGatewayResult.RetryableFailure -> {
                    blocked += pending.conversationId; markFailure(scope, requestGeneration)
                }
            }
        }
        flushVoiceAcks(scope, requestGeneration)
        for (receipt in lock.withLock { if (isCurrent(scope, requestGeneration)) durable.readReceipts else emptyList() }) if (request(scope, requestGeneration) { read(receipt.conversationId, receipt.sequence) } is ChatGatewayResult.Success) lock.withLock {
            if (isCurrent(scope, requestGeneration)) { durable = durable.copy(readReceipts = durable.readReceipts.filterNot { it.conversationId == receipt.conversationId && it.sequence <= receipt.sequence }); persistAndPublish(scope) }
        }
        }
    }

    private suspend fun flushVoiceAcks(scope: ChatAccountScope, requestGeneration: Long) = ackLock.withLock {
        for (ack in lock.withLock { if (isCurrent(scope, requestGeneration)) durable.voiceAcks else emptyList() }) {
            val survives = lock.withLock {
                if (!isCurrent(scope, requestGeneration)) false else durable.localVoiceKeys[ack.transferId]?.let { voiceStore.read(scope, it)?.size?.toLong() == ack.length } == true
            }
            if (survives && request(scope, requestGeneration) { acknowledgeVoice(ack) } is ChatGatewayResult.Success) lock.withLock {
                if (isCurrent(scope, requestGeneration)) { durable = durable.copy(voiceAcks = durable.voiceAcks.filterNot { it.transferId == ack.transferId }); persistAndPublish(scope) }
            }
        }
    }

    suspend fun deleteAccountData(scope: ChatAccountScope) {
        val pendingDrafts = lock.withLock {
            val drafts = if (mutableState.value.account == scope) durable.voiceOutbox.map {
                ChatVoiceDraft(it.draftToken, it.durationMilliseconds, it.length, owner = scope, conversationId = it.conversationId)
            } else durableStore.load(scope).voiceOutbox.map {
                ChatVoiceDraft(it.draftToken, it.durationMilliseconds, it.length, owner = scope, conversationId = it.conversationId)
            }
            if (mutableState.value.account == scope) { sessionJob.cancel(); generation++; durable = ChatDurableState(); committed = durable; mutableState.value = ChatSnapshot(accountGeneration = generation) }
            voiceStore.deleteAccount(scope); durableStore.deleteAccount(scope)
            drafts
        }
        pendingDrafts.forEach { voiceStore.deleteDraft(it) }
    }

    private suspend fun syncConversations(scope: ChatAccountScope, ticket: Long) {
        var cursor = lock.withLock { durable.conversationCursor }
        repeat(MaxCatchUpPages) {
            when (val result = request(scope, ticket) { conversations(cursor) }) {
                is ChatGatewayResult.Success -> {
                    val next = result.value.nextConversationCursor
                    lock.withLock {
                        if (!isCurrent(scope, ticket)) return
                        durable = durable.copy(conversations = mergeConversations(durable.conversations, result.value.items),
                            conversationCursor = if (result.value.hasMore) next else null)
                        persistAndPublish(scope)
                    }
                    if (!result.value.hasMore) return
                    if (next == null || next == cursor) { markFailure(scope, ticket); return }
                    cursor = next
                }
                else -> { markFailure(scope, ticket); return }
            }
        }
        markFailure(scope, ticket) // Durable cursor resumes the next bounded catch-up.
    }

    private suspend fun syncMessages(scope: ChatAccountScope, requestGeneration: Long, conversationId: String) {
        var cursor = lock.withLock { durable.cursors[conversationId] ?: 0 }
        repeat(MaxCatchUpPages) {
            when (val result = request(scope, requestGeneration) { messages(conversationId, cursor) }) {
                is ChatGatewayResult.Success -> {
                    val accepted = lock.withLock {
                        if (!isCurrent(scope, requestGeneration)) return@withLock false
                        val merged = mergeMessages(durable.messages[conversationId].orEmpty(), result.value.items)
                        // Only fetched history advances this cursor, never optimistic/own sends.
                        val next = maxOf(cursor, result.value.items.maxOfOrNull { it.sequence } ?: cursor)
                        durable = durable.copy(messages = durable.messages + (conversationId to merged), cursors = durable.cursors + (conversationId to next)).anchorTimeline(scope)
                        durable = durable.copy(textOutbox = durable.textOutbox.filterNot { pending -> merged.any { canonical -> matchesText(scope, pending, canonical) } })
                        persistAndPublish(scope, if (durable.conversations.isEmpty()) ChatLoadState.Empty else ChatLoadState.Ready)
                        cursor = next
                        true
                    }
                    if (!accepted || !result.value.hasMore) return
                }
                else -> { markFailure(scope, requestGeneration); return }
            }
        }
        markFailure(scope, requestGeneration)
    }

    private suspend fun refreshMetadata(scope: ChatAccountScope, ticket: Long, conversationId: String) {
        // Round-robin metadata refresh is independent from the canonical append cursor.
        // Old voice terminal/read/provider state therefore cannot remain permanently first-wins.
        val start = lock.withLock { durable.metadataCursors[conversationId] ?: 0 }
        when (val result = request(scope, ticket) { messages(conversationId, start) }) {
            is ChatGatewayResult.Success -> lock.withLock {
                if (!isCurrent(scope, ticket)) return
                durable = durable.copy(messages = durable.messages + (conversationId to mergeMessages(durable.messages[conversationId].orEmpty(), result.value.items)),
                    metadataCursors = durable.metadataCursors + (conversationId to
                        if (result.value.hasMore) (result.value.items.lastOrNull()?.sequence ?: start) else 0))
                persistAndPublish(scope)
            }
            else -> Unit
        }
    }

    private suspend fun acceptSent(scope: ChatAccountScope, requestGeneration: Long, message: ChatMessage, clientId: String,
        voice: Boolean, localVoice: StoredChatVoice? = null) = lock.withLock {
        if (!isCurrent(scope, requestGeneration)) return@withLock
        if (!voice && durable.textOutbox.none { it.clientMessageId == clientId && matchesText(scope, it, message) }) {
            mutableState.value = mutableState.value.copy(retryableFailure = true)
            return@withLock
        }
        val confirmed = if (voice) durable.voiceOutbox.firstOrNull { it.clientMessageId == clientId && it.conversationId == message.conversationId }
            ?.let { ChatPendingActivity(voice = it) to message.sequence } else null
        durable = durable.copy(messages = durable.messages + (message.conversationId to mergeMessages(durable.messages[message.conversationId].orEmpty(), listOf(message)))).anchorTimeline(scope, confirmed)
        durable = durable.copy(
            textOutbox = if (voice) durable.textOutbox else durable.textOutbox.filterNot { it.clientMessageId == clientId },
            voiceOutbox = if (voice) durable.voiceOutbox.filterNot { it.clientMessageId == clientId } else durable.voiceOutbox,
            localVoiceKeys = if (localVoice != null && message.voiceTransferId != null)
                durable.localVoiceKeys + (message.voiceTransferId to localVoice.localKey) else durable.localVoiceKeys,
        )
        persistAndPublish(scope, ChatLoadState.Ready)
    }
    private fun matchesText(scope: ChatAccountScope, pending: PendingChatText, message: ChatMessage) =
        ChatPendingActivity(text = pending).matches(scope, message)

    private fun PendingChatVoice.draft(scope: ChatAccountScope) = ChatVoiceDraft(draftToken, durationMilliseconds, length,
        owner = scope, conversationId = conversationId, sha256 = sha256)

    private fun verifiedDraft(draft: ChatVoiceDraft, local: ChatDraftRead.Available): ChatVoiceDraft? {
        if (local.bytes.size.toLong() != draft.length) return null
        if (local.sha256 != null && draft.sha256 != null && local.sha256 != draft.sha256) return null
        val hash = local.sha256 ?: draft.sha256 ?: return null
        val duration = ChatVoiceDuration.milliseconds(local.bytes) ?: return null
        return draft.copy(durationMilliseconds = duration, sha256 = hash)
    }

    private suspend fun reconcileText(scope: ChatAccountScope, ticket: Long, pending: PendingChatText): Boolean {
        val cached = lock.withLock { if (isCurrent(scope, ticket)) durable.messages[pending.conversationId].orEmpty().firstOrNull { matchesText(scope, pending, it) } else null }
        if (cached != null) { acceptSent(scope, ticket, cached, pending.clientMessageId, voice = false); return true }
        var cursor = 0L
        repeat(MaxCatchUpPages) {
            when (val result = request(scope, ticket) { messages(pending.conversationId, cursor) }) {
                is ChatGatewayResult.Success -> {
                    result.value.items.firstOrNull { matchesText(scope, pending, it) }?.let {
                        acceptSent(scope, ticket, it, pending.clientMessageId, voice = false); return true
                    }
                    val next = result.value.items.maxOfOrNull { it.sequence } ?: cursor
                    if (!result.value.hasMore || next <= cursor) return false
                    cursor = next
                }
                else -> return false
            }
        }
        return false // Keep the content; bounded reconciliation is never proof of non-existence.
    }

    private suspend fun failText(scope: ChatAccountScope, ticket: Long, id: String, reason: ChatTextFailure) = lock.withLock {
        if (isCurrent(scope, ticket)) {
            durable = durable.copy(textOutbox = durable.textOutbox.map { if (it.clientMessageId == id) it.copy(failure = reason) else it })
            persistAndPublish(scope)
        }
    }

    suspend fun reconcileFailedText(id: String) {
        val (scope, ticket) = active() ?: return
        val pending = lock.withLock { durable.textOutbox.firstOrNull { it.clientMessageId == id && it.failure == ChatTextFailure.Conflict } } ?: return
        reconcileText(scope, ticket, pending)
    }

    suspend fun removeFailedText(id: String) {
        val (scope, ticket) = active() ?: return
        lock.withLock { if (isCurrent(scope, ticket)) {
            durable = durable.copy(textOutbox = durable.textOutbox.filterNot { it.clientMessageId == id && it.failure != null })
            persistAndPublish(scope)
        } }
    }

    /** An explicit edit creates a new send identity only for a definite rejection, never an ambiguous conflict. */
    suspend fun editFailedText(id: String, text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.length > 4000) return false
        val (scope, ticket) = active() ?: return false
        return lock.withLock {
            if (!isCurrent(scope, ticket)) return@withLock false
            val failed = durable.textOutbox.firstOrNull { it.clientMessageId == id && it.failure == ChatTextFailure.Forbidden } ?: return@withLock false
            val replacement = PendingChatText(failed.conversationId, ids.next(), normalized, durable.nextEnqueueOrdinal, creationTime(),
                afterSequence = durable.knownSequence(failed.conversationId))
            durable = durable.copy(textOutbox = durable.textOutbox.filterNot { it.clientMessageId == id } + replacement,
                nextEnqueueOrdinal = durable.nextEnqueueOrdinal + 1)
            persistAndPublish(scope)
            true
        }
    }
    private suspend fun failVoice(scope: ChatAccountScope, ticket: Long, id: String, reason: ChatVoiceFailure) = lock.withLock {
        if (isCurrent(scope, ticket)) {
            durable = durable.copy(voiceOutbox = durable.voiceOutbox.map { if (it.clientMessageId == id) it.copy(failure = reason) else it })
            persistAndPublish(scope)
        }
    }
    suspend fun retryFailedVoice(id: String) {
        val (scope, ticket) = active() ?: return
        val retryable = lock.withLock {
            if (!isCurrent(scope, ticket)) return
            val failed = durable.voiceOutbox.any { it.clientMessageId == id && it.failure == ChatVoiceFailure.Rejected }
            if (failed) {
                durable = durable.copy(voiceOutbox = durable.voiceOutbox.map {
                    if (it.clientMessageId == id && it.failure == ChatVoiceFailure.Rejected) it.copy(failure = null) else it
                })
                persistAndPublish(scope)
            }
            failed
        }
        if (retryable) flush(ticket)
    }
    suspend fun removeFailedVoice(id: String) {
        val (scope, ticket) = active() ?: return
        val failed = lock.withLock { durable.voiceOutbox.any { it.clientMessageId == id && it.failure != null } }
        if (failed) dropVoice(scope, ticket, id)
    }
    private suspend fun dropVoice(scope: ChatAccountScope, generation: Long, id: String) {
        lock.withLock { if (isCurrent(scope, generation)) {
            val pending = durable.voiceOutbox.firstOrNull { it.clientMessageId == id }
            durable = durable.copy(voiceOutbox = durable.voiceOutbox.filterNot { it.clientMessageId == id }); persistAndPublish(scope)
            pending?.let { voiceStore.deleteDraft(ChatVoiceDraft(it.draftToken, it.durationMilliseconds, it.length, owner = scope, conversationId = it.conversationId)) }
        } }
    }
    private suspend fun markFailure(scope: ChatAccountScope, requestGeneration: Long) = lock.withLock { if (isCurrent(scope, requestGeneration)) mutableState.value = mutableState.value.copy(state = ChatLoadState.Error, syncing = false, retryableFailure = true) }
    private suspend fun active(): Pair<ChatAccountScope, Long>? = lock.withLock { mutableState.value.account?.let { it to generation } }
    private fun isCurrent(scope: ChatAccountScope, expected: Long): Boolean {
        val snapshot = mutableState.value
        return snapshot.accountGeneration == expected && snapshot.account == scope
    }
    private suspend fun persistAndPublish(scope: ChatAccountScope, state: ChatLoadState = mutableState.value.state) {
        durable = durable.anchorTimeline(scope)
        try { durableStore.save(scope, durable); committed = durable; publish(scope, state) }
        catch (error: Exception) { durable = committed; throw error }
    }
    private fun publish(scope: ChatAccountScope, state: ChatLoadState) { mutableState.value = mutableState.value.copy(account = scope, accountGeneration = generation, state = state, conversations = durable.conversations, messages = durable.messages, pendingClientMessageIds = (durable.textOutbox.map { it.clientMessageId } + durable.voiceOutbox.map { it.clientMessageId }).toSet(), pendingTexts = durable.textOutbox, pendingVoices = durable.voiceOutbox, localVoiceKeys = durable.localVoiceKeys,
        activityOrders = durable.activityOrders,
        localReadWatermarks = durable.localReadWatermarks + durable.readReceipts.associate { it.conversationId to maxOf(it.sequence, durable.localReadWatermarks[it.conversationId] ?: 0) }) }
    private fun mergeConversations(existing: List<ChatConversation>, incoming: List<ChatConversation>): List<ChatConversation> {
        val rows = existing.associateBy { it.conversationId }.toMutableMap()
        incoming.forEach { row ->
            val known = rows[row.conversationId]
            rows[row.conversationId] = if (known == null) row else row.copy(
                lastSequence = maxOf(known.lastSequence, row.lastSequence),
                lastReadSequence = maxOf(known.lastReadSequence, row.lastReadSequence),
                counterpartLastReadSequence = maxOf(known.counterpartLastReadSequence, row.counterpartLastReadSequence),
                updatedAtUtc = maxOf(known.updatedAtUtc, row.updatedAtUtc))
        }
        return rows.values.sortedWith(compareByDescending<ChatConversation> { it.updatedAtUtc }.thenByDescending { it.conversationId })
    }
    private fun mergeMessages(existing: List<ChatMessage>, incoming: List<ChatMessage>) = (existing + incoming).associateBy { it.messageId }.values.sortedBy { it.sequence }

    private suspend fun <T> request(scope: ChatAccountScope, ticket: Long, call: suspend ChatGateway.() -> ChatGatewayResult<T>): ChatGatewayResult<T> {
        val binding = lock.withLock { if (isCurrent(scope, ticket)) sessionGateway to sessionJob else null }
            ?: return ChatGatewayResult.AuthenticationRequired
        return try {
            coroutineScope {
                currentCoroutineContext().ensureActive()
                if (!isCurrent(scope, ticket)) return@coroutineScope ChatGatewayResult.AuthenticationRequired
                val operation = async { binding.first.call() }
                val revoked = binding.second.invokeOnCompletion { operation.cancel() }
                try { operation.await() } finally { revoked.dispose() }
            }
        } catch (error: CancellationException) {
            if (isCurrent(scope, ticket)) throw error else ChatGatewayResult.AuthenticationRequired
        }
    }

    private companion object { const val MaxCatchUpPages = 10 }
}
