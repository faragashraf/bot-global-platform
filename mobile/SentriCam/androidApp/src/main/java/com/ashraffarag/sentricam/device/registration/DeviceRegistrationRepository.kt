package com.ashraffarag.sentricam.device.registration

import com.ashraffarag.sentricam.device.domain.CameraOperatingMode
import com.ashraffarag.sentricam.device.domain.CameraOperatingModeResolver
import com.ashraffarag.sentricam.device.domain.DeviceCapabilities
import com.ashraffarag.sentricam.device.domain.DeviceIdentity
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

class DeviceRegistrationRepository(
    private val api: DeviceRegistrationApi,
    private val credentialStore: DeviceCredentialStore,
    private val serverConfiguration: ServerConfiguration,
    private val connectivity: RegistrationConnectivity,
    private val clock: RegistrationClock,
    private val mapper: DeviceRegistrationRequestMapper,
    private val logger: RegistrationLogger = NoOpRegistrationLogger,
) {
    private val urlPolicy = ServerUrlPolicy(serverConfiguration.isDebug)

    fun configuredBaseUrl(): String = serverConfiguration.baseUrl()

    /**
     * Reads the operating-mode source of truth directly from the encrypted Hub credential.
     * Connection and registration states are deliberately not consulted here.
     */
    fun operatingMode(): CameraOperatingMode = CameraOperatingModeResolver.resolve(
        runCatching { credentialStore.load() }
            .getOrNull()
            ?.details
            ?.serverDeviceId,
    )

    fun configure(rawUrl: String): RegistrationCallResult<String> {
        val normalized = urlPolicy.normalize(rawUrl)
        if (normalized !is RegistrationCallResult.Success) return normalized
        val current = serverConfiguration.baseUrl().trim()
        if (current != normalized.value && !serverConfiguration.updateBaseUrl(normalized.value)) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl())
        }
        if (current.isNotEmpty() && current != normalized.value) {
            val cleared = clearCredentials()
            if (cleared is RegistrationCallResult.Failure) return cleared
        }
        return normalized
    }

    fun canRegisterAutomatically(): Boolean =
        connectivity.isNetworkAvailable() && urlPolicy.normalize(serverConfiguration.baseUrl()) is RegistrationCallResult.Success

    fun restoreState(): RegistrationState {
        val normalized = urlPolicy.normalize(serverConfiguration.baseUrl())
        if (normalized is RegistrationCallResult.Failure) {
            return RegistrationState.InvalidConfiguration(normalized.failure, null)
        }
        val credentials = try {
            credentialStore.load()
        } catch (exception: DeviceCredentialRecoveryRequiredException) {
            return RegistrationState.TokenExpired(
                RegistrationDetails((normalized as RegistrationCallResult.Success).value),
            )
        } catch (exception: Exception) {
            return RegistrationState.Error(RegistrationFailure.SecureStorageFailure(exception), null)
        } ?: return RegistrationState.Unregistered(RegistrationDetails((normalized as RegistrationCallResult.Success).value))
        if (credentials.details.serverBaseUrl != (normalized as RegistrationCallResult.Success).value) {
            return RegistrationState.Unregistered(RegistrationDetails((normalized as RegistrationCallResult.Success).value))
        }
        if (credentials.accessToken.isBlank()) {
            return RegistrationState.Error(RegistrationFailure.TokenInvalid(), credentials.details)
        }
        return if ((credentials.details.accessTokenExpiresAtMillis ?: 0L) <= clock.nowMillis()) {
            RegistrationState.TokenExpired(credentials.details)
        } else {
            RegistrationState.Registered(credentials.details)
        }
    }

    suspend fun register(
        attemptId: Long,
        identity: DeviceIdentity,
        capabilities: DeviceCapabilities,
    ): RegistrationCallResult<DeviceCredentials> {
        if (!connectivity.isNetworkAvailable()) {
            return RegistrationCallResult.Failure(RegistrationFailure.NetworkUnavailable)
        }
        val normalized = urlPolicy.normalize(serverConfiguration.baseUrl())
        if (normalized is RegistrationCallResult.Failure) return normalized
        val baseUrl = (normalized as RegistrationCallResult.Success).value
        val host = URI(baseUrl).host
        logger.attempt(attemptId, host)
        val now = clock.nowMillis()
        return when (val response = api.register(baseUrl, mapper.map(identity, capabilities, now))) {
            is RegistrationCallResult.Failure -> {
                logger.failure(attemptId, host, response.failure)
                response
            }
            is RegistrationCallResult.Success -> mapResponse(
                attemptId,
                host,
                baseUrl,
                identity.deviceId,
                now,
                response.value,
            )
        }
    }

    suspend fun pair(
        attemptId: Long,
        rawPayload: String,
        identity: DeviceIdentity,
        capabilities: DeviceCapabilities,
    ): RegistrationCallResult<DeviceCredentials> {
        if (!connectivity.isNetworkAvailable()) {
            return RegistrationCallResult.Failure(RegistrationFailure.NetworkUnavailable)
        }
        val payload = when (val parsed = HubPairingPayloadParser.parse(rawPayload)) {
            is RegistrationCallResult.Success -> parsed.value
            is RegistrationCallResult.Failure -> return parsed
        }
        val normalized = urlPolicy.normalize(payload.hubBaseUrl)
        if (normalized is RegistrationCallResult.Failure) return normalized
        val baseUrl = (normalized as RegistrationCallResult.Success).value
        val previousUrl = serverConfiguration.baseUrl().trim()
        if (!serverConfiguration.updateBaseUrl(baseUrl)) {
            return RegistrationCallResult.Failure(RegistrationFailure.InvalidServerUrl())
        }
        if (previousUrl.isNotEmpty() && previousUrl != baseUrl) {
            val cleared = clearCredentials()
            if (cleared is RegistrationCallResult.Failure) return cleared
        }
        val host = URI(baseUrl).host
        logger.attempt(attemptId, host)
        val now = clock.nowMillis()
        return when (val response = api.completePairing(
            baseUrl,
            payload.pairingCode,
            mapper.map(identity, capabilities, now),
        )) {
            is RegistrationCallResult.Failure -> {
                logger.failure(attemptId, host, response.failure)
                response
            }
            is RegistrationCallResult.Success -> mapResponse(
                attemptId,
                host,
                baseUrl,
                identity.deviceId,
                now,
                response.value,
                expectedHubFingerprint = payload.hubFingerprint,
            )
        }
    }

    suspend fun testConnection(attemptId: Long): RegistrationCallResult<Long> {
        if (!connectivity.isNetworkAvailable()) {
            return RegistrationCallResult.Failure(RegistrationFailure.NetworkUnavailable)
        }
        val normalized = urlPolicy.normalize(serverConfiguration.baseUrl())
        if (normalized is RegistrationCallResult.Failure) return normalized
        val baseUrl = (normalized as RegistrationCallResult.Success).value
        val host = URI(baseUrl).host
        logger.attempt(attemptId, host)
        return when (val result = api.testConnection(baseUrl)) {
            is RegistrationCallResult.Success -> RegistrationCallResult.Success(clock.nowMillis())
            is RegistrationCallResult.Failure -> {
                logger.failure(attemptId, host, result.failure)
                result
            }
        }
    }

    fun saveCredentials(credentials: DeviceCredentials): RegistrationCallResult<Unit> = try {
        credentialStore.save(credentials)
        RegistrationCallResult.Success(Unit)
    } catch (exception: Exception) {
        RegistrationCallResult.Failure(RegistrationFailure.SecureStorageFailure(exception))
    }

    fun clearCredentials(): RegistrationCallResult<Unit> = try {
        credentialStore.clear()
        RegistrationCallResult.Success(Unit)
    } catch (exception: Exception) {
        RegistrationCallResult.Failure(RegistrationFailure.SecureStorageFailure(exception))
    }

    fun updateLastConnection(details: RegistrationDetails, connectedAtMillis: Long): RegistrationCallResult<RegistrationDetails> {
        val credentials = try {
            credentialStore.load()
        } catch (exception: Exception) {
            return RegistrationCallResult.Failure(RegistrationFailure.SecureStorageFailure(exception))
        } ?: return RegistrationCallResult.Success(details.copy(lastSuccessfulConnectionAtMillis = connectedAtMillis))
        val updated = credentials.copy(
            details = credentials.details.copy(lastSuccessfulConnectionAtMillis = connectedAtMillis),
        )
        return when (val saved = saveCredentials(updated)) {
            is RegistrationCallResult.Success -> RegistrationCallResult.Success(updated.details)
            is RegistrationCallResult.Failure -> saved
        }
    }

    private fun mapResponse(
        attemptId: Long,
        host: String,
        baseUrl: String,
        installationId: String,
        attemptedAtMillis: Long,
        response: DeviceRegistrationResponse,
        expectedHubFingerprint: String? = null,
    ): RegistrationCallResult<DeviceCredentials> {
        val serverDeviceId = response.deviceId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        val token = response.accessToken?.takeIf { it.isNotBlank() }
        val expiresAt = response.accessTokenExpiresAtUtc.parseTimestamp()
        val serverUtc = response.serverUtcNow.parseTimestamp()
        val acknowledgedInstallationId = response.installationId
        val validAcknowledgement = acknowledgedInstallationId == installationId &&
            response.registrationState.equals("Registered", ignoreCase = true) &&
            response.registrationSchemaVersion == REGISTRATION_SCHEMA_VERSION &&
            (expectedHubFingerprint == null || response.hubFingerprint.equals(expectedHubFingerprint, ignoreCase = true))
        if (serverDeviceId == null || token == null || expiresAt == null || serverUtc == null || !validAcknowledgement) {
            val failure = RegistrationFailure.SerializationFailure()
            logger.failure(attemptId, host, failure)
            return RegistrationCallResult.Failure(failure)
        }
        if (expiresAt <= clock.nowMillis()) {
            val failure = RegistrationFailure.TokenInvalid()
            logger.failure(attemptId, host, failure)
            return RegistrationCallResult.Failure(failure)
        }
        val details = RegistrationDetails(
            serverBaseUrl = baseUrl,
            serverDeviceId = serverDeviceId,
            installationId = acknowledgedInstallationId,
            accessTokenExpiresAtMillis = expiresAt,
            lastRegistrationAttemptAtMillis = attemptedAtMillis,
            lastSuccessfulConnectionAtMillis = clock.nowMillis(),
            serverUtcAtRegistrationMillis = serverUtc,
            serverUtcOffsetMinutes = response.serverUtcOffsetMinutes,
            serverTimeZoneId = response.serverTimeZoneId,
            hubFingerprint = response.hubFingerprint?.lowercase(),
            registrationSchemaVersion = response.registrationSchemaVersion,
        )
        logger.success(attemptId, host, serverDeviceId)
        return RegistrationCallResult.Success(
            DeviceCredentials(
                details = details,
                accessToken = token,
                refreshToken = response.refreshToken?.takeIf { it.isNotBlank() },
                refreshTokenExpiresAtMillis = response.refreshTokenExpirationUtc.parseTimestamp(),
            ),
        )
    }

    private fun String?.parseTimestamp(): Long? = try {
        this?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (_: Exception) {
        null
    }
}
