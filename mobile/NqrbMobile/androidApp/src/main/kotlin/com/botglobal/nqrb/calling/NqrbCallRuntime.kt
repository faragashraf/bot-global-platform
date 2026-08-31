package com.botglobal.nqrb.calling

import android.util.Log
import com.botglobal.mobile.platform.calling.CallSessionController
import com.botglobal.mobile.platform.identity.SessionVault
import com.botglobal.mobile.platform.voice.AndroidVoiceMediaPeerFactory
import com.botglobal.mobile.platform.voice.ManagedVoiceRoomController
import com.botglobal.mobile.platform.voice.VoiceIcePolicy
import com.botglobal.nqrb.NqrbApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NqrbCallRuntime(
    application: NqrbApplication,
    apiBaseUrl: String,
    sessionVault: SessionVault,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signaling = AndroidCallingSignaling(apiBaseUrl, sessionVault)
    private val voice = ManagedVoiceRoomController(
        scope = scope,
        signaling = signaling,
        mediaFactory = AndroidVoiceMediaPeerFactory(
            context = application,
            icePolicy = VoiceIcePolicy.All,
            mediaIdPrefix = "nqrb",
            logTag = "NqrbVoice",
            manageAudioRouting = false,
        ),
        logger = { message -> Log.i("NqrbVoice", message) },
        logTopologyIdentifiers = false,
    )
    val session = CallSessionController(
        scope = scope,
        signaling = signaling,
        voice = voice,
        platform = AndroidCallPlatformLifecycle(application),
        nowEpochMillis = System::currentTimeMillis,
        logger = { message -> Log.i("NqrbCalling", message) },
    )
}
