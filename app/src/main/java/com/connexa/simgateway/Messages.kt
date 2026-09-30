package com.connexa.simgateway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Local SMS conversation history, relayed through the gateway phone's SIM. Sent messages are
 * recorded when the gateway confirms it handed them off; received messages arrive via the
 * SMS_INCOMING protocol event. Stored only on this device.
 */
object Messages {
    enum class Direction { OUT, IN }

    data class Entry(val id: Long, val number: String, val direction: Direction, val text: String, val time: Long, val read: Boolean)

    data class Thread(val number: String, val name: String?, val lastText: String, val lastTime: Long, val unread: Int)

    private const val MAX_ENTRIES = 500
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
            val raw = prefs(ctx).getString("messages", null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    Entry(
                        o.optLong("id"), o.optString("number"),
                        Direction.valueOf(o.optString("dir", "OUT")),
                        o.optString("text"), o.optLong("time"), o.optBoolean("read", true)
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
                put("dir", e.direction.name)
                put("text", e.text)
                put("time", e.time)
                put("read", e.read)
            })
        }
        prefs(ctx).edit().putString("messages", arr.toString()).apply()
    }

    fun addOutgoing(ctx: Context, number: String, text: String) {
        ensureLoaded(ctx)
        entries.add(0, Entry(System.nanoTime(), number, Direction.OUT, text, System.currentTimeMillis(), true))
        trim()
        persist()
        notifyChanged()
    }

    fun addIncoming(ctx: Context, number: String, text: String, time: Long) {
        ensureLoaded(ctx)
        entries.add(0, Entry(System.nanoTime(), number, Direction.IN, text, time, false))
        trim()
        persist()
        notifyChanged()
    }

    private fun trim() {
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
    }

    fun markThreadRead(ctx: Context, number: String) {
        ensureLoaded(ctx)
        var changed = false
        for (i in entries.indices) {
            val e = entries[i]
            if (e.number == number && e.direction == Direction.IN && !e.read) {
                entries[i] = e.copy(read = true)
                changed = true
            }
        }
        if (changed) {
            persist()
            notifyChanged()
        }
    }

    fun conversation(ctx: Context, number: String): List<Entry> {
        ensureLoaded(ctx)
        return entries.filter { it.number == number }.sortedBy { it.time }
    }

    fun threads(ctx: Context): List<Thread> {
        ensureLoaded(ctx)
        val byNumber = entries.groupBy { it.number }
        return byNumber.map { (number, list) ->
            val last = list.maxByOrNull { it.time }!!
            val unread = list.count { it.direction == Direction.IN && !it.read }
            Thread(number, ContactsStore.nameFor(number), last.text, last.time, unread)
        }.sortedByDescending { it.lastTime }
    }

    fun deleteThread(ctx: Context, number: String) {
        ensureLoaded(ctx)
        entries.removeAll { it.number == number }
        persist()
        notifyChanged()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { for (l in listeners) l() }
}
