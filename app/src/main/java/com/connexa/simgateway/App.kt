package com.connexa.simgateway

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Client-side application model. Lives at process scope so UI recreation (theme change, etc.)
 * never loses connection or call state. The Activity only renders this and forwards taps.
 */
object App {

    enum class Screen { HOME, PROVIDER, SEARCH, CONNECTING, HUB, DIALER, SMS, LOG }
    enum class CallUi { READY, DIALING, IN_CALL, ENDING, ENDED, FAILED }

    class PinPrompt(val gateway: Discovery.Gateway, val reason: String?)

    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    private var discoveryInstance: Discovery? = null

    val connection = ConnectionManager()

    // navigation / discovery
    var screen = Screen.HOME
    var launchHandled = false
    var logReturn = Screen.HOME
    var gateways: List<Discovery.Gateway> = emptyList()
    var searching = false
    var searchTimedOut = false
    var searchError: String? = null
    var showDetails = false
    var target: Discovery.Gateway? = null
    var gatewayName = ""
    var pinPrompt: PinPrompt? = null

    // connection
    var connState = ConnectionManager.State.DISCONNECTED
    var remoteSim = "unknown"
    var remoteCanCall = true
    var remoteCanSms = true
    var notice: String? = null

    // calls
    var callUi = CallUi.READY
    var callMessage: String? = null
    var callNumber = ""
    var incomingNumber: String? = null
    var dial = ""

    // sms
    var smsNumber = ""
    var smsText = ""
    var smsStatus: String? = null
    var smsOk = false
    var smsSending = false

    private var pendingPin = ""
    private var reconnectKey: String? = null
    private var revertRunnable: Runnable? = null
    private val noticeClear = Runnable {
        notice = null
        changed()
    }

    fun init(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        connection.addListener(connListener)
        val d = Discovery(ctx.applicationContext)
        d.listener = discListener
        discoveryInstance = d
    }

    private fun ctx(): Context = appContext!!
    private fun discovery(): Discovery = discoveryInstance!!

    fun addObserver(o: () -> Unit) { observers.add(o) }
    fun removeObserver(o: () -> Unit) { observers.remove(o) }

    fun changed() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (o in observers) o()
        } else {
            main.post { for (o in observers) o() }
        }
    }

    // ---- listeners -------------------------------------------------------------------------

    private val connListener = object : ConnectionManager.Listener {
        override fun onStateChanged(state: ConnectionManager.State, detail: String?) {
            val prev = connState
            connState = state
            when (state) {
                ConnectionManager.State.CONNECTED -> {
                    Prefs.setClientPin(ctx(), pendingPin)
                    if (screen == Screen.CONNECTING || screen == Screen.SEARCH) screen = Screen.HUB
                    EventLog.add("Connected to $gatewayName")
                    syncStatus()
                }
                ConnectionManager.State.AUTH_FAILED -> {
                    Prefs.clearClientPin(ctx())
                    val locked = detail == "locked"
                    EventLog.add(if (locked) "Pairing locked by gateway" else "Wrong PIN")
                    val g = target
                    screen = Screen.SEARCH
                    discovery().start()
                    if (g != null) pinPrompt = PinPrompt(g, friendly(if (locked) "locked" else "auth_failed"))
                }
                ConnectionManager.State.DISCONNECTED -> {
                    if (prev == ConnectionManager.State.CONNECTED || prev == ConnectionManager.State.RECONNECTING) {
                        EventLog.add("Disconnected from gateway")
                    }
                    clearLiveCall()
                    if (screen == Screen.CONNECTING && detail == "unreachable") {
                        screen = Screen.SEARCH
                        discovery().start()
                        searchError = "Couldn't reach that gateway. Make sure both phones are on the same Wi-Fi."
                    }
                }
                ConnectionManager.State.RECONNECTING -> {
                    if (prev == ConnectionManager.State.CONNECTED) {
                        EventLog.add("Connection lost, reconnecting")
                        clearLiveCall()
                    }
                }
                else -> {
                }
            }
            changed()
        }

        override fun onEvent(message: JSONObject) {
            when (message.optString("type")) {
                Proto.INCOMING_CALL -> {
                    if (incomingNumber == null) EventLog.add("Incoming call")
                    incomingNumber = message.optString("number", "")
                }
                Proto.CALL_STATE -> applyRemoteCall(message.optString("state"), message.optString("number"))
            }
            changed()
        }
    }

    private val discListener = object : Discovery.Listener {
        override fun onGatewaysChanged(list: List<Discovery.Gateway>) {
            gateways = list
            val key = reconnectKey
            if (key != null) {
                val g = list.firstOrNull { it.key == key }
                if (g != null) {
                    reconnectKey = null
                    val pin = Prefs.clientPin(ctx())
                    if (pin != null) connectWith(g, pin) else selectGateway(g)
                }
            }
            changed()
        }

        override fun onSearchState(searching: Boolean, timedOut: Boolean) {
            this@App.searching = searching
            searchTimedOut = timedOut
            if (timedOut && reconnectKey != null) {
                reconnectKey = null
                screen = Screen.SEARCH
            }
            changed()
        }

        override fun onError(message: String) {
            searchError = message
            searching = false
            changed()
        }
    }

    // ---- navigation / connection actions ---------------------------------------------------

    fun startSearch() {
        reconnectKey = null
        if (connection.state != ConnectionManager.State.DISCONNECTED) connection.disconnect()
        screen = Screen.SEARCH
        gateways = emptyList()
        searchError = null
        searchTimedOut = false
        searching = true
        discovery().start()
        changed()
    }

    fun leaveClient() {
        discovery().stop()
        reconnectKey = null
        connection.disconnect()
        clearLiveCall()
        screen = Screen.HOME
        changed()
    }

    fun selectGateway(g: Discovery.Gateway) {
        target = g
        gatewayName = g.name
        val pin = Prefs.clientPin(ctx())
        if (pin == null) {
            pinPrompt = PinPrompt(g, null)
            changed()
        } else {
            connectWith(g, pin)
        }
    }

    fun submitPin(g: Discovery.Gateway, pin: String) {
        pinPrompt = null
        connectWith(g, pin.trim())
    }

    fun cancelPin() {
        pinPrompt = null
        changed()
    }

    private fun connectWith(g: Discovery.Gateway, pin: String) {
        target = g
        gatewayName = g.name
        pendingPin = pin
        discovery().stop()
        searchError = null
        screen = Screen.CONNECTING
        connection.connect(g, pin)
        changed()
    }

    fun cancelConnect() {
        reconnectKey = null
        connection.disconnect()
        startSearch()
    }

    fun reconnect() {
        val g = target
        if (g == null) {
            startSearch()
            return
        }
        // Re-discover by service name: never rely on a remembered IP address.
        reconnectKey = g.key
        connection.disconnect()
        screen = Screen.CONNECTING
        discovery().start()
        changed()
    }

    fun userDisconnect() {
        EventLog.add("Disconnected by user")
        connection.disconnect()
        clearLiveCall()
        startSearch()
    }

    val isLookingForGateway: Boolean get() = reconnectKey != null

    // ---- status / calls --------------------------------------------------------------------

    private fun syncStatus() {
        connection.request(Proto.STATUS) { r ->
            val m = r.message
            if (r.ok && m != null) {
                remoteSim = m.optString("sim", "unknown")
                remoteCanCall = m.optBoolean("can_call", true)
                remoteCanSms = m.optBoolean("can_sms", true)
                val cs = m.optString("call_state", "idle")
                val num = m.optString("number", "")
                if (cs == "ringing") {
                    incomingNumber = num
                } else if (cs == "in_call") {
                    callUi = CallUi.IN_CALL
                    callNumber = num
                }
                changed()
            }
        }
    }

    private fun applyRemoteCall(state: String, number: String) {
        when (state) {
            "ringing" -> {
                incomingNumber = if (number.isNotEmpty()) number else (incomingNumber ?: "")
            }
            "in_call" -> {
                incomingNumber = null
                callUi = CallUi.IN_CALL
                if (number.isNotEmpty()) callNumber = number
            }
            else -> {
                val wasActive = callUi == CallUi.DIALING || callUi == CallUi.IN_CALL || callUi == CallUi.ENDING
                incomingNumber = null
                if (wasActive) {
                    callUi = CallUi.ENDED
                    EventLog.add("Call ended")
                    scheduleRevert()
                }
            }
        }
    }

    private fun clearLiveCall() {
        incomingNumber = null
        if (callUi != CallUi.FAILED) callUi = CallUi.READY
    }

    private fun scheduleRevert() {
        revertRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (callUi == CallUi.ENDED) {
                callUi = CallUi.READY
                changed()
            }
        }
        revertRunnable = r
        main.postDelayed(r, 3000)
    }

    fun dialPress(c: Char) {
        if (callUi == CallUi.ENDED || callUi == CallUi.FAILED) callUi = CallUi.READY
        if (dial.length < 20) dial += c
        changed()
    }

    fun dialBackspace() {
        if (dial.isNotEmpty()) dial = dial.dropLast(1)
        changed()
    }

    fun dialClear() {
        dial = ""
        changed()
    }

    fun dismissCall() {
        callUi = CallUi.READY
        callMessage = null
        changed()
    }

    fun placeCall() {
        if (connState != ConnectionManager.State.CONNECTED) {
            callFailed("not_connected")
            return
        }
        val n = Proto.cleanNumber(dial)
        if (n == null) {
            callFailed("invalid_number")
            return
        }
        callUi = CallUi.DIALING
        callNumber = n
        callMessage = null
        changed()
        EventLog.add("Call request sent to ${EventLog.mask(n)}")
        connection.request(Proto.CALL, mapOf("number" to n), 10000) { r ->
            if (r.ok) {
                EventLog.add("Call started")
                val tracking = r.message?.optBoolean("tracking", true) ?: true
                if (!tracking && callUi == CallUi.DIALING) callUi = CallUi.IN_CALL
                changed()
            } else {
                callFailed(r.error)
            }
        }
    }

    private fun callFailed(code: String?) {
        callUi = CallUi.FAILED
        callMessage = friendly(code)
        EventLog.add("Call failed")
        changed()
    }

    fun hangup() {
        callUi = CallUi.ENDING
        changed()
        connection.request(Proto.HANGUP) { r ->
            if (!r.ok) {
                callUi = CallUi.FAILED
                callMessage = friendly(r.error)
                changed()
            }
        }
    }

    fun answer() {
        connection.request(Proto.ANSWER) { r ->
            if (!r.ok) showNotice(friendly(r.error))
        }
    }

    fun reject() {
        connection.request(Proto.REJECT) { r ->
            if (r.ok) {
                incomingNumber = null
                changed()
            } else {
                showNotice(friendly(r.error))
            }
        }
    }

    fun showNotice(text: String) {
        notice = text
        main.removeCallbacks(noticeClear)
        main.postDelayed(noticeClear, 5000)
        changed()
    }

    // ---- sms -------------------------------------------------------------------------------

    fun sendSms() {
        if (connState != ConnectionManager.State.CONNECTED) {
            smsOk = false
            smsStatus = friendly("not_connected")
            changed()
            return
        }
        val n = Proto.cleanNumber(smsNumber)
        if (n == null) {
            smsOk = false
            smsStatus = friendly("invalid_number")
            changed()
            return
        }
        val text = smsText.trim()
        if (text.isEmpty()) {
            smsOk = false
            smsStatus = "Type a message first."
            changed()
            return
        }
        smsSending = true
        smsStatus = null
        changed()
        EventLog.add("SMS request sent to ${EventLog.mask(n)}")
        connection.request(Proto.SMS, mapOf("number" to n, "message" to text), 15000) { r ->
            smsSending = false
            if (r.ok) {
                smsOk = true
                smsStatus = "Handed to the gateway phone for sending."
                smsText = ""
                EventLog.add("SMS handed to gateway")
            } else {
                smsOk = false
                smsStatus = friendly(r.error)
                EventLog.add("SMS failed")
            }
            changed()
        }
    }

    // ---- messages --------------------------------------------------------------------------

    fun friendly(code: String?): String = when (code) {
        "invalid_number" -> "That phone number doesn't look valid."
        "invalid_message" -> "That message is empty or too long."
        "permission_denied" -> "The gateway phone hasn't allowed this yet. Open SIM Gateway on that phone and grant permissions."
        "busy" -> "The gateway phone is already on a call."
        "no_sim" -> "The gateway phone has no working SIM."
        "telephony_unavailable" -> "Phone service isn't available on the gateway phone."
        "timeout" -> "The request timed out. Check the Wi-Fi connection."
        "connection_lost", "not_connected" -> "Not connected to the gateway."
        "no_ringing_call" -> "There's no incoming call to answer."
        "no_active_call" -> "There's no active call."
        "unsupported" -> "This Android version doesn't allow that action."
        "locked" -> "Too many wrong PINs. Try again in a minute."
        "auth_failed" -> "That PIN isn't right. Check the PIN shown on the SIM phone."
        else -> "Something went wrong on the gateway phone."
    }
}
