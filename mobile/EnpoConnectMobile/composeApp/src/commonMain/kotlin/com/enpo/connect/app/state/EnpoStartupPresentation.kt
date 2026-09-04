package com.enpo.connect.app.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.botglobal.mobile.platform.appearance.AppearancePreference

enum class EnpoStartupContext {
    VisibleLaunch,
    BackgroundPushBootstrap,
}

object EnpoStartupRuntimePolicy {
    fun requiresVisibleAnimation(context: EnpoStartupContext): Boolean =
        context == EnpoStartupContext.VisibleLaunch
}

object EnpoStartupAnimationSpec {
    const val VisibleLaunchDurationMillis = 3_000L
    const val LogoTravelDp = 560
    const val ConnectInitialTranslationMultiplier = 1
    const val EgyptPostInitialTranslationMultiplier = -1
    const val LogoTravelDurationMillis = 1_250
    const val BrandFadeDurationMillis = 520
    const val BackgroundScaleDurationMillis = 1_900
    const val SecondaryRevealDelayMillis = 120L
    const val DividerFadeDurationMillis = 360
    const val LoadingFadeDurationMillis = 520

    val supportedAppearances: Set<AppearancePreference> = AppearancePreference.entries.toSet()
}

object EnpoStartupBranding {
    const val Connect = "Connect"
    const val EgyptPost = "Egypt Post"
}

@Stable
class EnpoVisibleLaunchState(
    initiallyComplete: Boolean = false,
) {
    var isComplete by mutableStateOf(initiallyComplete)
        private set

    fun complete(): Boolean {
        if (isComplete) return false
        isComplete = true
        return true
    }

    companion object {
        val Saver: Saver<EnpoVisibleLaunchState, Boolean> = Saver(
            save = { it.isComplete },
            restore = ::EnpoVisibleLaunchState,
        )
    }
}
