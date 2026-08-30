package com.botglobal.mobile.platform.contacts

import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DevicePhoneNumber(
    val displayValue: String,
    val sanitizedValue: String = displayValue.filter { it == '+' || it in '0'..'9' },
)

data class DeviceContact(
    val localId: String,
    val displayName: String,
    val phoneNumbers: List<DevicePhoneNumber>,
)

interface ContactsGateway {
    suspend fun readLocalContacts(): List<DeviceContact>
}

object UnavailableContactsGateway : ContactsGateway {
    override suspend fun readLocalContacts(): List<DeviceContact> = emptyList()
}

enum class ContactsStatus {
    NotRequested,
    Loading,
    Available,
    Empty,
    Denied,
    PermanentlyDenied,
    Unavailable,
}

data class ContactsSnapshot(
    val status: ContactsStatus = ContactsStatus.NotRequested,
    val contacts: List<DeviceContact> = emptyList(),
)

class ContactsController(
    private val permissions: PermissionController,
    private val gateway: ContactsGateway,
) {
    private val mutableState = MutableStateFlow(ContactsSnapshot())
    val state: StateFlow<ContactsSnapshot> = mutableState.asStateFlow()

    suspend fun requestAndLoad(): ContactsSnapshot {
        val permission = when (val current = permissions.state(PermissionKind.Contacts)) {
            PermissionState.Granted -> current
            PermissionState.Unavailable -> return update(ContactsStatus.Unavailable)
            PermissionState.PermanentlyDenied -> return update(ContactsStatus.PermanentlyDenied)
            PermissionState.Unknown,
            PermissionState.Denied,
            -> permissions.requestAfterExplanation(PermissionKind.Contacts)
        }
        return resolve(permission)
    }

    suspend fun refresh(): ContactsSnapshot = resolve(permissions.state(PermissionKind.Contacts))

    private suspend fun resolve(permission: PermissionState): ContactsSnapshot = when (permission) {
        PermissionState.Granted -> load()
        PermissionState.PermanentlyDenied -> update(ContactsStatus.PermanentlyDenied)
        PermissionState.Denied -> update(ContactsStatus.Denied)
        PermissionState.Unavailable -> update(ContactsStatus.Unavailable)
        PermissionState.Unknown -> update(ContactsStatus.NotRequested)
    }

    private suspend fun load(): ContactsSnapshot {
        mutableState.value = ContactsSnapshot(ContactsStatus.Loading)
        val contacts = runCatching { gateway.readLocalContacts() }.getOrElse {
            return update(ContactsStatus.Unavailable)
        }
        val safeContacts = contacts
            .filter { it.displayName.isNotBlank() && it.phoneNumbers.isNotEmpty() }
            .sortedBy { it.displayName.lowercase() }
        mutableState.value = ContactsSnapshot(
            status = if (safeContacts.isEmpty()) ContactsStatus.Empty else ContactsStatus.Available,
            contacts = safeContacts,
        )
        return mutableState.value
    }

    private fun update(status: ContactsStatus): ContactsSnapshot =
        ContactsSnapshot(status).also { mutableState.value = it }
}
