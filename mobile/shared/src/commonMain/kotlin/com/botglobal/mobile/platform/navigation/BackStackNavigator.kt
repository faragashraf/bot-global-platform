package com.botglobal.mobile.platform.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BackStackNavigator<Destination : Any>(initialDestination: Destination) {
    private val mutableBackStack = MutableStateFlow(listOf(initialDestination))
    val backStack: StateFlow<List<Destination>> = mutableBackStack.asStateFlow()

    val current: Destination
        get() = mutableBackStack.value.last()

    val canNavigateBack: Boolean
        get() = mutableBackStack.value.size > 1

    fun push(destination: Destination) {
        if (destination != current) {
            mutableBackStack.value = mutableBackStack.value + destination
        }
    }

    fun selectTopLevel(destination: Destination) {
        val root = mutableBackStack.value.first()
        mutableBackStack.value = if (destination == root) listOf(root) else listOf(root, destination)
    }

    fun reset(destination: Destination) {
        mutableBackStack.value = listOf(destination)
    }

    fun navigateBack(): Boolean {
        if (!canNavigateBack) return false
        mutableBackStack.value = mutableBackStack.value.dropLast(1)
        return true
    }
}
