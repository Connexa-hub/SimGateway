package com.connexa.simgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            createNotification("Starting gateway...")
        )

        startTcpServer()
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

                updateNotification(
                    "Sending raw greeting..."
                )

                val greeting =
                    "HELLO FROM PHONE A\n".toByteArray()

                output.write(greeting)
                output.flush()

                updateNotification(
                    "Raw greeting sent"
                )

                socket.shutdownOutput()

                updateNotification(
                    "Output shutdown"
                )

                while (true) {

                    val message = reader.readLine()
                        ?: break

                    when (message.trim().uppercase()) {

                        "STATUS" -> {

                            output.write(
                                "SIM GATEWAY ONLINE\n".toByteArray()
                            )

                            output.flush()
                        }

                        "PING" -> {

                            output.write(
                                "PONG\n".toByteArray()
                            )

                            output.flush()
                        }

                        else -> {

                            output.write(
                                "UNKNOWN COMMAND: $message\n".toByteArray()
                            )

                            output.flush()
                        }
                    }
                }

            } catch (e: Exception) {

                updateNotification(
                    "Client error: ${e.javaClass.simpleName}"
                )

                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {

        serverThread?.interrupt()

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null

        super.onDestroy()
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
            getSystemService(NotificationManager::class.java)

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
            getSystemService(NotificationManager::class.java)

        manager.notify(
            NOTIFICATION_ID,
            createNotification(text)
        )
    }
}
