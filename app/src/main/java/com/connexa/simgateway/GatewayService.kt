package com.connexa.simgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class GatewayService : Service() {

    companion object {
        private const val CHANNEL_ID = "sim_gateway_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PORT = 8765
        private const val SERVICE_TYPE = "_simgateway._tcp."
        private const val SERVICE_NAME = "SIM Gateway"
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var heartbeatThread: Thread? = null

    // Tracks every accepted client socket so onDestroy can close them
    // all explicitly instead of leaking their threads/sockets when
    // the gateway is stopped or the service is killed.
    private val activeClients =
        java.util.Collections.synchronizedSet(
            mutableSetOf<Socket>()
        )

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener:
        NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            createNotification("Starting gateway...")
        )

        nsdManager =
            getSystemService(Context.NSD_SERVICE) as NsdManager

        startTcpServer()
        startHeartbeat()
    }

    private fun startTcpServer() {

        serverThread = thread(
            start = true,
            name = "SimGatewayServer"
        ) {

            try {

                serverSocket = ServerSocket(PORT)

                updateNotification(
                    "Listening on port $PORT"
                )

                registerNsdService()

                while (!Thread.currentThread().isInterrupted) {

                    val client = serverSocket!!.accept()

                    updateNotification(
                        "Client connected: " +
                            client.inetAddress.hostAddress
                    )

                    thread(
                        start = true,
                        name = "SimGatewayClient"
                    ) {
                        handleClient(client)
                    }
                }

            } catch (e: Exception) {

                if (!Thread.currentThread().isInterrupted) {

                    updateNotification(
                        "Server error: " +
                            e.javaClass.simpleName
                    )

                    e.printStackTrace()
                }
            }
        }
    }

    private fun registerNsdService() {

        val manager = nsdManager ?: return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }

        nsdRegistrationListener =
            object : NsdManager.RegistrationListener {

                override fun onServiceRegistered(
                    serviceInfo: NsdServiceInfo
                ) {
                    updateNotification(
                        "Gateway discoverable"
                    )
                }

                override fun onRegistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int
                ) {
                    updateNotification(
                        "NSD registration failed: $errorCode"
                    )
                }

                override fun onServiceUnregistered(
                    serviceInfo: NsdServiceInfo
                ) {
                }

                override fun onUnregistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int
                ) {
                }
            }

        try {

            manager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                nsdRegistrationListener
            )

        } catch (e: Exception) {

            updateNotification(
                "NSD error: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun handleClient(client: Socket) {

        activeClients.add(client)

        try {

        client.use { socket ->

            try {

                val output = socket.getOutputStream()

                val reader =
                    BufferedReader(
                        InputStreamReader(
                            socket.getInputStream()
                        )
                    )

                // Connection greeting.
                output.write(
                    "HELLO FROM PHONE A\n"
                        .toByteArray(Charsets.UTF_8)
                )
                output.flush()

                updateNotification(
                    "Client session active"
                )

                while (true) {

                    val message =
                        reader.readLine()
                            ?: break

                    val command =
                        message.trim()

                    when {

                        command.equals(
                            "PING",
                            ignoreCase = true
                        ) -> {

                            output.write(
                                "PONG\n"
                                    .toByteArray(Charsets.UTF_8)
                            )
                            output.flush()
                        }

                        command.equals(
                            "STATUS",
                            ignoreCase = true
                        ) -> {

                            output.write(
                                "SIM GATEWAY ONLINE\n"
                                    .toByteArray(Charsets.UTF_8)
                            )
                            output.flush()
                        }

                        command.equals(
                            "QUIT",
                            ignoreCase = true
                        ) -> {

                            output.write(
                                "GOODBYE\n"
                                    .toByteArray(Charsets.UTF_8)
                            )
                            output.flush()

                            break
                        }

                        command.startsWith(
                            "{\"v\":1"
                        ) -> {

                            handleJsonCommand(
                                command,
                                output
                            )
                        }

                        else -> {

                            output.write(
                                "UNKNOWN COMMAND\n"
                                    .toByteArray(Charsets.UTF_8)
                            )
                            output.flush()
                        }
                    }
                }

                updateNotification(
                    "Client disconnected"
                )

            } catch (e: Exception) {

                updateNotification(
                    "Client error: " +
                        e.javaClass.simpleName
                )

                e.printStackTrace()
            }
        }

        } finally {
            activeClients.remove(client)
        }
    }

    private fun handleJsonCommand(
        message: String,
        output: java.io.OutputStream
    ) {

        val id =
            extractJsonValue(message, "id")

        val type =
            extractJsonValue(message, "type")

        when (type) {

            "ping" -> {

                sendJson(
                    output,
                    "{\"v\":1,\"id\":\"$id\",\"type\":\"pong\"}"
                )
            }

            "status" -> {

                sendJson(
                    output,
                    "{\"v\":1,\"id\":\"$id\",\"type\":\"status_response\",\"online\":true}"
                )
            }

            "call" -> {

                val number =
                    extractJsonValue(
                        message,
                        "number"
                    )

                if (
                    number == null ||
                    number.isBlank()
                ) {

                    sendJson(
                        output,
                        "{\"v\":1,\"id\":\"$id\",\"type\":\"error\",\"message\":\"Missing phone number\"}"
                    )

                    return
                }

                placeCall(number)

                sendJson(
                    output,
                    "{\"v\":1,\"id\":\"$id\",\"type\":\"call_started\",\"number\":\"$number\"}"
                )
            }

            else -> {

                sendJson(
                    output,
                    "{\"v\":1,\"id\":\"$id\",\"type\":\"error\",\"message\":\"Unknown command\"}"
                )
            }
        }
    }

    private fun extractJsonValue(
        json: String,
        key: String
    ): String? {

        val pattern =
            "\"$key\"\\s*:\\s*\"([^\"]*)\""

        val regex =
            Regex(pattern)

        return regex
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun sendJson(
        output: java.io.OutputStream,
        message: String
    ) {

        output.write(
            (message + "\n")
                .toByteArray(Charsets.UTF_8)
        )

        output.flush()
    }

    private fun placeCall(number: String) {

        try {

            updateNotification(
                "Calling $number"
            )

            val intent =
                Intent(
                    Intent.ACTION_CALL,
                    Uri.parse(
                        "tel:" + Uri.encode(number)
                    )
                )

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            startActivity(intent)

        } catch (e: Exception) {

            updateNotification(
                "Call failed: " +
                    e.javaClass.simpleName
            )

            e.printStackTrace()
        }
    }

    private fun startHeartbeat() {

        heartbeatThread = thread(
            start = true,
            name = "SimGatewayHeartbeat"
        ) {

            var count = 0

            while (!Thread.currentThread().isInterrupted) {

                try {

                    count++

                    updateNotification(
                        "Service alive • heartbeat $count"
                    )

                    Thread.sleep(3000)

                } catch (
                    e: InterruptedException
                ) {

                    break
                }
            }
        }
    }

    override fun onDestroy() {

        heartbeatThread?.interrupt()
        serverThread?.interrupt()

        unregisterNsdService()

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null

        // Close every still-open client connection so their
        // per-client threads unblock from readLine() (via
        // IOException) and exit cleanly instead of leaking.
        synchronized(activeClients) {
            for (client in activeClients) {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
            activeClients.clear()
        }

        super.onDestroy()
    }

    private fun unregisterNsdService() {

        val manager = nsdManager
        val listener = nsdRegistrationListener

        if (
            manager != null &&
            listener != null
        ) {

            try {
                manager.unregisterService(listener)
            } catch (_: Exception) {
            }
        }

        nsdRegistrationListener = null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        return START_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    private fun createNotificationChannel() {

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "SIM Gateway",
                NotificationManager.IMPORTANCE_LOW
            )

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(channel)
    }

    private fun createNotification(
        text: String
    ): Notification {

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("SIM Gateway")
            .setContentText(text)
            .setSmallIcon(
                android.R.drawable.stat_sys_phone_call
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(
        text: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            createNotification(text)
        )
    }
}
