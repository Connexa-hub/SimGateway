package com.connexa.simgateway

import android.Manifest
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 100
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val animators = mutableListOf<Animator>()
    private var uptimeLabel: TextView? = null
    private var pinDialog: AlertDialog? = null
    private var launchAfterPermissions = false
    private var fixingPermissions = false

    private val appObserver: () -> Unit = { render() }
    private val gatewayObserver: () -> Unit = {
        handler.post { if (App.screen == App.Screen.PROVIDER) render() }
    }
    private val logObserver: () -> Unit = {
        handler.post { if (App.screen == App.Screen.LOG) render() }
    }
    private val ticker = object : Runnable {
        override fun run() {
            uptimeLabel?.text = uptimeText()
            handler.postDelayed(this, 1000)
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(this)
        ui = Ui(this)
        buildRoot()
        setContentView(root)
        if (!App.launchHandled) {
            App.launchHandled = true
            if (GatewayState.status == GatewayState.Status.ONLINE) App.screen = App.Screen.PROVIDER
        }
    }

    override fun onStart() {
        super.onStart()
        App.addObserver(appObserver)
        GatewayState.addListener(gatewayObserver)
        EventLog.addListener(logObserver)
        render()
        handler.postDelayed(ticker, 1000)
    }

    override fun onStop() {
        App.removeObserver(appObserver)
        GatewayState.removeListener(gatewayObserver)
        EventLog.removeListener(logObserver)
        handler.removeCallbacks(ticker)
        cancelAnimators()
        pinDialog?.dismiss()
        pinDialog = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (App.screen) {
            App.Screen.HOME -> super.onBackPressed()
            App.Screen.PROVIDER -> go(App.Screen.HOME)
            App.Screen.SEARCH, App.Screen.CONNECTING -> App.leaveClient()
            App.Screen.HUB -> go(App.Screen.HOME)
            App.Screen.DIALER, App.Screen.SMS -> go(App.Screen.HUB)
            App.Screen.LOG -> go(App.logReturn)
        }
    }

    private fun go(s: App.Screen) {
        App.screen = s
        App.changed()
    }

    private fun buildRoot() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ui.color(R.color.sg_bg))

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(28))

        scroll.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Android 15+ draws edge-to-edge for targetSdk 35: keep content clear of system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            }
            insets
        }
    }

    // ---- rendering helpers -----------------------------------------------------------------

    private fun render() {
        cancelAnimators()
        uptimeLabel = null
        content.removeAllViews()
        when (App.screen) {
            App.Screen.HOME -> homeScreen()
            App.Screen.PROVIDER -> providerScreen()
            App.Screen.SEARCH -> searchScreen()
            App.Screen.CONNECTING -> connectingScreen()
            App.Screen.HUB -> hubScreen()
            App.Screen.DIALER -> dialerScreen()
            App.Screen.SMS -> smsScreen()
            App.Screen.LOG -> logScreen()
        }
        maybeShowPinDialog()
    }

    private fun cancelAnimators() {
        for (a in animators) a.cancel()
        animators.clear()
    }

    private fun LinearLayout.add(v: View, top: Int = 0, height: Int = WRAP): View {
        addView(v, LinearLayout.LayoutParams(MATCH, height).apply { topMargin = ui.dp(top) })
        return v
    }

    private fun LinearLayout.addWeighted(v: View, startMarginDp: Int = 0) {
        addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(startMarginDp) })
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        return r
    }

    private fun column(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        return c
    }

    private fun circleIcon(iconRes: Int, sizeDp: Int, iconDp: Int, bg: Int, tint: Int): FrameLayout {
        val f = FrameLayout(this)
        f.background = ui.oval(bg)
        f.addView(ui.icon(iconRes, tint), FrameLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
        return f
    }

    private fun header(title: String, subtitle: String? = null, onBack: (() -> Unit)?) {
        val r = row()
        if (onBack != null) {
            val back = FrameLayout(this)
            back.isClickable = true
            back.isFocusable = true
            back.contentDescription = "Back"
            back.background = ui.clickableBg(Color.TRANSPARENT, 24)
            back.addView(
                ui.icon(R.drawable.ic_arrow_back, R.color.sg_text),
                FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER)
            )
            back.setOnClickListener { onBack() }
            r.addView(back, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        }
        val col = column()
        val t = ui.text(title, 22f, bold = true)
        t.isFocusable = true
        col.addView(t)
        if (subtitle != null) col.addView(ui.text(subtitle, 14f, R.color.sg_text_dim))
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(if (onBack != null) 4 else 0) })
        content.add(r)
    }

    private fun infoRow(label: String, value: String, valueColor: Int = R.color.sg_text): View {
        val r = row()
        r.addView(ui.text(label, 14f, R.color.sg_text_dim), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(ui.text(value, 15f, valueColor, bold = true))
        r.setPadding(0, ui.dp(6), 0, ui.dp(6))
        return r
    }

    private fun statCard(label: String, value: String, valueColor: Int = R.color.sg_text): Pair<LinearLayout, TextView> {
        val c = ui.card(14)
        c.addView(ui.text(label, 12f, R.color.sg_text_dim))
        val v = ui.text(value, 18f, valueColor, bold = true)
        c.add(v, top = 4)
        c.isFocusable = true
        return Pair(c, v)
    }

    private fun statRow(a: LinearLayout, b: LinearLayout) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.addWeighted(a)
        r.addWeighted(b, 12)
        content.add(r, top = 12)
    }

    private fun connectionStrip() {
        val connected = App.connState == ConnectionManager.State.CONNECTED
        val strip = row()
        strip.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        strip.background = ui.rounded(ui.color(R.color.sg_surface_alt), 14)
        strip.addView(ui.dot(if (connected) R.color.sg_success else R.color.sg_danger, 10))
        val label = if (connected) "Connected to ${App.gatewayName}" else "Not connected"
        strip.addView(
            ui.text(label, 14f, R.color.sg_text, bold = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(10) }
        )
        content.add(strip, top = 12)
    }

    // ---- HOME ------------------------------------------------------------------------------

    private fun homeScreen() {
        val brand = row()
        val logo = FrameLayout(this)
        logo.background = ui.rounded(ui.color(R.color.sg_primary), 14)
        logo.addView(
            ui.icon(R.drawable.ic_sim_card, R.color.sg_on_primary),
            FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER)
        )
        brand.addView(logo, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        brand.addView(
            ui.text("SIM GATEWAY", 22f, bold = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(12) }
        )
        content.add(brand, top = 8)
        content.add(
            ui.text("Share your phone's cellular connection with another trusted device.", 16f, R.color.sg_text_dim),
            top = 16
        )

        val providerActive = GatewayState.status == GatewayState.Status.ONLINE ||
            GatewayState.status == GatewayState.Status.STARTING
        content.add(
            roleCard(
                "PROVIDER", "PROVIDE SIM", "Share this phone's SIM with another device.",
                R.drawable.ic_sim_card, if (providerActive) "Gateway active" else null
            ) {
                if (providerActive) go(App.Screen.PROVIDER) else startProvider()
            },
            top = 28
        )

        val clientConnected = App.connState == ConnectionManager.State.CONNECTED
        content.add(
            roleCard(
                "CLIENT", "USE ANOTHER PHONE", "Connect to another phone's SIM Gateway.",
                R.drawable.ic_phone_android, if (clientConnected) "Connected" else null
            ) {
                if (clientConnected) go(App.Screen.HUB) else App.startSearch()
            },
            top = 14
        )

        content.add(
            ui.button("Activity log", R.drawable.ic_history, Ui.Kind.GHOST) {
                App.logReturn = App.Screen.HOME
                go(App.Screen.LOG)
            },
            top = 20
        )
        content.add(ui.text("Version ${versionName()}", 12f, R.color.sg_text_dim, center = true), top = 8)
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun roleCard(
        role: String, title: String, desc: String, iconRes: Int, activeLabel: String?, onClick: () -> Unit
    ): View {
        val active = activeLabel != null
        val card = row()
        card.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))
        card.background = ui.clickableBg(
            ui.color(R.color.sg_surface), 22,
            ui.color(if (active) R.color.sg_primary else R.color.sg_outline),
            if (active) 2 else 1
        )
        card.isClickable = true
        card.isFocusable = true
        card.contentDescription = "$title. $desc" + (if (activeLabel != null) ". $activeLabel" else "")
        card.setOnClickListener { onClick() }

        card.addView(
            circleIcon(iconRes, 56, 28, ui.color(R.color.sg_surface_alt), R.color.sg_primary)
        )
        val col = column()
        col.addView(ui.text(role, 12f, R.color.sg_text_dim, bold = true))
        col.add(ui.text(title, 20f, bold = true), top = 2)
        col.add(ui.text(desc, 14f, R.color.sg_text_dim), top = 2)
        if (activeLabel != null) {
            col.addView(
                ui.badge(activeLabel, R.color.sg_success),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ui.dp(8) }
            )
        }
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(16) })
        return card
    }

    // ---- PROVIDER --------------------------------------------------------------------------

    private fun requiredPermissions(): List<String> {
        val l = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun isGranted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(): List<String> = requiredPermissions().filter { !isGranted(it) }

    private fun startProvider() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            launchProvider()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow this phone to act as the SIM")
            .setMessage(
                "To share its SIM, this phone needs permission to:\n\n" +
                    "\u2022 Make and end phone calls\n" +
                    "\u2022 Answer calls\n" +
                    "\u2022 See incoming calls and the caller's number\n" +
                    "\u2022 Send text messages\n" +
                    "\u2022 Show the gateway notification\n\n" +
                    "Only a device that enters this phone's PIN can use them. " +
                    "You can continue without some permissions, but those features won't work."
            )
            .setPositiveButton("Continue") { _, _ ->
                launchAfterPermissions = true
                requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchProvider() {
        try {
            GatewayState.status = GatewayState.Status.STARTING
            GatewayState.error = null
            GatewayService.start(this)
            go(App.Screen.PROVIDER)
        } catch (e: Exception) {
            GatewayState.status = GatewayState.Status.ERROR
            GatewayState.error = "Couldn't start the gateway service."
            EventLog.add("Gateway could not start: ${e.javaClass.simpleName}")
            go(App.Screen.PROVIDER)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (launchAfterPermissions) {
            launchAfterPermissions = false
            launchProvider()
        } else if (GatewayState.status != GatewayState.Status.STOPPED) {
            GatewayService.refreshPermissions(this)
        }
        if (fixingPermissions) {
            fixingPermissions = false
            val blocked = missingPermissions().filter { !shouldShowRequestPermissionRationale(it) }
            if (blocked.isNotEmpty()) offerSettings()
        }
        App.changed()
    }

    private fun offerSettings() {
        AlertDialog.Builder(this)
            .setTitle("Permission blocked")
            .setMessage("Android won't ask again. Open the app settings and allow the missing permissions.")
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                } catch (_: ActivityNotFoundException) {
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun uptimeText(): String {
        val start = GatewayState.startedAt
        if (GatewayState.status != GatewayState.Status.ONLINE || start == 0L) return "\u2014"
        val s = (System.currentTimeMillis() - start) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m ${sec}s"
    }

    private fun networkLabel(): String {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return "Unknown"
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Hotspot / local network"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data / hotspot"
                else -> "Connected"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun simLabel(): Pair<String, Int> = when (simState(this)) {
        "ready" -> Pair("Ready", R.color.sg_success)
        "absent" -> Pair("No SIM", R.color.sg_danger)
        "not_ready" -> Pair("Locked / starting", R.color.sg_warning)
        else -> Pair("Unknown", R.color.sg_text)
    }

    private fun providerScreen() {
        header("Provider", null) { go(App.Screen.HOME) }

        val st = GatewayState.status
        val spec = when (st) {
            GatewayState.Status.ONLINE ->
                Triple(R.color.sg_success, "Gateway Online", "This phone is providing its SIM")
            GatewayState.Status.STARTING ->
                Triple(R.color.sg_warning, "Starting gateway\u2026", "Getting things ready")
            GatewayState.Status.ERROR ->
                Triple(R.color.sg_danger, "Gateway problem", GatewayState.error ?: "Something went wrong")
            GatewayState.Status.STOPPED ->
                Triple(R.color.sg_text_dim, "Gateway stopped", "Start it to share this phone's SIM")
        }
        val statusCard = ui.card(18)
        val top = row()
        top.addView(ui.dot(spec.first, 14))
        top.addView(
            ui.text(spec.second, 22f, bold = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(12) }
        )
        statusCard.addView(top)
        statusCard.add(ui.text(spec.third, 15f, R.color.sg_text_dim), top = 6)
        if (st == GatewayState.Status.STARTING) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            pb.isIndeterminate = true
            pb.indeterminateTintList = ColorStateList.valueOf(ui.color(R.color.sg_primary))
            statusCard.add(pb, top = 12)
        }
        content.add(statusCard, top = 16)

        val running = st == GatewayState.Status.ONLINE
        val n = GatewayState.clientCount
        val sim = simLabel()
        val (devCard, _) = statCard("Connected devices", n.toString())
        val (discCard, _) = statCard(
            "Discovery", GatewayState.discovery,
            if (GatewayState.discovery.startsWith("Failed")) R.color.sg_danger else R.color.sg_text
        )
        statRow(devCard, discCard)
        val (portCard, _) = statCard("Gateway port", Proto.PORT.toString())
        val (simCard, _) = statCard("SIM", sim.first, sim.second)
        statRow(portCard, simCard)
        val (netCard, _) = statCard("Network", networkLabel())
        val (upCard, upView) = statCard("Uptime", uptimeText())
        uptimeLabel = upView
        statRow(netCard, upCard)

        // Pairing PIN
        val pinCard = ui.card(18)
        val pr = row()
        pr.addView(ui.icon(R.drawable.ic_lock, R.color.sg_primary), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
        pr.addView(
            ui.text("Pairing PIN", 15f, bold = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(8) }
        )
        pinCard.addView(pr)
        val pin = Prefs.providerPin(this)
        val pinView = ui.text(pin.chunked(3).joinToString(" "), 34f, R.color.sg_text, bold = true)
        pinView.letterSpacing = 0.12f
        pinView.contentDescription = "Pairing PIN " + pin.toCharArray().joinToString(" ")
        pinCard.add(pinView, top = 10)
        pinCard.add(
            ui.text("Enter this on the other phone the first time it connects.", 13f, R.color.sg_text_dim),
            top = 4
        )
        pinCard.add(
            ui.button("New PIN", null, Ui.Kind.SECONDARY) {
                Prefs.regeneratePin(this)
                EventLog.add("Pairing PIN changed")
                render()
            },
            top = 12
        )
        content.add(pinCard, top = 12)

        // Permissions
        val permCard = ui.card(18)
        permCard.addView(ui.text("Permissions", 15f, bold = true))
        permCard.add(permRow("Make calls", isGranted(Manifest.permission.CALL_PHONE)), top = 6)
        permCard.add(permRow("Answer and end calls", isGranted(Manifest.permission.ANSWER_PHONE_CALLS)))
        permCard.add(
            permRow(
                "Incoming call alerts",
                isGranted(Manifest.permission.READ_PHONE_STATE) && isGranted(Manifest.permission.READ_CALL_LOG)
            )
        )
        permCard.add(permRow("Send text messages", isGranted(Manifest.permission.SEND_SMS)))
        if (missingPermissions().isNotEmpty()) {
            permCard.add(
                ui.button("Grant permissions", null, Ui.Kind.PRIMARY) {
                    fixingPermissions = true
                    requestPermissions(missingPermissions().toTypedArray(), REQ_PERMISSIONS)
                },
                top = 12
            )
        }
        content.add(permCard, top = 12)

        // Reliability tip
        val tip = ui.card(18)
        tip.addView(ui.text("Keep it running", 15f, bold = true))
        tip.add(
            ui.text(
                "Keep both phones on the same Wi-Fi. If Android stops the gateway in the background, " +
                    "exclude SIM Gateway from battery optimization.",
                13f, R.color.sg_text_dim
            ),
            top = 4
        )
        tip.add(
            ui.button("Battery settings", null, Ui.Kind.SECONDARY) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                }
            },
            top = 12
        )
        content.add(tip, top = 12)

        if (running || st == GatewayState.Status.STARTING) {
            content.add(
                ui.button("Stop Gateway", R.drawable.ic_call_end, Ui.Kind.DANGER) {
                    GatewayService.stop(this)
                    go(App.Screen.HOME)
                },
                top = 20
            )
        } else {
            content.add(ui.button("Start Gateway", R.drawable.ic_sim_card, Ui.Kind.PRIMARY) { startProvider() }, top = 20)
        }
        content.add(
            ui.button("Activity log", R.drawable.ic_history, Ui.Kind.GHOST) {
                App.logReturn = App.Screen.PROVIDER
                go(App.Screen.LOG)
            },
            top = 8
        )
    }

    private fun permRow(label: String, granted: Boolean): View {
        val r = row()
        r.setPadding(0, ui.dp(6), 0, ui.dp(6))
        r.addView(ui.text(label, 14f, R.color.sg_text), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(
            ui.text(
                if (granted) "\u2713 Allowed" else "\u2715 Not allowed", 13f,
                if (granted) R.color.sg_success else R.color.sg_danger, bold = true
            )
        )
        return r
    }

    // ---- CLIENT: search / connect ----------------------------------------------------------

    private fun pulseView(): View {
        val box = FrameLayout(this)
        val size = ui.dp(170)
        for (i in 0 until 3) {
            val ringView = View(this)
            ringView.background = ui.ring(ui.color(R.color.sg_primary))
            box.addView(ringView, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            if (ValueAnimator.areAnimatorsEnabled()) {
                val a = ObjectAnimator.ofPropertyValuesHolder(
                    ringView,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.35f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.35f, 1f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f)
                )
                a.duration = 2400
                a.repeatCount = ValueAnimator.INFINITE
                a.startDelay = i * 800L
                a.interpolator = LinearInterpolator()
                a.start()
                animators.add(a)
            } else {
                ringView.alpha = 0.25f
            }
        }
        box.addView(
            circleIcon(R.drawable.ic_wifi, 72, 34, ui.color(R.color.sg_primary), R.color.sg_on_primary),
            FrameLayout.LayoutParams(ui.dp(72), ui.dp(72), Gravity.CENTER)
        )
        return box
    }

    private fun searchScreen() {
        header("Use another phone", null) { App.leaveClient() }

        val err = App.searchError
        if (err != null) {
            val c = ui.card()
            c.background = ui.rounded(ui.color(R.color.sg_surface), 20, ui.color(R.color.sg_danger), 2)
            c.addView(ui.text("Something went wrong", 15f, R.color.sg_danger, bold = true))
            c.add(ui.text(err, 14f, R.color.sg_text_dim), top = 4)
            content.add(c, top = 16)
        }

        if (App.gateways.isEmpty()) {
            if (App.searchTimedOut && !App.searching.not()) {
                emptyState()
            } else if (App.searchTimedOut) {
                emptyState()
            } else {
                content.add(pulseView(), top = 24, height = ui.dp(190))
                content.add(ui.text("SEARCHING FOR GATEWAYS\u2026", 16f, R.color.sg_text, bold = true, center = true), top = 8)
                content.add(
                    ui.text(
                        "Make sure the other phone is on the same Wi-Fi and has chosen PROVIDE SIM.",
                        14f, R.color.sg_text_dim, center = true
                    ),
                    top = 8
                )
            }
        } else {
            content.add(ui.text("SIM Gateway found", 18f, bold = true), top = 20)
            for (g in App.gateways) content.add(gatewayCard(g), top = 12)
        }
    }

    private fun emptyState() {
        val c = ui.card(24)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(
            circleIcon(R.drawable.ic_wifi, 64, 30, ui.color(R.color.sg_surface_alt), R.color.sg_text_dim)
        )
        c.add(ui.text("No gateway found", 18f, R.color.sg_text, bold = true, center = true), top = 14)
        c.add(
            ui.text(
                "Check that the SIM phone has chosen PROVIDE SIM and that both phones are on the same Wi-Fi network.",
                14f, R.color.sg_text_dim, center = true
            ),
            top = 6
        )
        c.add(ui.button("Search again", null, Ui.Kind.PRIMARY) { App.startSearch() }, top = 16)
        content.add(c, top = 24)
    }

    private fun gatewayCard(g: Discovery.Gateway): View {
        val c = ui.card(16)
        val r = row()
        r.addView(circleIcon(R.drawable.ic_phone_android, 48, 24, ui.color(R.color.sg_surface_alt), R.color.sg_primary))
        val col = column()
        col.addView(ui.text(g.name, 18f, bold = true))
        val avail = row()
        avail.addView(ui.dot(R.color.sg_success, 8))
        avail.addView(
            ui.text("Available", 14f, R.color.sg_success, bold = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(6) }
        )
        col.add(avail, top = 2)
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(14) })
        c.addView(r)
        c.add(ui.button("CONNECT", null, Ui.Kind.PRIMARY) { App.selectGateway(g) }, top = 14)

        val toggle = ui.text(if (App.showDetails) "Connection details \u25B4" else "Connection details \u25BE", 13f, R.color.sg_primary, bold = true)
        toggle.setPadding(0, ui.dp(12), 0, ui.dp(4))
        toggle.isClickable = true
        toggle.setOnClickListener {
            App.showDetails = !App.showDetails
            App.changed()
        }
        c.add(toggle)
        if (App.showDetails) {
            c.add(ui.text("Address: ${g.address.hostAddress}\nPort: ${g.port}\nService: ${g.key}", 13f, R.color.sg_text_dim))
        }
        return c
    }

    private fun connectingScreen() {
        header("Connecting", null) { App.cancelConnect() }
        val text = when {
            App.isLookingForGateway -> "Looking for ${App.gatewayName}\u2026"
            App.connState == ConnectionManager.State.AUTHENTICATING -> "Verifying PIN\u2026"
            else -> "Connecting to ${App.gatewayName}\u2026"
        }
        val pb = ProgressBar(this)
        pb.isIndeterminate = true
        pb.indeterminateTintList = ColorStateList.valueOf(ui.color(R.color.sg_primary))
        content.add(pb, top = 80, height = ui.dp(64))
        content.add(ui.text(text, 18f, R.color.sg_text, bold = true, center = true), top = 20)
        content.add(ui.text("This usually takes a few seconds.", 14f, R.color.sg_text_dim, center = true), top = 6)
        content.add(ui.button("Cancel", null, Ui.Kind.SECONDARY) { App.cancelConnect() }, top = 32)
    }

    // ---- CLIENT: hub -----------------------------------------------------------------------

    private fun noticeBanner() {
        val n = App.notice ?: return
        val c = ui.card(14)
        c.background = ui.rounded(ui.color(R.color.sg_surface), 16, ui.color(R.color.sg_warning), 2)
        c.addView(ui.text(n, 14f, R.color.sg_text))
        content.add(c, top = 12)
    }

    private fun incomingBanner() {
        val n = App.incomingNumber ?: return
        val c = ui.card(16)
        c.background = ui.rounded(ui.color(R.color.sg_surface), 20, ui.color(R.color.sg_success), 2)
        c.addView(ui.text("INCOMING CALL", 12f, R.color.sg_success, bold = true))
        c.add(ui.text(if (n.isBlank()) "Unknown number" else n, 26f, bold = true), top = 4)
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.addWeighted(ui.button("ANSWER", R.drawable.ic_call, Ui.Kind.CALL) { App.answer() })
        r.addWeighted(ui.button("REJECT", R.drawable.ic_call_end, Ui.Kind.DANGER) { App.reject() }, 10)
        c.add(r, top = 14)
        content.add(c, top = 12)
    }

    private fun audioNote() {
        content.add(
            ui.text(
                "Calls are placed and controlled through the SIM phone. Their voice audio stays on that phone " +
                    "for now: Android doesn't let apps carry cellular call audio over Wi-Fi.",
                12f, R.color.sg_text_dim
            ),
            top = 12
        )
    }

    private fun hubScreen() {
        val cs = App.connState
        val connected = cs == ConnectionManager.State.CONNECTED
        header("SIM Gateway", "Remote SIM access") { go(App.Screen.HOME) }
        noticeBanner()
        incomingBanner()

        val card = ui.card(18)
        val top = row()
        val (dotColor, title, sub) = when (cs) {
            ConnectionManager.State.CONNECTED -> Triple(R.color.sg_success, "CONNECTED", "Gateway online")
            ConnectionManager.State.RECONNECTING -> Triple(R.color.sg_warning, "Reconnecting\u2026", "Trying to reach the gateway again")
            ConnectionManager.State.CONNECTING, ConnectionManager.State.AUTHENTICATING ->
                Triple(R.color.sg_warning, "Connecting\u2026", "Please wait")
            else -> Triple(R.color.sg_danger, "Disconnected", "The gateway isn't reachable")
        }
        top.addView(ui.dot(dotColor, 14))
        top.addView(
            ui.text(title, 24f, bold = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(12) }
        )
        card.addView(top)
        card.add(ui.text(sub, 15f, R.color.sg_text_dim), top = 4)
        if (connected) {
            val status = when {
                App.incomingNumber != null -> "Ringing"
                App.callUi == App.CallUi.IN_CALL || App.callUi == App.CallUi.DIALING -> "On a call"
                else -> "Ready"
            }
            card.add(infoRow("Connected to", App.gatewayName), top = 12)
            card.add(infoRow("Status", status, if (status == "Ready") R.color.sg_success else R.color.sg_text))
            val simText = when (App.remoteSim) {
                "ready" -> "Ready"
                "absent" -> "No SIM"
                "not_ready" -> "Locked / starting"
                else -> "Unknown"
            }
            card.add(infoRow("SIM on gateway", simText, if (App.remoteSim == "ready") R.color.sg_success else R.color.sg_warning))
        }
        if (cs == ConnectionManager.State.RECONNECTING || cs == ConnectionManager.State.CONNECTING) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            pb.isIndeterminate = true
            pb.indeterminateTintList = ColorStateList.valueOf(ui.color(R.color.sg_primary))
            card.add(pb, top = 12)
        }
        content.add(card, top = 16)

        if (connected && (App.callUi == App.CallUi.DIALING || App.callUi == App.CallUi.IN_CALL || App.callUi == App.CallUi.ENDING)) {
            content.add(activeCallCard(), top = 12)
        }

        if (connected) {
            content.add(ui.button("CALL", R.drawable.ic_call, Ui.Kind.CALL) { go(App.Screen.DIALER) }, top = 20)
            content.add(ui.button("SMS", R.drawable.ic_message, Ui.Kind.PRIMARY) { go(App.Screen.SMS) }, top = 12)
            content.add(ui.button("DISCONNECT", null, Ui.Kind.SECONDARY) { App.userDisconnect() }, top = 12)
            audioNote()
        } else if (cs == ConnectionManager.State.DISCONNECTED || cs == ConnectionManager.State.AUTH_FAILED) {
            content.add(ui.button("RECONNECT", null, Ui.Kind.PRIMARY) { App.reconnect() }, top = 20)
            content.add(ui.button("Choose another gateway", null, Ui.Kind.SECONDARY) { App.startSearch() }, top = 12)
        }
        content.add(
            ui.button("Activity log", R.drawable.ic_history, Ui.Kind.GHOST) {
                App.logReturn = App.Screen.HUB
                go(App.Screen.LOG)
            },
            top = 12
        )
    }

    private fun activeCallCard(): View {
        val c = ui.card(18)
        val label = when (App.callUi) {
            App.CallUi.DIALING -> "DIALING\u2026"
            App.CallUi.ENDING -> "ENDING CALL\u2026"
            else -> "CALL IN PROGRESS"
        }
        c.addView(ui.text(label, 12f, R.color.sg_success, bold = true))
        c.add(ui.text(if (App.callNumber.isBlank()) "Call" else App.callNumber, 24f, bold = true), top = 4)
        c.add(ui.text("The call is running on the gateway phone.", 13f, R.color.sg_text_dim), top = 2)
        c.add(
            ui.button("END CALL", R.drawable.ic_call_end, Ui.Kind.DANGER, enabled = App.callUi != App.CallUi.ENDING) { App.hangup() },
            top = 14
        )
        return c
    }

    // ---- CLIENT: dialer --------------------------------------------------------------------

    private fun dialerScreen() {
        header("CALL THROUGH SIM GATEWAY", null) { go(App.Screen.HUB) }
        connectionStrip()
        noticeBanner()
        incomingBanner()

        val connected = App.connState == ConnectionManager.State.CONNECTED
        val busy = App.callUi == App.CallUi.DIALING || App.callUi == App.CallUi.IN_CALL || App.callUi == App.CallUi.ENDING

        val display = row()
        display.setPadding(ui.dp(16), ui.dp(8), ui.dp(4), ui.dp(8))
        display.background = ui.rounded(ui.color(R.color.sg_surface), 20, ui.color(R.color.sg_outline))
        val number = ui.text(
            if (App.dial.isEmpty()) "Enter number" else App.dial, 30f,
            if (App.dial.isEmpty()) R.color.sg_text_dim else R.color.sg_text, bold = true, center = true
        )
        number.maxLines = 1
        number.ellipsize = TextUtils.TruncateAt.START
        number.contentDescription = if (App.dial.isEmpty()) "No number entered" else "Number ${App.dial}"
        display.addView(number, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
        val back = FrameLayout(this)
        back.isClickable = true
        back.isFocusable = true
        back.contentDescription = "Delete last digit"
        back.background = ui.clickableBg(Color.TRANSPARENT, 24)
        back.addView(
            ui.icon(R.drawable.ic_backspace, R.color.sg_text),
            FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER)
        )
        back.setOnClickListener { if (!busy) App.dialBackspace() }
        back.setOnLongClickListener {
            if (!busy) App.dialClear()
            true
        }
        display.addView(back, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        content.add(display, top = 16)

        when (App.callUi) {
            App.CallUi.DIALING, App.CallUi.IN_CALL, App.CallUi.ENDING -> content.add(activeCallCard(), top = 12)
            App.CallUi.ENDED -> {
                val c = ui.card(14)
                c.addView(ui.text("Call ended", 16f, R.color.sg_text_dim, bold = true, center = true))
                content.add(c, top = 12)
            }
            App.CallUi.FAILED -> {
                val c = ui.card(16)
                c.background = ui.rounded(ui.color(R.color.sg_surface), 20, ui.color(R.color.sg_danger), 2)
                c.addView(ui.text("Call failed", 18f, R.color.sg_danger, bold = true))
                c.add(ui.text(App.callMessage ?: "The provider phone could not start the call.", 14f, R.color.sg_text), top = 4)
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                r.addWeighted(ui.button("TRY AGAIN", null, Ui.Kind.PRIMARY, enabled = connected) { App.placeCall() })
                r.addWeighted(ui.button("Dismiss", null, Ui.Kind.SECONDARY) { App.dismissCall() }, 10)
                c.add(r, top = 12)
                content.add(c, top = 12)
            }
            App.CallUi.READY -> {
            }
        }

        if (!busy) {
            val grid = column()
            for (rowKeys in listOf("123", "456", "789", "*0#")) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                for (ch in rowKeys) {
                    r.addView(
                        keyView(ch),
                        LinearLayout.LayoutParams(0, ui.dp(64), 1f).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) }
                    )
                }
                grid.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            content.add(grid, top = 8)

            val canCall = connected && Proto.cleanNumber(App.dial) != null
            content.add(ui.button("CALL", R.drawable.ic_call, Ui.Kind.CALL, enabled = canCall) { App.placeCall() }, top = 12)
        }
        audioNote()
    }

    private fun keyView(ch: Char): View {
        val k = column()
        k.gravity = Gravity.CENTER
        k.background = ui.clickableBg(ui.color(R.color.sg_surface), 18, ui.color(R.color.sg_outline))
        k.isClickable = true
        k.isFocusable = true
        k.contentDescription = when (ch) {
            '*' -> "star"
            '#' -> "pound"
            else -> ch.toString()
        }
        k.addView(ui.text(ch.toString(), 26f, bold = true, center = true))
        if (ch == '0') {
            k.addView(ui.text("+", 11f, R.color.sg_text_dim, center = true))
            k.setOnLongClickListener {
                App.dialPress('+')
                true
            }
        }
        k.setOnClickListener { App.dialPress(ch) }
        return k
    }

    // ---- CLIENT: sms -----------------------------------------------------------------------

    private fun input(hint: String, value: String, type: Int, lines: Int, onChange: (String) -> Unit): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.inputType = type
        e.setTextColor(ui.color(R.color.sg_text))
        e.setHintTextColor(ui.color(R.color.sg_text_dim))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        e.background = ui.rounded(ui.color(R.color.sg_surface), 14, ui.color(R.color.sg_outline))
        e.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        if (lines > 1) {
            e.minLines = lines
            e.gravity = Gravity.TOP or Gravity.START
        }
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        return e
    }

    private fun smsScreen() {
        header("SMS", "Sent from the gateway phone's SIM") { go(App.Screen.HUB) }
        connectionStrip()
        noticeBanner()
        incomingBanner()
        val connected = App.connState == ConnectionManager.State.CONNECTED

        content.add(ui.text("To", 13f, R.color.sg_text_dim, bold = true), top = 16)
        content.add(
            input("Phone number", App.smsNumber, InputType.TYPE_CLASS_PHONE, 1) { App.smsNumber = it },
            top = 6
        )
        content.add(ui.text("Message", 13f, R.color.sg_text_dim, bold = true), top = 14)
        val msg = input(
            "Type your message", App.smsText,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 5
        ) { App.smsText = it }
        msg.filters = arrayOf(InputFilter.LengthFilter(1000))
        content.add(msg, top = 6)

        val status = App.smsStatus
        if (status != null) {
            val c = ui.card(14)
            c.background = ui.rounded(
                ui.color(R.color.sg_surface), 16,
                ui.color(if (App.smsOk) R.color.sg_success else R.color.sg_danger), 2
            )
            c.addView(ui.text(if (App.smsOk) "\u2713 $status" else "\u2715 $status", 14f, R.color.sg_text))
            content.add(c, top = 12)
        }

        content.add(
            ui.button(
                if (App.smsSending) "Sending\u2026" else "SEND SMS", R.drawable.ic_send, Ui.Kind.PRIMARY,
                enabled = connected && !App.smsSending
            ) {
                currentFocus?.clearFocus()
                App.sendSms()
            },
            top = 16
        )
        content.add(
            ui.text(
                "Standard carrier rates apply on the gateway phone's SIM. \"Sent\" means the gateway phone accepted " +
                    "the message; delivery isn't confirmed.",
                12f, R.color.sg_text_dim
            ),
            top = 12
        )
    }

    // ---- LOG -------------------------------------------------------------------------------

    private fun logScreen() {
        header("Activity log", "Numbers are masked; messages are never stored") { go(App.logReturn) }
        val entries = EventLog.snapshot()
        if (entries.isEmpty()) {
            val c = ui.card(24)
            c.addView(ui.text("No activity yet", 16f, R.color.sg_text_dim, bold = true, center = true))
            content.add(c, top = 20)
        } else {
            val c = ui.card(14)
            for ((i, e) in entries.withIndex()) {
                val r = row()
                r.setPadding(0, ui.dp(7), 0, ui.dp(7))
                r.addView(
                    ui.text(EventLog.timeLabel(e.time), 12f, R.color.sg_text_dim),
                    LinearLayout.LayoutParams(ui.dp(64), WRAP)
                )
                r.addView(ui.text(e.text, 14f, R.color.sg_text), LinearLayout.LayoutParams(0, WRAP, 1f))
                c.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
                if (i < entries.size - 1) {
                    val d = View(this)
                    d.setBackgroundColor(ui.color(R.color.sg_outline))
                    c.addView(d, LinearLayout.LayoutParams(MATCH, 1))
                }
            }
            content.add(c, top = 16)
            content.add(ui.button("Clear history", null, Ui.Kind.SECONDARY) { EventLog.clear() }, top = 16)
        }
    }

    // ---- PIN dialog ------------------------------------------------------------------------

    private fun maybeShowPinDialog() {
        val prompt = App.pinPrompt ?: return
        if (pinDialog?.isShowing == true) return
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.filters = arrayOf(InputFilter.LengthFilter(6))
        e.hint = "6-digit PIN"
        e.gravity = Gravity.CENTER
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        e.letterSpacing = 0.2f
        val wrap = FrameLayout(this)
        wrap.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
        wrap.addView(e, FrameLayout.LayoutParams(MATCH, WRAP))
        val msg = (prompt.reason?.let { it + "\n\n" } ?: "") + "Enter the PIN shown on the SIM phone (${prompt.gateway.name})."
        val d = AlertDialog.Builder(this)
            .setTitle("Pair with gateway")
            .setMessage(msg)
            .setView(wrap)
            .setPositiveButton("Connect") { _, _ ->
                pinDialog = null
                App.submitPin(prompt.gateway, e.text.toString())
            }
            .setNegativeButton("Cancel") { _, _ ->
                pinDialog = null
                App.cancelPin()
            }
            .setOnCancelListener {
                pinDialog = null
                App.cancelPin()
            }
            .create()
        pinDialog = d
        d.show()
        e.requestFocus()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
}
