package com.botglobal.mobile.platform.chat

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

@Serializable
data class ChatAccountScope(val applicationId: String, val subjectId: String) {
    init {
        require(applicationId.isNotBlank())
        require(subjectId.isNotBlank() && subjectId.length <= 200)
    }
}

data class ChatCredential(val authorization: String)

fun interface ChatCredentialProvider {
    suspend fun current(): ChatCredential?
}

@Serializable
data class ChatConversation(
    val conversationId: String,
    val counterpartSubjectId: String,
    val counterpartReference: String?,
    val counterpartDisplayName: String?,
    val lastSequence: Long,
    val lastReadSequence: Long,
    val counterpartLastReadSequence: Long = 0,
    val updatedAtUtc: String,
)

@Serializable
data class ChatMessage(
    val messageId: String,
    val conversationId: String,
    val sequence: Long,
    val senderSubjectId: String,
    val clientMessageId: String,
    val kind: String,
    val text: String? = null,
    val voiceTransferId: String? = null,
    val voiceSha256: String? = null,
    val voiceLength: Long? = null,
    val voiceDurationMilliseconds: Int? = null,
    val voiceState: String? = null,
    val replyToMessageId: String? = null,
    val replyToSenderSubjectId: String? = null,
    val replyToKind: String? = null,
    val replyToText: String? = null,
    val replyToVoiceDurationMilliseconds: Int? = null,
    val deliveryState: String = "Pending",
    val createdAtUtc: String,
)

@Serializable
data class ChatPage<T>(val items: List<T>, val hasMore: Boolean, val nextCursor: Long? = null,
    val nextConversationCursor: String? = null)

@Serializable
data class ChatMessageHint(
    val applicationId: String,
    val conversationId: String,
    val messageId: String,
    val sequence: Long,
    val kind: String,
    val createdAtUtc: String,
)

@Serializable
data class PendingChatText(
    val conversationId: String,
    val clientMessageId: String,
    val text: String,
    val enqueueOrdinal: Long = 0,
    val createdAtUtc: String? = null,
    val failure: ChatTextFailure? = null,
    val afterSequence: Long? = null,
    val beforeSequence: Long? = null,
    val replyToMessageId: String? = null,
    val replyToSenderSubjectId: String? = null,
    val replyToKind: String? = null,
    val replyToText: String? = null,
    val replyToVoiceDurationMilliseconds: Int? = null,
)

@Serializable
enum class ChatTextFailure { Forbidden, Conflict }

@Serializable
data class PendingChatVoiceAck(
    val transferId: String,
    val installationId: String,
    val sha256: String,
    val length: Long,
)

@Serializable
data class PendingChatVoice(
    val conversationId: String,
    val clientMessageId: String,
    val draftToken: String,
    val durationMilliseconds: Int,
    val length: Long,
    val sha256: String? = null,
    val failure: ChatVoiceFailure? = null,
    val enqueueOrdinal: Long = 0,
    val createdAtUtc: String? = null,
    val afterSequence: Long? = null,
    val beforeSequence: Long? = null,
    val encodedDurationVerified: Boolean = false,
)

@Serializable
enum class ChatVoiceFailure { MissingOrCorrupt, Rejected }
sealed interface ChatDraftRead {
    /** The store verifies account/thread ownership, length and any supplied digest before returning.
     * sha256 supplies the verified byte digest for legacy drafts that did not persist one. */
    data class Available(val bytes: ByteArray, val sha256: String? = null) : ChatDraftRead
    data object MissingOrCorrupt : ChatDraftRead
    data object TemporaryFailure : ChatDraftRead
}

@Serializable
data class PendingChatReadReceipt(val conversationId: String, val sequence: Long)

@Serializable
data class ChatActivityOrder(val sequence: Long, val ordinal: Long = 0)

@Serializable
data class ChatDurableState(
    val conversations: List<ChatConversation> = emptyList(),
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val textOutbox: List<PendingChatText> = emptyList(),
    val voiceOutbox: List<PendingChatVoice> = emptyList(),
    val voiceAcks: List<PendingChatVoiceAck> = emptyList(),
    val readReceipts: List<PendingChatReadReceipt> = emptyList(),
    val cursors: Map<String, Long> = emptyMap(),
    val localVoiceKeys: Map<String, String> = emptyMap(),
    val conversationCursor: String? = null,
    val metadataCursors: Map<String, Long> = emptyMap(),
    val nextEnqueueOrdinal: Long = 1,
    val localReadWatermarks: Map<String, Long> = emptyMap(),
    val activityOrders: Map<String, ChatActivityOrder> = emptyMap(),
)

/** One projection for dispatch and every consumer. Legacy type-local order is all that is known. */
data class ChatPendingActivity(val text: PendingChatText? = null, val voice: PendingChatVoice? = null) {
    val conversationId get() = text?.conversationId ?: requireNotNull(voice).conversationId
    val clientMessageId get() = text?.clientMessageId ?: requireNotNull(voice).clientMessageId
    val enqueueOrdinal get() = text?.enqueueOrdinal ?: requireNotNull(voice).enqueueOrdinal
    val createdAtUtc get() = text?.createdAtUtc ?: voice?.createdAtUtc
    val afterSequence get() = text?.afterSequence ?: voice?.afterSequence ?: 0
    val beforeSequence get() = text?.beforeSequence ?: voice?.beforeSequence

    fun matches(scope: ChatAccountScope, message: ChatMessage): Boolean =
        message.senderSubjectId == scope.subjectId && message.conversationId == conversationId &&
            message.clientMessageId == clientMessageId && when {
                text != null -> message.kind == "text" && message.text == text.text
                    && message.replyToMessageId.orEmpty() == text.replyToMessageId.orEmpty()
                voice != null -> message.kind == "voice" && voice.encodedDurationVerified && voice.sha256 != null &&
                    message.voiceSha256 == voice.sha256 && message.voiceLength == voice.length &&
                    message.voiceDurationMilliseconds == voice.durationMilliseconds
                else -> false
            }
}

fun pendingChatActivity(texts: List<PendingChatText>, voices: List<PendingChatVoice>): List<ChatPendingActivity> =
    (texts.map { ChatPendingActivity(text = it) } + voices.map { ChatPendingActivity(voice = it) }).sortedBy { it.enqueueOrdinal }

fun ChatDurableState.normalizePendingOrder(): ChatDurableState {
    var next = maxOf(nextEnqueueOrdinal, (textOutbox.map { it.enqueueOrdinal } + voiceOutbox.map { it.enqueueOrdinal }).maxOrNull()?.plus(1) ?: 1)
    // Older JSON has no mixed chronology. Deterministic text-then-voice preserves each known list;
    // no historical time is synthesized. Persist this once before accepting new work.
    return copy(textOutbox = textOutbox.map { it.copy(enqueueOrdinal = if (it.enqueueOrdinal <= 0) next++ else it.enqueueOrdinal, afterSequence = it.afterSequence ?: 0) },
        voiceOutbox = voiceOutbox.map { it.copy(enqueueOrdinal = if (it.enqueueOrdinal <= 0) next++ else it.enqueueOrdinal, afterSequence = it.afterSequence ?: 0) },
        nextEnqueueOrdinal = next,
        activityOrders = (conversations.map { it.conversationId } + messages.keys).distinct().associateWith {
            activityOrders[it] ?: ChatActivityOrder(knownSequence(it))
        },
        localReadWatermarks = localReadWatermarks + readReceipts.associate { it.conversationId to maxOf(it.sequence, localReadWatermarks[it.conversationId] ?: 0) })
}

fun ChatDurableState.knownSequence(conversationId: String): Long = maxOf(
    conversations.firstOrNull { it.conversationId == conversationId }?.lastSequence ?: 0,
    messages[conversationId].orEmpty().maxOfOrNull { it.sequence } ?: 0)

/** Copy proven local relationships onto remaining queue entries before deleting a reconciled entry.
 * Only two scalar bounds per pending message survive; no sent-message ledger accumulates. */
fun ChatDurableState.anchorTimeline(scope: ChatAccountScope, confirmed: Pair<ChatPendingActivity, Long>? = null): ChatDurableState {
    val pending = pendingChatActivity(textOutbox, voiceOutbox)
    val matches = pending.mapNotNull { local ->
        messages[local.conversationId].orEmpty().filter { local.matches(scope, it) }.singleOrNull()?.let { local to it.sequence }
    } + listOfNotNull(confirmed)
    fun bounds(local: ChatPendingActivity): Pair<Long, Long?> {
        val peers = matches.filter { it.first.conversationId == local.conversationId }
        val after = maxOf(local.afterSequence, peers.filter { it.first.enqueueOrdinal < local.enqueueOrdinal }.maxOfOrNull { it.second } ?: 0)
        val before = (listOfNotNull(local.beforeSequence) + peers.filter { it.first.enqueueOrdinal > local.enqueueOrdinal }.map { it.second }).minOrNull()
        return after to before
    }
    var next = nextEnqueueOrdinal
    val orders = activityOrders.toMutableMap()
    // First observation is history hydration, never evidence of later activity. Order a batch
    // of actual advances by canonical recency, so iteration/ID order cannot reverse chronology.
    (conversations.map { it.conversationId } + messages.keys).distinct().sortedWith(
        compareBy<String> { id -> maxOf(conversations.firstOrNull { it.conversationId == id }?.updatedAtUtc.orEmpty(),
            messages[id].orEmpty().maxOfOrNull { it.createdAtUtc }.orEmpty()) }.thenBy { it }
    ).forEach { id ->
        val sequence = knownSequence(id)
        val previous = orders[id]
        if (previous == null) orders[id] = ChatActivityOrder(sequence)
        else if (sequence > previous.sequence) orders[id] = ChatActivityOrder(sequence, next++)
    }
    return copy(textOutbox = textOutbox.map { val (after, before) = bounds(ChatPendingActivity(text = it)); it.copy(afterSequence = after, beforeSequence = before) },
        voiceOutbox = voiceOutbox.map { val (after, before) = bounds(ChatPendingActivity(voice = it)); it.copy(afterSequence = after, beforeSequence = before) },
        activityOrders = orders, nextEnqueueOrdinal = next)
}

data class ChatTimelineActivity(val key: String, val message: ChatMessage? = null, val pending: ChatPendingActivity? = null) {
    val createdAtUtc get() = message?.createdAtUtc ?: pending?.createdAtUtc
}

/** Canonical sequence wins. Unmatched local rows occupy the earliest known sequence gap,
 * ordered by enqueue ordinal. Unknown legacy anchors use gap zero, never guessed wall time. */
fun ChatSnapshot.timeline(conversationId: String): List<ChatTimelineActivity> {
    val scope = account ?: return emptyList()
    val canonical = messages[conversationId].orEmpty().filter { it.conversationId == conversationId }.distinctBy { it.messageId }.sortedBy { it.sequence }
    val pending = pendingActivity.filter { it.conversationId == conversationId }
    fun ownKey(local: ChatPendingActivity): String = listOf(scope.applicationId, scope.subjectId, conversationId,
        local.clientMessageId, if (local.text != null) "text" else "voice").joinToString(":", prefix = "own:") { "${it.length}:$it" }
    val pairs = pending.mapNotNull { local -> canonical.filter { local.matches(scope, it) }.singleOrNull()?.let { it.messageId to local } }.toMap()
    val ownCounts = canonical.filter { it.senderSubjectId == scope.subjectId }.groupingBy { it.clientMessageId to it.kind }.eachCount()
    val canonicalRows = canonical.map { message ->
        val local = pairs[message.messageId]
        val collision = pending.any { it.clientMessageId == message.clientMessageId && !it.matches(scope, message) } ||
            (ownCounts[message.clientMessageId to message.kind] ?: 0) > 1
        val identity = local ?: if (message.kind == "text") ChatPendingActivity(text = PendingChatText(conversationId, message.clientMessageId, message.text.orEmpty()))
            else ChatPendingActivity(voice = PendingChatVoice(conversationId, message.clientMessageId, "", 0, 0))
        ChatTimelineActivity(if (local != null || (message.senderSubjectId == scope.subjectId && !collision)) ownKey(identity) else "message:${message.messageId}", message, local)
    }
    val slots = mutableMapOf<Int, MutableList<ChatPendingActivity>>()
    var previousSlot = 0
    pending.filter { it !in pairs.values }.forEach { local ->
        val after = canonical.count { it.sequence <= local.afterSequence }
        val before = local.beforeSequence?.let { bound -> canonical.indexOfFirst { it.sequence >= bound }.takeIf { it >= 0 } } ?: canonical.size
        val slot = maxOf(previousSlot, minOf(after, before)).coerceAtMost(canonical.size)
        slots.getOrPut(slot) { mutableListOf() }.add(local); previousSlot = slot
    }
    return buildList {
        for (slot in 0..canonical.size) {
            slots[slot].orEmpty().forEach { add(ChatTimelineActivity(ownKey(it), pending = it)) }
            if (slot < canonical.size) add(canonicalRows[slot])
        }
    }
}

data class ChatConversationActivity(val conversation: ChatConversation, val latestMessage: ChatMessage?,
    val pending: ChatPendingActivity?, val lastReadSequence: Long, val activityOrdinal: Long = 0) {
    val unreadCount get() = (conversation.lastSequence - lastReadSequence).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val unread get() = conversation.lastSequence > lastReadSequence
    val updatedAtUtc get() = if (pending != null && latestMessage == null) pending.createdAtUtc ?: conversation.updatedAtUtc
        else maxOf(latestMessage?.createdAtUtc.orEmpty(), conversation.updatedAtUtc)
}

enum class ChatVoiceLoadState { Loading, Ready, RetryableFailure, Unavailable }

enum class ChatLoadState { Idle, Loading, Ready, Empty, Error }

data class ChatSnapshot(
    val account: ChatAccountScope? = null,
    val state: ChatLoadState = ChatLoadState.Idle,
    val conversations: List<ChatConversation> = emptyList(),
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val selectedConversationId: String? = null,
    val syncing: Boolean = false,
    val retryableFailure: Boolean = false,
    val pendingClientMessageIds: Set<String> = emptySet(),
    val pendingTexts: List<PendingChatText> = emptyList(),
    val pendingVoices: List<PendingChatVoice> = emptyList(),
    val localVoiceKeys: Map<String, String> = emptyMap(),
    val localReadWatermarks: Map<String, Long> = emptyMap(),
    val voiceLoads: Map<String, ChatVoiceLoadState> = emptyMap(),
    val accountGeneration: Long = 0,
    val activityOrders: Map<String, ChatActivityOrder> = emptyMap(),
) {
    val pendingActivity get() = pendingChatActivity(pendingTexts, pendingVoices)
    val totalUnreadCount: Int get() = conversationActivity.sumOf { it.unreadCount }
    fun unreadCountForCounterpart(counterpartReference: String): Int =
        conversationActivity.filter { it.conversation.counterpartReference == counterpartReference }.sumOf { it.unreadCount }

    val conversationActivity: List<ChatConversationActivity> get() {
        val activities = conversations.map { conversation ->
            val rows = timeline(conversation.conversationId)
            val known = rows.maxOfOrNull { maxOf(it.message?.sequence ?: 0, it.pending?.afterSequence ?: 0) } ?: 0
            val latest = rows.lastOrNull().takeUnless { conversation.lastSequence > known }
            ChatConversationActivity(conversation, latest?.message, latest?.pending,
                maxOf(conversation.lastReadSequence, localReadWatermarks[conversation.conversationId] ?: 0),
                if (latest?.message == null && latest?.pending != null) latest.pending.enqueueOrdinal
                else activityOrders[conversation.conversationId]?.ordinal ?: 0)
        }
        // Canonical rows always retain server recency across pages and restarts. Ordinals
        // relate explicit pending local work to later observed activity, not history to history.
        val result = activities.filter { it.pending == null || it.latestMessage != null }
            .sortedWith(compareByDescending<ChatConversationActivity> { it.updatedAtUtc }
                .thenByDescending { it.conversation.conversationId }).toMutableList()
        var pendingFloor = 0
        activities.filter { it.pending != null && it.latestMessage == null }
            .sortedWith(compareByDescending<ChatConversationActivity> { it.activityOrdinal }.thenBy { it.conversation.conversationId })
            .forEach { local ->
                val afterLaterCanonical = result.indexOfLast {
                    (it.pending == null || it.latestMessage != null) && it.activityOrdinal > local.activityOrdinal
                } + 1
                val slot = maxOf(pendingFloor, afterLaterCanonical)
                result.add(slot, local); pendingFloor = slot + 1
            }
        return result
    }
}

fun interface ChatIdGenerator { fun next(): String }

interface ChatDurableStore {
    suspend fun rememberedScope(identityKey: String): ChatAccountScope? = null
    suspend fun rememberScope(identityKey: String, scope: ChatAccountScope) = Unit
    suspend fun load(scope: ChatAccountScope): ChatDurableState
    suspend fun save(scope: ChatAccountScope, state: ChatDurableState)
    suspend fun deleteAccount(scope: ChatAccountScope)
}

object UnavailableChatDurableStore : ChatDurableStore {
    override suspend fun load(scope: ChatAccountScope) = ChatDurableState()
    override suspend fun save(scope: ChatAccountScope, state: ChatDurableState) = Unit
    override suspend fun deleteAccount(scope: ChatAccountScope) = Unit
}

data class ChatVoiceDraft(
    val token: String,
    val durationMilliseconds: Int,
    val length: Long,
    val amplitudes: List<Float> = emptyList(),
    val owner: ChatAccountScope? = null,
    val conversationId: String? = null,
    val sha256: String? = null,
)
data class StoredChatVoice(val localKey: String, val length: Long, val sha256: String)

interface ChatVoiceStore {
    suspend fun readDraft(draft: ChatVoiceDraft): ByteArray?
    suspend fun inspectDraft(draft: ChatVoiceDraft): ChatDraftRead =
        readDraft(draft)?.let { ChatDraftRead.Available(it) } ?: ChatDraftRead.MissingOrCorrupt
    suspend fun promoteDraft(scope: ChatAccountScope, transferId: String, expectedSha256: String,
        expectedLength: Long, draft: ChatVoiceDraft): StoredChatVoice?
    suspend fun deleteDraft(draft: ChatVoiceDraft)
    suspend fun storeVerified(scope: ChatAccountScope, transferId: String, expectedSha256: String,
        expectedLength: Long, content: ByteArray): StoredChatVoice?
    suspend fun read(scope: ChatAccountScope, localKey: String): ByteArray?
    suspend fun deleteAccount(scope: ChatAccountScope)
}

object UnavailableChatVoiceStore : ChatVoiceStore {
    override suspend fun readDraft(draft: ChatVoiceDraft) = null
    override suspend fun promoteDraft(scope: ChatAccountScope, transferId: String, expectedSha256: String, expectedLength: Long, draft: ChatVoiceDraft) = null
    override suspend fun deleteDraft(draft: ChatVoiceDraft) = Unit
    override suspend fun storeVerified(scope: ChatAccountScope, transferId: String, expectedSha256: String, expectedLength: Long, content: ByteArray) = null
    override suspend fun read(scope: ChatAccountScope, localKey: String) = null
    override suspend fun deleteAccount(scope: ChatAccountScope) = Unit
}

interface ChatVoiceRecorder {
    val available: Boolean
    val progress: StateFlow<ChatRecordingProgress> get() = EmptyRecordingProgress
    suspend fun start(): Boolean
    suspend fun start(scope: ChatAccountScope, conversationId: String): Boolean = start()
    fun onAutomaticStop(callback: (ChatVoiceDraft?) -> Unit) = Unit
    suspend fun stop(): ChatVoiceDraft?
    suspend fun cancel()
    suspend fun discard(draft: ChatVoiceDraft)
}

interface ChatVoicePlayer {
    val available: Boolean
    val state: StateFlow<ChatPlayback> get() = EmptyPlayback
    suspend fun play(scope: ChatAccountScope, localKey: String): Boolean
    suspend fun playDraft(draft: ChatVoiceDraft): Boolean
    suspend fun stop()
    suspend fun pause() = stop()
    suspend fun resume(): Boolean = false
}

data class ChatRecordingProgress(val elapsedMilliseconds: Int = 0, val level: Float = 0f)
enum class ChatPlaybackPhase { Idle, Preparing, Playing, Paused, Completed, Failed }
data class ChatPlayback(val key: String? = null, val phase: ChatPlaybackPhase = ChatPlaybackPhase.Idle,
    val elapsedMilliseconds: Int = 0, val durationMilliseconds: Int = 0)
private val EmptyRecordingProgress: StateFlow<ChatRecordingProgress> = MutableStateFlow(ChatRecordingProgress())
private val EmptyPlayback: StateFlow<ChatPlayback> = MutableStateFlow(ChatPlayback())

object UnavailableChatVoiceRecorder : ChatVoiceRecorder {
    override val available = false
    override suspend fun start() = false
    override suspend fun stop(): ChatVoiceDraft? = null
    override suspend fun cancel() = Unit
    override suspend fun discard(draft: ChatVoiceDraft) = Unit
}

object UnavailableChatVoicePlayer : ChatVoicePlayer {
    override val available = false
    override suspend fun play(scope: ChatAccountScope, localKey: String) = false
    override suspend fun playDraft(draft: ChatVoiceDraft) = false
    override suspend fun stop() = Unit
}

interface ChatRealtime {
    suspend fun connect(onHint: suspend (ChatMessageHint) -> Unit)
    suspend fun connect(onHint: suspend (ChatMessageHint) -> Unit, onConnected: suspend () -> Unit) = connect(onHint)
    suspend fun disconnect()
}

object UnavailableChatRealtime : ChatRealtime {
    override suspend fun connect(onHint: suspend (ChatMessageHint) -> Unit) = Unit
    override suspend fun disconnect() = Unit
}
