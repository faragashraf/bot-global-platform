package com.enpo.connect.app.pairing

import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import com.botglobal.mobile.platform.invitations.QrScanResult
import com.botglobal.mobile.platform.invitations.QrScannerCapability
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialAvailability
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class EnpoPairingCoordinatorTests {
    @Test
    fun claimSuccessPersistsAndVerifiesBeforePaired() = runTest {
        val events = mutableListOf<String>()
        val credential = MobileDeviceCredential("test-device", "test-credential")
        val vault = RecordingVault(events = events)
        val coordinator = coordinator(
            client = RecordingClient(EnpoPairingClaimResult.Success(credential), events),
            vault = vault,
        )

        coordinator.startPairing("scan")

        assertEquals(listOf("claim", "save", "restore"), events)
        assertEquals(credential, vault.value)
        assertEquals(EnpoPairingState.Paired, coordinator.state.value)
    }

    @Test
    fun persistenceFailureNeverEmitsPairedAndDoesNotClearEvidence() = runTest {
        val vault = RecordingVault(failSave = true)
        val coordinator = coordinator(
            client = RecordingClient(
                EnpoPairingClaimResult.Success(MobileDeviceCredential("test-device", "test-credential")),
            ),
            vault = vault,
        )

        coordinator.startPairing("scan")

        assertEquals(
            EnpoPairingState.FatalError(EnpoPairingError.PersistenceFailure),
            coordinator.state.value,
        )
        assertEquals(0, vault.clears)
    }

    @Test
    fun duplicateConcurrentStartsProduceOneScanAndOneClaim() = runTest {
        val scannerStarted = CompletableDeferred<Unit>()
        val releaseScanner = CompletableDeferred<Unit>()
        val scanner = object : QrScannerCapability {
            var scans = 0
            override suspend fun scan(prompt: String): QrScanResult {
                scans += 1
                scannerStarted.complete(Unit)
                releaseScanner.await()
                return recognized()
            }
        }
        val client = RecordingClient(
            EnpoPairingClaimResult.Success(MobileDeviceCredential("test-device", "test-credential")),
        )
        val coordinator = coordinator(scanner = scanner, client = client)

        val first = async { coordinator.startPairing("scan") }
        scannerStarted.await()
        val duplicate = async { coordinator.startPairing("scan") }
        duplicate.await()
        releaseScanner.complete(Unit)
        first.await()

        assertEquals(1, scanner.scans)
        assertEquals(1, client.claims)
        assertEquals(EnpoPairingState.Paired, coordinator.state.value)
    }

    @Test
    fun networkFailureCanRetryFromASafeState() = runTest {
        val client = QueueClient(
            mutableListOf(
                EnpoPairingClaimResult.Failure(EnpoPairingError.NetworkUnavailable),
                EnpoPairingClaimResult.Success(MobileDeviceCredential("test-device", "test-credential")),
            ),
        )
        val coordinator = coordinator(client = client)

        coordinator.startPairing("scan")
        assertEquals(
            EnpoPairingState.RecoverableError(EnpoPairingError.NetworkUnavailable),
            coordinator.state.value,
        )

        coordinator.startPairing("scan")
        assertEquals(2, client.claims)
        assertEquals(EnpoPairingState.Paired, coordinator.state.value)
    }

    @Test
    fun expiredAndAlreadyUsedSemanticsRemainVisibleToTheUiState() = runTest {
        listOf(EnpoPairingError.Expired, EnpoPairingError.AlreadyUsed).forEach { error ->
            val coordinator = coordinator(
                client = RecordingClient(EnpoPairingClaimResult.Failure(error, 400)),
            )

            coordinator.startPairing("scan")

            assertEquals(EnpoPairingState.RecoverableError(error), coordinator.state.value)
        }
    }

    @Test
    fun permissionDenialDoesNotOpenScannerAndScannerBackReturnsToEntry() = runTest {
        val deniedScanner = RecordingScanner(recognized())
        val denied = coordinator(
            permission = FixedPermission(PermissionState.PermanentlyDenied),
            scanner = deniedScanner,
        )

        denied.startPairing("scan")
        assertEquals(0, deniedScanner.scans)
        assertEquals(
            EnpoPairingState.RecoverableError(EnpoPairingError.CameraPermissionDenied),
            denied.state.value,
        )

        val cancelled = coordinator(scanner = RecordingScanner(QrScanResult.Cancelled))
        cancelled.startPairing("scan")
        assertEquals(EnpoPairingState.Unpaired, cancelled.state.value)
    }

    @Test
    fun unreadableCredentialIsFatalAndCannotBeOverwrittenByScanning() = runTest {
        val vault = RecordingVault()
        val scanner = RecordingScanner(recognized())
        val coordinator = coordinator(scanner = scanner, vault = vault)

        coordinator.initializeCredentialUnreadable()
        coordinator.startPairing("scan")

        assertEquals(
            EnpoPairingState.FatalError(EnpoPairingError.CredentialUnreadable),
            coordinator.state.value,
        )
        assertEquals(0, scanner.scans)
        assertEquals(null, vault.value)
        assertEquals(0, vault.clears)
    }

    @Test
    fun diagnosticRepresentationsNeverContainQrOrCredentialValues() {
        val rawToken = "A".repeat(43)
        val token = assertIs<ConnectQrValidation.Valid>(ConnectQrContract.validate(rawToken)).token
        val credential = MobileDeviceCredential("test-device", "test-credential")
        val texts = listOf(
            token.toString(),
            EnpoPairingClaimResult.Success(credential).toString(),
            EnpoPairingDiagnostic("claiming", EnpoPairingError.Timeout).toString(),
            QrScanResult.Recognized(rawToken).toString(),
        )

        assertFalse(texts.any { rawToken in it })
        assertFalse(texts.any { "test-credential" in it })
        assertFalse(texts.any { "test-device" in it })
    }

    private fun coordinator(
        permission: PermissionController = FixedPermission(PermissionState.Granted),
        scanner: QrScannerCapability = RecordingScanner(recognized()),
        client: EnpoPairingClient = RecordingClient(
            EnpoPairingClaimResult.Success(MobileDeviceCredential("test-device", "test-credential")),
        ),
        vault: MobileDeviceCredentialVault = RecordingVault(),
    ) = EnpoPairingCoordinator(permission, scanner, client, vault)

    private fun recognized() = QrScanResult.Recognized("A".repeat(43))

    private class FixedPermission(
        private val state: PermissionState,
        private val requestedState: PermissionState = state,
    ) : PermissionController {
        override suspend fun state(permission: PermissionKind) = state
        override suspend fun requestAfterExplanation(permission: PermissionKind) = requestedState
    }

    private class RecordingScanner(
        private val result: QrScanResult,
    ) : QrScannerCapability {
        var scans = 0
        override suspend fun scan(prompt: String): QrScanResult {
            scans += 1
            return result
        }
    }

    private class RecordingClient(
        private val result: EnpoPairingClaimResult,
        private val events: MutableList<String>? = null,
    ) : EnpoPairingClient {
        var claims = 0
        override suspend fun claim(token: ConnectPairingToken): EnpoPairingClaimResult {
            claims += 1
            events?.add("claim")
            return result
        }
    }

    private class QueueClient(
        private val results: MutableList<EnpoPairingClaimResult>,
    ) : EnpoPairingClient {
        var claims = 0
        override suspend fun claim(token: ConnectPairingToken): EnpoPairingClaimResult {
            claims += 1
            return results.removeAt(0)
        }
    }

    private class RecordingVault(
        private val events: MutableList<String>? = null,
        private val failSave: Boolean = false,
        var value: MobileDeviceCredential? = null,
    ) : MobileDeviceCredentialVault {
        var clears = 0

        override suspend fun restore(): MobileDeviceCredential? {
            events?.add("restore")
            return value
        }

        override suspend fun save(credential: MobileDeviceCredential) {
            events?.add("save")
            if (failSave) error("simulated persistence failure")
            value = credential
        }

        override suspend fun clear() {
            clears += 1
            value = null
        }

        override suspend fun availability(): MobileDeviceCredentialAvailability =
            if (value == null) MobileDeviceCredentialAvailability.Absent
            else MobileDeviceCredentialAvailability.Available
    }
}
