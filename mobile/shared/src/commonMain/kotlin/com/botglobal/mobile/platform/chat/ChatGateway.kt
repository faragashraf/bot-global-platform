package com.botglobal.mobile.platform.chat

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface ChatGatewayResult<out T> {
    data class Success<T>(val value: T) : ChatGatewayResult<T>
    data object AuthenticationRequired : ChatGatewayResult<Nothing>
    data object Forbidden : ChatGatewayResult<Nothing>
    data object Conflict : ChatGatewayResult<Nothing>
    data object RetryableFailure : ChatGatewayResult<Nothing>
}

fun interface ChatGatewayDiagnostics {
    fun log(message: String)
}

interface ChatGateway {
    /** A request session must never consult a subsequently switched account's credentials. */
    suspend fun snapshot(): ChatGateway = this
    suspend fun context(): ChatGatewayResult<ChatAccountScope>
    suspend fun direct(reference: String): ChatGatewayResult<ChatConversation>
    suspend fun conversations(): ChatGatewayResult<ChatPage<ChatConversation>>
    suspend fun conversations(cursor: String?): ChatGatewayResult<ChatPage<ChatConversation>> = conversations()
    suspend fun messages(conversationId: String, afterSequence: Long): ChatGatewayResult<ChatPage<ChatMessage>>
    suspend fun sendText(message: PendingChatText): ChatGatewayResult<ChatMessage>
    suspend fun sendVoice(conversationId: String, clientMessageId: String, draft: ChatVoiceDraft, bytes: ByteArray): ChatGatewayResult<ChatMessage>
    suspend fun downloadVoice(transferId: String, expectedLength: Long): ChatGatewayResult<ByteArray>
    suspend fun acknowledgeVoice(ack: PendingChatVoiceAck): ChatGatewayResult<Unit>
    suspend fun read(conversationId: String, sequence: Long): ChatGatewayResult<Long>
}

object UnavailableChatGateway : ChatGateway {
    override suspend fun context() = ChatGatewayResult.RetryableFailure
    override suspend fun direct(reference: String) = ChatGatewayResult.RetryableFailure
    override suspend fun conversations() = ChatGatewayResult.RetryableFailure
    override suspend fun messages(conversationId: String, afterSequence: Long) = ChatGatewayResult.RetryableFailure
    override suspend fun sendText(message: PendingChatText) = ChatGatewayResult.RetryableFailure
    override suspend fun sendVoice(conversationId: String, clientMessageId: String, draft: ChatVoiceDraft, bytes: ByteArray) = ChatGatewayResult.RetryableFailure
    override suspend fun downloadVoice(transferId: String, expectedLength: Long) = ChatGatewayResult.RetryableFailure
    override suspend fun acknowledgeVoice(ack: PendingChatVoiceAck) = ChatGatewayResult.RetryableFailure
    override suspend fun read(conversationId: String, sequence: Long) = ChatGatewayResult.RetryableFailure
}

class KtorChatGateway private constructor(
    private val transport: JsonTransport,
    apiBaseUrl: String,
    private val credentials: ChatCredentialProvider,
    private val diagnostics: ChatGatewayDiagnostics,
) : ChatGateway {
    constructor(
        client: HttpClient,
        apiBaseUrl: String,
        credentials: ChatCredentialProvider,
        diagnostics: ChatGatewayDiagnostics = ChatGatewayDiagnostics {},
    ) : this(
        JsonTransport(client.config {
            installOrReplace(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }),
        apiBaseUrl,
        credentials,
        diagnostics,
    )

    private class JsonTransport(val client: HttpClient)
    private val client: HttpClient get() = transport.client
    private val base = apiBaseUrl.trimEnd('/') + "/api/mobile/communications/chat"
    private val apiRoot = apiBaseUrl
    override suspend fun snapshot(): ChatGateway {
        val captured = credentials.current()
        // Never substitute a new account/token into an old generation. Rotation denies it immediately.
        return KtorChatGateway(transport, apiRoot, ChatCredentialProvider { captured?.takeIf { credentials.current() == it } }, diagnostics)
    }

    override suspend fun context() = request<ContextResponse> {
        client.get("$base/context") { authenticate(this); accept(ContentType.Application.Json) }
    }.let { when (it) { is ChatGatewayResult.Success -> ChatGatewayResult.Success(ChatAccountScope(it.value.applicationId, it.value.subjectId)); is ChatGatewayResult.AuthenticationRequired -> it; is ChatGatewayResult.Forbidden -> it; is ChatGatewayResult.Conflict -> it; is ChatGatewayResult.RetryableFailure -> it } }

    override suspend fun direct(reference: String) = request<ChatConversation> {
        client.post("$base/conversations/direct") { authenticate(this); contentType(ContentType.Application.Json); setBody(DirectRequest(reference)) }
    }
    override suspend fun conversations() = conversations(null)
    override suspend fun conversations(cursor: String?) = request<ChatPage<ChatConversation>> {
        client.get("$base/conversations") { authenticate(this); parameter("cursor", cursor); accept(ContentType.Application.Json) }
    }
    override suspend fun messages(conversationId: String, afterSequence: Long) = request<ChatPage<ChatMessage>> {
        client.get("$base/conversations/$conversationId/messages?afterSequence=$afterSequence") { authenticate(this); accept(ContentType.Application.Json) }
    }
    override suspend fun sendText(message: PendingChatText) = request<ChatMessage> {
        client.post("$base/conversations/${message.conversationId}/messages/text") {
            authenticate(this); contentType(ContentType.Application.Json); setBody(TextRequest(message.clientMessageId, message.text))
        }
    }
    override suspend fun sendVoice(conversationId: String, clientMessageId: String, draft: ChatVoiceDraft, bytes: ByteArray) = request<ChatMessage>(
        badRequest = { code ->
            if (code == "chat_voice_decoder_unavailable") ChatGatewayResult.RetryableFailure
            else ChatGatewayResult.Conflict
        },
        failure = { status, code ->
            diagnostics.log("chat voice upload rejected status=$status code=${code ?: "none"} length=${bytes.size} durationMs=${draft.durationMilliseconds}")
        },
    ) {
        require(bytes.size <= MaxVoiceBytes)
        client.post("$base/conversations/$conversationId/messages/voice") {
            authenticate(this); contentType(ContentType("audio", "mp4")); header("X-Client-Message-Id", clientMessageId)
            header("X-Voice-Duration-Ms", draft.durationMilliseconds); setBody(bytes)
        }
    }
    override suspend fun downloadVoice(transferId: String, expectedLength: Long): ChatGatewayResult<ByteArray> {
        if (expectedLength !in 1..MaxVoiceBytes.toLong()) return ChatGatewayResult.Conflict
        return request<ByteArray> { client.get("$base/voice/$transferId") { authenticate(this); accept(ContentType("audio", "mp4")) } }
            .let { result -> if (result is ChatGatewayResult.Success && result.value.size.toLong() != expectedLength) ChatGatewayResult.Conflict else result }
    }
    override suspend fun acknowledgeVoice(ack: PendingChatVoiceAck) = requestUnit {
        client.post("$base/voice/${ack.transferId}/durable-download") {
            authenticate(this); contentType(ContentType.Application.Json); setBody(AckRequest(ack.installationId, ack.sha256, ack.length))
        }
    }
    override suspend fun read(conversationId: String, sequence: Long) = request<ReadResponse> {
        client.put("$base/conversations/$conversationId/read") { authenticate(this); contentType(ContentType.Application.Json); setBody(ReadRequest(sequence)) }
    }.let { when (it) { is ChatGatewayResult.Success -> ChatGatewayResult.Success(it.value.lastReadSequence); is ChatGatewayResult.AuthenticationRequired -> it; is ChatGatewayResult.Forbidden -> it; is ChatGatewayResult.Conflict -> it; is ChatGatewayResult.RetryableFailure -> it } }

    private suspend fun authenticate(builder: io.ktor.client.request.HttpRequestBuilder) {
        val credential = credentials.current() ?: throw MissingCredential
        builder.header("Authorization", credential.authorization)
    }
    private suspend inline fun <reified T> request(
        badRequest: (String?) -> ChatGatewayResult<T> = { ChatGatewayResult.RetryableFailure },
        failure: (status: Int, code: String?) -> Unit = { _, _ -> },
        crossinline call: suspend () -> io.ktor.client.statement.HttpResponse,
    ): ChatGatewayResult<T> = try {
        val response = call()
        val status = response.status.value
        if (status in 200..299) ChatGatewayResult.Success(response.body())
        else {
            val code = response.errorCode()
            failure(status, code)
            when (status) { 400 -> badRequest(code); 401 -> ChatGatewayResult.AuthenticationRequired; 403, 404 -> ChatGatewayResult.Forbidden; 409 -> ChatGatewayResult.Conflict; else -> ChatGatewayResult.RetryableFailure }
        }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: MissingCredential) { ChatGatewayResult.AuthenticationRequired } catch (_: Exception) { ChatGatewayResult.RetryableFailure }
    private suspend fun requestUnit(call: suspend () -> io.ktor.client.statement.HttpResponse): ChatGatewayResult<Unit> = try {
        when (call().status.value) { in 200..299 -> ChatGatewayResult.Success(Unit); 401 -> ChatGatewayResult.AuthenticationRequired; 403, 404 -> ChatGatewayResult.Forbidden; 409 -> ChatGatewayResult.Conflict; else -> ChatGatewayResult.RetryableFailure }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: MissingCredential) { ChatGatewayResult.AuthenticationRequired } catch (_: Exception) { ChatGatewayResult.RetryableFailure }

    @Serializable private data class DirectRequest(val reference: String)
    @Serializable private data class TextRequest(val clientMessageId: String, val text: String)
    @Serializable private data class AckRequest(val installationId: String, val sha256: String, val length: Long)
    @Serializable private data class ReadRequest(val sequence: Long)
    @Serializable private data class ReadResponse(val lastReadSequence: Long)
    @Serializable private data class ContextResponse(val applicationId: String, val subjectId: String)
    private object MissingCredential : Exception()
    private companion object { const val MaxVoiceBytes = 10 * 1024 * 1024 }
}

private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String? =
    try { body<ChatErrorResponse>().code } catch (_: Exception) { null }

@Serializable private data class ChatErrorResponse(val code: String? = null)
