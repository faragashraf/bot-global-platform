package com.botglobal.nqrb.calling

import com.botglobal.mobile.platform.calling.CallId
import com.botglobal.mobile.platform.calling.CallTerminationReason
import com.botglobal.mobile.platform.notifications.PushMessage
import com.botglobal.mobile.platform.notifications.PushMessageHandler
import android.util.Log

class NqrbPushMessageHandler(private val runtime: NqrbCallRuntime) : PushMessageHandler {
    override suspend fun onMessage(message: PushMessage) {
        val callId = message.data["callId"]?.takeIf(::isOpaqueCallId)?.let(::CallId) ?: return
        when (message.data["type"]) {
            "incoming_call" -> runCatching { runtime.session.receiveIncoming(callId) }
                .onFailure { Log.w("NqrbCalling", "incoming call revalidation failed type=${it::class.simpleName}") }
            "incoming_call_cancelled", "incoming_call_answered_elsewhere" ->
                runtime.session.dismissIncoming(callId, CallTerminationReason.Cancelled)
            "incoming_call_expired" -> runtime.session.dismissIncoming(callId, CallTerminationReason.Expired)
        }
    }

    private fun isOpaqueCallId(value: String) = runCatching { java.util.UUID.fromString(value) }.isSuccess
}
