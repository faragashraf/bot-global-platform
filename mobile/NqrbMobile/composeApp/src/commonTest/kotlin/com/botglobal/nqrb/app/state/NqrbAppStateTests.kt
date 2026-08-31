package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.contacts.ContactsController
import com.botglobal.mobile.platform.contacts.ContactsGateway
import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
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
import com.botglobal.mobile.platform.localization.ContentDirection
import com.botglobal.mobile.platform.notifications.PushRegistrationLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class NqrbAppStateTests {
    @Test
    fun startsSignedOutInArabicWithoutPhoneIdentityGate() = runTest {
        val state = state()
        state.startup()

        assertEquals("ar", state.locale.state.value.languageTag)
        assertEquals(ContentDirection.RightToLeft, state.locale.state.value.direction)
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
        assertFalse(state.canUseHome())
    }

    @Test
    fun authoritativeSignInContinuesToOptionalContactsThenHome() = runTest {
        val state = state(signIn = FederatedSignInResult.Authenticated(session()))
        state.startup()
        state.signInWithGoogle()
        assertEquals(NqrbDestination.ContactsOnboarding, state.navigation.current)
        assertTrue(state.canUseHome())

        state.skipContacts()
        assertEquals(NqrbDestination.Home, state.navigation.current)
    }

    @Test
    fun deniedContactsNeverBlocksAuthenticatedHome() = runTest {
        val state = state(
            restored = session(),
            permission = PermissionState.Denied,
        )
        state.startup()
        state.allowContacts()

        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertTrue(state.canUseHome())
    }

    @Test
    fun restoredCentralSessionBypassesSignInAndPhoneIdentityIsNotRequired() = runTest {
        val push = RecordingPushLifecycle()
        val state = state(restored = session(), push = push)
        state.startup()

        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertTrue(state.canUseHome())
        assertEquals(1, push.activations)
    }

    @Test
    fun pushRegistrationFailureNeverBlocksRestoredIdentityOrHome() = runTest {
        val state = state(
            restored = session(),
            push = ThrowingPushLifecycle,
        )

        state.startup()

        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertTrue(state.canUseHome())
    }

    @Test
    fun backendRejectionDoesNotAuthenticate() = runTest {
        val state = state(signIn = FederatedSignInResult.Rejected)
        state.startup()
        state.signInWithGoogle()

        assertTrue(state.identity.state.value is FederatedAuthenticationState.AuthenticationError)
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
        assertFalse(state.selectTopLevel(NqrbDestination.Home))
    }

    @Test
    fun settingsBackAndLogoutRemainCoherent() = runTest {
        val push = RecordingPushLifecycle()
        val state = state(restored = session(), push = push)
        state.startup()
        assertTrue(state.selectTopLevel(NqrbDestination.People))
        state.openSettings()
        assertTrue(state.navigation.navigateBack())
        assertEquals(NqrbDestination.People, state.navigation.current)

        state.logout()
        assertEquals(NqrbDestination.SignIn, state.navigation.current)
        assertFalse(state.navigation.navigateBack())
        assertEquals(1, push.deactivations)
    }

    @Test
    fun microphone_is_explained_just_in_time_before_android_permission() = runTest {
        val state = NqrbAppState(
            identity = FederatedIdentityController(FixedCredentials, FixedIdentityGateway(session(), FederatedSignInResult.Rejected)),
            contacts = ContactsController(FixedPermission(PermissionState.Denied), EmptyContacts),
            permissions = FixedPermission(PermissionState.Denied),
            callTargetMembershipId = "known-nqrb-member",
            callTargetDisplayName = "Known NQRB user",
            callActionScope = backgroundScope,
        )

        state.requestOutgoingCall()
        runCurrent()

        assertTrue(state.microphoneExplanationVisible.value)
        assertEquals(com.botglobal.mobile.platform.calling.CallState.Idle, state.calling.state.value.state)
    }

    private fun state(
        restored: MobileSession? = null,
        signIn: FederatedSignInResult = FederatedSignInResult.Rejected,
        permission: PermissionState = PermissionState.Unknown,
        push: PushRegistrationLifecycle = RecordingPushLifecycle(),
    ) = NqrbAppState(
        identity = FederatedIdentityController(
            credentials = FixedCredentials,
            gateway = FixedIdentityGateway(restored, signIn),
        ),
        contacts = ContactsController(FixedPermission(permission), EmptyContacts),
        push = push,
    )

    private object FixedCredentials : FederatedCredentialProvider {
        override suspend fun acquire(provider: FederatedIdentityProvider) = FederatedCredentialResult.Acquired(
            FederatedCredential(provider, FederatedCredentialType.IdToken, "transient-test-token"),
        )
    }

    private class FixedIdentityGateway(
        private val restored: MobileSession?,
        private val result: FederatedSignInResult,
    ) : FederatedIdentityGateway {
        override suspend fun restore() = restored
        override suspend fun authenticate(credential: FederatedCredential) = result
        override suspend fun logout() = Unit
    }

    private class FixedPermission(private val result: PermissionState) : PermissionController {
        override suspend fun state(permission: PermissionKind) = result
        override suspend fun requestAfterExplanation(permission: PermissionKind) = result
    }

    private object EmptyContacts : ContactsGateway {
        override suspend fun readLocalContacts() = emptyList<com.botglobal.mobile.platform.contacts.DeviceContact>()
    }

    private class RecordingPushLifecycle : PushRegistrationLifecycle {
        var activations = 0
        var deactivations = 0
        override suspend fun activate() { activations++ }
        override suspend fun deactivate() { deactivations++ }
    }

    private object ThrowingPushLifecycle : PushRegistrationLifecycle {
        override suspend fun activate() = error("Synthetic push registration failure")
        override suspend fun deactivate() = error("Synthetic push invalidation failure")
    }

    private fun session() = MobileSession(
        "platform-access", "2099-01-01T00:00:00Z", "platform-refresh", "2099-02-01T00:00:00Z",
        ApplicationIdentity("membership", "subject", "NQRB User", IdentityKind.Registered, "nqrb"),
    )
}
