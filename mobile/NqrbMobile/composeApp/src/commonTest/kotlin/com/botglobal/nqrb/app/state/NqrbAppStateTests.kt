package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.localization.ContentDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NqrbAppStateTests {
    @Test
    fun productStartsInArabicOnHome() {
        val state = NqrbAppState()

        assertEquals("ar", state.locale.state.value.languageTag)
        assertEquals(ContentDirection.RightToLeft, state.locale.state.value.direction)
        assertEquals(NqrbDestination.Home, state.navigation.current)
    }

    @Test
    fun settingsReturnsToThePreviouslySelectedTopLevelDestination() {
        val state = NqrbAppState()
        state.selectTopLevel(NqrbDestination.People)
        state.openSettings()

        assertEquals(NqrbDestination.Settings, state.navigation.current)
        assertTrue(state.navigation.navigateBack())
        assertEquals(NqrbDestination.People, state.navigation.current)
        assertTrue(state.navigation.navigateBack())
        assertEquals(NqrbDestination.Home, state.navigation.current)
        assertFalse(state.navigation.navigateBack())
    }
}
