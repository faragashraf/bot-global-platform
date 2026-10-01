package com.ashraffarag.sentricam.device.time

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DeviceTimeValidationKind {
    VALID,
    CLOCK_DRIFT,
    TIMEZONE_MISMATCH,
    UNABLE_TO_VALIDATE,
}

enum class HubTimeVerificationFailureReason {
    ENDPOINT_UNAVAILABLE,
    TIMEOUT,
    AUTHORIZATION_FAILURE,
    PARSE_FAILURE,
    HUB_UNREACHABLE,
}

enum class DeviceTimeValidationAction {
    NONE,
    RETRY,
    OPEN_DATE_TIME_SETTINGS,
}

data class DeviceTimeValidation(
    val kind: DeviceTimeValidationKind,
    val driftMillis: Long? = null,
    val deviceUtcOffsetMinutes: Int? = null,
    val hubUtcOffsetMinutes: Int? = null,
    val hubTimeZoneId: String? = null,
    val verificationFailureReason: HubTimeVerificationFailureReason? = null,
    val roundTripMillis: Long? = null,
    val isVerifying: Boolean = false,
)

fun DeviceTimeValidation.action(): DeviceTimeValidationAction = when (kind) {
    DeviceTimeValidationKind.CLOCK_DRIFT,
    DeviceTimeValidationKind.TIMEZONE_MISMATCH,
    -> DeviceTimeValidationAction.OPEN_DATE_TIME_SETTINGS
    DeviceTimeValidationKind.UNABLE_TO_VALIDATE -> if (verificationFailureReason == null) {
        DeviceTimeValidationAction.NONE
    } else {
        DeviceTimeValidationAction.RETRY
    }
    DeviceTimeValidationKind.VALID -> DeviceTimeValidationAction.NONE
}

class DeviceTimeValidator(
    val maximumClockDriftMillis: Long = DEFAULT_MAXIMUM_CLOCK_DRIFT_MILLIS,
) {
    init {
        require(maximumClockDriftMillis > 0)
    }

    fun validate(
        serverUtcMillis: Long?,
        serverUtcOffsetMinutes: Int?,
        hubTimeZoneId: String?,
        localNowMillis: Long,
        deviceZoneId: ZoneId = ZoneId.systemDefault(),
        roundTripMillis: Long? = null,
    ): DeviceTimeValidation {
        if (serverUtcMillis == null || serverUtcOffsetMinutes == null) {
            return DeviceTimeValidation(
                DeviceTimeValidationKind.UNABLE_TO_VALIDATE,
                verificationFailureReason = HubTimeVerificationFailureReason.PARSE_FAILURE,
                roundTripMillis = roundTripMillis,
            )
        }
        val drift = abs(serverUtcMillis - localNowMillis)
        val deviceOffset = deviceZoneId.rules.getOffset(Instant.ofEpochMilli(serverUtcMillis)).totalSeconds / 60
        return when {
            drift > maximumClockDriftMillis -> DeviceTimeValidation(
                DeviceTimeValidationKind.CLOCK_DRIFT,
                drift,
                deviceOffset,
                serverUtcOffsetMinutes,
                hubTimeZoneId,
                roundTripMillis = roundTripMillis,
            )
            deviceOffset != serverUtcOffsetMinutes -> DeviceTimeValidation(
                DeviceTimeValidationKind.TIMEZONE_MISMATCH,
                drift,
                deviceOffset,
                serverUtcOffsetMinutes,
                hubTimeZoneId,
                roundTripMillis = roundTripMillis,
            )
            else -> DeviceTimeValidation(
                DeviceTimeValidationKind.VALID,
                drift,
                deviceOffset,
                serverUtcOffsetMinutes,
                hubTimeZoneId,
                roundTripMillis = roundTripMillis,
            )
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_CLOCK_DRIFT_MILLIS = 120_000L
    }
}

class DeviceTimeValidationController(
    private val validator: DeviceTimeValidator = DeviceTimeValidator(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutableState = MutableStateFlow(
        DeviceTimeValidation(DeviceTimeValidationKind.UNABLE_TO_VALIDATE),
    )
    val state: StateFlow<DeviceTimeValidation> = mutableState.asStateFlow()

    fun update(
        serverUtc: String?,
        serverUtcOffsetMinutes: Int?,
        hubTimeZoneId: String?,
        receivedAtMillis: Long = nowMillis(),
        roundTripMillis: Long? = null,
    ) {
        val serverMillis = runCatching {
            serverUtc?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        }.getOrNull()
        update(serverMillis, serverUtcOffsetMinutes, hubTimeZoneId, receivedAtMillis, roundTripMillis)
    }

    fun update(
        serverUtcMillis: Long?,
        serverUtcOffsetMinutes: Int?,
        hubTimeZoneId: String?,
        receivedAtMillis: Long = nowMillis(),
        roundTripMillis: Long? = null,
    ) {
        mutableState.value = validator.validate(
            serverUtcMillis,
            serverUtcOffsetMinutes,
            hubTimeZoneId,
            receivedAtMillis,
            zoneId(),
            roundTripMillis,
        )
    }

    fun verifying() {
        mutableState.value = mutableState.value.copy(isVerifying = true)
    }

    fun verificationInterrupted() {
        mutableState.value = mutableState.value.copy(isVerifying = false)
    }

    fun unavailable(reason: HubTimeVerificationFailureReason) {
        mutableState.value = DeviceTimeValidation(
            kind = DeviceTimeValidationKind.UNABLE_TO_VALIDATE,
            verificationFailureReason = reason,
        )
    }
}
