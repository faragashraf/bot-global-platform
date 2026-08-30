package com.botglobal.mobile.platform.localization

import kotlin.test.Test
import kotlin.test.assertEquals

class LocaleControllerTests {
    @Test
    fun arabicResolvesRightToLeft() {
        assertEquals(ContentDirection.RightToLeft, LocaleDirectionPolicy.directionFor("ar"))
        assertEquals(ContentDirection.RightToLeft, LocaleDirectionPolicy.directionFor("ar-EG"))
    }

    @Test
    fun englishResolvesLeftToRight() {
        assertEquals(ContentDirection.LeftToRight, LocaleDirectionPolicy.directionFor("en"))
        assertEquals(ContentDirection.LeftToRight, LocaleDirectionPolicy.directionFor("en-US"))
    }

    @Test
    fun selectingLanguageUpdatesLocaleAndDirection() {
        val controller = LocaleController("ar")

        controller.selectLanguage("en")

        assertEquals("en", controller.state.value.languageTag)
        assertEquals(ContentDirection.LeftToRight, controller.state.value.direction)
    }
}
