package com.ashraffarag.sentricam.device.registration

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object HubPairingPayloadParser {
    private val codePattern = Regex("^[a-fA-F0-9]{32}$")
    private val fingerprintPattern = Regex("^[a-fA-F0-9]{64}$")

    fun parse(rawPayload: String): RegistrationCallResult<HubPairingPayload> {
        val uri = try {
            URI(rawPayload.trim())
        } catch (exception: Exception) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl(exception))
        }
        if (!uri.scheme.equals("sentricam", ignoreCase = true) ||
            !uri.host.equals("pair", ignoreCase = true)
        ) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl())
        }
        val parameters = uri.rawQuery.orEmpty().split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator < 1) null else decode(part.substring(0, separator)) to decode(part.substring(separator + 1))
            }
            .toMap()
        val version = parameters["v"]
        val hub = parameters["hub"]
        val code = parameters["code"]
        val fingerprint = parameters["fp"]
        if (version != SUPPORTED_VERSION || hub.isNullOrBlank() || code == null || !codePattern.matches(code) ||
            fingerprint == null || !fingerprintPattern.matches(fingerprint)
        ) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl())
        }
        return RegistrationCallResult.Success(
            HubPairingPayload(version, hub, code.lowercase(), fingerprint.lowercase()),
        )
    }

    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private const val SUPPORTED_VERSION = "1"
}
