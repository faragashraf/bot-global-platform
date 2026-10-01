package com.ashraffarag.sentricam.device.registration

import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegistrationCoordinatorTest {
    @Test
    fun persistedHubIdentityIsTheOnlyOperatingModeSourceOfTruth() = runBlocking {
        val fixture = fixture(scope = this)
        assertSame(CameraOperatingMode.Standalone, fixture.coordinator.operatingMode())

        fixture.coordinator.registerNow()
        assertTrue(fixture.coordinator.operatingMode() is CameraOperatingMode.HubManaged)

        fixture.api.registerHandler = {
            RegistrationCallResult.Failure(RegistrationFailure.ServerUnreachable(503))
        }
        assertTrue(fixture.coordinator.registerNow() is RegistrationState.ConnectionFailed)
        assertTrue(fixture.coordinator.operatingMode() is CameraOperatingMode.HubManaged)

        fixture.coordinator.forgetRegistration()
        assertSame(CameraOperatingMode.Standalone, fixture.coordinator.operatingMode())
    }

    @Test
    fun qrPairingRequiresTheScannedHubFingerprintToMatchTheHubResponse() = runBlocking {
        val fingerprint = "a".repeat(64)
        val api = FakeRegistrationApi(registerHandler = { request ->
            RegistrationCallResult.Success(
                successResponse(request.identity.installationId).copy(hubFingerprint = fingerprint),
            )
        })
        val fixture = fixture(api = api, scope = this)
        val payload = "sentricam://pair?v=1&hub=https%3A%2F%2Fserver.example%2F" +
            "&code=0123456789abcdef0123456789abcdef&fp=$fingerprint"

        assertTrue(fixture.coordinator.pairNow(payload) is RegistrationState.Registered)

        val mismatched = fixture(scope = this)
        assertTrue(mismatched.coordinator.pairNow(payload) is RegistrationState.Error)
    }

    @Test
    fun registrationSucceedsAndStoresServerIdentitySeparately() = runBlocking {
        val fixture = fixture(scope = this)

        val state = fixture.coordinator.registerNow()

        assertTrue(state is RegistrationState.Registered)
        assertEquals(SERVER_DEVICE_ID, state.details?.serverDeviceId)
        assertEquals(INSTALLATION_ID, state.details?.installationId)
        assertNotEquals(INSTALLATION_ID, state.details?.serverDeviceId)
        assertEquals(ACCESS_TOKEN, fixture.store.credentials?.accessToken)
        assertEquals(INSTALLATION_ID, fixture.api.lastRequest?.identity?.installationId)
        assertFalse(fixture.logger.entries.joinToString().contains(ACCESS_TOKEN))
    }

    @Test
    fun idempotentReregistrationKeepsSameLocalAndServerIds() = runBlocking {
        val fixture = fixture(scope = this)

        val first = fixture.coordinator.registerNow()
        val second = fixture.coordinator.registerNow()

        assertEquals(first.details?.serverDeviceId, second.details?.serverDeviceId)
        assertEquals(INSTALLATION_ID, fixture.api.lastRequest?.identity?.installationId)
        assertEquals(2, fixture.api.registerCalls)
        assertEquals(2, fixture.store.saveCount)
    }

    @Test
    fun duplicateConcurrentRegistrationIsPrevented() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeRegistrationApi(registerHandler = { request ->
            entered.complete(Unit)
            release.await()
            RegistrationCallResult.Success(successResponse(request.identity.installationId))
        })
        val fixture = fixture(api = api, scope = this)

        val first = async { fixture.coordinator.registerNow() }
        entered.await()
        val duplicate = async { fixture.coordinator.registerNow() }.await()
        release.complete(Unit)

        assertTrue(duplicate is RegistrationState.Registering)
        assertTrue(first.await() is RegistrationState.Registered)
        assertEquals(1, api.registerCalls)
    }

    @Test
    fun forgetInvalidatesOldAttemptAndClearsCredentials() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeRegistrationApi(registerHandler = { request ->
            entered.complete(Unit)
            release.await()
            RegistrationCallResult.Success(successResponse(request.identity.installationId))
        })
        val fixture = fixture(api = api, scope = this)
        val first = async { fixture.coordinator.registerNow() }
        entered.await()

        fixture.coordinator.forgetRegistration()
        release.complete(Unit)
        first.await()

        assertTrue(fixture.coordinator.state.value is RegistrationState.Unregistered)
        assertNull(fixture.store.credentials)
        assertEquals(1, fixture.store.clearCount)
        assertEquals(0, fixture.store.saveCount)
    }

    @Test
    fun retryAfterTimeoutCanSucceed() = runBlocking {
        var attempts = 0
        val api = FakeRegistrationApi(registerHandler = { request ->
            attempts++
            if (attempts == 1) {
                RegistrationCallResult.Failure(RegistrationFailure.Timeout())
            } else {
                RegistrationCallResult.Success(successResponse(request.identity.installationId))
            }
        })
        val fixture = fixture(api = api, scope = this)

        assertTrue(fixture.coordinator.registerNow() is RegistrationState.ConnectionFailed)
        assertTrue(fixture.coordinator.registerNow() is RegistrationState.Registered)
        assertEquals(2, api.registerCalls)
    }

    @Test
    fun registeredStateRestoresAcrossCoordinatorRecreationAndDetectsExpiration() = runBlocking {
        val initial = fixture(scope = this)
        initial.coordinator.registerNow()

        val recreated = fixture(scope = this, store = initial.store, clock = initial.clock).coordinator
        assertTrue(recreated.state.value is RegistrationState.Registered)
        assertEquals(SERVER_DEVICE_ID, recreated.state.value.details?.serverDeviceId)

        initial.clock.value = EXPIRES
        val expired = fixture(scope = this, store = initial.store, clock = initial.clock).coordinator
        assertTrue(expired.state.value is RegistrationState.TokenExpired)
    }

    @Test
    fun liveCoordinatorRefreshesExpiredDeviceCredentialForQueuedUploads() = runBlocking {
        var attempts = 0
        val api = FakeRegistrationApi(registerHandler = { request ->
            attempts++
            val response = successResponse(request.identity.installationId)
            RegistrationCallResult.Success(
                if (attempts == 1) {
                    response
                } else {
                    response.copy(
                        accessTokenExpiresAtUtc = java.time.Instant.ofEpochMilli(EXPIRES + 3_600_000L).toString(),
                        registeredAtUtc = java.time.Instant.ofEpochMilli(EXPIRES).toString(),
                        serverUtcNow = java.time.Instant.ofEpochMilli(EXPIRES).toString(),
                    )
                },
            )
        })
        val fixture = fixture(api = api, scope = this)
        fixture.coordinator.registerNow()
        fixture.clock.value = EXPIRES

        fixture.coordinator.refreshExpiredCredentials()
        withTimeout(1_000) {
            fixture.coordinator.state.first {
                it is RegistrationState.Registered && fixture.api.registerCalls == 2
            }
        }

        assertEquals(SERVER_DEVICE_ID, fixture.coordinator.state.value.details?.serverDeviceId)
        assertEquals(INSTALLATION_ID, fixture.api.lastRequest?.identity?.installationId)
    }

    @Test
    fun rejectedUnexpiredCredentialIsRenewedWithoutChangingInstallationIdentity() = runBlocking {
        val fixture = fixture(scope = this)
        fixture.coordinator.registerNow()

        fixture.coordinator.refreshRejectedCredentials()
        withTimeout(1_000) {
            fixture.coordinator.state.first {
                it is RegistrationState.Registered && fixture.api.registerCalls == 2
            }
        }

        assertEquals(INSTALLATION_ID, fixture.api.lastRequest?.identity?.installationId)
        assertEquals(SERVER_DEVICE_ID, fixture.coordinator.state.value.details?.serverDeviceId)
        assertEquals(2, fixture.store.saveCount)
    }

    @Test
    fun networkAndServerRejectionsBecomeStructuredStates() = runBlocking {
        val offline = fixture(networkAvailable = false, scope = this)
        val offlineState = offline.coordinator.registerNow()
        assertTrue(offlineState is RegistrationState.ConnectionFailed)
        assertSame(
            RegistrationFailure.NetworkUnavailable,
            (offlineState as RegistrationState.ConnectionFailed).failure,
        )
        assertEquals(0, offline.api.registerCalls)

        val failures = listOf(
            RegistrationFailure.ValidationRejected(),
            RegistrationFailure.Unauthorized(),
            RegistrationFailure.Conflict(),
        )
        failures.forEach { failure ->
            val api = FakeRegistrationApi(registerHandler = { RegistrationCallResult.Failure(failure) })
            val state = fixture(api = api, scope = this).coordinator.registerNow()
            assertTrue(failure.code, state is RegistrationState.ServerRejected)
            assertEquals(failure.code, (state as RegistrationState.ServerRejected).failure.code)
        }
    }

    @Test
    fun testConnectionUpdatesSafeMetadataWithoutRegistering() = runBlocking {
        val fixture = fixture(scope = this)

        val state = fixture.coordinator.testConnectionNow()

        assertTrue(state is RegistrationState.Unregistered)
        assertEquals(NOW, state.details?.lastSuccessfulConnectionAtMillis)
        assertEquals(1, fixture.api.connectionCalls)
        assertEquals(0, fixture.api.registerCalls)
    }

    @Test
    fun unchangedConfigurationPreservesLastConnectionDuringRegistrationRetry() = runBlocking {
        val api = FakeRegistrationApi(registerHandler = {
            RegistrationCallResult.Failure(RegistrationFailure.ServerUnreachable(500))
        })
        val fixture = fixture(api = api, scope = this)
        fixture.coordinator.testConnectionNow()

        fixture.coordinator.configure(fixture.coordinator.configuredBaseUrl())
        val failed = fixture.coordinator.registerNow()

        assertTrue(failed is RegistrationState.ConnectionFailed)
        assertEquals(NOW, failed.details?.lastSuccessfulConnectionAtMillis)
    }

    @Test
    fun secureStorageFailureIsRestoredAsErrorWithoutCrashing() = runBlocking {
        val store = FakeCredentialStore().apply { failure = IllegalStateException("corrupt") }

        val state = fixture(store = store, scope = this).coordinator.state.value

        assertTrue(state is RegistrationState.Error)
        assertTrue((state as RegistrationState.Error).failure is RegistrationFailure.SecureStorageFailure)
    }

    @Test
    fun unreadableRemovedCredentialResolvesStandaloneWithoutAutomaticHubRegistration() = runBlocking {
        val store = FakeCredentialStore().apply {
            failure = DeviceCredentialRecoveryRequiredException(IllegalStateException("invalidated key"))
        }
        val fixture = fixture(store = store, scope = this)
        assertTrue(fixture.coordinator.state.value is RegistrationState.TokenExpired)
        store.failure = null

        fixture.coordinator.maybeRegisterAutomatically()

        assertSame(CameraOperatingMode.Standalone, fixture.coordinator.operatingMode())
        assertTrue(fixture.coordinator.state.value is RegistrationState.TokenExpired)
        assertEquals(0, fixture.api.registerCalls)
    }

    @Test
    fun sharedCoordinatorStateSurvivesObserverRecreation() = runBlocking {
        val fixture = fixture(scope = this)
        fixture.coordinator.registerNow()

        val firstObserver = fixture.coordinator.state
        val secondObserver = fixture.coordinator.state

        assertSame(firstObserver, secondObserver)
        assertTrue(secondObserver.value is RegistrationState.Registered)
    }

    private fun fixture(
        api: FakeRegistrationApi = FakeRegistrationApi(),
        store: FakeCredentialStore = FakeCredentialStore(),
        clock: MutableRegistrationClock = MutableRegistrationClock(),
        networkAvailable: Boolean = true,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Fixture {
        val logger = FakeRegistrationLogger()
        val repository = DeviceRegistrationRepository(
            api = api,
            credentialStore = store,
            serverConfiguration = FakeServerConfiguration(),
            connectivity = RegistrationConnectivity { networkAvailable },
            clock = clock,
            mapper = AndroidRegistrationRequestMapper(),
            logger = logger,
        )
        return Fixture(
            api,
            store,
            clock,
            logger,
            DeviceRegistrationCoordinator(
                repository,
                ::identity,
                ::capabilities,
                clock,
                scope,
            ),
        )
    }

    private data class Fixture(
        val api: FakeRegistrationApi,
        val store: FakeCredentialStore,
        val clock: MutableRegistrationClock,
        val logger: FakeRegistrationLogger,
        val coordinator: DeviceRegistrationCoordinator,
    )
}
