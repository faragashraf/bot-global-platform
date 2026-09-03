package com.botglobal.mobile.platform.notifications

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AndroidDeviceCredentialStorageConfig(
    val preferencesFile: String,
    val deviceIdKey: String,
    val credentialPayloadKey: String,
    val credentialIvKey: String,
    val keyAlias: String,
) {
    init {
        listOf(preferencesFile, deviceIdKey, credentialPayloadKey, credentialIvKey, keyAlias).forEach {
            require(it.isNotBlank()) { "Secure storage identifiers cannot be blank." }
        }
    }
}

class AndroidSecureMobileDeviceCredentialVault(
    context: Context,
    private val configuration: AndroidDeviceCredentialStorageConfig,
) : MobileDeviceCredentialVault {
    private val preferences = context.applicationContext.getSharedPreferences(
        configuration.preferencesFile,
        Context.MODE_PRIVATE,
    )

    override suspend fun restore(): MobileDeviceCredential? {
        val encrypted = encryptedCredential() ?: return null
        return runCatching {
            val credential = decrypt(encrypted.payload, encrypted.iv)
            credential.takeIf(String::isNotBlank)?.let {
                MobileDeviceCredential(encrypted.deviceId, it)
            }
        }.getOrNull()
    }

    override suspend fun availability(): MobileDeviceCredentialAvailability {
        val values = listOf(
            preferences.getString(configuration.deviceIdKey, null),
            preferences.getString(configuration.credentialPayloadKey, null),
            preferences.getString(configuration.credentialIvKey, null),
        )
        if (values.all { it.isNullOrBlank() }) return MobileDeviceCredentialAvailability.Absent
        if (values.any { it.isNullOrBlank() }) return MobileDeviceCredentialAvailability.Unreadable
        return if (restore() == null) {
            MobileDeviceCredentialAvailability.Unreadable
        } else {
            MobileDeviceCredentialAvailability.Available
        }
    }

    override suspend fun save(credential: MobileDeviceCredential) {
        val encrypted = encrypt(credential.credential)
        check(
            preferences.edit()
                .putString(configuration.deviceIdKey, credential.deviceId)
                .putString(configuration.credentialPayloadKey, encrypted.payload)
                .putString(configuration.credentialIvKey, encrypted.iv)
                .commit(),
        ) {
            "Unable to persist the mobile device credential."
        }
    }

    override suspend fun clear() {
        check(
            preferences.edit()
                .remove(configuration.deviceIdKey)
                .remove(configuration.credentialPayloadKey)
                .remove(configuration.credentialIvKey)
                .commit(),
        ) {
            "Unable to clear the mobile device credential."
        }
    }

    private fun encryptedCredential(): EncryptedCredential? {
        val deviceId = preferences.getString(configuration.deviceIdKey, null)
            ?.takeIf(String::isNotBlank) ?: return null
        val payload = preferences.getString(configuration.credentialPayloadKey, null)
            ?.takeIf(String::isNotBlank) ?: return null
        val iv = preferences.getString(configuration.credentialIvKey, null)
            ?.takeIf(String::isNotBlank) ?: return null
        return EncryptedCredential(deviceId, payload, iv)
    }

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return EncryptedValue(
            payload = Base64.encodeToString(cipher.doFinal(value.encodeToByteArray()), Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    private fun decrypt(payload: String, iv: String): String {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingKey() ?: error("The secure storage key is unavailable."),
            GCMParameterSpec(TagLengthBits, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)).decodeToString()
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(
                    configuration.keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        return keyStore.getKey(configuration.keyAlias, null) as? SecretKey
    }

    private data class EncryptedCredential(
        val deviceId: String,
        val payload: String,
        val iv: String,
    )

    private data class EncryptedValue(
        val payload: String,
        val iv: String,
    )

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val Transformation = "AES/GCM/NoPadding"
        const val TagLengthBits = 128
    }
}
