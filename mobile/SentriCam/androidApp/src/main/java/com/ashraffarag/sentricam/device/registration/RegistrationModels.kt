package com.ashraffarag.sentricam.device.registration

import com.ashraffarag.sentricam.device.domain.DeviceCapabilities
import com.ashraffarag.sentricam.device.domain.DeviceIdentity

const val REGISTRATION_SCHEMA_VERSION = 1

data class RegistrationIdentityRequest(
    val installationId: String,
    val manufacturer: String,
    val model: String,
    val platform: String,
    val operatingSystemVersion: String,
    val appVersion: String,
    val appBuild: String,
    val apiLevel: Int,
)

data class RegistrationCapabilityRequest(
    val name: String,
    val version: String = "1",
    val isEnabled: Boolean = true,
)

data class DeviceRegistrationRequest(
    val displayName: String,
    val identity: RegistrationIdentityRequest,
    val capabilities: List<RegistrationCapabilityRequest>,
    val clientRegistrationSchemaVersion: Int = REGISTRATION_SCHEMA_VERSION,
    val clientUtcNow: String,
)

data class HubPairingCompletionRequest(
    val pairingCode: String,
    val device: DeviceRegistrationRequest,
)

data class HubPairingPayload(
    val protocolVersion: String,
    val hubBaseUrl: String,
    val pairingCode: String,
    val hubFingerprint: String,
)

data class DeviceRegistrationResponse(
    val deviceId: String?,
    val isNewDevice: Boolean?,
    val accessToken: String?,
    val accessTokenExpiresAtUtc: String?,
    val registeredAtUtc: String?,
    val refreshToken: String?,
    val refreshTokenExpirationUtc: String?,
    val serverUtcNow: String?,
    val installationId: String?,
    val registrationState: String?,
    val registrationSchemaVersion: Int?,
    val serverUtcOffsetMinutes: Int? = null,
    val serverTimeZoneId: String? = null,
    val hubFingerprint: String? = null,
)

data class RegistrationDetails(
    val serverBaseUrl: String,
    val serverDeviceId: String? = null,
    val installationId: String? = null,
    val accessTokenExpiresAtMillis: Long? = null,
    val lastRegistrationAttemptAtMillis: Long? = null,
    val lastSuccessfulConnectionAtMillis: Long? = null,
    val serverUtcAtRegistrationMillis: Long? = null,
    val serverUtcOffsetMinutes: Int? = null,
    val serverTimeZoneId: String? = null,
    val hubFingerprint: String? = null,
    val registrationSchemaVersion: Int = REGISTRATION_SCHEMA_VERSION,
)

data class DeviceCredentials(
    val details: RegistrationDetails,
    val accessToken: String,
    val refreshToken: String? = null,
    val refreshTokenExpiresAtMillis: Long? = null,
)

sealed class RegistrationFailure(
    val code: String,
    val retryAllowed: Boolean,
    val userSafeMessage: String,
    internal val technicalCause: Throwable? = null,
    val httpStatus: Int? = null,
) {
    data object NetworkUnavailable : RegistrationFailure(
        "network_unavailable",
        true,
        "No network connection is available.",
    )

    class Timeout(cause: Throwable? = null) : RegistrationFailure(
        "timeout",
        true,
        "The server did not respond in time.",
        cause,
    )

    class InvalidServerUrl(cause: Throwable? = null) : RegistrationFailure(
        "invalid_server_url",
        false,
        "Enter a valid server URL.",
        cause,
    )

    class CleartextBlocked : RegistrationFailure(
        "cleartext_blocked",
        false,
        "This build requires an HTTPS server URL.",
    )

    class ServerUnreachable(status: Int? = null, cause: Throwable? = null) : RegistrationFailure(
        "server_unreachable",
        true,
        "The SentriCam server could not be reached.",
        cause,
        status,
    )

    class ValidationRejected(status: Int = 400) : RegistrationFailure(
        "validation_rejected",
        false,
        "The server rejected the device information.",
        httpStatus = status,
    )

    class Unauthorized(status: Int = 401) : RegistrationFailure(
        "unauthorized",
        false,
        "The server did not authorize this registration.",
        httpStatus = status,
    )

    class Conflict(status: Int = 409) : RegistrationFailure(
        "conflict",
        true,
        "The registration conflicted with server state. Try again.",
        httpStatus = status,
    )

    class SerializationFailure(cause: Throwable? = null) : RegistrationFailure(
        "serialization_failure",
        true,
        "The server returned an unreadable registration response.",
        cause,
    )

    class SecureStorageFailure(cause: Throwable? = null) : RegistrationFailure(
        "secure_storage_failure",
        false,
        "Secure credential storage is unavailable.",
        cause,
    )

    class TokenInvalid : RegistrationFailure(
        "token_invalid",
        true,
        "The server returned invalid or expired credentials.",
    )

    class UnexpectedFailure(cause: Throwable? = null) : RegistrationFailure(
        "unexpected_failure",
        true,
        "Device registration failed unexpectedly.",
        cause,
    )
}

sealed interface RegistrationState {
    val details: RegistrationDetails?

    data class Unregistered(override val details: RegistrationDetails? = null) : RegistrationState
    data class Configuring(override val details: RegistrationDetails?) : RegistrationState
    data class Registering(val attemptId: Long, override val details: RegistrationDetails) : RegistrationState
    data class Registered(override val details: RegistrationDetails) : RegistrationState
    data class TokenExpired(override val details: RegistrationDetails) : RegistrationState
    data class ConnectionFailed(
        val failure: RegistrationFailure,
        override val details: RegistrationDetails?,
    ) : RegistrationState
    data class ServerRejected(
        val failure: RegistrationFailure,
        override val details: RegistrationDetails?,
    ) : RegistrationState
    data class InvalidConfiguration(
        val failure: RegistrationFailure,
        override val details: RegistrationDetails?,
    ) : RegistrationState
    data class Error(
        val failure: RegistrationFailure,
        override val details: RegistrationDetails?,
    ) : RegistrationState
}

sealed interface RegistrationCallResult<out T> {
    data class Success<T>(val value: T) : RegistrationCallResult<T>
    data class Failure(val failure: RegistrationFailure) : RegistrationCallResult<Nothing>
}

fun interface RegistrationClock {
    fun nowMillis(): Long
}

fun interface RegistrationConnectivity {
    fun isNetworkAvailable(): Boolean
}

interface DeviceRegistrationApi {
    suspend fun register(
        serverBaseUrl: String,
        request: DeviceRegistrationRequest,
    ): RegistrationCallResult<DeviceRegistrationResponse>

    suspend fun testConnection(serverBaseUrl: String): RegistrationCallResult<Unit>

    suspend fun completePairing(
        serverBaseUrl: String,
        pairingCode: String,
        request: DeviceRegistrationRequest,
    ): RegistrationCallResult<DeviceRegistrationResponse>
}

interface DeviceCredentialStore {
    fun load(): DeviceCredentials?
    fun save(credentials: DeviceCredentials)
    fun clear()
}

/**
 * The encrypted credential blob could not be decrypted and was removed, but the underlying
 * credential store remains writable. Callers may safely obtain a replacement credential without
 * changing the installation identity or deleting local recordings.
 */
class DeviceCredentialRecoveryRequiredException(cause: Throwable) :
    IllegalStateException("Stored device credentials must be renewed", cause)

interface ServerConfiguration {
    val isDebug: Boolean
    fun baseUrl(): String
    fun updateBaseUrl(normalizedUrl: String): Boolean
}

interface RegistrationLogger {
    fun attempt(attemptId: Long, serverHost: String)
    fun success(attemptId: Long, serverHost: String, serverDeviceId: String)
    fun failure(attemptId: Long, serverHost: String?, failure: RegistrationFailure)
}

object NoOpRegistrationLogger : RegistrationLogger {
    override fun attempt(attemptId: Long, serverHost: String) = Unit
    override fun success(attemptId: Long, serverHost: String, serverDeviceId: String) = Unit
    override fun failure(attemptId: Long, serverHost: String?, failure: RegistrationFailure) = Unit
}

fun interface DeviceRegistrationRequestMapper {
    fun map(
        identity: DeviceIdentity,
        capabilities: DeviceCapabilities,
        nowMillis: Long,
    ): DeviceRegistrationRequest
}
