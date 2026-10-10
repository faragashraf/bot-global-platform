package com.botglobal.nqrb.app.ui

import com.botglobal.mobile.platform.chat.ChatMessage
import com.botglobal.mobile.platform.chat.ChatConversation
import kotlin.test.Test
import kotlin.test.assertEquals

class NqrbChatUiTests {
    @Test
    fun canonicalOutgoingTextShowsDeliveredUntilCounterpartReads() {
        val strings = nqrbChatStrings("en")
        val message = textMessage(sequence = 7)

        val delivered = chatMessageMetadata(
            mine = true,
            time = "2:31 PM",
            message = message,
            counterpartReadSequence = 6,
            strings = strings,
        )
        val read = chatMessageMetadata(
            mine = true,
            time = "2:31 PM",
            message = message,
            counterpartReadSequence = 7,
            strings = strings,
        )

        assertEquals(ChatMessageStatus.Delivered, delivered.status)
        assertEquals("Delivered", delivered.statusLabel)
        assertEquals(ChatMessageStatus.Read, read.status)
        assertEquals("Read", read.statusLabel)
    }

    @Test
    fun retryPendingOutgoingTextKeepsRetryStatus() {
        val metadata = chatMessageMetadata(
            mine = true,
            time = "2:31 PM",
            message = textMessage(sequence = 7).copy(deliveryState = "RetryPending"),
            counterpartReadSequence = 0,
            strings = nqrbChatStrings("en"),
        )

        assertEquals(ChatMessageStatus.RetryPending, metadata.status)
        assertEquals("Waiting to retry", metadata.statusLabel)
    }

    @Test
    fun latestMessageSequenceIncludesOutgoingMessagesToClearThreadUnreadState() {
        val rows = listOf(
            ChatThreadRow("mine", message = textMessage(sequence = 8).copy(senderSubjectId = "me")),
            ChatThreadRow("incoming-old", message = textMessage(sequence = 9).copy(senderSubjectId = "peer")),
            ChatThreadRow("incoming-new", message = textMessage(sequence = 10).copy(senderSubjectId = "peer")),
            ChatThreadRow("mine-new", message = textMessage(sequence = 11).copy(senderSubjectId = "me")),
        )

        assertEquals(11, latestMessageSequence(rows))
    }

    @Test
    fun replySwipeDirectionFollowsLayoutDirection() {
        assertEquals(true, shouldStartReplyFromSwipe(72f, 64f, isRtl = false))
        assertEquals(false, shouldStartReplyFromSwipe(-72f, 64f, isRtl = false))
        assertEquals(false, shouldStartReplyFromSwipe(40f, 64f, isRtl = false))
        assertEquals(true, shouldStartReplyFromSwipe(-72f, 64f, isRtl = true))
        assertEquals(false, shouldStartReplyFromSwipe(72f, 64f, isRtl = true))
        assertEquals(false, shouldStartReplyFromSwipe(-40f, 64f, isRtl = true))
    }

    @Test
    fun conversationThreadKeepsServerDisplayName() {
        val strings = nqrbChatStrings("en")
        val conversation = ChatConversation(
            conversationId = "conversation",
            counterpartSubjectId = "subject",
            counterpartReference = "saved-local-contact",
            counterpartDisplayName = "Ashraf Farag",
            lastSequence = 1,
            lastReadSequence = 1,
            updatedAtUtc = "2026-10-10T16:00:00Z",
        )

        assertEquals("Ashraf Farag", chatConversationDisplayName(conversation, strings))
        assertEquals("This person is currently unavailable", chatConversationDisplayName(conversation.copy(counterpartDisplayName = ""), strings))
    }

    private fun textMessage(sequence: Long) = ChatMessage(
        messageId = "message-$sequence",
        conversationId = "conversation",
        sequence = sequence,
        senderSubjectId = "sender",
        clientMessageId = "client-$sequence",
        kind = "text",
        text = "hello",
        deliveryState = "Delivered",
        createdAtUtc = "2026-10-10T11:31:00Z",
    )
}
