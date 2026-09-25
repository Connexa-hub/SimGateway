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
        private const val NOTIFICATION_PERMISSION_REQUEST = 100
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

        stopDiscovery()
        disconnectGateway()

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )

            return
        }

        startGatewayService()
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

    private fun showConnectScreen() {

        runOnUiThread {

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
                    "GATEWAY FOUND\n\n" +
                    "Address: $gatewayHost\n" +
                    "Port: $gatewayPort\n\n" +
                    "Ready to connect."
                textSize = 18f
                gravity = Gravity.CENTER
            }

            val connectButton = Button(this).apply {
                text = "CONNECT TO GATEWAY"
                textSize = 16f

                setOnClickListener {
                    connectToGateway()
                }
            }

            val backButton = Button(this).apply {
                text = "BACK"

                setOnClickListener {
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
                connectButton,
                LinearLayout.LayoutParams(
                    -1,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 40
                }
            )

            root.addView(
                backButton,
                LinearLayout.LayoutParams(
                    -1,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 15
                }
            )

            setContentView(root)
        }
    }

    private fun connectToGateway() {

        val host = gatewayHost
        val port = gatewayPort

        if (host == null || port <= 0) {

            updateStatus(
                "CONNECTION ERROR\n\n" +
                "Gateway address is unavailable."
            )

            return
        }

        updateStatus(
            "CONNECTING...\n\n" +
            "$host:$port"
        )

        thread(
            start = true,
            name = "SimGatewayClient"
        ) {

            try {

                val socket =
                    Socket(host, port)

                gatewaySocket = socket

                gatewayOutput =
                    socket.getOutputStream()

                gatewayReader =
                    BufferedReader(
                        InputStreamReader(
                            socket.getInputStream()
                        )
                    )

                val greeting =
                    gatewayReader!!.readLine()

                updateStatus(
                    "CONNECTED\n\n" +
                    "Gateway: $host:$port\n\n" +
                    "Server: $greeting"
                )

                sendPing()

            } catch (e: Exception) {

                disconnectGateway()

                updateStatus(
                    "CONNECTION FAILED\n\n" +
                    "${e.javaClass.simpleName}\n" +
                    "${e.message}"
                )
            }
        }
    }

    private fun sendPing() {

        thread(
            start = true,
            name = "SimGatewayPing"
        ) {

            try {

                gatewayOutput?.write(
                    "PING\n".toByteArray(Charsets.UTF_8)
                )

                gatewayOutput?.flush()

                val response =
                    gatewayReader?.readLine()

                if (response != null) {

                    updateStatus(
                        "CONNECTED\n\n" +
                        "Gateway online.\n\n" +
                        "Response: $response"
                    )
                }

            } catch (e: Exception) {

                updateStatus(
                    "CONNECTION LOST\n\n" +
                    e.message
                )
            }
        }
    }

    private fun disconnectGateway() {

        try {
            gatewaySocket?.close()
        } catch (_: Exception) {
        }

        gatewaySocket = null
        gatewayOutput = null
        gatewayReader = null
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

        if (
            requestCode ==
            NOTIFICATION_PERMISSION_REQUEST
        ) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] ==
                PackageManager.PERMISSION_GRANTED
            ) {

                startGatewayService()

            } else {

                showRoleSelection()
            }
        }
    }
}
