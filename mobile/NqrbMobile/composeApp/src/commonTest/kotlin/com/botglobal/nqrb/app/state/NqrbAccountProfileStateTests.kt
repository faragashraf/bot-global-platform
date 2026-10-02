package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.identity.ApplicationIdentity
import com.botglobal.mobile.platform.identity.FederatedAuthenticationState
import com.botglobal.mobile.platform.identity.FederatedCredential
import com.botglobal.mobile.platform.identity.FederatedCredentialProvider
import com.botglobal.mobile.platform.identity.FederatedCredentialResult
import com.botglobal.mobile.platform.identity.FederatedCredentialType
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.mobile.platform.identity.FederatedIdentityGateway
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.FederatedSignInResult
import com.botglobal.mobile.platform.identity.IdentityKind
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.notifications.PushRegistrationLifecycle
import com.botglobal.mobile.platform.notifications.PushRegistrationOutcome
import com.botglobal.mobile.platform.notifications.UnavailablePushRegistrationLifecycle
import com.botglobal.nqrb.app.data.NqrbAccountDeletionGateway
import com.botglobal.nqrb.app.data.NqrbAccountDeletionOutcome
import com.botglobal.nqrb.app.data.NqrbAccountProfile
import com.botglobal.nqrb.app.data.NqrbAccountProfileGateway
import com.botglobal.nqrb.app.data.NqrbAccountProfileResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NqrbAccountProfileStateTests {
    @Test
    fun staleSuccessFromPreviousSessionCannotOverwriteNewSessionProfile() = runTest {
        val first = session("first")
        val second = session("second")
        val identityGateway = SwitchingIdentityGateway(first, second)
        val profiles = DeferredProfileGateway()
        val state = state(identityGateway, profiles)
        state.startup()

        assertTrue(state.selectTopLevel(NqrbDestination.Profile))
        runCurrent()
        val firstRequest = profiles.requests.single()

        state.signInWithGoogle()
        assertTrue(state.selectTopLevel(NqrbDestination.Profile))
        runCurrent()
        val secondRequest = profiles.requests.last()
        secondRequest.result.complete(available("Second Person"))
        runCurrent()
        assertEquals("Second Person", availableProfile(state).displayName)

        firstRequest.result.complete(available("First Person"))
        runCurrent()

        assertEquals("Second Person", availableProfile(state).displayName)
        assertEquals(second, signedInSession(state))
    }

    @Test
    fun staleAuthenticationRejectionCannotClearNewSession() = runTest {
        val second = session("second")
        val identityGateway = SwitchingIdentityGateway(session("first"), second)
        val profiles = DeferredProfileGateway()
        val state = state(identityGateway, profiles)
        state.startup()

        state.selectTopLevel(NqrbDestination.Profile)
        runCurrent()
        val firstRequest = profiles.requests.single()

        state.signInWithGoogle()
        firstRequest.result.complete(NqrbAccountProfileResult.AuthenticationRequired)
        runCurrent()

        assertEquals(second, signedInSession(state))
        assertEquals(0, identityGateway.logouts)
        assertIs<NqrbAccountProfileState.Hidden>(state.accountProfileState.value)
    }

    @Test
    fun logoutInvalidatesPendingProfileCompletion() = runTest {
        val profiles = DeferredProfileGateway()
        val state = state(SwitchingIdentityGateway(session("first")), profiles)
        state.startup()
        state.selectTopLevel(NqrbDestination.Profile)
        runCurrent()
        val request = profiles.requests.single()

        state.logout()
        request.result.complete(available("Late Person"))
        runCurrent()

        assertIs<FederatedAuthenticationState.SignedOut>(state.identity.state.value)
        assertIs<NqrbAccountProfileState.Hidden>(state.accountProfileState.value)
    }

    @Test
    fun accountDeletionInvalidatesPendingProfileCompletion() = runTest {
        val profiles = DeferredProfileGateway()
        val state = state(
            SwitchingIdentityGateway(session("first")),
            profiles,
            NqrbAccountDeletionGateway { NqrbAccountDeletionOutcome.Accepted },
        )
        state.startup()
        state.selectTopLevel(NqrbDestination.Profile)
        runCurrent()
        val request = profiles.requests.single()

        state.deleteAccount()
        request.result.complete(available("Late Person"))
        runCurrent()

        assertIs<FederatedAuthenticationState.SignedOut>(state.identity.state.value)
        assertIs<NqrbAccountProfileState.Hidden>(state.accountProfileState.value)
    }

    @Test
    fun recoverableLogoutFailureMovesInvalidatedLoadingProfileToRetryableFailure() = runTest {
        val profiles = DeferredProfileGateway()
        val state = state(
            SwitchingIdentityGateway(session("current")),
            profiles,
            push = RetryableFailurePush,
        )
        state.startup()
        state.selectTopLevel(NqrbDestination.Profile)
        runCurrent()
        val invalidatedRequest = profiles.requests.single()

        state.logout()

        assertIs<FederatedAuthenticationState.SignedIn>(state.identity.state.value)
        assertIs<NqrbAccountProfileState.Failed>(state.accountProfileState.value)
        assertEquals(NqrbAccountActionState.SignOutFailed, state.accountActionState.value)
        invalidatedRequest.result.complete(available("Late Person"))
        runCurrent()
        assertIs<NqrbAccountProfileState.Failed>(state.accountProfileState.value)

        state.refreshAccountProfile()
        runCurrent()
        profiles.requests.last().result.complete(available("Current Person"))
        runCurrent()
        assertEquals("Current Person", availableProfile(state).displayName)
    }

    @Test
    fun recoverableDeletionFailureMovesInvalidatedLoadingProfileToRetryableFailure() = runTest {
        val profiles = DeferredProfileGateway()
        val state = state(SwitchingIdentityGateway(session("current")), profiles)
        state.startup()
        state.selectTopLevel(NqrbDestination.Profile)
        runCurrent()
        val invalidatedRequest = profiles.requests.single()

        state.deleteAccount()

        assertIs<FederatedAuthenticationState.SignedIn>(state.identity.state.value)
        assertIs<NqrbAccountProfileState.Failed>(state.accountProfileState.value)
        assertEquals(NqrbAccountActionState.DeletionFailed, state.accountActionState.value)
        invalidatedRequest.result.complete(available("Late Person"))
        runCurrent()
        assertIs<NqrbAccountProfileState.Failed>(state.accountProfileState.value)

        state.refreshAccountProfile()
        runCurrent()
        profiles.requests.last().result.complete(available("Current Person"))
        runCurrent()
        assertEquals("Current Person", availableProfile(state).displayName)
    }

    @Test
    fun successfulNewSignInInvalidatesOldProfileWorkBeforeAnotherLoad() = runTest {
        val second = session("second")
        val profiles = DeferredProfileGateway()
        val state = state(SwitchingIdentityGateway(session("first"), second), profiles)
        state.startup()
        state.selectTopLevel(NqrbDestination.Profile)
        runCurrent()
        val oldRequest = profiles.requests.single()

        state.signInWithGoogle()
        oldRequest.result.complete(available("Previous Person"))
        runCurrent()

        assertEquals(second, signedInSession(state))
        assertIs<NqrbAccountProfileState.Hidden>(state.accountProfileState.value)
    }

    @Test
    fun retryableFailurePreservesAuthenticationAndRetryCanRecover() = runTest {
        val profiles = DeferredProfileGateway()
        val state = state(SwitchingIdentityGateway(session("current")), profiles)
        state.startup()
        state.selectTopLevel(NqrbDestination.Profile)
        runCurrent()
        profiles.requests.single().result.complete(NqrbAccountProfileResult.RetryableFailure)
        runCurrent()

        assertIs<FederatedAuthenticationState.SignedIn>(state.identity.state.value)
        assertIs<NqrbAccountProfileState.Failed>(state.accountProfileState.value)

        state.refreshAccountProfile()
        runCurrent()
        profiles.requests.last().result.complete(available("Recovered Person"))
        runCurrent()

        assertEquals("Recovered Person", availableProfile(state).displayName)
        assertIs<FederatedAuthenticationState.SignedIn>(state.identity.state.value)
    }

    @Test
    fun currentSessionAuthenticationRejectionHidesProfileAndReturnsToSignIn() = runTest {
        val identityGateway = SwitchingIdentityGateway(session("current"))
        val profiles = DeferredProfileGateway()
        val state = state(identityGateway, profiles)
        state.startup()
        state.selectTopLevel(NqrbDestination.Profile)
        runCurrent()

        profiles.requests.single().result.complete(NqrbAccountProfileResult.AuthenticationRequired)
        runCurrent()

        assertIs<FederatedAuthenticationState.SignedOut>(state.identity.state.value)
        assertIs<NqrbAccountProfileState.Hidden>(state.accountProfileState.value)
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
        assertEquals(1, identityGateway.logouts)
    }

    @Test
    fun authenticatedStartupDoesNotWaitForOrRequestAccountProfile() = runTest {
        val profiles = DeferredProfileGateway()
        val state = state(SwitchingIdentityGateway(session("current")), profiles)

        state.startup()

        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertEquals(emptyList(), profiles.requests)
        assertIs<NqrbAccountProfileState.Hidden>(state.accountProfileState.value)
    }

    @Test
    fun successfulSignInDoesNotWaitForOrRequestAccountProfile() = runTest {
        val profiles = DeferredProfileGateway()
        val state = state(
            SwitchingIdentityGateway(restored = null, signedIn = session("signed-in")),
            profiles,
        )
        state.startup()

        state.signInWithGoogle()

        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertEquals(emptyList(), profiles.requests)
        assertIs<FederatedAuthenticationState.SignedIn>(state.identity.state.value)
    }

    private fun TestScope.state(
        identityGateway: SwitchingIdentityGateway,
        profiles: NqrbAccountProfileGateway,
        deletion: NqrbAccountDeletionGateway = NqrbAccountDeletionGateway {
            NqrbAccountDeletionOutcome.RetryableFailure
        },
        push: PushRegistrationLifecycle = UnavailablePushRegistrationLifecycle,
    ) = NqrbAppState(
        identity = FederatedIdentityController(FixedCredentials, identityGateway),
        accountProfile = profiles,
        accountDeletion = deletion,
        push = push,
        callActionScope = backgroundScope,
    )

    private class DeferredProfileGateway : NqrbAccountProfileGateway {
        data class Request(
            val session: MobileSession,
            val result: CompletableDeferred<NqrbAccountProfileResult>,
        )

        val requests = mutableListOf<Request>()

        override suspend fun load(session: MobileSession): NqrbAccountProfileResult {
            val request = Request(session, CompletableDeferred())
            requests += request
            return request.result.await()
        }
    }

    private class SwitchingIdentityGateway(
        private val restored: MobileSession?,
        private val signedIn: MobileSession? = null,
    ) : FederatedIdentityGateway {
        var logouts = 0

        override suspend fun restore() = restored

        override suspend fun authenticate(credential: FederatedCredential) =
            signedIn?.let(FederatedSignInResult::Authenticated) ?: FederatedSignInResult.Rejected

        override suspend fun logout() {
            logouts++
        }
    }

    private object FixedCredentials : FederatedCredentialProvider {
        override suspend fun acquire(provider: FederatedIdentityProvider) =
            FederatedCredentialResult.Acquired(
                FederatedCredential(provider, FederatedCredentialType.IdToken, "transient-test-token"),
            )
    }

    private object RetryableFailurePush : PushRegistrationLifecycle {
        override suspend fun activate() = Unit
        override suspend fun deactivate() = PushRegistrationOutcome.RetryableFailure
        override suspend fun clearLocalState() = Unit
    }

    private fun signedInSession(state: NqrbAppState) =
        (state.identity.state.value as FederatedAuthenticationState.SignedIn).session

    private fun availableProfile(state: NqrbAppState) =
        (state.accountProfileState.value as NqrbAccountProfileState.Available).profile

    private fun available(displayName: String) = NqrbAccountProfileResult.Available(
        NqrbAccountProfile(displayName, "user@example.test"),
    )

    private fun session(marker: String) = MobileSession(
        "$marker-access",
        "2099-01-01T00:00:00Z",
        "$marker-refresh",
        "2099-02-01T00:00:00Z",
        ApplicationIdentity(
            "$marker-membership",
            "$marker-subject",
            "$marker snapshot",
            IdentityKind.Registered,
            "nqrb",
        ),
    )
}
