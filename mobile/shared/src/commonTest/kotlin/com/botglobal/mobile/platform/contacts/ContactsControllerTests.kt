package com.botglobal.mobile.platform.contacts

import com.botglobal.mobile.platform.device.PermissionController
import com.botglobal.mobile.platform.device.PermissionKind
import com.botglobal.mobile.platform.device.PermissionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ContactsControllerTests {
    @Test
    fun contactsAreNeverReadBeforePermissionIsGranted() = runTest {
        val gateway = RecordingGateway()
        val controller = ContactsController(FixedPermission(PermissionState.Denied), gateway)
        controller.requestAndLoad()
        assertEquals(0, gateway.reads)
        assertEquals(ContactsStatus.Denied, controller.state.value.status)
    }

    @Test
    fun grantedPermissionLoadsMultipleLocalNumbers() = runTest {
        val contact = DeviceContact(
            "local-1", "أحمد Smith",
            listOf(DevicePhoneNumber("+20 10 1234 5678"), DevicePhoneNumber("02 1234 5678")),
        )
        val controller = ContactsController(FixedPermission(PermissionState.Granted), RecordingGateway(listOf(contact)))
        controller.requestAndLoad()
        assertEquals(ContactsStatus.Available, controller.state.value.status)
        assertEquals(2, controller.state.value.contacts.single().phoneNumbers.size)
    }

    @Test
    fun emptyListAndRevocationAreHandled() = runTest {
        val permission = MutablePermission(PermissionState.Granted)
        val controller = ContactsController(permission, RecordingGateway())
        controller.refresh()
        assertEquals(ContactsStatus.Empty, controller.state.value.status)
        permission.value = PermissionState.Denied
        controller.refresh()
        assertEquals(ContactsStatus.Denied, controller.state.value.status)
        assertTrue(controller.state.value.contacts.isEmpty())
    }

    private open class FixedPermission(var value: PermissionState) : PermissionController {
        override suspend fun state(permission: PermissionKind) = value
        override suspend fun requestAfterExplanation(permission: PermissionKind) = value
    }
    private class MutablePermission(value: PermissionState) : FixedPermission(value)
    private class RecordingGateway(private val contacts: List<DeviceContact> = emptyList()) : ContactsGateway {
        var reads = 0
        override suspend fun readLocalContacts(): List<DeviceContact> = contacts.also { reads++ }
    }
}
