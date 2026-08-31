package com.ashraffarag.sentricam.device.time

import android.util.Log
import com.ashraffarag.sentricam.BuildConfig

class AndroidHubTimeVerificationLogger : HubTimeVerificationLogger {
    override fun received(response: HubTimeResponse) {
        if (!BuildConfig.DEBUG) return
        Log.i(
            TAG,
            "event=endpoint_response serverUtc=${response.serverUtcNow ?: "missing"} " +
                "hubOffsetMin=${response.serverUtcOffsetMinutes ?: "missing"} " +
                "hubZone=${response.serverTimeZoneId ?: "missing"}",
        )
    }

    override fun succeeded(validation: DeviceTimeValidation) {
        if (!BuildConfig.DEBUG) return
        Log.i(
            TAG,
            "event=verification_succeeded result=${validation.kind.name.lowercase()} " +
                "driftMs=${validation.driftMillis ?: "unknown"} " +
                "roundTripMs=${validation.roundTripMillis ?: "unknown"} " +
                "deviceOffsetMin=${validation.deviceUtcOffsetMinutes ?: "unknown"} " +
                "hubOffsetMin=${validation.hubUtcOffsetMinutes ?: "unknown"}",
        )
    }

    override fun failed(reason: HubTimeVerificationFailureReason, cause: Throwable?) {
        if (!BuildConfig.DEBUG) return
        Log.i(
            TAG,
            "event=verification_failed reason=${reason.name.lowercase()} " +
                "cause=${cause?.javaClass?.simpleName ?: "none"}",
        )
    }

    private companion object {
        const val TAG = "SentriCamTime"
    }
}
