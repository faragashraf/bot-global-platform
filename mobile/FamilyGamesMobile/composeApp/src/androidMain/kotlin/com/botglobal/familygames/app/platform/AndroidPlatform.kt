package com.botglobal.familygames.app.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.botglobal.familygames.app.data.MobileSessionDto
import com.botglobal.familygames.app.state.AppLanguage
import com.botglobal.familygames.app.state.ApplicationLanguagePreferences
import com.botglobal.familygames.app.state.appLanguageFromPreference
import com.botglobal.familygames.app.state.preferenceValue
import com.botglobal.mobile.platform.device.HapticEvent
import com.botglobal.mobile.platform.device.SemanticHaptics
import com.botglobal.mobile.platform.identity.MobileSession
import com.botglobal.mobile.platform.identity.SessionVault
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureSessionVault(context: Context) : SessionVault {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun restore(): MobileSession? {
        val payload = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            val clear = decrypt(payload, iv)
            json.decodeFromString<MobileSessionDto>(clear).toDomain()
        }.getOrElse {
            clear()
            null
        }
    }

    override suspend fun save(session: MobileSession) {
        val encrypted = encrypt(json.encodeToString(MobileSessionDto.fromDomain(session)))
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
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "botglobal_family_games_secure_session"
        const val KEY_PAYLOAD = "session_payload"
        const val KEY_IV = "session_iv"
        const val KEY_ALIAS = "botglobal.familygames.mobile.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

class AndroidApplicationLanguagePreferences(context: Context) : ApplicationLanguagePreferences {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun restore(): AppLanguage? = appLanguageFromPreference(preferences.getString(KEY_LANGUAGE, null))

    override fun save(language: AppLanguage) {
        preferences.edit().putString(KEY_LANGUAGE, language.preferenceValue()).commit()
    }

    private companion object {
        const val PREFERENCES = "botglobal_family_games_preferences"
        const val KEY_LANGUAGE = "application_language"
    }
}

class AndroidSemanticHaptics(context: Context) : SemanticHaptics {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    override fun perform(event: HapticEvent) {
        if (!vibrator.hasVibrator()) return
        val duration = when (event) {
            HapticEvent.Selection -> 12L
            HapticEvent.LightImpact -> 18L
            HapticEvent.Success -> 35L
            HapticEvent.Warning -> 45L
            HapticEvent.Error -> 70L
            HapticEvent.ImportantAction -> 50L
            HapticEvent.GameEvent -> 24L
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}
