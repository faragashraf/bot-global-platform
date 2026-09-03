package com.botglobal.mobile.platform.networking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ApiResultTests {
    @Test
    fun successfulStatusesDoNotCreateErrors() {
        assertNull(apiErrorFromHttpStatus(200))
        assertNull(apiErrorFromHttpStatus(204))
    }

    @Test
    fun httpStatusesMapToStableProductNeutralClasses() {
        val expectations = mapOf(
            400 to ApiErrorKind.Validation,
            401 to ApiErrorKind.Unauthorized,
            403 to ApiErrorKind.Forbidden,
            404 to ApiErrorKind.NotFound,
            408 to ApiErrorKind.Timeout,
            409 to ApiErrorKind.Conflict,
            422 to ApiErrorKind.Validation,
            429 to ApiErrorKind.Unavailable,
            500 to ApiErrorKind.Server,
            503 to ApiErrorKind.Unavailable,
            504 to ApiErrorKind.Timeout,
            599 to ApiErrorKind.Server,
            799 to ApiErrorKind.Unknown,
        )

        expectations.forEach { (status, expected) ->
            assertEquals(expected, apiErrorFromHttpStatus(status)?.kind)
        }
    }

    @Test
    fun transportFailuresMapWithoutExceptionMessagesOrPayloads() {
        assertEquals(ApiErrorKind.Transport, apiErrorFromTransport(TransportFailureKind.NetworkUnavailable).kind)
        assertEquals(ApiErrorKind.Transport, apiErrorFromTransport(TransportFailureKind.Connection).kind)
        assertEquals(ApiErrorKind.Timeout, apiErrorFromTransport(TransportFailureKind.Timeout).kind)
        assertEquals(ApiErrorKind.Unknown, apiErrorFromTransport(TransportFailureKind.Unknown).kind)
    }

    @Test
    fun diagnosticsCannotAcceptSensitiveOperationValues() {
        val diagnostic = NetworkDiagnostic(
            NetworkOperationId("device.bootstrap"),
            ApiError(ApiErrorKind.Unauthorized, 401),
        ).toString()

        assertFalse("credential" in diagnostic.lowercase())
        assertFalse("authorization" in diagnostic.lowercase())
        assertFailsWith<IllegalArgumentException> {
            NetworkOperationId("https://service/path?token=sensitive")
        }

        val successful = ApiResult.Success("sensitive-response-value", 200).toString()
        assertFalse("sensitive-response-value" in successful)
        assertFalse("sensitive" in successful)
    }
}
