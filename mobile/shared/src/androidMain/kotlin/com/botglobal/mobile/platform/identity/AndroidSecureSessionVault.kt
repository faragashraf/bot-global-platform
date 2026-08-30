package com.botglobal.mobile.platform.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AndroidSecureSessionVault(
    context: Context,
    storageNamespace: String,
) : SessionVault {
    private val safeNamespace = storageNamespace.filter { it.isLetterOrDigit() || it in "._-" }
        .takeIf(String::isNotBlank)
        ?: error("A secure storage namespace is required.")
    private val preferences = context.applicationContext.getSharedPreferences(
        "botglobal_${safeNamespace}_secure_session",
        Context.MODE_PRIVATE,
    )
    private val keyAlias = "botglobal.$safeNamespace.mobile.session"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun restore(): MobileSession? {
        val payload = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            json.decodeFromString<SessionPayload>(decrypt(payload, iv)).toDomain()
        }.getOrElse {
            clear()
            null
        }
    }

    override suspend fun save(session: MobileSession) {
        val encrypted = encrypt(json.encodeToString(SessionPayload.fromDomain(session)))
        preferences.edit()
            .putString(KEY_PAYLOAD, encrypted.first)
            .putString(KEY_IV, encrypted.second)
            .apply()
    }

    override suspend fun clear() {
        preferences.edit().remove(KEY_PAYLOAD).remove(KEY_IV).apply()
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

    @Serializable
    private data class SessionPayload(
        val accessToken: String,
        val accessExpiresAtUtc: String,
        val refreshToken: String,
        val refreshExpiresAtUtc: String,
        val membershipId: String,
        val subjectId: String,
        val displayName: String,
        val identityKind: String,
        val applicationKey: String,
    ) {
        fun toDomain() = MobileSession(
            accessToken,
            accessExpiresAtUtc,
            refreshToken,
            refreshExpiresAtUtc,
            ApplicationIdentity(
                membershipId,
                subjectId,
                displayName,
                IdentityKind.valueOf(identityKind),
                applicationKey,
            ),
        )

        companion object {
            fun fromDomain(session: MobileSession) = SessionPayload(
                session.accessToken,
                session.accessExpiresAtUtc,
                session.refreshToken,
                session.refreshExpiresAtUtc,
                session.identity.membershipId,
                session.identity.subjectId,
                session.identity.displayName,
                session.identity.kind.name,
                session.identity.applicationKey,
            )
        }
    }

    private companion object {
        const val KEY_PAYLOAD = "session_payload"
        const val KEY_IV = "session_iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
