package com.connexa.simgateway

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 100
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            text = "SIM Gateway\n\nStarting gateway service..."
            textSize = 22f
            setPadding(40, 80, 40, 40)
        }

        setContentView(statusText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {

            statusText.text =
                "SIM Gateway\n\nNotification permission required."

            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )

        } else {
            startGatewayService()
        }
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

        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {

            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startGatewayService()
            } else {
                statusText.text =
                    "SIM Gateway\n\nNotification permission denied."
            }
        }
    }

    private fun startGatewayService() {
        try {
            val serviceIntent =
                Intent(this, GatewayService::class.java)

            startForegroundService(serviceIntent)

            statusText.text =
                "SIM Gateway\n\nGateway service start requested."

        } catch (e: Exception) {
            statusText.text =
                "SIM Gateway\n\nSERVICE ERROR:\n" +
                "${e.javaClass.name}\n\n${e.message}"
        }
    }
}
