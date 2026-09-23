package com.connexa.simgateway

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = "SIM Gateway\n\nStarting gateway service..."
            textSize = 22f
            setPadding(40, 80, 40, 40)
        }

        setContentView(textView)

        try {
            val serviceIntent = Intent(this, GatewayService::class.java)
            startForegroundService(serviceIntent)

            textView.text =
                "SIM Gateway\n\nGateway service start requested."
        } catch (e: Exception) {
            textView.text =
                "SIM Gateway\n\nSERVICE ERROR:\n${e.javaClass.name}\n\n${e.message}"
        }
    }
}
