package com.enpo.connect.app.pairing

import kotlin.jvm.JvmInline

@JvmInline
value class ConnectPairingToken internal constructor(internal val value: String) {
    override fun toString(): String = "<redacted>"
}

sealed interface ConnectQrValidation {
    data class Valid(val token: ConnectPairingToken) : ConnectQrValidation {
        override fun toString(): String = "ConnectQrValidation.Valid(token=<redacted>)"
    }

    data object InvalidQr : ConnectQrValidation
    data object UnsupportedQr : ConnectQrValidation
}

object ConnectQrContract {
    private val OpaqueToken = Regex("^[A-Za-z0-9_-]{20,256}$")

    fun validate(candidate: String): ConnectQrValidation {
        val normalized = candidate.trim()
        if (normalized.isEmpty()) return ConnectQrValidation.InvalidQr
        if (looksStructuredOrNavigable(normalized)) return ConnectQrValidation.UnsupportedQr
        return if (OpaqueToken.matches(normalized)) {
            ConnectQrValidation.Valid(ConnectPairingToken(normalized))
        } else {
            ConnectQrValidation.InvalidQr
        }
    }

    private fun looksStructuredOrNavigable(value: String): Boolean =
        value.startsWith("{") ||
            value.startsWith("[") ||
            value.contains("://") ||
            value.startsWith("www.", ignoreCase = true) ||
            value.startsWith("mailto:", ignoreCase = true) ||
            '@' in value
}
