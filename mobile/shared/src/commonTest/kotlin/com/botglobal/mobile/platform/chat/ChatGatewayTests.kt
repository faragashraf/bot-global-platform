package com.botglobal.mobile.platform.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ChatGatewayTests {
    @Test fun rawClientDecodesContextWithServerOnlyFields() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/mobile/communications/chat/context", request.url.encodedPath)
            assertEquals("Bearer synthetic-context", request.headers[HttpHeaders.Authorization])
            respond(
                """{"applicationId":"10000000-0000-0000-0000-000000000001","subjectId":"nqrb:registered:self","mechanism":"ApplicationSession","installationId":null}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        })
        val gateway = KtorChatGateway(client, "https://synthetic.invalid", credential("synthetic-context"))

        val scope = assertIs<ChatGatewayResult.Success<ChatAccountScope>>(gateway.context()).value

        assertEquals(ChatAccountScope("10000000-0000-0000-0000-000000000001", "nqrb:registered:self"), scope)
        client.close()
    }

    @Test fun rawClientSerializesDirectBodyAndDecodesActualConversationContract() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/mobile/communications/chat/conversations/direct", request.url.encodedPath)
            assertEquals("Bearer synthetic-direct", request.headers[HttpHeaders.Authorization])
            assertEquals(ContentType.Application.Json, request.body.contentType)
            assertEquals("""{"reference":"synthetic-contact"}""", requestBody(request))
            respond(conversationJson("30000000-0000-0000-0000-000000000003", "Synthetic Direct"), HttpStatusCode.OK, jsonHeaders())
        })
        val gateway = KtorChatGateway(client, "https://synthetic.invalid/", credential("synthetic-direct"))

        val conversation = assertIs<ChatGatewayResult.Success<ChatConversation>>(
            gateway.direct("synthetic-contact"),
        ).value

        assertEquals(1, requests)
        assertEquals(expectedConversation("30000000-0000-0000-0000-000000000003", "Synthetic Direct"), conversation)
        client.close()
    }

    @Test fun rawClientDecodesPagedConversationListContract() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/mobile/communications/chat/conversations", request.url.encodedPath)
            assertEquals("cursor-synthetic", request.url.parameters["cursor"])
            respond(
                """{"items":[${conversationJson("40000000-0000-0000-0000-000000000004", "Synthetic List")}],"hasMore":true,"nextCursor":null,"nextConversationCursor":"cursor-next"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        })
        val gateway = KtorChatGateway(client, "https://synthetic.invalid", credential("synthetic-list"))

        val page = assertIs<ChatGatewayResult.Success<ChatPage<ChatConversation>>>(
            gateway.conversations("cursor-synthetic"),
        ).value

        assertEquals(listOf(expectedConversation("40000000-0000-0000-0000-000000000004", "Synthetic List")), page.items)
        assertEquals(true, page.hasMore)
        assertEquals("cursor-next", page.nextConversationCursor)
        client.close()
    }

    @Test fun configuredClientRemainsCompatibleThroughCapturedSnapshot() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals("Bearer synthetic-captured", request.headers[HttpHeaders.Authorization])
            respond(
                """{"applicationId":"10000000-0000-0000-0000-000000000002","subjectId":"nqrb:registered:captured","mechanism":"ApplicationSession","installationId":"20000000-0000-0000-0000-000000000002"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }) {
            install(ContentNegotiation) { json() }
        }
        val captured = KtorChatGateway(
            client,
            "https://synthetic.invalid",
            credential("synthetic-captured"),
        ).snapshot()

        val scope = assertIs<ChatGatewayResult.Success<ChatAccountScope>>(captured.context()).value

        assertEquals(1, requests)
        assertEquals(ChatAccountScope("10000000-0000-0000-0000-000000000002", "nqrb:registered:captured"), scope)
        client.close()
    }

    @Test fun tokenRotationDeniesOldSnapshotAndNewSnapshotUsesOnlyNewCredential() = runTest {
        val sent = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request -> sent += request.headers["Authorization"]; respond("", HttpStatusCode.Forbidden) }) {
            install(ContentNegotiation) { json() }
        }
        var token = ChatCredential("Bearer synthetic-generation-1")
        val gateway = KtorChatGateway(client, "https://synthetic.invalid", ChatCredentialProvider { token })
        val old = gateway.snapshot()
        token = ChatCredential("Bearer synthetic-generation-2")
        val current = gateway.snapshot()
        assertSame(ChatGatewayResult.AuthenticationRequired, old.sendText(PendingChatText("thread", "durable-key", "same message")))
        assertSame(ChatGatewayResult.Forbidden, current.sendText(PendingChatText("thread", "durable-key", "same message")))
        assertEquals<List<String?>>(listOf("Bearer synthetic-generation-2"), sent)
        client.close()
    }
    @Test fun capturedRequestCannotAcquireAnotherAccountsAuthorization() = runTest {
        val sent = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request -> sent += request.headers["Authorization"]; respond("", HttpStatusCode.Forbidden) }) {
            install(ContentNegotiation) { json() }
        }
        var current: ChatCredential? = ChatCredential("Bearer synthetic-account-a")
        val live = KtorChatGateway(client, "https://synthetic.invalid", ChatCredentialProvider { current })
        val captured = live.snapshot()
        current = ChatCredential("Bearer synthetic-account-b")
        assertSame(ChatGatewayResult.AuthenticationRequired, captured.sendText(PendingChatText("conversation", "key", "synthetic")))
        assertEquals<List<String?>>(emptyList(), sent)
        client.close()
    }

    @Test fun missingCapturedCredentialCannotAdoptLaterLogin() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond("", HttpStatusCode.Forbidden) })
        var current: ChatCredential? = null
        val captured = KtorChatGateway(client, "https://synthetic.invalid", ChatCredentialProvider { current }).snapshot()
        current = ChatCredential("Bearer synthetic-later-login")
        assertSame(ChatGatewayResult.AuthenticationRequired, captured.sendText(PendingChatText("conversation", "key", "synthetic")))
        assertEquals(0, requests)
        client.close()
    }

    private fun credential(token: String) = ChatCredentialProvider { ChatCredential("Bearer $token") }

    private fun expectedConversation(conversationId: String, displayName: String) = ChatConversation(
        conversationId = conversationId,
        counterpartSubjectId = "nqrb:registered:remote",
        counterpartReference = "synthetic-contact",
        counterpartDisplayName = displayName,
        lastSequence = 8,
        lastReadSequence = 5,
        counterpartLastReadSequence = 3,
        updatedAtUtc = "2099-01-02T03:04:05Z",
    )

    private fun conversationJson(conversationId: String, displayName: String) =
        """{"conversationId":"$conversationId","counterpartSubjectId":"nqrb:registered:remote","counterpartReference":"synthetic-contact","counterpartDisplayName":"$displayName","lastSequence":8,"lastReadSequence":5,"counterpartLastReadSequence":3,"updatedAtUtc":"2099-01-02T03:04:05Z"}"""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun requestBody(request: HttpRequestData): String = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> body.toString()
    }
}
