package com.botglobal.mobile.platform.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackStackNavigatorTests {
    private enum class Destination { Home, History, People, Profile, Settings }

    @Test
    fun initialDestinationIsTheRootAndBackIsNotConsumed() {
        val navigator = BackStackNavigator(Destination.Home)

        assertEquals(Destination.Home, navigator.current)
        assertFalse(navigator.navigateBack())
    }

    @Test
    fun settingsPushesAndBackReturnsToPreviousDestination() {
        val navigator = BackStackNavigator(Destination.Home)
        navigator.selectTopLevel(Destination.People)

        navigator.push(Destination.Settings)

        assertEquals(Destination.Settings, navigator.current)
        assertTrue(navigator.navigateBack())
        assertEquals(Destination.People, navigator.current)
    }

    @Test
    fun topLevelSelectionKeepsHomeAsTheConsistentBackDestination() {
        val navigator = BackStackNavigator(Destination.Home)

        navigator.selectTopLevel(Destination.History)
        navigator.selectTopLevel(Destination.Profile)

        assertEquals(listOf(Destination.Home, Destination.Profile), navigator.backStack.value)
        assertTrue(navigator.navigateBack())
        assertEquals(Destination.Home, navigator.current)
        assertFalse(navigator.navigateBack())
    }

    @Test
    fun resetReplacesTheEntireHistoryWithANewRoot() {
        val navigator = BackStackNavigator(Destination.Home)
        navigator.push(Destination.Settings)

        navigator.reset(Destination.Profile)

        assertEquals(listOf(Destination.Profile), navigator.backStack.value)
        assertFalse(navigator.navigateBack())
    }
}
