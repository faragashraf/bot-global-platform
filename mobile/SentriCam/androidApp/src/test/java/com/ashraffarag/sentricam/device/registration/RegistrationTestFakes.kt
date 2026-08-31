package com.ashraffarag.sentricam.device.registration

import com.ashraffarag.sentricam.capability.domain.AppCapability
import com.ashraffarag.sentricam.capability.domain.CapabilityAccess
import com.ashraffarag.sentricam.device.domain.DeviceCapabilities
import com.ashraffarag.sentricam.device.domain.DeviceIdentity
import com.ashraffarag.sentricam.device.domain.DevicePlatform
import java.time.Instant

internal class FakeRegistrationApi(
    var registerHandler: suspend (DeviceRegistrationRequest) -> RegistrationCallResult<DeviceRegistrationResponse> = {
        RegistrationCallResult.Success(successResponse(it.identity.installationId))
    },
    var connectionResult: RegistrationCallResult<Unit> = RegistrationCallResult.Success(Unit),
) : DeviceRegistrationApi {
    var registerCalls = 0
    var connectionCalls = 0
    var lastRequest: DeviceRegistrationRequest? = null

    override suspend fun register(
        serverBaseUrl: String,
        request: DeviceRegistrationRequest,
    ): RegistrationCallResult<DeviceRegistrationResponse> {
        registerCalls++
        lastRequest = request
        return registerHandler(request)
    }

    override suspend fun testConnection(serverBaseUrl: String): RegistrationCallResult<Unit> {
        connectionCalls++
        return connectionResult
    }

    override suspend fun completePairing(
        serverBaseUrl: String,
        pairingCode: String,
        request: DeviceRegistrationRequest,
    ): RegistrationCallResult<DeviceRegistrationResponse> = registerHandler(request)
}

internal class FakeCredentialStore(
    var credentials: DeviceCredentials? = null,
) : DeviceCredentialStore {
    var saveCount = 0
    var clearCount = 0
    var failure: RuntimeException? = null

    override fun load(): DeviceCredentials? {
        failure?.let { throw it }
        return credentials
    }

    override fun save(credentials: DeviceCredentials) {
        failure?.let { throw it }
        saveCount++
        this.credentials = credentials
    }

    override fun clear() {
        failure?.let { throw it }
        clearCount++
        credentials = null
    }
}

internal class FakeServerConfiguration(
    private var url: String = "https://server.example/",
    override val isDebug: Boolean = false,
) : ServerConfiguration {
    override fun baseUrl() = url
    override fun updateBaseUrl(normalizedUrl: String): Boolean {
        if (!isDebug && url != normalizedUrl) return false
        url = normalizedUrl
        return true
    }
}

internal class FakeRegistrationLogger : RegistrationLogger {
    val entries = mutableListOf<String>()
    override fun attempt(attemptId: Long, serverHost: String) {
        entries += "attempt=$attemptId host=$serverHost"
    }
    override fun success(attemptId: Long, serverHost: String, serverDeviceId: String) {
        entries += "success=$attemptId host=$serverHost device=$serverDeviceId"
    }
    override fun failure(attemptId: Long, serverHost: String?, failure: RegistrationFailure) {
        entries += "failure=$attemptId host=$serverHost code=${failure.code}"
    }
}

internal class MutableRegistrationClock(var value: Long = NOW) : RegistrationClock {
    override fun nowMillis() = value
}

internal fun identity() = DeviceIdentity(
    deviceId = INSTALLATION_ID,
    friendlyName = "Front Door",
    platform = DevicePlatform.ANDROID,
    appVersion = "1.2.3",
    deviceModel = "SM-S921B",
    androidVersion = "16",
    buildFingerprint = "not-sent",
    installedAtMillis = 1L,
    lastStartupAtMillis = 2L,
    manufacturer = "Samsung",
    appBuild = "42",
    apiLevel = 36,
)

internal fun capabilities() = DeviceCapabilities.of(
    AppCapability.MANUAL_RECORDING to CapabilityAccess.Available,
    AppCapability.RECORDING_LIBRARY to CapabilityAccess.Available,
    AppCapability.BASIC_MOTION_DETECTION to CapabilityAccess.Available,
    AppCapability.MONITORING_SERVICE to CapabilityAccess.Available,
    AppCapability.MULTIPLE_CAMERAS to CapabilityAccess.UnsupportedOnDevice("one_camera"),
    AppCapability.SMART_PERSON_DETECTION to CapabilityAccess.ComingSoon,
)

internal fun successResponse(installationId: String = INSTALLATION_ID) = DeviceRegistrationResponse(
    deviceId = SERVER_DEVICE_ID,
    isNewDevice = true,
    accessToken = ACCESS_TOKEN,
    accessTokenExpiresAtUtc = Instant.ofEpochMilli(EXPIRES).toString(),
    registeredAtUtc = Instant.ofEpochMilli(NOW).toString(),
    refreshToken = null,
    refreshTokenExpirationUtc = null,
    serverUtcNow = Instant.ofEpochMilli(NOW).toString(),
    installationId = installationId,
    registrationState = "Registered",
    registrationSchemaVersion = REGISTRATION_SCHEMA_VERSION,
)

internal const val INSTALLATION_ID = "1cf83aa2-bd35-4299-8fe3-55d4fe75d6b8"
internal const val SERVER_DEVICE_ID = "a8b861db-0608-4fe2-891d-cf4fc01d39f4"
internal const val ACCESS_TOKEN = "eyJ.secret.payload"
internal const val NOW = 1_785_499_200_000L
internal const val EXPIRES = NOW + 3_600_000L
