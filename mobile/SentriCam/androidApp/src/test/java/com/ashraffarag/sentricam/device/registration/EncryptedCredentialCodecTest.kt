package com.ashraffarag.sentricam.device.registration

import com.ashraffarag.sentricam.device.registration.android.EncryptedCredentialCodec
import com.ashraffarag.sentricam.device.registration.android.SecretCipher
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EncryptedCredentialCodecTest {
    @Test
    fun accessTokenIsEncryptedAtRestAndRoundTrips() {
        val codec = EncryptedCredentialCodec(TestCipher())
        val credentials = credentials()

        val encrypted = codec.encode(credentials)
        val restored = codec.decode(encrypted)

        assertFalse(encrypted.contains(ACCESS_TOKEN))
        assertEquals(credentials, restored)
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptedSecurePayloadFailsClosed() {
        EncryptedCredentialCodec(TestCipher()).decode("not-an-encrypted-payload")
    }

    private fun credentials() = DeviceCredentials(
        details = RegistrationDetails(
            serverBaseUrl = "https://server.example/",
            serverDeviceId = SERVER_DEVICE_ID,
            installationId = INSTALLATION_ID,
            accessTokenExpiresAtMillis = EXPIRES,
        ),
        accessToken = ACCESS_TOKEN,
    )

    private class TestCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray): String = "v1:" + Base64.getEncoder().encodeToString(
            plaintext.map { (it.toInt() xor MASK).toByte() }.toByteArray(),
        )

        override fun decrypt(ciphertext: String): ByteArray {
            require(ciphertext.startsWith("v1:"))
            return Base64.getDecoder().decode(ciphertext.removePrefix("v1:"))
                .map { (it.toInt() xor MASK).toByte() }
                .toByteArray()
        }
    }

    private companion object {
        const val MASK = 0x5A
    }
}
