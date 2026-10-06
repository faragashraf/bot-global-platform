package com.enpo.connect.app.pairing

import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialAvailability
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

fun interface EnpoDeviceUnpairClient {
    suspend fun revoke(credential: MobileDeviceCredential): EnpoDeviceUnpairResponse
}

enum class EnpoDeviceUnpairResponse {
    Revoked,
    AlreadyInvalid,
    Unavailable,
}

sealed interface EnpoDeviceUnpairState {
    data object Idle : EnpoDeviceUnpairState
    data object Working : EnpoDeviceUnpairState
    data object Completed : EnpoDeviceUnpairState
    data object ServiceUnavailable : EnpoDeviceUnpairState
    data object LocalCleanupFailed : EnpoDeviceUnpairState
    data object CredentialUnreadable : EnpoDeviceUnpairState
}

class EnpoDeviceUnpairCoordinator(
    private val client: EnpoDeviceUnpairClient,
    private val credentialVault: MobileDeviceCredentialVault,
    private val clearLocalData: suspend () -> Unit = {},
    private val blockNotifications: suspend () -> Unit = {},
) {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow<EnpoDeviceUnpairState>(EnpoDeviceUnpairState.Idle)
    val state: StateFlow<EnpoDeviceUnpairState> = mutableState.asStateFlow()

    suspend fun unpair(): EnpoDeviceUnpairState {
        if (!operation.tryLock()) return mutableState.value
        try {
            val credential = try {
                credentialVault.restore()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (credential == null) {
                mutableState.value = EnpoDeviceUnpairState.CredentialUnreadable
                return mutableState.value
            }
            mutableState.value = EnpoDeviceUnpairState.Working
            try {
                blockNotifications()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = EnpoDeviceUnpairState.LocalCleanupFailed
                return mutableState.value
            }
            val response = try {
                client.revoke(credential)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EnpoDeviceUnpairResponse.Unavailable
            }
            if (response == EnpoDeviceUnpairResponse.Unavailable) {
                // A timeout does not prove the server rejected the revoke. Keep delivery
                // blocked until a retry confirms revocation and clears the local state.
                mutableState.value = EnpoDeviceUnpairState.ServiceUnavailable
                return mutableState.value
            }

            val cleared = try {
                clearLocalData()
                credentialVault.clear()
                credentialVault.availability() == MobileDeviceCredentialAvailability.Absent
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            mutableState.value = if (cleared) {
                EnpoDeviceUnpairState.Completed
            } else {
                EnpoDeviceUnpairState.LocalCleanupFailed
            }
            return mutableState.value
        } finally {
            operation.unlock()
        }
    }

    fun resetNotice() {
        if (!operation.isLocked && mutableState.value == EnpoDeviceUnpairState.Completed) {
            mutableState.value = EnpoDeviceUnpairState.Idle
        }
    }
}
