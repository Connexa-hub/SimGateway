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

    enum class Screen {
        ONBOARDING, HOME, PROVIDER, SEARCH, CONNECTING, CLIENT, DIALPAD, CALLING,
        CONTACT_INFO, CONTACT_EDIT, MESSAGES, CONVERSATION, LOG, SETTINGS
    }
    enum class ClientTab { RECENTS, CONTACTS }
    enum class RecentsFilter { ALL, MISSED }
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
    var settingsReturn = Screen.HOME
    var clientTab = ClientTab.RECENTS
    var recentsFilter = RecentsFilter.ALL
    var callReturnScreen = Screen.CLIENT
    var callReturnTab = ClientTab.RECENTS
    var searchQuery = ""
    var searchActive = false
    var threadSearchQuery = ""
    var openContact: Contact? = null
    var editingContact: Contact? = null
    var pickingRecipient = false
    var conversationNumber = ""
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

    // messaging
    var draftText = ""
    var messageStatus: String? = null
    var sendingMessage = false

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

        screen = when {
            !Prefs.onboardingDone(ctx) -> Screen.ONBOARDING
            Prefs.appMode(ctx) == "provider" -> Screen.PROVIDER
            Prefs.appMode(ctx) == "client" -> Screen.CLIENT
            else -> Screen.HOME
        }
    }

    fun finishOnboarding() {
        Prefs.setOnboardingDone(ctx())
        screen = Screen.HOME
        changed()
    }

    /** Locks the app into a role. Reachable again only through Settings > Switch mode. */
    fun chooseMode(mode: String) {
        Prefs.setAppMode(ctx(), mode)
        screen = if (mode == "provider") Screen.PROVIDER else Screen.CLIENT
        if (mode == "client") ContactsStore.invalidate()
        changed()
    }

    /** Called from Settings: stops whatever is currently running and returns to the role picker. */
    fun switchMode() {
        if (GatewayState.status != GatewayState.Status.STOPPED) appContext?.let { GatewayService.stop(it) }
        discoveryInstance?.stop()
        reconnectKey = null
        if (connState != ConnectionManager.State.DISCONNECTED) connection.disconnect()
        clearLiveCall()
        Prefs.setAppMode(ctx(), null)
        screen = Screen.HOME
        changed()
    }

    /** True once a mode is locked in: back-navigation must not surface Screen.HOME any more. */
    fun modeLocked(): Boolean = Prefs.appMode(ctx()) != null

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
                    if (screen == Screen.CONNECTING || screen == Screen.SEARCH) screen = Screen.CLIENT
                    EventLog.add("Connected to $gatewayName")
                    syncStatus()
                }
                ConnectionManager.State.AUTH_FAILED -> {
                    val busy = detail == "gateway_busy"
                    if (!busy) Prefs.clearClientPin(ctx())
                    EventLog.add(
                        when (detail) {
                            "locked" -> "Pairing locked by gateway"
                            "gateway_busy" -> "Gateway already has a connected phone"
                            else -> "Wrong PIN"
                        }
                    )
                    val g = target
                    screen = Screen.SEARCH
                    discovery().start()
                    if (g != null && !busy) pinPrompt = PinPrompt(g, friendly(detail ?: "auth_failed"))
                    else if (g != null) searchError = friendly("gateway_busy")
                }
                ConnectionManager.State.DISCONNECTED -> {
                    if (prev == ConnectionManager.State.CONNECTED || prev == ConnectionManager.State.RECONNECTING) {
                        EventLog.add("Disconnected from gateway")
                    }
                    clearLiveCall()
                    if (screen == Screen.CALLING) screen = Screen.CLIENT
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
                    if (screen != Screen.CALLING) {
                        callReturnScreen = screen
                        callReturnTab = clientTab
                        screen = Screen.CALLING
                    }
                }
                Proto.CALL_STATE -> applyRemoteCall(message.optString("state"), message.optString("number"))
                Proto.SMS_INCOMING -> {
                    val number = message.optString("number", "")
                    val text = message.optString("message", "")
                    val time = message.optLong("time", System.currentTimeMillis())
                    if (number.isNotEmpty() && text.isNotEmpty()) {
                        Messages.addIncoming(ctx(), number, text, time)
                        EventLog.add("Message received")
                        if (!(screen == Screen.CONVERSATION && conversationNumber == number)) {
                            showNotice("New message from ${ContactsStore.nameFor(number) ?: number}")
                        }
                    }
                }
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

    /** Cancels an in-progress search/connect and returns to the client tab shell (not Home). */
    fun cancelSearchToClient() {
        discovery().stop()
        reconnectKey = null
        if (connState != ConnectionManager.State.CONNECTED) connection.disconnect()
        clearLiveCall()
        screen = Screen.CLIENT
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
        val wasRinging = incomingNumber != null
        when (state) {
            "ringing" -> {
                incomingNumber = if (number.isNotEmpty()) number else (incomingNumber ?: "")
            }
            "in_call" -> {
                incomingNumber = null
                callUi = CallUi.IN_CALL
                if (number.isNotEmpty()) callNumber = number
                if (wasRinging) {
                    val n = if (number.isNotEmpty()) number else callNumber
                    CallHistory.add(ctx(), n, ContactsStore.nameFor(n), CallHistory.Type.INCOMING)
                    EventLog.add("Call answered")
                }
            }
            else -> {
                val wasActive = callUi == CallUi.DIALING || callUi == CallUi.IN_CALL || callUi == CallUi.ENDING
                if (wasRinging && callUi != CallUi.IN_CALL) {
                    val n = incomingNumber ?: ""
                    if (n.isNotEmpty()) CallHistory.add(ctx(), n, ContactsStore.nameFor(n), CallHistory.Type.MISSED)
                    EventLog.add("Missed call")
                }
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
                if (screen == Screen.CALLING) returnFromCall()
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
        if (screen == Screen.CALLING) returnFromCall()
        changed()
    }

    /** Leaves the full-screen caller UI and restores wherever the call was placed from. */
    private fun returnFromCall() {
        screen = callReturnScreen
        if (screen == Screen.CLIENT) clientTab = callReturnTab
    }

    /** Starts a call to the currently typed number. Used by the dial pad. */
    fun placeCall() {
        val n = Proto.cleanNumber(dial)
        if (n == null) {
            callFailed("invalid_number")
            return
        }
        placeCallTo(n)
    }

    /** Starts a call to a specific number, e.g. tapping a contact or a recent-call row. */
    fun placeCallTo(number: String) {
        if (connState != ConnectionManager.State.CONNECTED) {
            callFailed("not_connected")
            return
        }
        val n = Proto.cleanNumber(number) ?: run { callFailed("invalid_number"); return }
        dial = n
        callUi = CallUi.DIALING
        callNumber = n
        callMessage = null
        if (screen != Screen.CALLING) {
            callReturnScreen = screen
            callReturnTab = clientTab
        }
        screen = Screen.CALLING
        changed()
        EventLog.add("Call request sent to ${EventLog.mask(n)}")
        connection.request(Proto.CALL, mapOf("number" to n), 10000) { r ->
            if (r.ok) {
                EventLog.add("Call started")
                CallHistory.add(ctx(), n, ContactsStore.nameFor(n), CallHistory.Type.OUTGOING)
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
        val n = incomingNumber.orEmpty()
        connection.request(Proto.REJECT) { r ->
            if (r.ok) {
                if (n.isNotEmpty()) CallHistory.add(ctx(), n, ContactsStore.nameFor(n), CallHistory.Type.MISSED)
                incomingNumber = null
                if (callUi == CallUi.READY && screen == Screen.CALLING) returnFromCall()
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

    // ---- messaging -------------------------------------------------------------------------

    /** Opens (or creates) a conversation thread with this number and switches to it. */
    fun openConversation(number: String) {
        conversationNumber = number
        draftText = ""
        messageStatus = null
        Messages.markThreadRead(ctx(), number)
        screen = Screen.CONVERSATION
        changed()
    }

    fun sendMessage() {
        if (connState != ConnectionManager.State.CONNECTED) {
            messageStatus = friendly("not_connected")
            changed()
            return
        }
        val n = Proto.cleanNumber(conversationNumber)
        if (n == null) {
            messageStatus = friendly("invalid_number")
            changed()
            return
        }
        val text = draftText.trim()
        if (text.isEmpty()) return
        sendingMessage = true
        messageStatus = null
        changed()
        connection.request(Proto.SMS, mapOf("number" to n, "message" to text), 15000) { r ->
            sendingMessage = false
            if (r.ok) {
                Messages.addOutgoing(ctx(), n, text)
                draftText = ""
                EventLog.add("Message handed to gateway")
            } else {
                messageStatus = friendly(r.error)
                EventLog.add("Message failed")
            }
            changed()
        }
    }

    fun deleteConversation(number: String) {
        Messages.deleteThread(ctx(), number)
    }

    // ---- messages --------------------------------------------------------------------------

    fun friendly(code: String?): String = when (code) {
        "invalid_number" -> "That phone number doesn't look valid."
        "invalid_message" -> "That message is empty or too long."
        "permission_denied" -> "The gateway phone hasn't allowed this yet. Open LinkSIM on that phone and grant permissions."
        "busy" -> "The gateway phone is already on a call."
        "no_sim" -> "The gateway phone has no working SIM."
        "telephony_unavailable" -> "Phone service isn't available on the gateway phone."
        "timeout" -> "The request timed out. Check the Wi-Fi connection."
        "connection_lost", "not_connected" -> "Not connected to the gateway."
        "no_ringing_call" -> "There's no incoming call to answer."
        "no_active_call" -> "There's no active call."
        "unsupported" -> "This Android version doesn't allow that action."
        "locked" -> "Too many wrong PINs. Try again in a minute."
        "gateway_busy" -> "That gateway already has a phone connected. Try again once it's free."
        "auth_failed" -> "That PIN isn't right. Check the PIN shown on the SIM phone."
        else -> "Something went wrong on the gateway phone."
    }
}
