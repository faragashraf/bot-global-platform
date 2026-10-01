package com.ashraffarag.sentricam.device.registration.android

import android.util.Log
import com.ashraffarag.sentricam.BuildConfig
import com.ashraffarag.sentricam.device.registration.RegistrationFailure
import com.ashraffarag.sentricam.device.registration.RegistrationLogger

class AndroidRegistrationLogger : RegistrationLogger {
    override fun attempt(attemptId: Long, serverHost: String) {
        Log.i(TAG, "Registration attempt=$attemptId host=$serverHost")
    }

    override fun success(attemptId: Long, serverHost: String, serverDeviceId: String) {
        Log.i(TAG, "Registration success attempt=$attemptId host=$serverHost deviceId=$serverDeviceId")
    }

    override fun failure(attemptId: Long, serverHost: String?, failure: RegistrationFailure) {
        val baseMessage =
            "Registration failure attempt=$attemptId host=${serverHost ?: "unconfigured"} " +
                "code=${failure.code} status=${failure.httpStatus ?: "none"}"

        if (!BuildConfig.DEBUG || failure.technicalCause == null) {
            Log.w(TAG, baseMessage)
            return
        }

        Log.w(
            TAG,
            "$baseMessage ${describeCauseChain(failure.technicalCause)}",
        )
    }

    private fun describeCauseChain(throwable: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = throwable
        var depth = 0

        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val type = current::class.java.name
            val message = sanitize(current.message)

            parts += "cause[$depth]=$type${if (message.isNotBlank()) ": $message" else ""}"

            current = current.cause
            depth++
        }

        return parts.joinToString(" | ")
    }

    private fun sanitize(message: String?): String {
        if (message.isNullOrBlank()) return ""

        return message
            .replace(Regex("""(?i)(pairing[_-]?code|token|access[_-]?token|refresh[_-]?token)=([^&\s]+)""")) {
                "${it.groupValues[1]}=<redacted>"
            }
            .take(MAX_MESSAGE_LENGTH)
    }

    private companion object {
        const val TAG = "DeviceRegistration"
        const val MAX_CAUSE_DEPTH = 5
        const val MAX_MESSAGE_LENGTH = 500
    }
}
