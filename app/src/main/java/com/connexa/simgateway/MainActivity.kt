package com.connexa.simgateway

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = "SIM Gateway\n\nPhone A gateway app is running."
            textSize = 22f
            setPadding(40, 80, 40, 40)
        }

        setContentView(textView)

        val serviceIntent = Intent(this, GatewayService::class.java)
        startForegroundService(serviceIntent)
    }
}
