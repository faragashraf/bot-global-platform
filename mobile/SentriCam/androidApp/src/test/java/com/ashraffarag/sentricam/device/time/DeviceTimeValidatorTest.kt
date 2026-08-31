package com.ashraffarag.sentricam.device.time

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceTimeValidatorTest {
    private val now = Instant.parse("2026-08-02T12:00:00Z").toEpochMilli()
    private val validator = DeviceTimeValidator(maximumClockDriftMillis = 60_000)

    @Test
    fun acceptsAccurateClockAndCurrentTimezoneOffset() {
        val result = validator.validate(now - 500, 180, "Africa/Cairo", now, ZoneId.of("Africa/Cairo"))

        assertEquals(DeviceTimeValidationKind.VALID, result.kind)
    }

    @Test
    fun distinguishesSevereClockDriftFromTimezoneMismatch() {
        assertEquals(
            DeviceTimeValidationKind.CLOCK_DRIFT,
            validator.validate(now - 120_000, 180, "Africa/Cairo", now, ZoneId.of("Africa/Cairo")).kind,
        )
        assertEquals(
            DeviceTimeValidationKind.TIMEZONE_MISMATCH,
            validator.validate(now, 0, "UTC", now, ZoneId.of("Africa/Cairo")).kind,
        )
    }

    @Test
    fun reportsUnableWhenHubTimeContractIsIncomplete() {
        val result = validator.validate(null, null, null, now, ZoneId.of("UTC"))

        assertEquals(DeviceTimeValidationKind.UNABLE_TO_VALIDATE, result.kind)
        assertEquals(HubTimeVerificationFailureReason.PARSE_FAILURE, result.verificationFailureReason)
        assertEquals(DeviceTimeValidationAction.RETRY, result.action())
    }

    @Test
    fun utcPlusThreeDeviceAndUtcHubSeparatesTimezoneFromClockDrift() {
        val result = validator.validate(now, 0, "UTC", now, ZoneId.of("+03:00"))

        assertEquals(DeviceTimeValidationKind.TIMEZONE_MISMATCH, result.kind)
        assertEquals(0L, result.driftMillis)
        assertEquals(DeviceTimeValidationAction.OPEN_DATE_TIME_SETTINGS, result.action())
    }

    @Test
    fun verifiedTimeHasNoFailureReasonOrAction() {
        val result = validator.validate(now - 250, 180, "Africa/Cairo", now, ZoneId.of("Africa/Cairo"))

        assertEquals(DeviceTimeValidationKind.VALID, result.kind)
        assertNull(result.verificationFailureReason)
        assertEquals(DeviceTimeValidationAction.NONE, result.action())
    }
}
