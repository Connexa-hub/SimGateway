package com.connexa.simgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

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
                        "Client connected: ${client.inetAddress.hostAddress}"
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
                        "Server error: ${e.javaClass.simpleName}"
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
                        "Gateway discoverable as ${serviceInfo.serviceName}"
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

            e.printStackTrace()
        }
    }

    private fun handleClient(client: Socket) {

        client.use { socket ->

            try {

                updateNotification(
                    "Handling client: ${socket.inetAddress.hostAddress}"
                )

                val output = socket.getOutputStream()

                val reader = BufferedReader(
                    InputStreamReader(
                        socket.getInputStream()
                    )
                )

                output.write(
                    "HELLO FROM PHONE A\n".toByteArray()
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
                        message.trim().uppercase()

                    when (command) {

                        "PING" -> {

                            output.write(
                                "PONG\n".toByteArray()
                            )

                            output.flush()

                            updateNotification(
                                "PING received"
                            )
                        }

                        "STATUS" -> {

                            output.write(
                                "SIM GATEWAY ONLINE\n".toByteArray()
                            )

                            output.flush()

                            updateNotification(
                                "STATUS requested"
                            )
                        }

                        "HELLO" -> {

                            output.write(
                                "HELLO FROM PHONE A\n".toByteArray()
                            )

                            output.flush()
                        }

                        "QUIT" -> {

                            output.write(
                                "GOODBYE\n".toByteArray()
                            )

                            output.flush()

                            break
                        }

                        else -> {

                            output.write(
                                "UNKNOWN COMMAND: $message\n"
                                    .toByteArray()
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
                    "Client error: ${e.javaClass.simpleName}"
                )

                e.printStackTrace()
            }
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

                } catch (e: InterruptedException) {

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

        super.onDestroy()
    }

    private fun unregisterNsdService() {

        val manager = nsdManager
        val listener = nsdRegistrationListener

        if (manager != null && listener != null) {

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

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {

        val channel = NotificationChannel(
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
