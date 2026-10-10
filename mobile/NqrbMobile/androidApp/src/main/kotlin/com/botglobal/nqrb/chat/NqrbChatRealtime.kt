package com.botglobal.nqrb.chat

import com.botglobal.mobile.platform.chat.AndroidChatRealtime
import com.botglobal.mobile.platform.chat.ChatMessageHint
import com.botglobal.mobile.platform.chat.ChatRealtime
import com.botglobal.mobile.platform.chat.ChatRealtimeTransport
import com.botglobal.mobile.platform.identity.SessionVault
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import io.reactivex.rxjava3.core.Single
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NqrbChatRealtime(apiBaseUrl: String, sessions: SessionVault,
    credentialProvider: suspend () -> String? = { sessions.restore()?.accessToken },
) : ChatRealtime by AndroidChatRealtime({
    credentialProvider()?.let { credential ->
        object : ChatRealtimeTransport {
            private val hub = HubConnectionBuilder.create("${apiBaseUrl.trimEnd('/')}/hubs/chat")
                .withAccessTokenProvider(Single.just(credential)).build()
            private var subscribed = false
            override val connected get() = hub.connectionState == HubConnectionState.CONNECTED
            override suspend fun start(onHint: (ChatMessageHint) -> Unit) = withContext(Dispatchers.IO) {
                check(credentialProvider() == credential) { "chat credential generation changed" }
                if (!subscribed) {
                    hub.on("chat_message", { value: HintDto -> onHint(value.toModel()) }, HintDto::class.java)
                    subscribed = true
                }
                hub.start().timeout(5, TimeUnit.SECONDS).blockingAwait()
            }
            override suspend fun stop() = withContext(Dispatchers.IO) {
                hub.stop().timeout(5, TimeUnit.SECONDS).blockingAwait()
            }
        }
    }
})

private data class HintDto(val applicationId: String = "", val conversationId: String = "", val messageId: String = "",
    val sequence: Long = 0, val kind: String = "", val createdAtUtc: String = "") {
    fun toModel() = ChatMessageHint(applicationId, conversationId, messageId, sequence, kind, createdAtUtc)
}
