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
        const val REQ_PERMISSIONS = 100
        const val REQ_CONTACTS = 101
        const val REQ_CALL_LOG = 102
        const val REQ_PICK_PHOTO = 103
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    internal lateinit var ui: Ui
    internal lateinit var root: FrameLayout
    internal lateinit var scroll: ScrollView
    internal lateinit var content: LinearLayout
    internal lateinit var fabSlot: FrameLayout
    internal lateinit var dialSheetSlot: FrameLayout
    internal lateinit var searchBarSlot: FrameLayout

    internal val handler = Handler(Looper.getMainLooper())
    internal val animators = mutableListOf<Animator>()
    internal var uptimeLabel: TextView? = null
    internal var callTimerLabel: TextView? = null
    internal var callTimerStart: Long = 0L
    private var pinDialog: AlertDialog? = null
    private var launchAfterPermissions = false
    private var fixingPermissions = false
    internal var onboardingIndex = 0
    internal var contactsLoading = false
    internal var dialSheetShown = false

    private val appObserver: () -> Unit = { render() }
    private val gatewayObserver: () -> Unit = {
        handler.post { if (App.screen == App.Screen.PROVIDER) render() }
    }
    private val logObserver: () -> Unit = {
        handler.post { if (App.screen == App.Screen.LOG) render() }
    }
    private val callHistoryObserver: () -> Unit = {
        handler.post { if (App.screen == App.Screen.CLIENT && App.clientTab == App.ClientTab.RECENTS) render() }
    }
    private val ticker = object : Runnable {
        override fun run() {
            uptimeLabel?.text = uptimeText()
            if (App.callUi == App.CallUi.IN_CALL && callTimerStart > 0) {
                callTimerLabel?.text = callDurationText()
            }
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
    }

    override fun onStart() {
        super.onStart()
        App.addObserver(appObserver)
        GatewayState.addListener(gatewayObserver)
        EventLog.addListener(logObserver)
        CallHistory.addListener(callHistoryObserver)
        if (App.screen == App.Screen.CLIENT && App.clientTab == App.ClientTab.CONTACTS &&
            isGranted(Manifest.permission.READ_CONTACTS) && ContactsStore.cached().isEmpty()
        ) {
            loadContacts()
        }
        render()
        handler.postDelayed(ticker, 1000)
    }

    override fun onStop() {
        App.removeObserver(appObserver)
        GatewayState.removeListener(gatewayObserver)
        EventLog.removeListener(logObserver)
        CallHistory.removeListener(callHistoryObserver)
        handler.removeCallbacks(ticker)
        cancelAnimators()
        pinDialog?.dismiss()
        pinDialog = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (App.screen) {
            App.Screen.ONBOARDING -> super.onBackPressed()
            App.Screen.HOME -> super.onBackPressed()
            App.Screen.PROVIDER -> super.onBackPressed()
            App.Screen.SEARCH, App.Screen.CONNECTING -> App.cancelSearchToClient()
            App.Screen.CLIENT -> {
                if (App.pickingRecipient) {
                    App.pickingRecipient = false
                    App.changed()
                } else if (App.dialSheetOpen) {
                    App.closeDialSheet()
                } else {
                    super.onBackPressed()
                }
            }
            App.Screen.CALLING -> {
                App.screen = App.callReturnScreen
                if (App.screen == App.Screen.CLIENT) App.clientTab = App.callReturnTab
                App.changed()
            }
            App.Screen.CONTACT_INFO -> go(App.Screen.CLIENT)
            App.Screen.CONTACT_EDIT -> go(if (App.editingContact != null) App.Screen.CONTACT_INFO else App.Screen.CLIENT)
            App.Screen.MESSAGES -> go(App.Screen.CLIENT)
            App.Screen.CONVERSATION -> go(App.Screen.MESSAGES)
            App.Screen.LOG -> go(App.logReturn)
            App.Screen.SETTINGS -> go(App.settingsReturn)
            App.Screen.APP_SEARCH -> App.closeAppSearch()
        }
    }

    internal fun go(s: App.Screen) {
        App.screen = s
        App.changed()
    }

    private fun buildRoot() {
        root = FrameLayout(this)
        root.setBackgroundColor(ui.color(R.color.sg_bg))

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(28))

        scroll.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        column.addView(scroll, LinearLayout.LayoutParams(MATCH, MATCH))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        fabSlot = FrameLayout(this)
        root.addView(
            fabSlot,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = ui.dp(20)
                bottomMargin = ui.dp(24)
            }
        )

        dialSheetSlot = FrameLayout(this)
        root.addView(dialSheetSlot, FrameLayout.LayoutParams(MATCH, MATCH))

        searchBarSlot = FrameLayout(this)
        root.addView(
            searchBarSlot,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                leftMargin = ui.dp(20)
                rightMargin = ui.dp(20)
                bottomMargin = ui.dp(16)
            }
        )

        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            }
            insets
        }
    }

    /** Shows a single circular floating action button, bottom-right. Pass null iconRes to hide it. */
    internal fun setFab(iconRes: Int?, contentDesc: String, onClick: () -> Unit) {
        fabSlot.removeAllViews()
        if (iconRes == null) return
        val fab = FrameLayout(this)
        fab.background = ui.ovalClickableBg(ui.color(R.color.sg_primary))
        fab.elevation = ui.dp(4).toFloat()
        fab.isClickable = true
        fab.isFocusable = true
        fab.contentDescription = contentDesc
        fab.addView(ui.icon(iconRes, R.color.sg_on_primary), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
        fab.setOnClickListener { onClick() }
        fabSlot.addView(fab, FrameLayout.LayoutParams(ui.dp(58), ui.dp(58)))
    }

    // ---- rendering helpers -----------------------------------------------------------------

    internal fun render() {
        cancelAnimators()
        uptimeLabel = null
        callTimerLabel = null
        content.removeAllViews()
        fabSlot.removeAllViews()
        searchBarSlot.removeAllViews()
        when (App.screen) {
            App.Screen.ONBOARDING -> onboardingScreen()
            App.Screen.HOME -> homeScreen()
            App.Screen.PROVIDER -> providerScreen()
            App.Screen.SEARCH -> searchScreen()
            App.Screen.CONNECTING -> connectingScreen()
            App.Screen.CLIENT -> clientScreen()
            App.Screen.CALLING -> callingScreen()
            App.Screen.CONTACT_INFO -> contactInfoScreen()
            App.Screen.CONTACT_EDIT -> contactEditScreen()
            App.Screen.MESSAGES -> messagesScreen()
            App.Screen.CONVERSATION -> conversationScreen()
            App.Screen.LOG -> logScreen()
            App.Screen.SETTINGS -> settingsScreen()
            App.Screen.APP_SEARCH -> appSearchScreen()
        }
        renderDialSheet()
        maybeShowPinDialog()
    }

    internal fun cancelAnimators() {
        for (a in animators) a.cancel()
        animators.clear()
    }

    internal fun LinearLayout.add(v: View, top: Int = 0, height: Int = WRAP): View {
        addView(v, LinearLayout.LayoutParams(MATCH, height).apply { topMargin = ui.dp(top) })
        return v
    }

    internal fun LinearLayout.addWeighted(v: View, startMarginDp: Int = 0) {
        addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(startMarginDp) })
    }

    internal fun row(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        return r
    }

    internal fun column(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        return c
    }

    internal fun circleIcon(iconRes: Int, sizeDp: Int, iconDp: Int, bg: Int, tint: Int): FrameLayout {
        val f = FrameLayout(this)
        f.background = ui.oval(bg)
        f.addView(ui.icon(iconRes, tint), FrameLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
        return f
    }

    internal fun avatarCircle(name: String, sizeDp: Int, bg: Int = ui.color(R.color.sg_surface_alt), textColor: Int = R.color.sg_primary): FrameLayout {
        val f = FrameLayout(this)
        f.background = ui.oval(bg)
        val initials = initialsFor(name)
        val t = ui.text(initials, sizeDp * 0.36f, textColor, bold = true, center = true)
        f.addView(t, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
        return f
    }

    /** Decodes an image URI into a circular bitmap sized for an avatar, or null if it can't be read. */
    internal fun circularBitmap(uri: android.net.Uri, sizeDp: Int): android.graphics.Bitmap? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val raw = input.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
            val size = ui.dp(sizeDp)
            val scaled = android.graphics.Bitmap.createScaledBitmap(raw, size, size, true)
            val output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(output)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            output
        } catch (e: Exception) {
            null
        }
    }

    /** Avatar that shows the contact's real photo (circularly cropped) when available, else initials. */
    internal fun contactAvatar(name: String, photoUri: String?, sizeDp: Int): View {
        if (photoUri != null) {
            val bmp = circularBitmap(android.net.Uri.parse(photoUri), sizeDp)
            if (bmp != null) {
                val iv = android.widget.ImageView(this)
                iv.setImageBitmap(bmp)
                iv.layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
                return iv
            }
        }
        return avatarCircle(name, sizeDp)
    }

    /** Same as [contactAvatar], but looks the contact up by number first (for Recents/Messages rows). */
    internal fun avatarForNumber(name: String, number: String, sizeDp: Int): View {
        val contact = ContactsStore.findByNumber(number)
        return contactAvatar(name, contact?.photoUri, sizeDp)
    }

    private fun initialsFor(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "#"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    internal fun header(title: String, subtitle: String? = null, onBack: (() -> Unit)?) {
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

    /** Header used on the two top-level mode screens: no back arrow, a settings gear instead. */
    /**
     * Header required across the top-level mode screens: left is ONLY a status dot + Online/Offline
     * text (no wordmark, no extra text); right is a search toggle (optional) and a 3-dot overflow menu.
     */
    internal fun statusHeader(
        online: Boolean,
        showSearch: Boolean,
        menuItems: List<Pair<String, () -> Unit>>
    ) {
        val r = row()
        r.addView(ui.dot(if (online) R.color.sg_success else R.color.sg_text_dim, 10))
        r.addView(
            ui.text(if (online) "Online" else "Offline", 16f, bold = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(8) }
        )
        val spacer = View(this)
        r.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val pill = row()
        pill.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
        pill.background = ui.rounded(ui.color(R.color.sg_surface_alt), 16)

        if (showSearch) {
            val search = FrameLayout(this)
            search.isClickable = true
            search.isFocusable = true
            search.contentDescription = "Search"
            search.background = ui.clickableBg(Color.TRANSPARENT, 13)
            search.addView(
                ui.icon(R.drawable.ic_search, R.color.sg_text),
                FrameLayout.LayoutParams(ui.dp(21), ui.dp(21), Gravity.CENTER)
            )
            search.setOnClickListener { App.openAppSearch() }
            pill.addView(search, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        }

        val overflow = FrameLayout(this)
        overflow.isClickable = true
        overflow.isFocusable = true
        overflow.contentDescription = "More options"
        overflow.background = ui.clickableBg(Color.TRANSPARENT, 13)
        overflow.addView(ui.icon(R.drawable.ic_more_vert, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
        overflow.setOnClickListener {
            val menu = android.widget.PopupMenu(this, overflow)
            for ((label, _) in menuItems) menu.menu.add(label)
            menu.setOnMenuItemClickListener { item ->
                menuItems.firstOrNull { it.first == item.title.toString() }?.second?.invoke()
                true
            }
            menu.show()
        }
        pill.addView(overflow, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))

        r.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(8) })
        content.add(r, top = 4)
    }

    internal fun infoRow(label: String, value: String, valueColor: Int = R.color.sg_text): View {
        val r = row()
        r.addView(ui.text(label, 14f, R.color.sg_text_dim), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(ui.text(value, 15f, valueColor, bold = true))
        r.setPadding(0, ui.dp(6), 0, ui.dp(6))
        return r
    }

    internal fun statCard(label: String, value: String, valueColor: Int = R.color.sg_text): Pair<LinearLayout, TextView> {
        val c = ui.card(14)
        c.addView(ui.text(label, 12f, R.color.sg_text_dim))
        val v = ui.text(value, 18f, valueColor, bold = true)
        c.add(v, top = 4)
        c.isFocusable = true
        return Pair(c, v)
    }

    internal fun statRow(a: LinearLayout, b: LinearLayout) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.addWeighted(a)
        r.addWeighted(b, 12)
        content.add(r, top = 12)
    }

    // ---- ONBOARDING ------------------------------------------------------------------------

    private data class OnboardPage(val icon: Int, val title: String, val body: String)

    private val onboardPages = listOf(
        OnboardPage(
            R.drawable.ic_sim_card, "Meet LinkSIM",
            "Share one phone's SIM \u2014 calls and texts \u2014 with another phone over Wi-Fi. No new SIM, no swapping cards."
        ),
        OnboardPage(
            R.drawable.ic_swap, "Two roles, one app",
            "On the phone with the SIM, choose Provide SIM. On the phone without one, choose Use Another Phone. Either phone can be either role."
        ),
        OnboardPage(
            R.drawable.ic_lock, "Private by design",
            "A 6-digit PIN pairs your two phones the first time they connect. Numbers in the diagnostics log are masked, and message text is never stored."
        )
    )

    private fun onboardingScreen() {
        val page = onboardPages[onboardingIndex]
        content.add(View(this), top = 24, height = ui.dp(1))
        val iconWrap = FrameLayout(this)
        iconWrap.addView(
            circleIcon(page.icon, 96, 44, ui.color(R.color.sg_surface_alt), R.color.sg_primary),
            FrameLayout.LayoutParams(ui.dp(96), ui.dp(96), Gravity.CENTER)
        )
        content.add(iconWrap, top = 20, height = ui.dp(96))
        content.add(ui.text(page.title, 24f, bold = true, center = true), top = 28)
        content.add(ui.text(page.body, 15f, R.color.sg_text_dim, center = true), top = 10)

        val dots = row()
        dots.gravity = Gravity.CENTER
        for (i in onboardPages.indices) {
            val d = View(this)
            d.background = ui.oval(ui.color(if (i == onboardingIndex) R.color.sg_primary else R.color.sg_outline))
            dots.addView(d, LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) })
        }
        content.add(dots, top = 24, height = ui.dp(8))

        if (onboardingIndex < onboardPages.size - 1) {
            content.add(ui.button("Next", null, Ui.Kind.PRIMARY) {
                onboardingIndex++
                render()
            }, top = 28)
            content.add(ui.button("Skip", null, Ui.Kind.GHOST) {
                App.finishOnboarding()
            }, top = 10)
        } else {
            content.add(ui.button("Get started", null, Ui.Kind.PRIMARY) {
                App.finishOnboarding()
            }, top = 28)
        }
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
            ui.text("LinkSIM", 22f, bold = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(12) }
        )
        content.add(brand, top = 8)
        content.add(
            ui.text("Choose how this phone will be used.", 16f, R.color.sg_text_dim),
            top = 16
        )

        content.add(
            roleCard(
                "PROVIDE SIM", "Share this phone's SIM", "Let another phone place calls and send texts through it.",
                R.drawable.ic_sim_card
            ) { App.chooseMode("provider") },
            top = 28
        )
        content.add(
            roleCard(
                "USE ANOTHER PHONE", "Borrow a SIM over Wi-Fi", "Call and text using a SIM that's in a different phone.",
                R.drawable.ic_phone_android
            ) { App.chooseMode("client") },
            top = 14
        )
        content.add(ui.text("Version ${versionName()}", 12f, R.color.sg_text_dim, center = true), top = 24)
    }

    internal fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun roleCard(role: String, title: String, desc: String, iconRes: Int, onClick: () -> Unit): View {
        val card = row()
        card.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))
        card.background = ui.clickableBg(ui.color(R.color.sg_surface), 22, ui.color(R.color.sg_outline))
        card.isClickable = true
        card.isFocusable = true
        card.contentDescription = "$title. $desc"
        card.setOnClickListener { onClick() }
        card.addView(circleIcon(iconRes, 56, 28, ui.color(R.color.sg_surface_alt), R.color.sg_primary))
        val col = column()
        col.addView(ui.text(role, 12f, R.color.sg_text_dim, bold = true))
        col.add(ui.text(title, 20f, bold = true), top = 2)
        col.add(ui.text(desc, 14f, R.color.sg_text_dim), top = 2)
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(16) })
        return card
    }

    // ---- PROVIDER --------------------------------------------------------------------------

    internal fun requiredPermissions(): List<String> {
        val l = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    internal fun isGranted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    internal fun missingPermissions(): List<String> = requiredPermissions().filter { !isGranted(it) }

    private fun startProvider() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            launchProvider()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Let this phone share its SIM")
            .setMessage(
                "To act as the gateway, this phone needs permission to:\n\n" +
                    "\u2022 Make and end phone calls\n" +
                    "\u2022 Answer calls\n" +
                    "\u2022 See incoming calls and the caller's number\n" +
                    "\u2022 Send and receive text messages\n" +
                    "\u2022 Show the gateway notification\n\n" +
                    "Only a phone that enters this phone's PIN can use them. " +
                    "You can continue without some permissions, but those features won't work until you grant them."
            )
            .setPositiveButton("Continue") { _, _ ->
                launchAfterPermissions = true
                requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            }
            .setNegativeButton("Not now", null)
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

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_PHOTO && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                App.editingPhotoUri = uri
                App.changed()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONTACTS) {
            if (isGranted(Manifest.permission.READ_CONTACTS)) {
                loadContacts()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                offerSettings()
            }
            App.changed()
            return
        }
        if (requestCode == REQ_CALL_LOG) {
            if (!isGranted(Manifest.permission.READ_CALL_LOG) && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALL_LOG)) {
                offerSettings()
            }
            App.changed()
            return
        }
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

    internal fun offerSettings() {
        AlertDialog.Builder(this)
            .setTitle("Permission blocked")
            .setMessage("Android won't ask again here. Open the app's settings and allow the missing permission.")
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

    internal fun loadContacts() {
        if (contactsLoading) return
        contactsLoading = true
        Thread {
            ContactsStore.load(applicationContext)
            handler.post {
                contactsLoading = false
                if (App.screen == App.Screen.CLIENT && App.clientTab == App.ClientTab.CONTACTS) render()
            }
        }.start()
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

    internal fun callDurationText(): String {
        val s = (System.currentTimeMillis() - callTimerStart) / 1000
        val m = s / 60
        val sec = s % 60
        return String.format("%d:%02d", m, sec)
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
        val gwOnline = GatewayState.status == GatewayState.Status.ONLINE
        statusHeader(
            online = gwOnline, showSearch = false,
            menuItems = listOf("Settings" to {
                App.settingsReturn = App.Screen.PROVIDER
                go(App.Screen.SETTINGS)
            })
        )

        val st = GatewayState.status
        val spec = when (st) {
            GatewayState.Status.ONLINE ->
                Triple(R.color.sg_success, "You're online", "This phone is sharing its SIM")
            GatewayState.Status.STARTING ->
                Triple(R.color.sg_warning, "Starting up\u2026", "Getting the gateway ready")
            GatewayState.Status.ERROR ->
                Triple(R.color.sg_danger, "Something's wrong", GatewayState.error ?: "The gateway hit a problem")
            GatewayState.Status.STOPPED ->
                Triple(R.color.sg_text_dim, "Not sharing", "Start the gateway to let another phone in")
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
        val (devCard, _) = statCard("Connected phones", n.toString())
        val (discCard, _) = statCard(
            "Visibility", GatewayState.discovery,
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
            ui.text("Share this with the other phone the first time it connects.", 13f, R.color.sg_text_dim),
            top = 4
        )
        pinCard.add(
            ui.button("Generate new PIN", null, Ui.Kind.SECONDARY) {
                Prefs.regeneratePin(this)
                EventLog.add("Pairing PIN changed")
                render()
            },
            top = 12
        )
        content.add(pinCard, top = 12)

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
        permCard.add(permRow("Send and receive text messages", isGranted(Manifest.permission.SEND_SMS) && isGranted(Manifest.permission.RECEIVE_SMS)))
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

        val tip = ui.card(18)
        tip.addView(ui.text("Keep it reliable", 15f, bold = true))
        tip.add(
            ui.text(
                "Keep both phones on the same Wi-Fi network. If Android ever stops the gateway in the " +
                    "background, exclude LinkSIM from battery optimization.",
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
                ui.button("Stop sharing", R.drawable.ic_call_end, Ui.Kind.DANGER) {
                    GatewayService.stop(this)
                },
                top = 20
            )
        } else {
            content.add(ui.button("Start sharing", R.drawable.ic_sim_card, Ui.Kind.PRIMARY) { startProvider() }, top = 20)
        }
    }

    internal fun permRow(label: String, granted: Boolean): View {
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

    internal fun pulseView(): View {
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
        header("Find a gateway", null) { App.cancelSearchToClient() }

        val err = App.searchError
        if (err != null) {
            val c = ui.card()
            c.background = ui.rounded(ui.color(R.color.sg_surface), 20, ui.color(R.color.sg_danger), 2)
            c.addView(ui.text("Something went wrong", 15f, R.color.sg_danger, bold = true))
            c.add(ui.text(err, 14f, R.color.sg_text_dim), top = 4)
            content.add(c, top = 16)
        }

        if (App.gateways.isEmpty()) {
            if (App.searchTimedOut) {
                emptyState()
            } else {
                content.add(pulseView(), top = 24, height = ui.dp(190))
                content.add(ui.text("SEARCHING\u2026", 16f, R.color.sg_text, bold = true, center = true), top = 8)
                content.add(
                    ui.text(
                        "Make sure the other phone is on the same Wi-Fi and has chosen Provide SIM.",
                        14f, R.color.sg_text_dim, center = true
                    ),
                    top = 8
                )
            }
        } else {
            content.add(ui.text("Found nearby", 18f, bold = true), top = 20)
            for (g in App.gateways) content.add(gatewayCard(g), top = 12)
        }
    }

    private fun emptyState() {
        val c = ui.card(24)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(
            circleIcon(R.drawable.ic_wifi, 64, 30, ui.color(R.color.sg_surface_alt), R.color.sg_text_dim)
        )
        c.add(ui.text("Nothing found yet", 18f, R.color.sg_text, bold = true, center = true), top = 14)
        c.add(
            ui.text(
                "Check that the SIM phone has chosen Provide SIM and that both phones share the same Wi-Fi network.",
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
        header("Connecting", null) { App.cancelSearchToClient() }
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
        content.add(ui.button("Cancel", null, Ui.Kind.SECONDARY) { App.cancelSearchToClient() }, top = 32)
    }

    // ---- shared small pieces used by ClientUi.kt / CallingUi.kt ----------------------------

    internal fun input(hint: String, value: String, type: Int, lines: Int, onChange: (String) -> Unit): EditText {
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

    internal fun noticeBanner() {
        val n = App.notice ?: return
        val c = ui.card(14)
        c.background = ui.rounded(ui.color(R.color.sg_surface), 16, ui.color(R.color.sg_warning), 2)
        c.addView(ui.text(n, 14f, R.color.sg_text))
        content.add(c, top = 12)
    }

    internal fun audioNote() {
        content.add(
            ui.text(
                "Calls are placed and controlled through the SIM phone. Their voice audio stays on that phone " +
                    "for now: Android doesn't let apps carry cellular call audio over Wi-Fi.",
                12f, R.color.sg_text_dim
            ),
            top = 12
        )
    }

    internal fun logScreen() {
        header("Diagnostics", "Technical activity; numbers are masked and messages are never stored") { go(App.logReturn) }
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
