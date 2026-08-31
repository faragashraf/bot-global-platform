package com.ashraffarag.sentricam.live.android

import android.util.Log
import com.ashraffarag.sentricam.BuildConfig

/** Debug-build-only diagnostics for physical Live View acceptance. */
internal object LiveViewDiagnostics {
    const val ORIENTATION = "SentriCamOrientation"
    const val PREVIEW = "SentriCamPreview"
    const val WEBRTC = "SentriCamWebRTC"

    inline fun log(tag: String, message: () -> String) {
        // Huawei production ROMs suppress application DEBUG entries even for debuggable APKs.
        if (BuildConfig.DEBUG) runCatching { Log.i(tag, message()) }
    }

    inline fun warn(tag: String, failure: Throwable? = null, message: () -> String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            if (failure == null) Log.w(tag, message()) else Log.w(tag, message(), failure)
        }
    }
}
