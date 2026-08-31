package com.ashraffarag.sentricam.device.registration.android

import android.content.Context
import com.ashraffarag.sentricam.device.registration.DeviceCredentialStore
import com.ashraffarag.sentricam.device.registration.DeviceCredentialRecoveryRequiredException
import com.ashraffarag.sentricam.device.registration.DeviceCredentials
import com.google.gson.Gson
import java.nio.charset.StandardCharsets

class EncryptedCredentialCodec(
    private val cipher: SecretCipher,
    private val gson: Gson = Gson(),
) {
    fun encode(credentials: DeviceCredentials): String = cipher.encrypt(
        gson.toJson(credentials).toByteArray(StandardCharsets.UTF_8),
    )

    fun decode(payload: String): DeviceCredentials {
        val json = String(cipher.decrypt(payload), StandardCharsets.UTF_8)
        val credentials = requireNotNull(gson.fromJson(json, DeviceCredentials::class.java)) {
            "Encrypted credential payload is empty"
        }
        require(credentials.accessToken.isNotBlank()) { "Encrypted credential token is empty" }
        require(!credentials.details.serverDeviceId.isNullOrBlank()) {
            "Encrypted credential device id is empty"
        }
        return credentials
    }
}

class EncryptedDeviceCredentialStore(
    context: Context,
    private val codec: EncryptedCredentialCodec = EncryptedCredentialCodec(AndroidKeystoreSecretCipher()),
) : DeviceCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun load(): DeviceCredentials? {
        val encrypted = preferences.getString(KEY_ENCRYPTED_CREDENTIALS, null)
            ?: if (preferences.getBoolean(KEY_RECOVERY_REQUIRED, false)) {
                throw DeviceCredentialRecoveryRequiredException(
                    IllegalStateException("Encrypted credential payload was previously unreadable"),
                )
            } else {
                return null
            }
        return try {
            codec.decode(encrypted)
        } catch (exception: Exception) {
            if (!preferences.edit()
                    .remove(KEY_ENCRYPTED_CREDENTIALS)
                    .putBoolean(KEY_RECOVERY_REQUIRED, true)
                    .commit()
            ) {
                exception.addSuppressed(IllegalStateException("Unreadable credential cleanup failed"))
            }
            throw DeviceCredentialRecoveryRequiredException(exception)
        }
    }

    override fun save(credentials: DeviceCredentials) {
        val encrypted = codec.encode(credentials)
        check(preferences.edit()
                .putString(KEY_ENCRYPTED_CREDENTIALS, encrypted)
                .remove(KEY_RECOVERY_REQUIRED)
                .commit()
        ) {
            "Secure credential storage write failed"
        }
    }

    override fun clear() {
        check(preferences.edit()
                .remove(KEY_ENCRYPTED_CREDENTIALS)
                .remove(KEY_RECOVERY_REQUIRED)
                .commit()
        ) {
            "Secure credential storage clear failed"
        }
    }

    private companion object {
        const val PREFERENCES = "device_registration_credentials"
        const val KEY_ENCRYPTED_CREDENTIALS = "encrypted_credentials"
        const val KEY_RECOVERY_REQUIRED = "credential_recovery_required"
    }
}
