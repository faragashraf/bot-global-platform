package com.botglobal.mobile.platform.contacts

import android.content.Context
import android.provider.ContactsContract

class AndroidContactsGateway(context: Context) : ContactsGateway {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun readLocalContacts(): List<DeviceContact> {
        val contacts = linkedMapOf<String, MutableContact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex).toString()
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                if (name.isBlank() || number.isBlank()) continue
                val contact = contacts.getOrPut(id) { MutableContact(id, name) }
                if (contact.numbers.none { it.displayValue == number }) {
                    contact.numbers += DevicePhoneNumber(number)
                }
            }
        }
        return contacts.values.map { contact ->
            DeviceContact(contact.id, contact.name, contact.numbers.toList())
        }
    }

    private data class MutableContact(
        val id: String,
        val name: String,
        val numbers: MutableList<DevicePhoneNumber> = mutableListOf(),
    )
}
