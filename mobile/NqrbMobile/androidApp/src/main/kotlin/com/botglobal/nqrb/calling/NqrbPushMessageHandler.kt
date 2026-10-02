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

internal class NqrbPushMessageHandler(
    private val session: NqrbIncomingCallPushSession,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = ::delay,
    private val logger: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.i("NqrbCalling", message) else Log.w("NqrbCalling", message, error)
    },
) : PushMessageHandler {
    constructor(runtime: NqrbCallRuntime) : this(NqrbCallSessionPushAdapter(runtime.session))

    override suspend fun onMessage(message: PushMessage) {
        val callId = message.data["callId"]?.takeIf(::isOpaqueCallId)?.let(::CallId) ?: return
        when (message.data["type"]) {
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

    private fun isOpaqueCallId(value: String) = runCatching { java.util.UUID.fromString(value) }.isSuccess

    private companion object {
        const val MaxIncomingAttempts = 3
        const val RetryDelayMillis = 500L
    }
}

private class NqrbCallSessionPushAdapter(
    private val session: CallSessionController,
) : NqrbIncomingCallPushSession {
    override suspend fun receiveIncoming(callId: CallId) = session.receiveIncoming(callId)
    override suspend fun dismissIncoming(callId: CallId, reason: CallTerminationReason) =
        session.dismissIncoming(callId, reason)
}
