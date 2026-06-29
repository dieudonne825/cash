package com.pettycash.cashsms.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactInfo(
    val displayName: String,
    val phoneNumber: String
)

object ContactResolver {

    fun resolve(context: Context, addresses: Collection<String>): Map<String, ContactInfo> {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyMap()
        }

        return addresses
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .associateWithNotNull { address -> resolveOne(context, address) }
    }

    private fun resolveOne(context: Context, address: String): ContactInfo? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.NUMBER
        )

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null

            val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.NUMBER)
            val displayName = cursor.getStringOrNull(nameIndex)?.takeIf { it.isNotBlank() }
                ?: return null
            val phoneNumber = cursor.getStringOrNull(numberIndex)?.takeIf { it.isNotBlank() }
                ?: address

            return ContactInfo(displayName = displayName, phoneNumber = phoneNumber)
        }

        return null
    }

    fun fetchAllContacts(context: Context): List<ContactInfo> {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val contacts = mutableListOf<ContactInfo>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            context.contentResolver.query(
                uri, 
                projection, 
                null, 
                null, 
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                while (cursor.moveToNext()) {
                    val displayName = cursor.getStringOrNull(nameIndex)?.takeIf { it.isNotBlank() } ?: "Inconnu"
                    val phoneNumber = cursor.getStringOrNull(numberIndex)?.takeIf { it.isNotBlank() } ?: continue
                    contacts.add(ContactInfo(displayName = displayName, phoneNumber = phoneNumber))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return contacts.distinctBy { it.phoneNumber.replace(" ", "").replace("-", "").trim() }
    }
}

private fun <K, V> Iterable<K>.associateWithNotNull(valueTransform: (K) -> V?): Map<K, V> {
    val destination = LinkedHashMap<K, V>()
    for (element in this) {
        val value = valueTransform(element)
        if (value != null) destination[element] = value
    }
    return destination
}

private fun android.database.Cursor.getStringOrNull(columnIndex: Int): String? {
    if (columnIndex < 0 || isNull(columnIndex)) return null
    return getString(columnIndex)
}
