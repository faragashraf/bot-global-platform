package com.botglobal.mobile.platform.notifications.firebase

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.botglobal.mobile.platform.notifications.MobileDeviceCredential
import com.botglobal.mobile.platform.notifications.MobileDeviceCredentialVault
import com.botglobal.mobile.platform.notifications.PushDeviceInstallation
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidPushDeviceInstallation(
    context: Context,
    private val appVersion: String,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "botglobal_push_installation",
        Context.MODE_PRIVATE,
    )

    val value: PushDeviceInstallation
        get() = PushDeviceInstallation(
            installationId = installationId(),
            platform = "android",
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter(String::isNotBlank)
                .joinToString(" ")
                .ifBlank { null },
            appVersion = appVersion.trim().ifBlank { null },
        )

    private fun installationId(): String {
        preferences.getString(KEY_INSTALLATION_ID, null)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        return UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_INSTALLATION_ID, it).commit()
        }
    }

    private companion object {
        const val KEY_INSTALLATION_ID = "installation_id"
    }
}

class AndroidSecureMobileDeviceCredentialVault(
    context: Context,
    storageNamespace: String,
) : MobileDeviceCredentialVault {
    private val safeNamespace = storageNamespace
        .filter { it.isLetterOrDigit() || it in "._-" }
        .takeIf(String::isNotBlank)
        ?: error("A secure storage namespace is required.")
    private val preferences = context.applicationContext.getSharedPreferences(
        "botglobal_${safeNamespace}_secure_push_device",
        Context.MODE_PRIVATE,
    )
    private val keyAlias = "botglobal.$safeNamespace.push.device"

    override suspend fun restore(): MobileDeviceCredential? {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        val payload = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            MobileDeviceCredential(deviceId, decrypt(payload, iv))
        }.getOrElse {
            clear()
            null
        }
    }

    override suspend fun save(credential: MobileDeviceCredential) {
        val encrypted = encrypt(credential.credential)
        preferences.edit()
            .putString(KEY_DEVICE_ID, credential.deviceId)
            .putString(KEY_PAYLOAD, encrypted.first)
            .putString(KEY_IV, encrypted.second)
            .apply()
    }

    override suspend fun clear() {
        preferences.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PAYLOAD)
            .remove(KEY_IV)
            .apply()
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.doFinal(value.encodeToByteArray()), Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
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
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
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

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PAYLOAD = "credential_payload"
        const val KEY_IV = "credential_iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
