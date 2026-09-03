package com.botglobal.mobile.platform.notifications.firebase

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidFirebaseDestinationStore(
    context: Context,
) : FirebaseDestinationStore {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )
    private val keyAlias = "botglobal.${context.packageName}.firebase.destination"

    override fun read(): String? {
        val payload = preferences.getString(KEY_PAYLOAD, null)
        val iv = preferences.getString(KEY_IV, null)
        if (!payload.isNullOrBlank() && !iv.isNullOrBlank()) {
            return runCatching { decrypt(payload, iv) }
                .getOrElse {
                    clear()
                    null
                }
                ?.takeIf(String::isNotBlank)
        }

        val legacy = preferences.getString(LEGACY_KEY_IDENTIFIER, null)
            ?.takeIf(String::isNotBlank) ?: return null
        write(legacy)
        return legacy
    }

    override fun write(identifier: String) {
        if (identifier.isBlank()) return
        val encrypted = encrypt(identifier)
        check(
            preferences.edit()
                .putString(KEY_PAYLOAD, encrypted.payload)
                .putString(KEY_IV, encrypted.iv)
                .remove(LEGACY_KEY_IDENTIFIER)
                .commit(),
        ) {
            "Unable to persist the Firebase destination."
        }
    }

    override fun clear() {
        check(
            preferences.edit()
                .remove(KEY_PAYLOAD)
                .remove(KEY_IV)
                .remove(LEGACY_KEY_IDENTIFIER)
                .commit(),
        ) {
            "Unable to clear the Firebase destination."
        }
    }

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return EncryptedValue(
            payload = Base64.encodeToString(cipher.doFinal(value.encodeToByteArray()), Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    private fun decrypt(payload: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)).decodeToString()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
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

    private data class EncryptedValue(val payload: String, val iv: String)

    private companion object {
        const val PREFERENCES_FILE = "botglobal_firebase_push_destination"
        const val LEGACY_KEY_IDENTIFIER = "registration_identifier"
        const val KEY_PAYLOAD = "registration_payload"
        const val KEY_IV = "registration_iv"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
