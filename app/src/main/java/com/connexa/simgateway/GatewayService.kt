package com.connexa.simgateway

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Provider: foreground service that advertises the gateway over NSD, accepts PIN-authenticated
 * clients on TCP 8765 and executes call / SMS requests using this phone's SIM.
 */
class GatewayService : Service() {

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "sim_gateway_channel"
        private const val NOTIFICATION_ID = 1001
        private const val AUTH_TIMEOUT_MS = 10000
        private const val IDLE_TIMEOUT_MS = 35000
        private const val MAX_LINE = 8192
        private const val MAX_FAILED_AUTH = 5
        private const val LOCKOUT_MS = 60000L

        const val ACTION_STOP = "com.connexa.simgateway.STOP"
        const val ACTION_REFRESH = "com.connexa.simgateway.REFRESH_PERMISSIONS"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GatewayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GatewayService::class.java))
        }

        fun refreshPermissions(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, GatewayService::class.java).apply { action = ACTION_REFRESH }
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = CopyOnWriteArrayList<ClientSession>()

    @Volatile private var stopping = false
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var lastNsdRegisterAt = 0L
    private var phoneReceiver: BroadcastReceiver? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var phoneState = "idle" // idle | ringing | in_call
    @Volatile private var phoneNumber = ""
    private var failedAuth = 0
    private var lockedUntil = 0L

    // ---- lifecycle -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createChannel()
        GatewayState.status = GatewayState.Status.STARTING
        GatewayState.error = null
        GatewayState.clientCount = 0
        GatewayState.discovery = "Starting"
        GatewayState.startedAt = System.currentTimeMillis()
        GatewayState.changed()

        try {
            val n = buildNotification("Starting gateway\u2026")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            EventLog.add("Gateway could not start (foreground service refused)")
            GatewayState.status = GatewayState.Status.ERROR
            GatewayState.error = "Android refused to start the gateway service."
            GatewayState.changed()
            stopSelf()
            return
        }

        EventLog.add("Gateway starting")
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        acquireLocks()
        registerPhoneReceiver()
        registerNetworkCallback()
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                unregisterPhoneReceiver()
                registerPhoneReceiver()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        unregisterNsd()
        unregisterPhoneReceiver()
        try {
            netCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        netCallback = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        serverThread?.interrupt()
        for (s in sessions) s.close()
        sessions.clear()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        if (GatewayState.status != GatewayState.Status.ERROR) {
            GatewayState.status = GatewayState.Status.STOPPED
        }
        GatewayState.clientCount = 0
        GatewayState.discovery = "Not started"
        GatewayState.changed()
        EventLog.add("Gateway stopped")
        super.onDestroy()
    }

    // ---- TCP server ------------------------------------------------------------------------

    private fun startServer() {
        serverThread = thread(name = "SimGatewayServer") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(Proto.PORT))
                serverSocket = ss
                GatewayState.status = GatewayState.Status.ONLINE
                GatewayState.changed()
                registerNsd()
                refreshNotification()
                EventLog.add("Gateway online on port ${Proto.PORT}")

                while (!stopping) {
                    val client = ss.accept()
                    val session = ClientSession(client)
                    sessions.add(session)
                    thread(name = "SimGatewayClient") { session.run() }
                }
            } catch (e: Exception) {
                if (!stopping) {
                    Log.e(TAG, "server failed", e)
                    GatewayState.status = GatewayState.Status.ERROR
                    GatewayState.error = "Couldn't open port ${Proto.PORT}. Is another copy already running?"
                    GatewayState.changed()
                    EventLog.add("Gateway error: ${e.javaClass.simpleName}")
                    refreshNotification()
                }
            }
        }
    }

    private inner class ClientSession(private val socket: Socket) {
        @Volatile var authed = false
        private val writeLock = Any()
        private lateinit var out: OutputStream

        fun send(obj: JSONObject) {
            val bytes = (obj.toString() + "\n").toByteArray(Charsets.UTF_8)
            synchronized(writeLock) {
                out.write(bytes)
                out.flush()
            }
        }

        fun trySend(obj: JSONObject) {
            try {
                send(obj)
            } catch (e: Exception) {
                close()
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = AUTH_TIMEOUT_MS
                out = socket.getOutputStream()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                send(Proto.build(Proto.HELLO, "0") {
                    put("auth", true)
                    put("name", Build.MODEL)
                })
                while (true) {
                    val line = readLineBounded(reader, MAX_LINE) ?: break
                    val msg = Proto.parse(line) ?: continue
                    if (!handle(msg)) break
                }
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "client timed out")
            } catch (e: Exception) {
                if (!stopping) Log.d(TAG, "client ended: ${e.javaClass.simpleName}")
            } finally {
                close()
                sessions.remove(this)
                if (authed) {
                    GatewayState.clientCount = sessions.count { it.authed }
                    GatewayState.changed()
                    EventLog.add("Device disconnected")
                    refreshNotification()
                }
            }
        }

        /** Returns false if the connection should be closed. */
        private fun handle(msg: JSONObject): Boolean {
            val id = msg.optString("id", "")
            val type = msg.optString("type", "")
            if (msg.optInt("v", 0) != Proto.VERSION) {
                send(Proto.err(id, "unsupported_version"))
                return false
            }

            if (!authed) {
                if (type != Proto.AUTH) {
                    send(Proto.err(id, "unauthorized"))
                    return false
                }
                return authenticate(id, msg.optString("pin", ""))
            }

            when (type) {
                Proto.PING -> send(Proto.build(Proto.PONG, id))
                Proto.STATUS -> send(Proto.build(Proto.STATUS_RESPONSE, id) {
                    put("online", true)
                    put("sim", simState(this@GatewayService))
                    put("call_state", phoneState)
                    put("number", phoneNumber)
                    put("incoming_alerts", granted(Manifest.permission.READ_PHONE_STATE))
                    put("can_call", granted(Manifest.permission.CALL_PHONE))
                    put("can_sms", granted(Manifest.permission.SEND_SMS))
                })
                Proto.CALL -> handleCall(id, msg)
                Proto.HANGUP, Proto.REJECT -> handleHangup(id, type)
                Proto.ANSWER -> handleAnswer(id)
                Proto.SMS -> handleSms(id, msg)
                else -> send(Proto.err(id, "unknown_type"))
            }
            return true
        }

        private fun authenticate(id: String, given: String): Boolean {
            val now = System.currentTimeMillis()
            val locked = synchronized(this@GatewayService) { now < lockedUntil }
            if (locked) {
                send(Proto.err(id, "locked"))
                return false
            }
            val expected = Prefs.providerPin(applicationContext)
            if (MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))) {
                synchronized(this@GatewayService) { failedAuth = 0 }
                authed = true
                socket.soTimeout = IDLE_TIMEOUT_MS
                send(Proto.build(Proto.AUTH_OK, id) { put("name", Build.MODEL) })
                GatewayState.clientCount = sessions.count { it.authed }
                GatewayState.changed()
                EventLog.add("Device connected")
                refreshNotification()
                return true
            }
            synchronized(this@GatewayService) {
                failedAuth++
                if (failedAuth >= MAX_FAILED_AUTH) {
                    failedAuth = 0
                    lockedUntil = now + LOCKOUT_MS
                }
            }
            EventLog.add("Rejected a pairing attempt (wrong PIN)")
            send(Proto.err(id, "auth_failed"))
            return false
        }

        private fun handleCall(id: String, msg: JSONObject) {
            val number = Proto.cleanNumber(msg.optString("number", ""))
            if (number == null) {
                send(Proto.err(id, "invalid_number", Proto.CALL_FAILED))
                return
            }
            if (!granted(Manifest.permission.CALL_PHONE)) {
                send(Proto.err(id, "permission_denied", Proto.CALL_FAILED))
                return
            }
            if (phoneState != "idle") {
                send(Proto.err(id, "busy", Proto.CALL_FAILED))
                return
            }
            if (simState(this@GatewayService) == "absent") {
                send(Proto.err(id, "no_sim", Proto.CALL_FAILED))
                return
            }
            val telecom = getSystemService(TelecomManager::class.java)
            if (telecom == null) {
                send(Proto.err(id, "telephony_unavailable", Proto.CALL_FAILED))
                return
            }
            try {
                phoneNumber = number
                // TelecomManager.placeCall is not subject to background-activity-start limits.
                telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
                EventLog.add("Call started to ${EventLog.mask(number)}")
                send(Proto.build(Proto.CALL_STARTED, id) {
                    put("number", number)
                    put("tracking", granted(Manifest.permission.READ_PHONE_STATE))
                })
            } catch (e: SecurityException) {
                phoneNumber = ""
                send(Proto.err(id, "permission_denied", Proto.CALL_FAILED))
            } catch (e: Exception) {
                phoneNumber = ""
                Log.e(TAG, "placeCall failed", e)
                send(Proto.err(id, "failed", Proto.CALL_FAILED))
            }
        }

        private fun handleHangup(id: String, type: String) {
            val okType = if (type == Proto.REJECT) Proto.REJECT_OK else Proto.HANGUP_OK
            if (phoneState == "idle") {
                send(Proto.err(id, "no_active_call"))
                return
            }
            if (Build.VERSION.SDK_INT < 28) {
                send(Proto.err(id, "unsupported"))
                return
            }
            if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
                send(Proto.err(id, "permission_denied"))
                return
            }
            try {
                val ended = getSystemService(TelecomManager::class.java)?.endCall() ?: false
                if (ended) {
                    EventLog.add(if (type == Proto.REJECT) "Call rejected" else "Call ended by remote device")
                    send(Proto.build(okType, id))
                } else {
                    send(Proto.err(id, "failed"))
                }
            } catch (e: SecurityException) {
                send(Proto.err(id, "permission_denied"))
            } catch (e: Exception) {
                send(Proto.err(id, "failed"))
            }
        }

        private fun handleAnswer(id: String) {
            if (phoneState != "ringing") {
                send(Proto.err(id, "no_ringing_call"))
                return
            }
            if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
                send(Proto.err(id, "permission_denied"))
                return
            }
            try {
                getSystemService(TelecomManager::class.java)?.acceptRingingCall()
                EventLog.add("Call answered from remote device")
                send(Proto.build(Proto.ANSWER_OK, id))
            } catch (e: SecurityException) {
                send(Proto.err(id, "permission_denied"))
            } catch (e: Exception) {
                send(Proto.err(id, "failed"))
            }
        }

        private fun handleSms(id: String, msg: JSONObject) {
            val number = Proto.cleanNumber(msg.optString("number", ""))
            val text = msg.optString("message", "")
            if (number == null) {
                send(Proto.err(id, "invalid_number"))
                return
            }
            if (text.isBlank() || text.length > 1000) {
                send(Proto.err(id, "invalid_message"))
                return
            }
            if (!granted(Manifest.permission.SEND_SMS)) {
                send(Proto.err(id, "permission_denied"))
                return
            }
            if (simState(this@GatewayService) == "absent") {
                send(Proto.err(id, "no_sim"))
                return
            }
            try {
                val sm: SmsManager? = smsManager()
                if (sm == null) {
                    send(Proto.err(id, "telephony_unavailable"))
                    return
                }
                val parts = sm.divideMessage(text)
                if (parts.size > 1) {
                    sm.sendMultipartTextMessage(number, null, parts, null, null)
                } else {
                    sm.sendTextMessage(number, null, text, null, null)
                }
                EventLog.add("SMS sent to ${EventLog.mask(number)}")
                send(Proto.build(Proto.SMS_SENT, id))
            } catch (e: SecurityException) {
                send(Proto.err(id, "permission_denied"))
            } catch (e: Exception) {
                Log.e(TAG, "sms failed", e)
                send(Proto.err(id, "failed"))
            }
        }
    }

    private fun broadcast(obj: JSONObject) {
        for (s in sessions) if (s.authed) s.trySend(obj)
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ---- phone state (incoming calls) ------------------------------------------------------

    private fun registerPhoneReceiver() {
        if (phoneReceiver != null) return
        if (!granted(Manifest.permission.READ_PHONE_STATE)) {
            EventLog.add("Incoming-call alerts off (permission not granted)")
            return
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                onPhoneStateChanged(state, number)
            }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(r, filter)
            }
            phoneReceiver = r
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver failed", e)
        }
    }

    private fun unregisterPhoneReceiver() {
        val r = phoneReceiver ?: return
        phoneReceiver = null
        try {
            unregisterReceiver(r)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun onPhoneStateChanged(state: String, number: String) {
        val newState = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "in_call"
            else -> "idle"
        }
        val numberChanged = number.isNotEmpty() && number != phoneNumber
        if (newState == phoneState && !numberChanged) return
        val previous = phoneState
        phoneState = newState
        if (number.isNotEmpty()) phoneNumber = number

        when (newState) {
            "ringing" -> {
                EventLog.add("Incoming call from ${EventLog.mask(phoneNumber)}")
                broadcast(Proto.build(Proto.INCOMING_CALL) { put("number", phoneNumber) })
            }
            "in_call" -> if (previous != "in_call") EventLog.add("Call in progress")
            else -> EventLog.add(if (previous == "ringing") "Call ended or missed" else "Call ended")
        }
        broadcast(Proto.build(Proto.CALL_STATE) {
            put("state", phoneState)
            put("number", phoneNumber)
        })
        if (newState == "idle") phoneNumber = ""
        refreshNotification()
    }

    // ---- NSD -------------------------------------------------------------------------------

    private fun registerNsd() {
        val mgr = nsdManager ?: return
        unregisterNsd()
        val info = NsdServiceInfo().apply {
            serviceName = Proto.SERVICE_NAME
            serviceType = Proto.SERVICE_TYPE
            port = Proto.PORT
            setAttribute("name", Build.MODEL ?: "Android phone")
            setAttribute("v", Proto.VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                GatewayState.discovery = "Visible on network"
                GatewayState.changed()
                EventLog.add("Gateway is discoverable")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                GatewayState.discovery = "Failed (code $errorCode)"
                GatewayState.changed()
                EventLog.add("Discovery registration failed ($errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = l
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            lastNsdRegisterAt = System.currentTimeMillis()
        } catch (e: Exception) {
            GatewayState.discovery = "Failed"
            GatewayState.changed()
            EventLog.add("Discovery error: ${e.javaClass.simpleName}")
        }
    }

    private fun unregisterNsd() {
        val l = regListener
        regListener = null
        if (l != null) {
            try {
                nsdManager?.unregisterService(l)
            } catch (_: Exception) {
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (!stopping && serverSocket != null &&
                        System.currentTimeMillis() - lastNsdRegisterAt > 3000
                    ) {
                        registerNsd()
                    }
                }
            }
        }
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback not registered", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    @Suppress("DEPRECATION")
    private fun createWifiLock(): WifiManager.WifiLock {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SimGateway:wifi")
        lock.setReferenceCounted(false)
        lock.acquire()
        return lock
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimGateway:gateway").apply {
                setReferenceCounted(false)
                acquire()
            }
            wifiLock = createWifiLock()
        } catch (e: Exception) {
            Log.w(TAG, "locks not acquired", e)
        }
    }

    // ---- notification ----------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "LinkSIM", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, GatewayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LinkSIM")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_sim_card)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_call_end), "Stop gateway", stopPi
                ).build()
            )
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun refreshNotification() {
        if (stopping) return
        try {
            val n = sessions.count { it.authed }
            val text = when {
                GatewayState.status == GatewayState.Status.ERROR -> "Problem: gateway not available"
                phoneState == "ringing" -> "Incoming call"
                phoneState == "in_call" -> "On a call"
                n == 0 -> "Online \u2022 waiting for a device"
                n == 1 -> "Online \u2022 1 device connected"
                else -> "Online \u2022 $n devices connected"
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }
}
