package com.enpo.connect.app.pairing

import com.botglobal.mobile.platform.notifications.MobileDeviceCredential

enum class EnpoPairingError {
    InvalidQr,
    UnsupportedQr,
    Expired,
    AlreadyUsed,
    InvalidExpiredOrAlreadyUsed,
    Unauthorized,
    Forbidden,
    NetworkUnavailable,
    Timeout,
    ServerUnavailable,
    ServerError,
    PersistenceFailure,
    CredentialUnreadable,
    CameraPermissionDenied,
    CameraUnavailable,
    ScannerUnavailable,
    Unknown,
}

sealed interface EnpoPairingClaimResult {
    data class Success(val credential: MobileDeviceCredential) : EnpoPairingClaimResult {
        override fun toString(): String = "EnpoPairingClaimResult.Success(credential=<redacted>)"
    }

    data class Failure(
        val error: EnpoPairingError,
        val httpStatus: Int? = null,
    ) : EnpoPairingClaimResult
}

fun interface EnpoPairingClient {
    suspend fun claim(token: ConnectPairingToken): EnpoPairingClaimResult
}

data class EnpoPairingDeviceInfo(
    val platform: String,
    val deviceName: String?,
    val appVersion: String?,
) {
    init {
        require(platform == "android") { "The Connect V2 mobile claim currently supports Android." }
        require(deviceName == null || deviceName.length <= 120)
        require(appVersion == null || appVersion.length <= 50)
    }

    override fun toString(): String =
        "EnpoPairingDeviceInfo(platform=$platform,deviceName=<redacted>,appVersion=$appVersion)"
}

data class EnpoPairingDiagnostic(
    val state: String,
    val error: EnpoPairingError? = null,
    val httpStatus: Int? = null,
) {
    override fun toString(): String =
        "EnpoPairingDiagnostic(state=$state,error=${error ?: "none"},httpStatus=${httpStatus ?: "none"})"
}
