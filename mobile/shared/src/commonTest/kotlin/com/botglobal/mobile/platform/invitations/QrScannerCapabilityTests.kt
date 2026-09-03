package com.botglobal.mobile.platform.invitations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class QrScannerCapabilityTests {
    @Test
    fun recognizedContentIsRedactedFromDiagnostics() {
        val result = QrScanResult.Recognized("opaque-sensitive-content")

        assertEquals("QrScanResult.Recognized(content=<redacted>)", result.toString())
        assertFalse("opaque-sensitive-content" in result.toString())
        assertFailsWith<IllegalArgumentException> { QrScanResult.Recognized("   ") }
    }
}
