package com.connexa.simgateway

import android.content.Context
import android.provider.ContactsContract

data class Contact(val name: String, val number: String)

/** Reads the device's own contacts (client phone). Requires READ_CONTACTS; call off the main thread. */
object ContactsStore {
    @Volatile private var cache: List<Contact>? = null

    fun cached(): List<Contact> = cache ?: emptyList()

    fun invalidate() { cache = null }

    fun load(ctx: Context): List<Contact> {
        val result = LinkedHashMap<String, Contact>()
        try {
            val cursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    val number = if (numIdx >= 0) c.getString(numIdx) else null
                    if (!name.isNullOrBlank() && !number.isNullOrBlank()) {
                        val clean = Proto.cleanNumber(number) ?: number.filter { it != ' ' }
                        val key = "$name|$clean"
                        if (!result.containsKey(key)) result[key] = Contact(name, clean)
                    }
                }
            }
        } catch (e: Exception) {
            // No permission, or provider unavailable; return whatever was found (possibly empty).
        }
        val list = result.values.sortedBy { it.name.lowercase() }
        cache = list
        return list
    }

    /** Best-effort name lookup for a number already in the cached list; null if not found. */
    fun nameFor(number: String): String? {
        val clean = Proto.cleanNumber(number) ?: number
        return cache?.firstOrNull { it.number == clean || it.number.endsWith(clean.takeLast(9)) }?.name
    }
}
