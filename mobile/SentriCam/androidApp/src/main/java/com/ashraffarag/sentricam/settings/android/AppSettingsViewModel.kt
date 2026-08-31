package com.ashraffarag.sentricam.settings.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ashraffarag.sentricam.settings.capability.AppSettingsCoordinator
import com.ashraffarag.sentricam.settings.domain.AppSettingsRepository
import com.ashraffarag.sentricam.settings.domain.SettingsDestination

class AppSettingsViewModel(
    val coordinator: AppSettingsCoordinator,
) : ViewModel() {
    class Factory(
        private val repository: AppSettingsRepository,
        private val destination: SettingsDestination,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppSettingsViewModel(AppSettingsCoordinator(repository, destination)) as T
    }
}
