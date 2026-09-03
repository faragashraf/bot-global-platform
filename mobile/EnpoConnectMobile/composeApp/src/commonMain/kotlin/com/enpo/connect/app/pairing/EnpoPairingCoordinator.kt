package com.enpo.connect.app.pairing

import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import com.botglobal.mobile.platform.device.UnavailablePermissionController
import com.botglobal.mobile.platform.invitations.QrScanResult
import com.botglobal.mobile.platform.invitations.QrScannerCapability
import com.botglobal.mobile.platform.invitations.UnavailableQrScanner
import com.botglobal.mobile.platform.notifications.InMemoryMobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

sealed interface EnpoPairingState {
    data object Unpaired : EnpoPairingState
    data object Scanning : EnpoPairingState
    data object Validating : EnpoPairingState
    data object Claiming : EnpoPairingState
    data object PersistingCredential : EnpoPairingState
    data object Paired : EnpoPairingState
    data class RecoverableError(val error: EnpoPairingError) : EnpoPairingState
    data class FatalError(val error: EnpoPairingError) : EnpoPairingState
}

class EnpoPairingCoordinator(
    private val permissions: PermissionController = UnavailablePermissionController,
    private val scanner: QrScannerCapability = UnavailableQrScanner,
    private val client: EnpoPairingClient = EnpoPairingClient {
        EnpoPairingClaimResult.Failure(EnpoPairingError.Unknown)
    },
    private val credentialVault: MobileDeviceCredentialVault = InMemoryMobileDeviceCredentialVault(),
) {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow<EnpoPairingState>(EnpoPairingState.Unpaired)
    val state: StateFlow<EnpoPairingState> = mutableState.asStateFlow()

    fun initializeUnpaired() {
        if (!operation.isLocked) mutableState.value = EnpoPairingState.Unpaired
    }

    fun initializePaired() {
        if (!operation.isLocked) mutableState.value = EnpoPairingState.Paired
    }

    fun initializeCredentialUnreadable() {
        if (!operation.isLocked) {
            mutableState.value = EnpoPairingState.FatalError(EnpoPairingError.CredentialUnreadable)
        }
    }

    suspend fun startPairing(scannerPrompt: String) {
        if (mutableState.value !is EnpoPairingState.Unpaired &&
            mutableState.value !is EnpoPairingState.RecoverableError
        ) {
            return
        }
        if (!operation.tryLock()) return
        try {
            when (cameraPermission()) {
                PermissionState.Granted -> scanAndClaim(scannerPrompt)
                PermissionState.Unavailable -> failRecoverably(EnpoPairingError.CameraUnavailable)
                PermissionState.Unknown,
                PermissionState.Denied,
                PermissionState.PermanentlyDenied,
                -> failRecoverably(EnpoPairingError.CameraPermissionDenied)
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = EnpoPairingState.Unpaired
            throw cancelled
        } catch (_: Throwable) {
            failRecoverably(EnpoPairingError.Unknown)
        } finally {
            operation.unlock()
        }
    }

    private suspend fun cameraPermission(): PermissionState =
        when (val current = permissions.state(PermissionKind.Camera)) {
            PermissionState.Granted,
            PermissionState.PermanentlyDenied,
            PermissionState.Unavailable,
            -> current

            PermissionState.Unknown,
            PermissionState.Denied,
            -> permissions.requestAfterExplanation(PermissionKind.Camera)
        }

    private suspend fun scanAndClaim(scannerPrompt: String) {
        mutableState.value = EnpoPairingState.Scanning
        when (val result = scanner.scan(scannerPrompt)) {
            QrScanResult.Cancelled -> mutableState.value = EnpoPairingState.Unpaired
            QrScanResult.Unavailable -> failRecoverably(EnpoPairingError.ScannerUnavailable)
            is QrScanResult.Recognized -> validateAndClaim(result.content)
        }
    }

    private suspend fun validateAndClaim(candidate: String) {
        mutableState.value = EnpoPairingState.Validating
        when (val validation = ConnectQrContract.validate(candidate)) {
            ConnectQrValidation.InvalidQr -> failRecoverably(EnpoPairingError.InvalidQr)
            ConnectQrValidation.UnsupportedQr -> failRecoverably(EnpoPairingError.UnsupportedQr)
            is ConnectQrValidation.Valid -> claimAndPersist(validation.token)
        }
    }

    private suspend fun claimAndPersist(token: ConnectPairingToken) {
        mutableState.value = EnpoPairingState.Claiming
        when (val result = client.claim(token)) {
            is EnpoPairingClaimResult.Failure -> failRecoverably(result.error)
            is EnpoPairingClaimResult.Success -> persist(result.credential)
        }
    }

    private suspend fun persist(credential: MobileDeviceCredential) {
        mutableState.value = EnpoPairingState.PersistingCredential
        val durable = runCatching {
            credentialVault.save(credential)
            credentialVault.restore() == credential
        }.getOrDefault(false)
        mutableState.value = if (durable) {
            EnpoPairingState.Paired
        } else {
            EnpoPairingState.FatalError(EnpoPairingError.PersistenceFailure)
        }
    }

    private fun failRecoverably(error: EnpoPairingError) {
        mutableState.value = EnpoPairingState.RecoverableError(error)
    }
}
