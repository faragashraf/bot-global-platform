package com.ashraffarag.sentricam.communication.signalr

import com.ashraffarag.sentricam.device.registration.DeviceCredentialStore

class SecureSignalRCredentialProvider(
    private val credentialStore: DeviceCredentialStore,
    private val clock: SignalRClock,
) {
    internal fun load(): Result<SignalRCredential> {
        return try {
            val credentials = credentialStore.load()
                ?: return Result.failure(
                    SignalRTransportException(SignalRFailureCode.CREDENTIALS_MISSING),
                )
            val serverDeviceId = credentials.details.serverDeviceId?.takeIf(String::isNotBlank)
                ?: return Result.failure(
                    SignalRTransportException(SignalRFailureCode.CREDENTIALS_MISSING),
                )
            val expiration = credentials.details.accessTokenExpiresAtMillis
                ?: return Result.failure(
                    SignalRTransportException(SignalRFailureCode.TOKEN_EXPIRED),
                )
            if (credentials.accessToken.isBlank()) {
                return Result.failure(
                    SignalRTransportException(SignalRFailureCode.CREDENTIALS_MISSING),
                )
            }
            if (expiration <= clock.nowMillis()) {
                return Result.failure(
                    SignalRTransportException(SignalRFailureCode.TOKEN_EXPIRED),
                )
            }
            Result.success(SignalRCredential(serverDeviceId, credentials.accessToken, expiration))
        } catch (exception: Exception) {
            Result.failure(
                SignalRTransportException(SignalRFailureCode.SECURE_STORAGE_FAILED, exception),
            )
        }
    }
}
