package com.connexa.simgateway

import android.content.Context
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
}
