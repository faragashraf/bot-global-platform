package com.ashraffarag.sentricam.device.registration

import java.net.URI

class ServerUrlPolicy(private val allowCleartext: Boolean) {
    fun normalize(rawUrl: String): RegistrationCallResult<String> {
        val uri = try {
            URI(rawUrl.trim())
        } catch (exception: Exception) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl(exception))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl())
        }
        if (scheme == "http" && !allowCleartext) {
            return RegistrationCallResult.Failure(RegistrationFailure.CleartextBlocked())
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl())
        }
        if (uri.path?.let { it.isNotEmpty() && it != "/" } == true) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl())
        }
        return try {
            RegistrationCallResult.Success(
                URI(scheme, null, uri.host, uri.port, "/", null, null).toASCIIString(),
            )
        } catch (exception: Exception) {
            RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl(exception))
        }
    }
}
