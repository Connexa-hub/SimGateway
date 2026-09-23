package com.connexa.simgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
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

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SIM Gateway")
            .setContentText("Gateway listening on port $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        startTcpServer()
    }

    private fun startTcpServer() {

        serverThread = thread(
            start = true,
            name = "SimGatewayServer"
        ) {

            try {
                serverSocket = ServerSocket(PORT)

                while (!Thread.currentThread().isInterrupted) {

                    val client = serverSocket!!.accept()

                    thread {
                        handleClient(client)
                    }
                }

            } catch (e: Exception) {

                if (!Thread.currentThread().isInterrupted) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleClient(client: Socket) {

        client.use { socket ->

            try {
                val writer = PrintWriter(
                    socket.getOutputStream(),
                    true
                )

                val reader = BufferedReader(
                    InputStreamReader(
                        socket.getInputStream()
                    )
                )

                writer.println("HELLO FROM PHONE A")

                while (true) {

                    val message = reader.readLine()
                        ?: break

                    when (message.trim().uppercase()) {

                        "STATUS" -> {
                            writer.println("SIM GATEWAY ONLINE")
                        }

                        "PING" -> {
                            writer.println("PONG")
                        }

                        else -> {
                            writer.println(
                                "UNKNOWN COMMAND: $message"
                            )
                        }
                    }
                }

            } catch (e: Exception) {
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
}
