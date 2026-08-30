package com.botglobal.nqrb.app.state

import com.botglobal.mobile.platform.appearance.AppearanceController
import com.botglobal.mobile.platform.contacts.ContactsController
import com.botglobal.mobile.platform.contacts.UnavailableContactsGateway
import com.botglobal.mobile.platform.device.UnavailablePermissionController
import com.botglobal.mobile.platform.identity.FederatedAuthenticationState
import com.botglobal.mobile.platform.identity.FederatedIdentityController
import com.botglobal.mobile.platform.identity.FederatedIdentityProvider
import com.botglobal.mobile.platform.identity.UnavailableFederatedCredentialProvider
import com.botglobal.mobile.platform.identity.UnavailableFederatedIdentityGateway
import com.botglobal.mobile.platform.localization.LocaleController
import com.botglobal.mobile.platform.navigation.BackStackNavigator

enum class NqrbDestination {
    SignIn,
    ContactsOnboarding,
    Home,
    History,
    People,
    Profile,
    Settings,
}

class NqrbAppState(
    val identity: FederatedIdentityController = FederatedIdentityController(
        UnavailableFederatedCredentialProvider,
        UnavailableFederatedIdentityGateway,
    ),
    val contacts: ContactsController = ContactsController(
        UnavailablePermissionController,
        UnavailableContactsGateway,
    ),
    val locale: LocaleController = LocaleController(DEFAULT_LANGUAGE),
    val appearance: AppearanceController = AppearanceController(),
    val navigation: BackStackNavigator<NqrbDestination> = BackStackNavigator(NqrbDestination.SignIn),
) {
    suspend fun startup() {
        identity.restore()
        navigation.reset(
            if (identity.state.value is FederatedAuthenticationState.SignedIn) {
                NqrbDestination.Home
            } else {
                NqrbDestination.SignIn
            },
        )
    }

    suspend fun signInWithGoogle() {
        identity.signIn(FederatedIdentityProvider.Google)
        if (identity.state.value is FederatedAuthenticationState.SignedIn) {
            navigation.reset(NqrbDestination.ContactsOnboarding)
        }
    }

    suspend fun allowContacts() {
        contacts.requestAndLoad()
        navigation.reset(NqrbDestination.Home)
    }

    fun skipContacts() {
        navigation.reset(NqrbDestination.Home)
    }

    suspend fun refreshContacts() {
        contacts.refresh()
    }

    suspend fun requestContactsFromPeople() {
        contacts.requestAndLoad()
    }

    suspend fun logout() {
        identity.logout()
        navigation.reset(NqrbDestination.SignIn)
    }

    fun openSettings() = navigation.push(NqrbDestination.Settings)

    fun selectTopLevel(destination: NqrbDestination): Boolean {
        require(destination in TOP_LEVEL_DESTINATIONS) { "Destination is not a top-level NQRB destination." }
        if (identity.state.value !is FederatedAuthenticationState.SignedIn) return false
        navigation.selectTopLevel(destination)
        return true
    }

    fun canUseHome(): Boolean = identity.state.value is FederatedAuthenticationState.SignedIn

    companion object {
        const val DEFAULT_LANGUAGE = "ar"
        val TOP_LEVEL_DESTINATIONS = setOf(
            NqrbDestination.Home,
            NqrbDestination.History,
            NqrbDestination.People,
            NqrbDestination.Profile,
        )
    }
}
