package com.botglobal.nqrb.calling

import com.botglobal.mobile.platform.calling.CallId
import com.botglobal.mobile.platform.calling.CallSessionController
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.PushMessageHandler
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Instant

internal interface NqrbIncomingCallPushSession {
    suspend fun receiveIncoming(callId: CallId)
    suspend fun dismissIncoming(callId: CallId, reason: CallTerminationReason)
}

internal interface NqrbGeneralPushNotificationSink {
    suspend fun show(messageId: String?, title: String, body: String, destination: String? = null)
}

internal fun interface NqrbChatPushSynchronizer {
    suspend fun synchronize(conversationId: String?)
}

internal class NqrbPushMessageHandler(
    private val session: NqrbIncomingCallPushSession,
    private val generalNotifications: NqrbGeneralPushNotificationSink = IgnoreGeneralPushNotifications,
    private val chat: NqrbChatPushSynchronizer = IgnoreChatPushSynchronizer,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = ::delay,
    private val logger: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.i("NqrbCalling", message) else Log.w("NqrbCalling", message, error)
    },
) : PushMessageHandler {
    constructor(runtime: NqrbCallRuntime) : this(NqrbCallSessionPushAdapter(runtime.session))

    constructor(
        runtime: NqrbCallRuntime,
        generalNotifications: NqrbGeneralPushNotificationSink,
        chat: NqrbChatPushSynchronizer = IgnoreChatPushSynchronizer,
    ) : this(NqrbCallSessionPushAdapter(runtime.session), generalNotifications, chat)

    override suspend fun onMessage(message: PushMessage) {
        val type = message.data["type"]
        if (type in CallPushTypes) {
            val callId = message.data["callId"]?.takeIf(::isOpaqueCallId)?.let(::CallId) ?: return
            handleCallPush(type, callId, message)
            return
        }
        if (type == "chat_message") {
            val conversationId = message.data["conversationId"]
            chat.synchronize(conversationId)
            val title = firstNonBlank(message.data, "title", "titleAr", "titleEn") ?: "Nqrb"
            val body = firstNonBlank(message.data, "body", "bodyAr", "bodyEn") ?: ""
            generalNotifications.show(
                message.data["notificationId"] ?: message.data["messageId"] ?: message.messageId,
                title,
                body,
                conversationId.chatDestination() ?: message.data["destination"]?.takeIf(String::isNotBlank),
            )
            return
        }
    }

    private suspend fun handleCallPush(type: String?, callId: CallId, message: PushMessage) {
        when (type) {
            "incoming_call" -> {
                if (message.isExpired()) {
                    session.dismissIncoming(callId, CallTerminationReason.Expired)
                    return
                }
                for (attempt in 1..MaxIncomingAttempts) {
                    if (message.isExpired()) {
                        session.dismissIncoming(callId, CallTerminationReason.Expired)
                        return
                    }
                    try {
                        session.receiveIncoming(callId)
                        return
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (message.isExpired()) {
                            session.dismissIncoming(callId, CallTerminationReason.Expired)
                            return
                        }
                        if (attempt == MaxIncomingAttempts) {
                            logger("incoming call revalidation failed after $attempt attempts type=${error::class.simpleName}", error)
                            return
                        }
                        pause(attempt * RetryDelayMillis)
                    }
                }
            }
            "incoming_call_cancelled", "incoming_call_answered_elsewhere" ->
                session.dismissIncoming(callId, CallTerminationReason.Cancelled)
            "incoming_call_expired" -> session.dismissIncoming(callId, CallTerminationReason.Expired)
        }
    }

    private fun PushMessage.isExpired(): Boolean {
        val callExpiry = data["expiresAtUtc"]?.let { value ->
            runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
        }
        if (callExpiry != null && nowEpochMillis() >= callExpiry) return true
        val ttl = timeToLiveSeconds.takeIf { it > 0 } ?: return false
        val sentAt = sentAtEpochMilliseconds.takeIf { it > 0 } ?: return false
        return nowEpochMillis() >= sentAt + ttl * 1_000L
    }

    private fun firstNonBlank(data: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> data[key]?.trim()?.takeIf(String::isNotEmpty) }

    private fun String?.chatDestination(): String? =
        this?.trim()?.takeIf(::isOpaqueUuid)?.let { "$ChatDestinationPrefix$it" }

    private fun isOpaqueCallId(value: String) = isOpaqueUuid(value)

    private fun isOpaqueUuid(value: String) = runCatching { java.util.UUID.fromString(value) }.isSuccess

    private companion object {
        const val ChatDestinationPrefix = "chat:"
        const val MaxIncomingAttempts = 3
        const val RetryDelayMillis = 500L
        val CallPushTypes = setOf(
            "incoming_call",
            "incoming_call_cancelled",
            "incoming_call_answered_elsewhere",
            "incoming_call_expired",
        )
    }
}

private object IgnoreGeneralPushNotifications : NqrbGeneralPushNotificationSink {
    override suspend fun show(messageId: String?, title: String, body: String, destination: String?) = Unit
}

private object IgnoreChatPushSynchronizer : NqrbChatPushSynchronizer {
    override suspend fun synchronize(conversationId: String?) = Unit
}

private class NqrbCallSessionPushAdapter(
    private val session: CallSessionController,
) : NqrbIncomingCallPushSession {
    override suspend fun receiveIncoming(callId: CallId) = session.receiveIncoming(callId)
    override suspend fun dismissIncoming(callId: CallId, reason: CallTerminationReason) =
        session.dismissIncoming(callId, reason)
}
