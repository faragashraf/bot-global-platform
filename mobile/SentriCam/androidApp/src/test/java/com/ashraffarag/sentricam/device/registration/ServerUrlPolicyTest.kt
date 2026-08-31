package com.ashraffarag.sentricam.device.registration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlPolicyTest {
    @Test
    fun debugAllowsHttpAndNormalizesRoot() {
        val result = ServerUrlPolicy(allowCleartext = true).normalize("http://192.168.1.20:5173")

        assertEquals("http://192.168.1.20:5173/", (result as RegistrationCallResult.Success).value)
    }

    @Test
    fun releaseRejectsCleartext() {
        val result = ServerUrlPolicy(allowCleartext = false).normalize("http://server.example")

        assertTrue((result as RegistrationCallResult.Failure).failure is RegistrationFailure.CleartextBlocked)
    }

    @Test
    fun releaseAcceptsHttps() {
        val result = ServerUrlPolicy(allowCleartext = false).normalize("https://server.example")

        assertEquals("https://server.example/", (result as RegistrationCallResult.Success).value)
    }

    @Test
    fun malformedOrCredentialBearingUrlIsRejected() {
        listOf("not a url", "ftp://server.example", "https://user:pass@server.example", "https://server.example/api")
            .forEach { raw ->
                val result = ServerUrlPolicy(allowCleartext = true).normalize(raw)
                assertTrue(raw, (result as RegistrationCallResult.Failure).failure is RegistrationFailure.InvalidServerUrl)
            }
    }
}
