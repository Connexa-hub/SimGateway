package com.connexa.simgateway

import android.content.Context
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * User-facing call log, like a normal phone app's Recents tab: real numbers, resolved contact
 * names, swipe-to-delete. Kept separate from EventLog (which masks numbers for diagnostics) since
 * this is deliberately the unmasked, user-owned record of who was called, stored only on-device.
 */
object CallHistory {
    enum class Type { OUTGOING, INCOMING, MISSED }

    data class Entry(val id: Long, val number: String, val name: String?, val type: Type, val time: Long)

    private const val MAX_ENTRIES = 300
    private var loaded = false
    private val entries = mutableListOf<Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var appCtx: Context? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("simgateway", Context.MODE_PRIVATE)

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        appCtx = ctx.applicationContext
        try {
            val raw = prefs(ctx).getString("call_history", null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    Entry(
                        o.optLong("id"),
                        o.optString("number"),
                        o.optString("name").ifEmpty { null },
                        Type.valueOf(o.optString("type", "OUTGOING")),
                        o.optLong("time")
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupt or missing history is not fatal; start empty.
        }
    }

    private fun persist() {
        val ctx = appCtx ?: return
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("number", e.number)
                put("name", e.name ?: "")
                put("type", e.type.name)
                put("time", e.time)
            })
        }
        prefs(ctx).edit().putString("call_history", arr.toString()).apply()
    }

    fun add(ctx: Context, number: String, name: String?, type: Type) {
        ensureLoaded(ctx)
        entries.add(0, Entry(System.currentTimeMillis(), number, name, type, System.currentTimeMillis()))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        persist()
        notifyChanged()
    }

    fun remove(ctx: Context, id: Long) {
        ensureLoaded(ctx)
        entries.removeAll { it.id == id }
        persist()
        notifyChanged()
    }

    fun clear(ctx: Context) {
        ensureLoaded(ctx)
        entries.clear()
        persist()
        notifyChanged()
    }

    fun snapshot(ctx: Context): List<Entry> {
        ensureLoaded(ctx)
        return entries.toList()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { for (l in listeners) l() }

    // ---- device call log (native calls, e.g. from before LinkSIM was used) --------------------

    private fun hiddenSet(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet("hidden_call_ids", emptySet())!!.toMutableSet()

    private fun hideSystemEntry(ctx: Context, id: Long) {
        val set = hiddenSet(ctx)
        set.add(id.toString())
        prefs(ctx).edit().putStringSet("hidden_call_ids", set).apply()
    }

    /** Reads the device's own Call Log (requires READ_CALL_LOG). Synthetic ids are negative. */
    private fun systemCallLog(ctx: Context): List<Entry> {
        val hidden = hiddenSet(ctx)
        val out = mutableListOf<Entry>()
        try {
            val cursor = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null,
                "${CallLog.Calls.DATE} DESC LIMIT 200"
            )
            cursor?.use { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val number = if (numIdx >= 0) c.getString(numIdx) else null
                    if (number.isNullOrBlank()) continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    val type = if (typeIdx >= 0) c.getInt(typeIdx) else CallLog.Calls.OUTGOING_TYPE
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                    val raw = number.hashCode().toLong() * 1_000_003L + date
                    val id = -(Math.abs(raw).coerceAtLeast(1L))
                    if (hidden.contains(id.toString())) continue
                    val kind = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> Type.INCOMING
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Type.MISSED
                        else -> Type.OUTGOING
                    }
                    out.add(Entry(id, number, name, kind, date))
                }
            }
        } catch (e: Exception) {
            // No permission granted yet, or the provider is unavailable; return what we have.
        }
        return out
    }

    /** Device call log merged with app-tracked calls, newest first. */
    fun merged(ctx: Context): List<Entry> =
        (systemCallLog(ctx) + snapshot(ctx)).sortedByDescending { it.time }

    /** Removes an entry regardless of source: app-tracked calls are deleted, device-log entries are hidden. */
    fun removeMerged(ctx: Context, id: Long) {
        if (id > 0) remove(ctx, id) else hideSystemEntry(ctx, id)
    }
}
