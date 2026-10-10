package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.calling.CallableParticipant
import com.botglobal.mobile.platform.calling.CallingDirectory
import com.botglobal.mobile.platform.calling.CallingDirectoryController
import com.botglobal.mobile.platform.calling.CallingDirectorySnapshot
import com.botglobal.mobile.platform.calling.CallingDirectoryStatus
import com.botglobal.mobile.platform.calling.CallingParticipantAvailability
import com.botglobal.mobile.platform.chat.ChatAccountScope
import com.botglobal.mobile.platform.chat.ChatConversation
import com.botglobal.mobile.platform.chat.ChatController
import com.botglobal.mobile.platform.chat.ChatGateway
import com.botglobal.mobile.platform.chat.ChatGatewayResult
import com.botglobal.mobile.platform.chat.ChatPage
import com.botglobal.mobile.platform.chat.UnavailableChatGateway
import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.FederatedCredential
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.mobile.platform.identity.FederatedIdentityGateway
import com.botglobal.mobile.platform.identity.FederatedSignInResult
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.UnavailableFederatedCredentialProvider
import com.botglobal.mobile.platform.notifications.PushRegistrationLifecycle
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
import com.botglobal.nqrb.app.data.NqrbContactBookGateway
import com.botglobal.nqrb.app.data.UnavailableNqrbContactBookGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NqrbChatEntryTests {
    @Test
    fun callResolutionUsesMembershipReferenceAndFailsClosed() {
        val conversation = conversation("target", counterpartSubjectId = "opaque-subject")
        val target = CallableParticipant("target", "Target", CallingParticipantAvailability.Reachable)
        val directory = CallingDirectorySnapshot(
            status = CallingDirectoryStatus.Ready,
            participants = listOf(
                target,
                CallableParticipant("opaque-subject", "Wrong identity", CallingParticipantAvailability.Online),
            ),
        )

        assertEquals(target, resolveNqrbChatCallTarget(conversation, "self", true, directory, emptySet()).participant)
        assertEquals(NqrbChatCallUnavailableReason.Self,
            resolveNqrbChatCallTarget(conversation("self"), "self", true, directory, emptySet()).unavailableReason)
        assertEquals(NqrbChatCallUnavailableReason.Blocked,
            resolveNqrbChatCallTarget(conversation, "self", true, directory, setOf("target")).unavailableReason)
        assertEquals(NqrbChatCallUnavailableReason.Unknown,
            resolveNqrbChatCallTarget(conversation.copy(counterpartReference = null), "self", true, directory, emptySet()).unavailableReason)
        assertEquals(NqrbChatCallUnavailableReason.Unknown,
            resolveNqrbChatCallTarget(conversation("missing", counterpartSubjectId = "opaque-subject"), "self", true, directory, emptySet()).unavailableReason)
        assertEquals(NqrbChatCallUnavailableReason.Offline,
            resolveNqrbChatCallTarget(conversation, "self", true,
                directory.copy(participants = listOf(target.copy(availability = CallingParticipantAvailability.Offline))), emptySet()).unavailableReason)
        assertEquals(NqrbChatCallUnavailableReason.StaleAccount,
            resolveNqrbChatCallTarget(conversation, "self", false, directory, emptySet()).unavailableReason)
        assertEquals(NqrbChatCallUnavailableReason.SignedOut,
            resolveNqrbChatCallTarget(conversation, null, true, directory, emptySet()).unavailableReason)
    }

    @Test
    fun appStateCallResolutionRequiresCurrentSubjectAndUsesMembershipReference() = runTest {
        val conversation = conversation("a", counterpartSubjectId = "b")
        val valid = app(
            gateway = DirectGateway(
                account = ChatAccountScope("nqrb", " opaque-self-subject "),
                initialConversations = listOf(conversation),
            ),
            scope = backgroundScope,
        )
        valid.startup()
        runCurrent()
        valid.chat.selectConversation(conversation.conversationId)

        assertEquals("a", valid.resolveChatCallTarget(conversation.conversationId).participant?.membershipId)

        val foreignAccount = app(
            gateway = DirectGateway(
                account = ChatAccountScope("nqrb", "foreign-subject"),
                initialConversations = listOf(conversation),
            ),
            scope = backgroundScope,
        )
        foreignAccount.startup()
        runCurrent()
        foreignAccount.chat.selectConversation(conversation.conversationId)

        assertEquals(
            NqrbChatCallUnavailableReason.StaleAccount,
            foreignAccount.resolveChatCallTarget(conversation.conversationId).unavailableReason,
        )
    }

    @Test
    fun rapidEntriesOpenOnlyLatestMembershipAndCoalesceRepeatedTap() = runTest {
        val gateway = DirectGateway()
        val app = app(gateway, backgroundScope)
        app.startup()
        runCurrent()

        app.openChatWith("a")
        runCurrent()
        assertIs<NqrbDirectChatEntryState.Opening>(app.directChatEntryState.value)
        app.openChatWith("a")
        app.openChatWith("b")
        app.openChatWith("b")
        runCurrent()
        assertEquals(listOf("a", "b"), gateway.directCalls)

        gateway.complete("a")
        runCurrent()
        assertEquals(NqrbDestination.Home, app.navigation.current)
        gateway.complete("b")
        runCurrent()

        assertEquals(NqrbDestination.ChatThread, app.navigation.current)
        assertEquals("thread-b", app.chat.state.value.selectedConversationId)
        assertEquals(NqrbDirectChatEntryState.Idle, app.directChatEntryState.value)
    }

    @Test
    fun peopleBackCancelsPendingEntryBeforeUncooperativeLateCompletion() = runTest {
        val gateway = DirectGateway(uncooperativeReferences = setOf("a"))
        val app = app(gateway, backgroundScope)
        app.startup()
        runCurrent()
        app.selectTopLevel(NqrbDestination.People)
        runCurrent()
        app.openChatWith("a")
        runCurrent()

        assertEquals(NqrbDestination.People, app.navigation.current)
        assertIs<NqrbDirectChatEntryState.Opening>(app.directChatEntryState.value)
        assertTrue(app.handleSystemBack())
        assertEquals(NqrbDestination.Home, app.navigation.current)
        assertEquals(NqrbDirectChatEntryState.Idle, app.directChatEntryState.value)

        gateway.complete("a")
        runCurrent()

        assertEquals(listOf("a"), gateway.returnedDirectCalls)
        assertEquals(NqrbDestination.Home, app.navigation.current)
        assertNull(app.chat.state.value.selectedConversationId)
        assertEquals(NqrbDirectChatEntryState.Idle, app.directChatEntryState.value)
    }

    @Test
    fun rootHomeBackConsumesPendingEntryCancellation() = runTest {
        val gateway = DirectGateway(uncooperativeReferences = setOf("a"))
        val app = app(gateway, backgroundScope)
        app.startup()
        runCurrent()
        app.openChatWith("a")
        runCurrent()

        assertEquals(NqrbDestination.Home, app.navigation.current)
        assertTrue(app.handleSystemBack())
        assertEquals(NqrbDirectChatEntryState.Idle, app.directChatEntryState.value)

        gateway.complete("a")
        runCurrent()

        assertEquals(listOf("a"), gateway.returnedDirectCalls)
        assertEquals(NqrbDestination.Home, app.navigation.current)
        assertNull(app.chat.state.value.selectedConversationId)
    }

    @Test
    fun rejectedNewerEntryCancelsPendingRequestAndFailureSurvivesLateCompletion() = runTest {
        listOf("", "self", "b", "unknown").forEach { rejectedReference ->
            val gateway = DirectGateway(uncooperativeReferences = setOf("a"))
            val app = app(gateway, backgroundScope)
            app.startup()
            runCurrent()
            app.openChatWith("a")
            runCurrent()
            if (rejectedReference == "b") {
                app.blockNqrbAccount("b")
                runCurrent()
            }

            app.openChatWith(rejectedReference)
            assertEquals(
                NqrbDirectChatEntryState.Failed(rejectedReference),
                app.directChatEntryState.value,
                "Rejected reference should remain visible: '$rejectedReference'",
            )

            gateway.complete("a")
            runCurrent()

            assertEquals(listOf("a"), gateway.returnedDirectCalls)
            assertEquals(NqrbDirectChatEntryState.Failed(rejectedReference), app.directChatEntryState.value)
            assertEquals(NqrbDestination.Home, app.navigation.current)
            assertNull(app.chat.state.value.selectedConversationId)
        }
    }

    @Test
    fun signOutInvalidatesPendingEntryBeforeLateCompletion() = runTest {
        val gateway = DirectGateway()
        val app = app(gateway, backgroundScope)
        app.startup()
        runCurrent()
        app.openChatWith("a")
        runCurrent()

        app.logout()
        gateway.complete("a")
        runCurrent()

        assertEquals(NqrbDestination.SignIn, app.navigation.current)
        assertNull(app.chat.state.value.selectedConversationId)
        assertEquals(NqrbDirectChatEntryState.Idle, app.directChatEntryState.value)
    }

    @Test
    fun failedEntryPublishesRecoverableVisibleStateWithoutNavigation() = runTest {
        val gateway = DirectGateway()
        val app = app(gateway, backgroundScope)
        app.startup()
        runCurrent()
        app.openChatWith("a")
        runCurrent()

        gateway.fail("a")
        runCurrent()

        assertEquals(NqrbDirectChatEntryState.Failed("a"), app.directChatEntryState.value)
        assertEquals(NqrbDestination.Home, app.navigation.current)
        assertNull(app.chat.state.value.selectedConversationId)
    }

    @Test
    fun unexpectedGatewayFailurePublishesRecoverableVisibleStateWithoutNavigation() = runTest {
        val gateway = DirectGateway(throwingReferences = setOf("a"))
        val app = app(gateway, backgroundScope)
        app.startup()
        runCurrent()

        app.openChatWith("a")
        runCurrent()

        assertEquals(NqrbDirectChatEntryState.Failed("a"), app.directChatEntryState.value)
        assertEquals(NqrbDestination.Home, app.navigation.current)
        assertNull(app.chat.state.value.selectedConversationId)
    }

    private fun app(gateway: DirectGateway, scope: CoroutineScope): NqrbAppState {
        val identityGateway = object : FederatedIdentityGateway {
            override suspend fun restore() = Session
            override suspend fun authenticate(credential: FederatedCredential) = FederatedSignInResult.Rejected
            override suspend fun logout() = Unit
        }
        val directory = CallingDirectoryController(object : CallingDirectory {
            override suspend fun loadCallableParticipants() = listOf(
                CallableParticipant("a", "Alpha", CallingParticipantAvailability.Online),
                CallableParticipant("b", "Beta", CallingParticipantAvailability.Reachable),
            )
        })
        return NqrbAppState(
            identity = FederatedIdentityController(UnavailableFederatedCredentialProvider, identityGateway),
            callingDirectory = directory,
            contactBook = NqrbContactBookController(object : NqrbContactBookGateway by UnavailableNqrbContactBookGateway {
                override suspend fun blockAccount(session: MobileSession, membershipId: String) = true
            }),
            chat = ChatController(gateway),
            push = object : PushRegistrationLifecycle {
                override suspend fun activate() = Unit
                override suspend fun deactivate() = PushRegistrationOutcome.Unregistered
                override suspend fun clearLocalState() = Unit
            },
            callActionScope = scope,
        )
    }

    private class DirectGateway(
        private val account: ChatAccountScope = Account,
        private val initialConversations: List<ChatConversation> = emptyList(),
        private val throwingReferences: Set<String> = emptySet(),
        private val uncooperativeReferences: Set<String> = emptySet(),
    ) : ChatGateway by UnavailableChatGateway {
        private val pending = mutableMapOf<String, CompletableDeferred<ChatGatewayResult<ChatConversation>>>()
        val directCalls = mutableListOf<String>()
        val returnedDirectCalls = mutableListOf<String>()

        override suspend fun snapshot(): ChatGateway = this
        override suspend fun context() = ChatGatewayResult.Success(account)
        override suspend fun conversations() = ChatGatewayResult.Success(ChatPage(initialConversations, false))
        override suspend fun conversations(cursor: String?) = conversations()
        override suspend fun direct(reference: String): ChatGatewayResult<ChatConversation> {
            directCalls += reference
            if (reference in throwingReferences) error("Unexpected direct-chat gateway failure")
            val response = if (reference in uncooperativeReferences) {
                withContext(NonCancellable) {
                    pending.getOrPut(reference) { CompletableDeferred() }.await().also {
                        returnedDirectCalls += reference
                    }
                }
            } else {
                pending.getOrPut(reference) { CompletableDeferred() }.await().also {
                    returnedDirectCalls += reference
                }
            }
            return response
        }

        fun complete(reference: String) {
            pending.getOrPut(reference) { CompletableDeferred() }
                .complete(ChatGatewayResult.Success(conversation(reference)))
        }

        fun fail(reference: String) {
            pending.getOrPut(reference) { CompletableDeferred() }
                .complete(ChatGatewayResult.RetryableFailure)
        }
    }

    private companion object {
        val Account = ChatAccountScope("nqrb", "opaque-self-subject")
        val Session = MobileSession(
            "token",
            "2099-01-01T00:00:00Z",
            "refresh",
            "2099-02-01T00:00:00Z",
            ApplicationIdentity("self", "opaque-self-subject", "Self", IdentityKind.Registered, "nqrb"),
        )

        fun conversation(reference: String, counterpartSubjectId: String = "opaque-$reference") = ChatConversation(
            conversationId = "thread-$reference",
            counterpartSubjectId = counterpartSubjectId,
            counterpartReference = reference,
            counterpartDisplayName = reference.replaceFirstChar { it.uppercase() },
            lastSequence = 0,
            lastReadSequence = 0,
            updatedAtUtc = "2026-10-09T00:00:00Z",
        )
    }
}
