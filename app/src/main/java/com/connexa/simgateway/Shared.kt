package com.connexa.simgateway

import android.content.Context
import android.telephony.TelephonyManager
import java.io.IOException
import java.io.Reader
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory activity history (numbers are masked, message text is never stored). */
object EventLog {
    data class Entry(val time: Long, val text: String)

    private val entries = mutableListOf<Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun add(text: String) {
        synchronized(entries) {
            entries.add(Entry(System.currentTimeMillis(), text))
            while (entries.size > 200) entries.removeAt(0)
        }
        for (l in listeners) l()
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.reversed() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        for (l in listeners) l()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun mask(number: String?): String {
        val digits = number.orEmpty().filter { it.isDigit() }
        return if (digits.length <= 3) "\u2022\u2022\u2022\u2022" else "\u2022\u2022\u2022\u2022" + digits.takeLast(3)
    }

    fun timeLabel(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))
}

/** Observable provider-side state, written by GatewayService and read by the UI. */
object GatewayState {
    enum class Status { STOPPED, STARTING, ONLINE, ERROR }

    @Volatile var status = Status.STOPPED
    @Volatile var clientCount = 0
    @Volatile var discovery = "Not started"
    @Volatile var startedAt = 0L
    @Volatile var error: String? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { for (l in listeners) l() }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("simgateway", Context.MODE_PRIVATE)

    private fun newPin(): String = String.format(Locale.US, "%06d", SecureRandom().nextInt(1000000))

    fun providerPin(ctx: Context): String {
        val existing = sp(ctx).getString("provider_pin", null)
        if (existing != null) return existing
        val pin = newPin()
        sp(ctx).edit().putString("provider_pin", pin).apply()
        return pin
    }

    fun regeneratePin(ctx: Context): String {
        val pin = newPin()
        sp(ctx).edit().putString("provider_pin", pin).apply()
        return pin
    }

    fun clientPin(ctx: Context): String? = sp(ctx).getString("client_pin", null)
    fun setClientPin(ctx: Context, pin: String) { sp(ctx).edit().putString("client_pin", pin).apply() }
    fun clearClientPin(ctx: Context) { sp(ctx).edit().remove("client_pin").apply() }
}

/** "ready", "absent", "unknown" or "not_ready". Needs no permission. */
fun simState(ctx: Context): String {
    val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return "unknown"
    return try {
        when (tm.simState) {
            TelephonyManager.SIM_STATE_READY -> "ready"
            TelephonyManager.SIM_STATE_ABSENT -> "absent"
            TelephonyManager.SIM_STATE_UNKNOWN -> "unknown"
            else -> "not_ready"
        }
    } catch (e: Exception) {
        "unknown"
    }
}

/** Reads one line, refusing lines longer than [max] chars so a hostile peer can't exhaust memory. */
@Throws(IOException::class)
fun readLineBounded(r: Reader, max: Int): String? {
    val sb = StringBuilder()
    while (true) {
        val c = r.read()
        if (c == -1) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) return sb.toString()
        if (c != '\r'.code) {
            sb.append(c.toChar())
            if (sb.length > max) throw IOException("line too long")
        }
    }
}
