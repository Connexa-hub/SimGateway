package com.connexa.simgateway

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

data class Contact(val contactId: Long, val name: String, val number: String)

/** Reads and edits the device's own contacts (client phone). Requires READ/WRITE_CONTACTS. */
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
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    val number = if (numIdx >= 0) c.getString(numIdx) else null
                    val id = if (idIdx >= 0) c.getLong(idIdx) else -1L
                    if (!name.isNullOrBlank() && !number.isNullOrBlank()) {
                        val clean = Proto.cleanNumber(number) ?: number.filter { it != ' ' }
                        val key = "$name|$clean"
                        if (!result.containsKey(key)) result[key] = Contact(id, name, clean)
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

    fun findByNumber(number: String): Contact? {
        val clean = Proto.cleanNumber(number) ?: number
        return cache?.firstOrNull { it.number == clean || it.number.endsWith(clean.takeLast(9)) }
    }

    /** Requires WRITE_CONTACTS. Returns true on success. */
    fun addContact(ctx: Context, name: String, number: String): Boolean {
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            invalidate()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Requires WRITE_CONTACTS. Updates the display name and the first phone number found. */
    fun updateContact(ctx: Context, contactId: Long, name: String, number: String): Boolean {
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            dataRowId(ctx, contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)?.let { dataId ->
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(dataId.toString()))
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build()
                )
            }
            dataRowId(ctx, contactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)?.let { dataId ->
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(dataId.toString()))
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .build()
                )
            }
            if (ops.isEmpty()) return false
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            invalidate()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Requires WRITE_CONTACTS. */
    fun deleteContact(ctx: Context, contactId: Long): Boolean {
        return try {
            ctx.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString())
            )
            invalidate()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun dataRowId(ctx: Context, contactId: Long, mimeType: String): Long? {
        val cursor = ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), mimeType), null
        )
        cursor?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }
}
