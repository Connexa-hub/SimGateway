package com.connexa.simgateway

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val PERMISSION_REQUEST = 100
        private const val SERVICE_TYPE = "_simgateway._tcp."
    }

    private lateinit var statusText: TextView

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var gatewaySocket: Socket? = null
    private var gatewayOutput: OutputStream? = null
    private var gatewayReader: BufferedReader? = null

    private var gatewayHost: String? = null
    private var gatewayPort: Int = 0

    // Single-owner socket I/O lock. Section 17 of the project spec
    // requires exactly one reader per TCP connection; a full
    // request/response ConnectionManager with correlation IDs is
    // planned for a later stage, but for Stage 01 we close the
    // concurrency hole by serializing every write+read pair through
    // this lock so two threads can never read the BufferedReader at
    // the same time (e.g. a user double-tapping CALL).
    private val socketIoLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nsdManager = getSystemService(NsdManager::class.java)

        showRoleSelection()
    }

    private fun showRoleSelection() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }

        val title = TextView(this).apply {
            text = "SIM Gateway"
            textSize = 30f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "\nChoose how this phone will operate."
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val gatewayButton = Button(this).apply {
            text = "PROVIDE SIM\nUse this phone's SIM"
            textSize = 16f

            setOnClickListener {
                startGatewayMode()
            }
        }

        val clientButton = Button(this).apply {
            text = "USE ANOTHER PHONE\nConnect to a SIM Gateway"
            textSize = 16f

            setOnClickListener {
                startClientMode()
            }
        }

        root.addView(title)

        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            gatewayButton,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 50
            }
        )

        root.addView(
            clientButton,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
            }
        )

        setContentView(root)
    }

    private fun startGatewayMode() {
        val permissions = mutableListOf<String>()

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        if (
            checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(android.Manifest.permission.CALL_PHONE)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(
                permissions.toTypedArray(),
                PERMISSION_REQUEST
            )
        } else {
            startGatewayService()
        }
    }

    private fun startGatewayService() {

        val serviceIntent =
            Intent(this, GatewayService::class.java)

        startForegroundService(serviceIntent)

        showGatewayScreen()
    }

    private fun showGatewayScreen() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "SIM Gateway"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text =
                "PROVIDER MODE\n\n" +
                "Gateway service started.\n\n" +
                "This phone is providing its SIM."
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val stopButton = Button(this).apply {
            text = "STOP GATEWAY"

            setOnClickListener {

                stopService(
                    Intent(
                        this@MainActivity,
                        GatewayService::class.java
                    )
                )

                showRoleSelection()
            }
        }

        root.addView(title)

        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 30
            }
        )

        root.addView(
            stopButton,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 40
            }
        )

        setContentView(root)
    }

    private fun startClientMode() {

        stopDiscovery()
        disconnectGateway()

        showClientScreen()

        startDiscovery()
    }

    private fun showClientScreen() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "SIM Gateway"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text =
                "CLIENT MODE\n\n" +
                "Searching for SIM Gateway..."
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val backButton = Button(this).apply {
            text = "BACK"

            setOnClickListener {
                stopDiscovery()
                disconnectGateway()
                showRoleSelection()
            }
        }

        root.addView(title)

        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 30
            }
        )

        root.addView(
            backButton,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 40
            }
        )

        setContentView(root)
    }

    private fun startDiscovery() {

        val manager = nsdManager ?: return

        discoveryListener =
            object : NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(
                    serviceType: String
                ) {

                    updateStatus(
                        "CLIENT MODE\n\n" +
                        "Searching for SIM Gateway..."
                    )
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {

                    if (
                        serviceInfo.serviceType.equals(
                            SERVICE_TYPE,
                            ignoreCase = true
                        )
                    ) {

                        updateStatus(
                            "GATEWAY FOUND\n\n" +
                            serviceInfo.serviceName +
                            "\n\nResolving..."
                        )

                        try {

                            manager.resolveService(
                                serviceInfo,
                                createResolveListener()
                            )

                        } catch (e: Exception) {

                            updateStatus(
                                "RESOLUTION ERROR\n\n" +
                                e.message
                            )
                        }
                    }
                }

                override fun onServiceLost(
                    serviceInfo: NsdServiceInfo
                ) {
                }

                override fun onDiscoveryStopped(
                    serviceType: String
                ) {
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {

                    updateStatus(
                        "DISCOVERY ERROR\n\n" +
                        "Code: $errorCode"
                    )

                    stopDiscovery()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {
                }
            }

        try {

            manager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )

        } catch (e: Exception) {

            updateStatus(
                "DISCOVERY ERROR\n\n" +
                e.message
            )
        }
    }

    private fun createResolveListener():
        NsdManager.ResolveListener {

        return object : NsdManager.ResolveListener {

            override fun onResolveFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int
            ) {

                updateStatus(
                    "RESOLUTION FAILED\n\n" +
                    "Code: $errorCode"
                )
            }

            override fun onServiceResolved(
                serviceInfo: NsdServiceInfo
            ) {

                // CRITICAL: NsdManager callbacks run on an internal
                // binder thread, never the main thread. Any UI work
                // triggered from here (including setContentView via
                // showConnectScreen) MUST be posted to the UI thread,
                // or Android throws CalledFromWrongThreadException.
                // This was the root cause of the provider-connects
                // crash: showConnectScreen() used to be called
                // directly from this callback.
                runOnUiThread {

                    gatewayHost =
                        serviceInfo.host.hostAddress

                    gatewayPort =
                        serviceInfo.port

                    updateStatus(
                        "GATEWAY FOUND\n\n" +
                        "Name: ${serviceInfo.serviceName}\n\n" +
                        "Address: $gatewayHost\n" +
                        "Port: $gatewayPort\n\n" +
                        "Ready to connect."
                    )

                    stopDiscovery()

                    showConnectScreen()
                }
            }
        }
    }

    private fun showConnectScreen() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(24, 24, 24, 24)

        val title = TextView(this)
        title.text = "Gateway Found"
        title.textSize = 24f

        val info = TextView(this)
        info.text = "Gateway: $gatewayHost:$gatewayPort"
        info.textSize = 16f

        val status = TextView(this)
        status.text = "Ready to connect."
        status.textSize = 16f

        val connectButton = Button(this)
        connectButton.text = "CONNECT TO GATEWAY"
        connectButton.setOnClickListener {
            connectToGateway()
        }

        val numberInput = android.widget.EditText(this)
        numberInput.hint = "Enter phone number"
        numberInput.inputType =
            android.text.InputType.TYPE_CLASS_PHONE

        val callButton = Button(this)
        callButton.text = "CALL THROUGH GATEWAY"
        callButton.isEnabled = false

        callButton.setOnClickListener {
            val number = numberInput.text.toString().trim()

            if (number.isEmpty()) {
                status.text = "Enter a phone number first."
                return@setOnClickListener
            }

            sendCall(number)
        }

        layout.addView(title)
        layout.addView(info)
        layout.addView(status)
        layout.addView(connectButton)
        layout.addView(numberInput)
        layout.addView(callButton)

        setContentView(layout)

    }

    private fun connectToGateway() {
        val host = gatewayHost
        val port = gatewayPort

        if (host == null || port <= 0) {
            updateStatus("CONNECTION ERROR: Gateway address is unavailable.")
            return
        }

        updateStatus("CONNECTING to $host:$port")

        thread(
            start = true,
            name = "SimGatewayClient"
        ) {
            try {
                val socket = Socket(host, port)
                gatewaySocket = socket

                gatewayOutput = socket.getOutputStream()

                gatewayReader = BufferedReader(
                    InputStreamReader(
                        socket.getInputStream()
                    )
                )

                // Greeting read happens before any other request can
                // be issued (button isn't enabled yet), but we still
                // route it through the shared lock for consistency.
                val greeting = synchronized(socketIoLock) {
                    gatewayReader!!.readLine()
                }

                updateStatus(
                    "CONNECTED. Gateway: $host:$port. Server: $greeting"
                )

                val response = sendAndAwaitResponse("PING\n")

                if (response != null) {
                    updateStatus(
                        "CONNECTED. Gateway online. Response: $response"
                    )
                }
            } catch (e: Exception) {
                disconnectGateway()

                updateStatus(
                    "CONNECTION FAILED: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    /**
     * Writes [payload] and reads exactly one response line, holding
     * [socketIoLock] for the whole write+read pair so no other thread
     * can interleave a read on the same BufferedReader in between.
     * Throws IOException if not currently connected.
     */
    private fun sendAndAwaitResponse(payload: String): String? {
        synchronized(socketIoLock) {
            val out = gatewayOutput
                ?: throw java.io.IOException("Not connected to gateway")
            val reader = gatewayReader
                ?: throw java.io.IOException("Not connected to gateway")

            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()

            return reader.readLine()
        }
    }

    private fun sendCall(number: String) {
        thread(
            start = true,
            name = "SimGatewayCall"
        ) {
            try {
                val id = System.currentTimeMillis().toString()

                val json =
                    "{\"v\":1,\"id\":\"$id\",\"type\":\"call\",\"number\":\"$number\"}\n"

                val response = sendAndAwaitResponse(json)

                updateStatus(
                    "CALL REQUEST SENT\n\nResponse: $response"
                )
            } catch (e: Exception) {
                updateStatus("CALL ERROR\n\n${e.message}")
            }
        }
    }

    private fun disconnectGateway() {

        synchronized(socketIoLock) {
            try {
                gatewaySocket?.close()
            } catch (_: Exception) {
            }

            gatewaySocket = null
            gatewayOutput = null
            gatewayReader = null
        }
    }

    private fun updateStatus(message: String) {

        runOnUiThread {

            if (::statusText.isInitialized) {
                statusText.text = message
            }
        }
    }

    private fun stopDiscovery() {

        val manager = nsdManager
        val listener = discoveryListener

        if (manager != null && listener != null) {

            try {
                manager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }

        discoveryListener = null
    }

    override fun onDestroy() {

        stopDiscovery()
        disconnectGateway()

        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == PERMISSION_REQUEST) {
            val callPhoneGranted =
                checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                    PackageManager.PERMISSION_GRANTED

            if (callPhoneGranted) {
                startGatewayService()
            } else {
                showRoleSelection()
            }
        }
    }
}
