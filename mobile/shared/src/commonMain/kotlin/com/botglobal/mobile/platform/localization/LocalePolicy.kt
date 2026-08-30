package com.botglobal.mobile.platform.localization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ContentDirection {
    LeftToRight,
    RightToLeft,
}

data class LocaleState(
    val languageTag: String,
    val direction: ContentDirection,
)

object LocaleDirectionPolicy {
    private val rightToLeftLanguages = setOf("ar", "fa", "he", "ur")

    fun directionFor(languageTag: String): ContentDirection {
        val language = languageTag.substringBefore('-').substringBefore('_').lowercase()
        return if (language in rightToLeftLanguages) {
            ContentDirection.RightToLeft
        } else {
            ContentDirection.LeftToRight
        }
    }
}

class LocaleController(initialLanguageTag: String) {
    private val mutableState = MutableStateFlow(initialLanguageTag.toLocaleState())
    val state: StateFlow<LocaleState> = mutableState.asStateFlow()

    fun selectLanguage(languageTag: String) {
        mutableState.value = languageTag.toLocaleState()
    }
}

private fun String.toLocaleState(): LocaleState {
    require(isNotBlank()) { "A language tag is required." }
    return LocaleState(
        languageTag = this,
        direction = LocaleDirectionPolicy.directionFor(this),
    )
}
