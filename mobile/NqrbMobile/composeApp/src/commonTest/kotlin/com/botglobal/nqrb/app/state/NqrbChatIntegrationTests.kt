package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.chat.*
import com.botglobal.mobile.platform.identity.*
import com.botglobal.mobile.platform.notifications.*
import com.botglobal.nqrb.app.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import com.botglobal.mobile.platform.calling.*
import com.botglobal.mobile.platform.voice.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.botglobal.nqrb.app.ui.NqrbCallTime
import com.botglobal.nqrb.app.ui.chatRecencyLabel
import com.botglobal.nqrb.app.ui.chatThreadRows
import com.botglobal.nqrb.app.ui.nqrbChatStrings

@OptIn(ExperimentalCoroutinesApi::class)
class NqrbChatIntegrationTests {
    private val account = ChatAccountScope("application", "subject")
    private val conversation = ChatConversation("thread", "peer", null, "Peer", 1, 0, updatedAtUtc = "2026-10-07T00:00:00Z")
    private val session = MobileSession("token-0", "2099-01-01T00:00:00Z", "refresh", "2099-02-01T00:00:00Z",
        ApplicationIdentity("membership", "subject", "Synthetic", IdentityKind.Registered, "nqrb"))
    private fun message(id: String, voice: Boolean = false) = ChatMessage("message-$id", "thread", 2, "subject", id,
        if (voice) "voice" else "text", text = if (voice) null else "hello", voiceTransferId = if (voice) "transfer" else null,
        voiceSha256 = if (voice) AacHash else null, voiceLength = if (voice) 1525 else null,
        voiceDurationMilliseconds = if (voice) 2024 else null, createdAtUtc = "2026-10-07T00:00:00Z")

    @Test fun threadRowsAndListShareChronologyWithUniqueRepeatedDateAndCoalescedVoiceKeys() {
        val failed = PendingChatText("thread", "old", "older failure", 1, "2026-10-08T10:00:00Z", ChatTextFailure.Forbidden, afterSequence = 0, beforeSequence = 2)
        val voice = PendingChatVoice("thread", "voice", "draft", 2024, 1525, AacHash, enqueueOrdinal = 2, afterSequence = 0, encodedDurationVerified = true)
        val canonical = message("voice", voice = true)
        val incoming = message("incoming").copy(messageId = "incoming", sequence = 3, senderSubjectId = "peer", text = "latest incoming", createdAtUtc = "2026-10-08T12:00:00Z")
        val snapshot = ChatSnapshot(account = account, conversations = listOf(conversation.copy(lastSequence = 3)),
            messages = mapOf("thread" to listOf(canonical, incoming)), pendingTexts = listOf(failed), pendingVoices = listOf(voice))
        for (language in listOf("en", "ar")) {
            val rows = chatThreadRows(snapshot, "thread", language) { timestamp, tag ->
                val day = timestamp.take(10)
                NqrbCallTime(day, if (tag == "ar") "يوم $day" else "Day $day", "12:00", "$day 12:00")
            }
            assertEquals(rows.size, rows.map { it.key }.distinct().size)
            assertEquals(3, rows.count { it.date != null }) // Oct 8, Oct 7, Oct 8: no duplicate date keys.
            val content = rows.filter { it.date == null }
            assertEquals("old", content.first().text?.clientMessageId)
            assertEquals(canonical, content[1].message)
            assertEquals(voice, content[1].voice) // One row keeps both receipt and recovery ownership.
            assertEquals(1, content.count { it.message?.clientMessageId == "voice" || it.voice?.clientMessageId == "voice" })
            assertEquals("latest incoming", snapshot.conversationActivity.single().latestMessage?.text)
            assertNull(snapshot.conversationActivity.single().pending)
            assertTrue(snapshot.conversationActivity.single().unread)
            val published = snapshot.copy(pendingVoices = emptyList())
            assertEquals(content[1].key, chatThreadRows(published, "thread", language) { _, _ -> NqrbCallTime("day", "Day", "12:00", "Day 12:00") }.single { it.message == canonical }.key)
        }
    }

    @Test fun voiceOwnershipSurvivesBackDuringPublicationDelayedUploadLogoutAndRestart() = runTest {
        for (leaveDuringPublication in listOf(true, false)) {
            val disk = Store(); val voices = Voices(); val api = Api(); val recorder = Recorder(voices)
            val chat = controller(api, disk, voices)
            val app = app(chat, recorder, backgroundScope)
            app.startup(); app.openChat("thread"); runCurrent()
            app.acceptChatStorageDisclosure(); runCurrent()
            recorder.complete(draft())
            disk.publication = CompletableDeferred()
            app.sendChatVoice(); app.sendChatVoice() // Guard must precede launch.
            if (leaveDuringPublication) app.leaveChat()
            runCurrent()
            assertTrue(voices.draftExists)
            assertTrue(app.chatSubmitting.value)
            disk.publication!!.complete(Unit); runCurrent()
            assertEquals(1, disk.value.voiceOutbox.size)
            assertEquals(2024, disk.value.voiceOutbox.single().durationMilliseconds)
            assertNull(app.chatVoiceDraft.value)
            assertEquals(1, api.voiceCalls)
            if (!leaveDuringPublication) app.leaveChat()
            app.cancelChatRecording(); runCurrent()
            assertTrue(voices.draftExists)
            app.logout(); runCurrent()
            assertTrue(voices.draftExists)
            assertEquals(1, disk.value.voiceOutbox.size)
            api.upload.complete(Unit)
            val restartedApi = Api().apply { upload.complete(Unit) }
            val restarted = controller(restartedApi, disk, voices)
            restarted.bind(account)
            assertEquals("permanent", restarted.state.value.localVoiceKeys["transfer"])
            assertFalse(voices.draftExists)
            assertTrue(voices.permanent)
            assertTrue(disk.value.voiceOutbox.isEmpty())
        }
    }

    @Test fun failedPublicationRetainsPreviewAndRapidTextTapsHaveOneDurableIdentity() = runTest {
        val disk = Store(); val voices = Voices(); val api = Api(); val recorder = Recorder(voices)
        val app = app(controller(api, disk, voices), recorder, backgroundScope)
        app.startup(); app.openChat("thread"); runCurrent()
        app.acceptChatStorageDisclosure(); runCurrent(); recorder.complete(draft())
        disk.fail = true
        app.sendChatVoice(); runCurrent()
        assertEquals(draft(), app.chatVoiceDraft.value)
        assertTrue(voices.draftExists)
        assertTrue(disk.value.voiceOutbox.isEmpty())
        assertFalse(app.chatSubmitting.value)
        disk.fail = false
        app.cancelChatRecording(); runCurrent()
        disk.publication = CompletableDeferred()
        var cleared = 0
        app.sendChatText("hello") { cleared++ }; app.sendChatText("hello") { cleared++ }
        runCurrent(); assertEquals(0, cleared)
        disk.publication!!.complete(Unit); runCurrent()
        assertEquals(1, cleared)
        assertEquals(1, disk.value.textOutbox.size)
        assertEquals(1, api.textCalls)
        assertFalse(app.chatSubmitting.value) // No wait for network completion.
    }

    @Test fun missingQueuedVoiceIsTerminalAcrossRestartAndCanBeRemoved() = runTest {
        val disk = Store(); val voices = Voices().apply { draftExists = false }
        disk.value = disk.value.copy(voiceOutbox = listOf(PendingChatVoice("thread", "lost", "draft", 1000, 3)))
        val api = Api(); val first = controller(api, disk, voices); first.bind(account)
        assertEquals(ChatVoiceFailure.MissingOrCorrupt, disk.value.voiceOutbox.single().failure)
        val restarted = controller(api, disk, voices); restarted.bind(account); restarted.flush()
        assertEquals(0, api.voiceCalls)
        assertEquals(ChatVoiceFailure.MissingOrCorrupt, restarted.state.value.pendingVoices.single().failure)
        restarted.removeFailedVoice("lost")
        assertTrue(disk.value.voiceOutbox.isEmpty())
    }

    @Test fun coldOfflineNqrbApiOpensLocalThreadWithoutCallsOrPushThenRebindsOnProfileRefresh() = runTest {
        val vault = Vault(session)
        var offline = true; var token = 0; var profileFirst = true
        val identityApi = NqrbIdentityApi(HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
            if (offline) throw io.ktor.utils.io.errors.IOException("synthetic offline")
            if (request.url.encodedPath.endsWith("/profile")) {
                if (profileFirst) { profileFirst = false; respond("", HttpStatusCode.Unauthorized) }
                else respond("""{"displayName":"Synthetic","email":"synthetic@example.test"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                token++
                respond("""{"accessToken":"token-$token","accessExpiresAtUtc":"2099-01-01T00:00:00Z","refreshToken":"refresh","refreshExpiresAtUtc":"2099-02-01T00:00:00Z","identity":{"membershipId":"membership","subjectId":"subject","displayName":"Synthetic","isGuest":false,"applicationKey":"nqrb"}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        } })), "https://synthetic.invalid", vault)
        val disk = Store(); val voices = Voices().apply { permanent = true }
        disk.value = disk.value.copy(messages = mapOf("thread" to listOf(message("saved", true))), localVoiceKeys = mapOf("transfer" to "permanent"),
            voiceOutbox = listOf(PendingChatVoice("thread", "queued-voice", "draft", 2179, 1525)),
            voiceAcks = listOf(PendingChatVoiceAck("transfer", "installation", AacHash, 1525)),
            readReceipts = listOf(PendingChatReadReceipt("thread", 1)))
        val api = Api { (identityApi.availability.value as? NqrbSessionAvailability.Online)?.session?.accessToken }
        val realtime = Realtime { (identityApi.availability.value as? NqrbSessionAvailability.Online)?.session?.accessToken }
        val chat = controller(api, disk, voices, realtime)
        val push = Push(); val player = Player()
        val hostIdentity = FederatedIdentityController(UnavailableFederatedCredentialProvider, identityApi)
        val app = NqrbAppState(identity = hostIdentity, chat = chat, chatVoicePlayer = player,
            accountProfile = identityApi, push = push, callActionScope = backgroundScope)
        app.startup(); runCurrent()
        assertIs<FederatedAuthenticationState.AuthenticationError>(hostIdentity.state.value)
        assertEquals(NqrbDestination.Chats, app.navigation.current)
        assertEquals(0, push.activations)
        assertEquals(0, realtime.connected.size)
        app.openChat("thread"); runCurrent()
        assertEquals(NqrbDestination.ChatThread, app.navigation.current)
        app.playChatVoice(chat.state.value.messages.getValue("thread").single()); runCurrent()
        assertEquals("permanent", player.lastKey)
        app.sendChatText("queued offline"); runCurrent()
        assertEquals(1, disk.value.textOutbox.size)
        assertEquals(0, api.textCalls)

        offline = false; app.refreshChat()
        identityApi.availability.first { it is NqrbSessionAvailability.Online }
        runCurrent()
        assertTrue(realtime.connected.contains("token-1"))
        assertEquals("token-1", api.captured.last())
        hostIdentity.restore(); runCurrent()
        val previousHost = assertIs<FederatedAuthenticationState.SignedIn>(hostIdentity.state.value)
        assertEquals("token-2", previousHost.session.accessToken)
        // API.load itself rotates authority while FederatedIdentityController retains its prior MobileSession.
        identityApi.load(session); runCurrent()
        assertEquals(previousHost, hostIdentity.state.value)
        assertEquals("token-3", api.captured.last())
        assertEquals("token-3", realtime.connected.last())
        assertTrue(realtime.disconnects >= 2)
        assertEquals(1, disk.value.textOutbox.size) // Delayed requests cannot erase durable work.
        assertEquals(0, push.activations)
        api.upload.complete(Unit); runCurrent()
        assertTrue(disk.value.textOutbox.isEmpty())
        assertTrue(disk.value.voiceOutbox.isEmpty())
        assertTrue(disk.value.voiceAcks.isEmpty())
        assertTrue(disk.value.readReceipts.isEmpty())
        assertEquals<List<String?>>(listOf("token-3"), api.ackTokens)
        assertEquals<List<String?>>(listOf("token-3"), api.readTokens)
        app.logout(); runCurrent()
        assertNull(chat.state.value.account)
    }

    @Test fun deferredVoiceCannotAutoplayAfterEveryPlaybackIntentInvalidation() = runTest {
        for (event in listOf("back", "background", "stop", "newer", "account", "same-account", "call", "thread")) {
            val disk = Store().apply { value = value.copy(localVoiceKeys = mapOf("second-transfer" to "second-local")) }
            val voices = Voices().apply { permanent = true }; val player = Player()
            val api = Api().apply { downloadWait = CompletableDeferred() }
            val signals = Signals(); val calls = CallSessionController(backgroundScope, signals, SilentVoice())
            val chat = controller(api, disk, voices)
            val app = app(chat, Recorder(voices), backgroundScope, player, calls)
            app.startup(); app.openChat("thread"); runCurrent()
            val remote = message("remote", true).copy(senderSubjectId = "peer")
            app.playChatVoice(remote); app.playChatVoice(remote); runCurrent()
            assertEquals(1, api.downloadCalls, event)
            assertEquals(ChatVoiceLoadState.Loading, chat.state.value.voiceLoads["transfer"], event)
            when (event) {
                "back" -> { app.leaveChat(); app.openChat("thread") }
                "background" -> { app.onBackground(); app.onForeground() }
                "stop" -> app.stopChatPlayback()
                "newer" -> app.playChatVoice(remote.copy(voiceTransferId = "second-transfer"))
                "account" -> chat.bind(ChatAccountScope("application", "other"))
                "same-account" -> { chat.bind(null); chat.bind(account); chat.selectConversation("thread") }
                "call" -> signals.events.emit(CallSignalingEvent.IncomingOffered(CallId("synthetic-call"), "nqrb", CallParticipant("peer", "Peer")))
                "thread" -> app.openChat("another-thread")
            }
            runCurrent()
            api.downloadWait!!.complete(Unit); runCurrent()
            assertEquals(if (event == "newer") listOf("second-local") else emptyList(), player.played, event)
            assertTrue(chat.state.value.voiceLoads["transfer"] != ChatVoiceLoadState.Loading, event)
        }
    }

    @Test fun validRemoteCompletionPlaysOnceAndRejectedLocalVoiceRemainsPlayable() = runTest {
        val disk = Store(); val voices = Voices(); val player = Player(); val api = Api().apply { downloadWait = CompletableDeferred() }
        val chat = controller(api, disk, voices); val app = app(chat, Recorder(voices), backgroundScope, player)
        app.startup(); app.openChat("thread"); runCurrent()
        val remote = message("remote", true).copy(senderSubjectId = "peer")
        app.playChatVoice(remote); app.playChatVoice(remote); runCurrent()
        assertEquals(1, api.downloadCalls); assertTrue(player.played.isEmpty())
        api.downloadWait!!.complete(Unit); runCurrent()
        assertEquals(listOf("permanent"), player.played)
        assertEquals(ChatVoiceLoadState.Ready, chat.state.value.voiceLoads["transfer"])
        disk.value = disk.value.copy(voiceOutbox = listOf(PendingChatVoice("thread", "rejected", "draft", 2179, 1525, failure = ChatVoiceFailure.Rejected)))
        chat.bind(account); chat.selectConversation("thread")
        app.playPendingChatVoice(chat.state.value.pendingVoices.single()); runCurrent()
        assertEquals("draft", player.played.last())
        assertTrue(voices.draftExists)
        assertEquals(ChatVoiceFailure.Rejected, chat.state.value.pendingVoices.single().failure)
    }

    @Test fun rejectedLocalVoiceCanBeRetriedFromSavedDraft() = runTest {
        val disk = Store(); val voices = Voices(); val api = Api().apply { upload.complete(Unit) }
        disk.value = disk.value.copy(voiceOutbox = listOf(PendingChatVoice("thread", "retry-voice", "draft",
            2024, 1525, AacHash, failure = ChatVoiceFailure.Rejected, encodedDurationVerified = true)))
        val chat = controller(api, disk, voices)
        chat.bind(account); chat.selectConversation("thread")

        chat.retryFailedVoice("retry-voice")

        assertEquals(1, api.voiceCalls)
        assertTrue(disk.value.voiceOutbox.isEmpty())
        assertTrue(voices.permanent)
    }

    @Test fun foregroundThreadRefreshKeepsVisibleReadReceiptsMovingWithoutSocketHint() = runTest {
        val disk = Store(); val voices = Voices(); val api = Api().apply { upload.complete(Unit) }
        val app = app(controller(api, disk, voices), Recorder(voices), backgroundScope)
        app.startup(); runCurrent()

        app.onForeground(); app.openChat("thread"); runCurrent()
        val first = api.messageCalls
        advanceTimeBy(3_500); runCurrent()
        assertTrue(api.messageCalls > first)

        app.onBackground(); runCurrent()
        val stopped = api.messageCalls
        advanceTimeBy(4_000); runCurrent()
        assertEquals(stopped, api.messageCalls)
    }

    @Test fun remoteVoiceFailureIsKeyedInlineAndRetryMakesOneNewTransfer() = runTest {
        val disk = Store(); val voices = Voices(); val player = Player(); val api = Api()
        val chat = controller(api, disk, voices); val app = app(chat, Recorder(voices), backgroundScope, player)
        app.startup(); app.openChat("thread"); runCurrent()
        val remote = message("remote", true).copy(senderSubjectId = "peer")
        api.downloadFailure = ChatGatewayResult.RetryableFailure
        app.playChatVoice(remote); runCurrent()
        assertEquals(ChatVoiceLoadState.RetryableFailure, chat.state.value.voiceLoads["transfer"])
        assertEquals(NqrbChatRecordingState.Idle, app.chatRecordingState.value)
        api.downloadFailure = ChatGatewayResult.Forbidden
        app.playChatVoice(remote); runCurrent()
        assertEquals(ChatVoiceLoadState.Unavailable, chat.state.value.voiceLoads["transfer"])
        assertTrue(player.played.isEmpty()); assertEquals(2, api.downloadCalls)
        assertTrue(chat.state.value.localVoiceKeys.isEmpty())
    }

    @Test fun recencyAndFailureCopyDistinguishTodayOlderAndIntactRejectedAudio() {
        assertEquals("10:00 AM", chatRecencyLabel(NqrbCallTime("today", "Today", "10:00 AM", "")))
        assertEquals("أمس", chatRecencyLabel(NqrbCallTime("yesterday", "أمس", "١٠:٠٠ ص", "")))
        assertEquals("1 October 2026", chatRecencyLabel(NqrbCallTime("old", "1 October 2026", "10:00 AM", "")))
        assertEquals("١٠:٠٠ ص", chatRecencyLabel(NqrbCallTime("today", "اليوم", "١٠:٠٠ ص", "")))
        for (language in listOf("ar", "en")) {
            val strings = nqrbChatStrings(language)
            assertNotEquals(strings.localUnavailable, strings.voiceRejected)
            assertNotEquals(strings.textFailure(ChatTextFailure.Forbidden), strings.textFailure(ChatTextFailure.Conflict))
        }
    }

    private fun draft() = ChatVoiceDraft("draft", 2179, 1525, owner = account, conversationId = "thread", sha256 = AacHash)
    private fun app(chat: ChatController, recorder: Recorder, scope: CoroutineScope,
        player: ChatVoicePlayer = UnavailableChatVoicePlayer,
        calls: CallSessionController = CallSessionController(scope, Signals(), SilentVoice())) = NqrbAppState(
        identity = FederatedIdentityController(UnavailableFederatedCredentialProvider, object : FederatedIdentityGateway {
            override suspend fun restore() = session
            override suspend fun authenticate(credential: FederatedCredential) = FederatedSignInResult.Rejected
            override suspend fun logout() = Unit
        }), chat = chat, chatVoiceRecorder = recorder, chatVoicePlayer = player, calling = calls, push = Push(), callActionScope = scope)
    private fun controller(api: Api, disk: Store, voices: Voices, realtime: ChatRealtime = UnavailableChatRealtime) =
        ChatController(api, disk, voices, realtime, authenticatedIdentityKey = { session.chatIdentityKey() })

    private inner class Store : ChatDurableStore {
        var value = ChatDurableState(conversations = listOf(conversation))
        var publication: CompletableDeferred<Unit>? = null
        var fail = false
        override suspend fun rememberedScope(identityKey: String) = account.takeIf { identityKey == session.chatIdentityKey() }
        override suspend fun load(scope: ChatAccountScope) = if (scope == account) value else ChatDurableState()
        override suspend fun save(scope: ChatAccountScope, state: ChatDurableState) {
            publication?.await(); check(!fail) { "synthetic disk failure" }; value = state
        }
        override suspend fun deleteAccount(scope: ChatAccountScope) { value = ChatDurableState() }
    }
    private class Voices : ChatVoiceStore by UnavailableChatVoiceStore {
        var draftExists = true; var permanent = false
        override suspend fun readDraft(draft: ChatVoiceDraft) = if (draftExists && draft.length == 1525L &&
            (draft.sha256 == null || draft.sha256 == AacHash)) aacBytes() else null
        override suspend fun inspectDraft(draft: ChatVoiceDraft) = readDraft(draft)?.let { ChatDraftRead.Available(it, AacHash) } ?: ChatDraftRead.MissingOrCorrupt
        override suspend fun promoteDraft(scope: ChatAccountScope, transferId: String, expectedSha256: String, expectedLength: Long, draft: ChatVoiceDraft): StoredChatVoice? {
            if (readDraft(draft) == null || draft.owner != scope || expectedLength != 1525L || expectedSha256 != AacHash) return null
            permanent = true; return StoredChatVoice("permanent", 1525, AacHash)
        }
        override suspend fun deleteDraft(draft: ChatVoiceDraft) { draftExists = false }
        override suspend fun read(scope: ChatAccountScope, localKey: String) = if (permanent) aacBytes() else null
        override suspend fun storeVerified(scope: ChatAccountScope, transferId: String, expectedSha256: String, expectedLength: Long, content: ByteArray): StoredChatVoice {
            assertContentEquals(aacBytes(), content)
            assertEquals(AacHash, expectedSha256); assertEquals(1525L, expectedLength)
            permanent = true; return StoredChatVoice("permanent", 1525, AacHash)
        }
    }
    private class Recorder(private val voices: Voices) : ChatVoiceRecorder by UnavailableChatVoiceRecorder {
        override val available = true
        override suspend fun start(scope: ChatAccountScope, conversationId: String) = true
        private var completion: (ChatVoiceDraft?) -> Unit = {}
        override fun onAutomaticStop(callback: (ChatVoiceDraft?) -> Unit) { completion = callback }
        fun complete(draft: ChatVoiceDraft) = completion(draft)
        override suspend fun discard(draft: ChatVoiceDraft) { voices.deleteDraft(draft) }
    }
    private class Player : ChatVoicePlayer by UnavailableChatVoicePlayer {
        var lastKey: String? = null
        val played = mutableListOf<String>()
        override suspend fun play(scope: ChatAccountScope, localKey: String): Boolean { lastKey = localKey; played += localKey; return true }
        override suspend fun playDraft(draft: ChatVoiceDraft): Boolean { played += draft.token; return true }
    }
    private class Signals : CallSignaling {
        override val events = MutableSharedFlow<CallSignalingEvent>(extraBufferCapacity = 1)
        override suspend fun startOutgoing(request: OutgoingCallRequest): StartedCall = error("not used")
        override suspend fun end(callId: CallId, reason: CallTerminationReason) = Unit
    }
    private class SilentVoice : VoiceRoomController {
        override val snapshot = MutableStateFlow(VoiceRoomSnapshot())
        override suspend fun join(roomId: String) = Unit
        override suspend fun leave() = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun signalingInterrupted() = Unit
        override suspend fun signalingRecovered() = Unit
    }
    private class Push : PushRegistrationLifecycle {
        var activations = 0
        override suspend fun activate() { activations++ }
        override suspend fun deactivate() = PushRegistrationOutcome.Unregistered
        override suspend fun clearLocalState() = Unit
    }
    private class Vault(var value: MobileSession?) : SessionVault {
        override suspend fun restore() = value
        override suspend fun save(session: MobileSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private class Realtime(private val credential: () -> String?) : ChatRealtime {
        val connected = mutableListOf<String>(); var disconnects = 0
        override suspend fun connect(onHint: suspend (ChatMessageHint) -> Unit) { credential()?.let(connected::add) }
        override suspend fun disconnect() { disconnects++ }
    }
    private inner class Api(private val credential: (() -> String?)? = null) : ChatGateway by UnavailableChatGateway {
        val captured = mutableListOf<String?>(); var textCalls = 0; var voiceCalls = 0
        var conversationCalls = 0; var messageCalls = 0
        val ackTokens = mutableListOf<String?>(); val readTokens = mutableListOf<String?>()
        val upload = CompletableDeferred<Unit>()
        var downloadWait: CompletableDeferred<Unit>? = null
        var downloadCalls = 0
        var downloadFailure: ChatGatewayResult<ByteArray>? = null
        override suspend fun downloadVoice(transferId: String, expectedLength: Long): ChatGatewayResult<ByteArray> {
            downloadCalls++; downloadWait?.await()
            return downloadFailure ?: ChatGatewayResult.Success(aacBytes())
        }
        override suspend fun snapshot(): ChatGateway {
            val token = credential?.invoke(); captured += token
            return object : ChatGateway by this@Api {
                override suspend fun context() = if (credential != null && token != credential.invoke()) ChatGatewayResult.AuthenticationRequired else ChatGatewayResult.Success(account)
                override suspend fun sendText(message: PendingChatText): ChatGatewayResult<ChatMessage> {
                    if (credential != null && token != credential.invoke()) return ChatGatewayResult.AuthenticationRequired
                    return this@Api.sendText(message)
                }
                override suspend fun acknowledgeVoice(ack: PendingChatVoiceAck): ChatGatewayResult<Unit> {
                    if (credential != null && token != credential.invoke()) return ChatGatewayResult.AuthenticationRequired
                    ackTokens += token; return ChatGatewayResult.Success(Unit)
                }
                override suspend fun read(conversationId: String, sequence: Long): ChatGatewayResult<Long> {
                    if (credential != null && token != credential.invoke()) return ChatGatewayResult.AuthenticationRequired
                    readTokens += token; return ChatGatewayResult.Success(sequence)
                }
            }
        }
        override suspend fun context() = ChatGatewayResult.Success(account)
        override suspend fun conversations(): ChatGatewayResult<ChatPage<ChatConversation>> {
            conversationCalls++; return ChatGatewayResult.Success(ChatPage(listOf(conversation), false))
        }
        override suspend fun messages(conversationId: String, afterSequence: Long): ChatGatewayResult<ChatPage<ChatMessage>> {
            messageCalls++; return ChatGatewayResult.Success(ChatPage<ChatMessage>(emptyList(), false))
        }
        override suspend fun sendText(message: PendingChatText): ChatGatewayResult<ChatMessage> { textCalls++; upload.await(); return ChatGatewayResult.Success(message(message.clientMessageId).copy(text = message.text)) }
        override suspend fun sendVoice(conversationId: String, clientMessageId: String, draft: ChatVoiceDraft, bytes: ByteArray): ChatGatewayResult<ChatMessage> {
            assertContentEquals(aacBytes(), bytes)
            assertEquals(2024, draft.durationMilliseconds) // Canonical fixture oracle does not echo the timer.
            voiceCalls++; upload.await(); return ChatGatewayResult.Success(message(clientMessageId, true))
        }
    }

    private companion object {
        const val AacHash = "ce66f2e7c1e9c8d940ed0b56b476f1900ba6f0cfacdb71b0cd1b43f5ddc26411"
        // Exact existing backend synthetic fixture; no microphone recording or new media file.
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        fun aacBytes() = kotlin.io.encoding.Base64.decode(
            "AAAAHGZ0eXBNNEEgAAACAE00QSBpc29taXNvMgAAAAhmcmVlAAABd21kYXTcAExhdmM2My4xLjEwMQACMEAOARggBwEYIAcBGCAH" +
            "ARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARgg" +
            "BwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEY" +
            "IAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcB" +
            "GCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAH" +
            "ARggBwEYIAcBGCAHARggBwEYIAcBGCAHARggBwEYIAcBGCAHAAAEWm1vb3YAAABsbXZoZAAAAAAAAAAAAAAAAAAArEQAAViIAAEA" +
            "AAEAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAIAAAOFdHJhawAAAFx0a2hkAAAAAwAAAAAAAAAAAAAAAQAAAAAAAViIAAAAAAAAAAAAAAABAQAAAAABAAAAAAAAAAAAAAAAAAAA" +
            "AQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAJGVkdHMAAAAcZWxzdAAAAAAAAAABAAFYiAAABAAAAQAAAAAC/W1kaWEAAAAg" +
            "bWRoZAAAAAAAAAAAAAAAAAAArEQAAVyIVcQAAAAAAC1oZGxyAAAAAAAAAABzb3VuAAAAAAAAAAAAAAAAU291bmRIYW5kbGVyAAAA" +
            "AqhtaW5mAAAAEHNtaGQAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAmxzdGJsAAAAanN0c2QA" +
            "AAAAAAAAAQAAAFptcDRhAAAAAAAAAAEAAAAAAAAAAAABABAAAAAArEQAAAAAADZlc2RzAAAAAAOAgIAlAAEABICAgBdAFQAAAAAA" +
            "+gAAAAWrBYCAgAUSCFblAAaAgIABAgAAACBzdHRzAAAAAAAAAAIAAABXAAAEAAAAAAEAAACIAAAAHHN0c2MAAAAAAAAAAQAAAAEA" +
            "AABYAAAAAQAAAXRzdHN6AAAAAAAAAAAAAABYAAAAEwAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAE" +
            "AAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAA" +
            "BAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAA" +
            "AAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQA" +
            "AAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAE" +
            "AAAABAAAABRzdGNvAAAAAAAAAAEAAAAsAAAAGnNncGQBAAAAcm9sbAAAAAIAAAAB//8AAAAcc2JncAAAAAByb2xsAAAAAQAAAFgA" +
            "AAABAAAAYXVkdGEAAABZbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAsaWxzdAAAACSpdG9vAAAA" +
            "HGRhdGEAAAABAAAAAExhdmY2My4xLjEwMQ==")
    }
}
