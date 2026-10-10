package com.botglobal.mobile.platform.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ChatControllerTests {
    @Test
    fun outboundTextSurvivesRestartWithItsOriginalClientId() = runTest {
        val scope = ChatAccountScope("application-a", "subject-a")
        val store = MemoryStore()
        val first = FakeGateway(scope).apply { textResult = ChatGatewayResult.RetryableFailure }
        val firstController = controller(first, store)
        firstController.bindAuthenticated()
        firstController.sendText(ConversationId, "hello")

        val pending = store.states.getValue(scope).textOutbox.single()
        val second = FakeGateway(scope).apply { textResult = ChatGatewayResult.Success(message(pending.clientMessageId)) }
        val restoredController = controller(second, store)
        restoredController.bindAuthenticated()

        assertEquals(pending.clientMessageId, second.sentClientIds.single())
        assertTrue(store.states.getValue(scope).textOutbox.isEmpty())
        assertEquals(1, restoredController.state.value.messages.getValue(ConversationId).size)
    }

    @Test
    fun responseFromOldAccountCannotEnterNewAccountState() = runTest {
        val oldScope = ChatAccountScope("application-a", "same-subject")
        val newScope = ChatAccountScope("application-b", "same-subject")
        val gateway = FakeGateway(oldScope)
        val store = MemoryStore()
        val release = CompletableDeferred<Unit>()
        gateway.beforeTextResponse = release
        val controller = controller(gateway, store)
        controller.bindAuthenticated()

        val send = async { controller.sendText(ConversationId, "old account") }
        gateway.textStarted.await()
        gateway.scope = newScope
        controller.bindAuthenticated()
        release.complete(Unit)
        send.await()

        assertEquals(newScope, controller.state.value.account)
        assertTrue(controller.state.value.messages.values.flatten().none { it.text == "old account" })
    }

    @Test
    fun outboundTextKeepsReplyPreviewUntilItFlushes() = runTest {
        val scope = ChatAccountScope("application-a", "subject-a")
        val reply = message("reply-root", sender = "subject-b").copy(messageId = "message-root", text = "original message")
        val store = MemoryStore().apply {
            states[scope] = ChatDurableState(
                conversations = listOf(conversation()),
                messages = mapOf(ConversationId to listOf(reply)),
            )
        }
        val gateway = FakeGateway(scope).apply { textResult = ChatGatewayResult.RetryableFailure }
        val controller = controller(gateway, store)
        controller.bindAuthenticated()

        controller.sendText(ConversationId, "answer", replyTo = reply)

        val pending = store.states.getValue(scope).textOutbox.single()
        assertEquals("message-root", pending.replyToMessageId)
        assertEquals("subject-b", pending.replyToSenderSubjectId)
        assertEquals("text", pending.replyToKind)
        assertEquals("original message", pending.replyToText)
        assertEquals("message-root", gateway.sentTexts.single().replyToMessageId)
    }

    @Test
    fun editAndDeleteCanonicalMessagesMergeServerTombstones() = runTest {
        val scope = ChatAccountScope("application-a", "subject-a")
        val original = message("client-1").copy(text = "before")
        val store = MemoryStore().apply {
            states[scope] = ChatDurableState(
                conversations = listOf(conversation()),
                messages = mapOf(ConversationId to listOf(original)),
            )
        }
        val gateway = FakeGateway(scope)
        val controller = controller(gateway, store)
        controller.bindAuthenticated()

        assertTrue(controller.editText(original, "after", scope))
        assertEquals("after", controller.state.value.messages.getValue(ConversationId).single().text)
        assertEquals("message-client-1", gateway.editedMessages.single().first)

        assertTrue(controller.deleteMessage(original, scope))
        val deleted = controller.state.value.messages.getValue(ConversationId).single()
        assertEquals("2026-10-07T00:10:00Z", deleted.deletedAtUtc)
        assertEquals("message-client-1", gateway.deletedMessages.single())
    }

    @Test
    fun canonicalMessageMutationWindowIsOneHourForOwnerOnly() {
        val own = message("client-1").copy(createdAtUtc = "2026-10-07T00:00:00Z")

        val within = Instant.parse("2026-10-07T00:59:59Z").toEpochMilliseconds()
        val outside = Instant.parse("2026-10-07T01:00:01Z").toEpochMilliseconds()

        assertTrue(own.canBeEditedBy("subject-a", within))
        assertTrue(own.canBeDeletedBy("subject-a", within))
        assertEquals(false, own.canBeEditedBy("subject-b", within))
        assertEquals(false, own.canBeDeletedBy("subject-a", outside))
    }

    @Test
    fun corruptDownloadIsNeverIndexedOrAcknowledged() = runTest {
        val scope = ChatAccountScope("application-a", "recipient")
        val gateway = FakeGateway(scope).apply { downloadBytes = byteArrayOf(1, 2, 3) }
        val voices = MemoryVoiceStore(acceptDownloadedContent = false)
        val controller = controller(gateway, MemoryStore(), voices)
        controller.bindAuthenticated()

        val voice = message("voice-client", sender = "sender", kind = "voice").copy(
            voiceTransferId = "transfer-1",
            voiceSha256 = "a".repeat(64),
            voiceLength = 3,
            voiceDurationMilliseconds = 1000,
        )
        assertNull(controller.downloadVoice(voice))
        runCurrent()

        assertEquals(0, gateway.ackCount)
        assertTrue(controller.state.value.localVoiceKeys.isEmpty())
    }

    private fun controller(gateway: FakeGateway, store: MemoryStore, voices: ChatVoiceStore = MemoryVoiceStore()) =
        ChatController(
            gateway = gateway,
            durableStore = store,
            voiceStore = voices,
            realtime = object : ChatRealtime {
                override suspend fun connect(onHint: suspend (ChatMessageHint) -> Unit) = Unit
                override suspend fun disconnect() = Unit
            },
            installationId = { "installation-1" },
            ids = ChatIdGenerator { "client-1" },
        )

    private class MemoryStore : ChatDurableStore {
        val states = mutableMapOf<ChatAccountScope, ChatDurableState>()
        override suspend fun load(scope: ChatAccountScope) = states[scope] ?: ChatDurableState()
        override suspend fun save(scope: ChatAccountScope, state: ChatDurableState) { states[scope] = state }
        override suspend fun deleteAccount(scope: ChatAccountScope) { states.remove(scope) }
    }

    private class MemoryVoiceStore(private val acceptDownloadedContent: Boolean = true) : ChatVoiceStore {
        override suspend fun readDraft(draft: ChatVoiceDraft) = byteArrayOf(0, 0, 0, 0, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        override suspend fun promoteDraft(scope: ChatAccountScope, transferId: String, expectedSha256: String, expectedLength: Long, draft: ChatVoiceDraft) =
            StoredChatVoice("sender-$transferId.m4a", expectedLength, expectedSha256)
        override suspend fun deleteDraft(draft: ChatVoiceDraft) = Unit
        override suspend fun storeVerified(scope: ChatAccountScope, transferId: String, expectedSha256: String,
            expectedLength: Long, content: ByteArray) = if (acceptDownloadedContent) StoredChatVoice("$transferId.m4a", expectedLength, expectedSha256) else null
        override suspend fun read(scope: ChatAccountScope, localKey: String): ByteArray? = null
        override suspend fun deleteAccount(scope: ChatAccountScope) = Unit
    }

    private class FakeGateway(initialScope: ChatAccountScope) : ChatGateway {
        var scope = initialScope
        var textResult: ChatGatewayResult<ChatMessage> = ChatGatewayResult.Success(message("client-1"))
        var beforeTextResponse: CompletableDeferred<Unit>? = null
        val textStarted = CompletableDeferred<Unit>()
        val sentClientIds = mutableListOf<String>()
        val sentTexts = mutableListOf<PendingChatText>()
        val editedMessages = mutableListOf<Pair<String, String>>()
        val deletedMessages = mutableListOf<String>()
        var downloadBytes = ByteArray(0)
        var ackCount = 0
        override suspend fun context() = ChatGatewayResult.Success(scope)
        override suspend fun direct(reference: String) = ChatGatewayResult.RetryableFailure
        override suspend fun conversations() = ChatGatewayResult.Success(ChatPage(listOf(conversation()), false))
        override suspend fun messages(conversationId: String, afterSequence: Long) = ChatGatewayResult.Success(ChatPage<ChatMessage>(emptyList(), false))
        override suspend fun sendText(message: PendingChatText): ChatGatewayResult<ChatMessage> {
            sentClientIds += message.clientMessageId
            sentTexts += message
            textStarted.complete(Unit)
            beforeTextResponse?.await()
            return when (val result = textResult) {
                is ChatGatewayResult.Success -> ChatGatewayResult.Success(result.value.copy(clientMessageId = message.clientMessageId, text = message.text))
                else -> result
            }
        }
        override suspend fun sendVoice(conversationId: String, clientMessageId: String, draft: ChatVoiceDraft, bytes: ByteArray) = ChatGatewayResult.RetryableFailure
        override suspend fun editText(conversationId: String, messageId: String, text: String): ChatGatewayResult<ChatMessage> {
            editedMessages += messageId to text
            return ChatGatewayResult.Success(message("client-1").copy(messageId = messageId, text = text, editedAtUtc = "2026-10-07T00:05:00Z"))
        }
        override suspend fun deleteMessage(conversationId: String, messageId: String): ChatGatewayResult<ChatMessage> {
            deletedMessages += messageId
            return ChatGatewayResult.Success(message("client-1").copy(messageId = messageId, text = null, deletedAtUtc = "2026-10-07T00:10:00Z"))
        }
        override suspend fun downloadVoice(transferId: String, expectedLength: Long) = ChatGatewayResult.Success(downloadBytes)
        override suspend fun acknowledgeVoice(ack: PendingChatVoiceAck): ChatGatewayResult<Unit> { ackCount++; return ChatGatewayResult.Success(Unit) }
        override suspend fun read(conversationId: String, sequence: Long) = ChatGatewayResult.Success(sequence)
    }

    private companion object {
        const val ConversationId = "conversation-1"
        fun conversation() = ChatConversation(ConversationId, "other", "reference", "Other", 0, 0, updatedAtUtc = "2026-10-07T00:00:00Z")
        fun message(clientId: String, sender: String = "subject-a", kind: String = "text") = ChatMessage(
            messageId = "message-$clientId", conversationId = ConversationId, sequence = 1,
            senderSubjectId = sender, clientMessageId = clientId, kind = kind,
            text = if (kind == "text") "hello" else null, createdAtUtc = "2026-10-07T00:00:00Z",
        )
    }
}
