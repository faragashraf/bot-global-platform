package com.botglobal.lamma.app.platform

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.botglobal.mobile.platform.identity.BiometricResult
import com.botglobal.mobile.platform.identity.BiometricSessionGate
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidBiometricSessionGate(
    private val activity: FragmentActivity,
) : BiometricSessionGate {
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    override val isAvailable: Boolean
        get() = BiometricManager.from(activity).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun unlock(reason: String): BiometricResult {
        if (!isAvailable) return BiometricResult.Unavailable
        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(BiometricResult.Succeeded)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        val result = if (
                            errorCode == BiometricPrompt.ERROR_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            BiometricResult.Cancelled
                        } else {
                            BiometricResult.Failed(errString.toString())
                        }
                        continuation.resume(result)
                    }

                    override fun onAuthenticationFailed() = Unit
                },
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(reason)
                    .setAllowedAuthenticators(authenticators)
                    .build(),
            )
        }
    }
}
