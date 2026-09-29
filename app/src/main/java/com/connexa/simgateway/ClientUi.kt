package com.connexa.simgateway

import android.Manifest
import android.app.AlertDialog
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val MATCH = MainActivity.MATCH
private const val WRAP = MainActivity.WRAP

// ---- top-level client shell (tab bar lives outside the scroll view, pinned to the bottom) ----

internal fun MainActivity.clientScreen() {
    val bar = row()
    val logo = FrameLayout(this)
    logo.background = ui.rounded(ui.color(R.color.sg_primary), 12)
    logo.addView(ui.icon(R.drawable.ic_sim_card, R.color.sg_on_primary), FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
    bar.addView(logo, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
    bar.addView(ui.text("LinkSIM", 18f, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(10) })
    val spacer = View(this)
    bar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
    val msgBtn = FrameLayout(this)
    msgBtn.isClickable = true
    msgBtn.isFocusable = true
    msgBtn.contentDescription = "Messages"
    msgBtn.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 20)
    msgBtn.addView(ui.icon(R.drawable.ic_message, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
    msgBtn.setOnClickListener { go(App.Screen.SMS) }
    bar.addView(msgBtn, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
    val gear = FrameLayout(this)
    gear.isClickable = true
    gear.isFocusable = true
    gear.contentDescription = "Settings"
    gear.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 20)
    gear.addView(ui.icon(R.drawable.ic_settings, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
    gear.setOnClickListener {
        App.settingsReturn = App.Screen.CLIENT
        go(App.Screen.SETTINGS)
    }
    bar.addView(gear, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
    content.add(bar, top = 4)

    val connected = App.connState == ConnectionManager.State.CONNECTED
    if (connected) {
        val strip = row()
        strip.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
        strip.background = ui.rounded(ui.color(R.color.sg_surface_alt), 14)
        strip.addView(ui.dot(R.color.sg_success, 9))
        strip.addView(
            ui.text("Connected to ${App.gatewayName}", 13f, R.color.sg_text, bold = true),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(8) }
        )
        content.add(strip, top = 14)
    } else {
        val c = ui.card(16)
        val (title, sub) = when (App.connState) {
            ConnectionManager.State.CONNECTING, ConnectionManager.State.AUTHENTICATING -> "Connecting\u2026" to "Reaching the gateway phone"
            ConnectionManager.State.RECONNECTING -> "Reconnecting\u2026" to "Trying to reach ${App.gatewayName} again"
            ConnectionManager.State.AUTH_FAILED -> "Couldn't pair" to "Check the PIN and try again"
            else -> "Not connected" to "Connect to a phone that's sharing its SIM"
        }
        c.addView(ui.text(title, 17f, bold = true))
        c.add(ui.text(sub, 13f, R.color.sg_text_dim), top = 3)
        val actionLabel = if (App.target != null) "RECONNECT" else "FIND A GATEWAY"
        c.add(ui.button(actionLabel, R.drawable.ic_wifi, Ui.Kind.PRIMARY) {
            if (App.target != null) App.reconnect() else App.startSearch()
        }, top = 12)
        content.add(c, top = 14)
    }
    noticeBanner()

    when (App.clientTab) {
        App.ClientTab.RECENTS -> recentsTab()
        App.ClientTab.CONTACTS -> contactsTab()
        App.ClientTab.DIAL -> dialTab(connected)
        App.ClientTab.MESSAGES -> { /* reached only via the message icon, which routes to Screen.SMS */ }
    }

    clientBottomTabBar()
}

internal fun MainActivity.clientBottomTabBar() {
    val wrap = column()
    val divider = View(this)
    divider.setBackgroundColor(ui.color(R.color.sg_outline))
    wrap.addView(divider, LinearLayout.LayoutParams(MATCH, 1))

    val bar = row()
    bar.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
    bar.setBackgroundColor(ui.color(R.color.sg_surface))

    val tabs = listOf(
        Triple(App.ClientTab.RECENTS, R.drawable.ic_history, "Recents"),
        Triple(App.ClientTab.CONTACTS, R.drawable.ic_person, "Contacts"),
        Triple(App.ClientTab.DIAL, R.drawable.ic_dialpad, "Dial")
    )
    for ((tab, icon, label) in tabs) {
        val active = App.clientTab == tab
        val item = column()
        item.gravity = Gravity.CENTER
        item.setPadding(0, ui.dp(6), 0, ui.dp(2))
        item.isClickable = true
        item.isFocusable = true
        item.contentDescription = label
        item.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 12)
        item.addView(
            ui.icon(icon, if (active) R.color.sg_primary else R.color.sg_text_dim),
            LinearLayout.LayoutParams(ui.dp(23), ui.dp(23))
        )
        item.add(ui.text(label, 11f, if (active) R.color.sg_primary else R.color.sg_text_dim, bold = active, center = true), top = 4)
        item.setOnClickListener {
            App.clientTab = tab
            App.changed()
        }
        bar.addWeighted(item)
    }
    wrap.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
    bottomBar.addView(wrap, FrameLayout.LayoutParams(MATCH, WRAP))
}

// ---- RECENTS (native-style call log, swipe left/right to delete) -----------------------------

internal fun MainActivity.recentsTab() {
    content.add(ui.text("Recents", 19f, bold = true), top = 18)
    val entries = CallHistory.snapshot(this)
    if (entries.isEmpty()) {
        val c = ui.card(24)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(circleIcon(R.drawable.ic_history, 60, 28, ui.color(R.color.sg_surface_alt), R.color.sg_text_dim))
        c.add(ui.text("No calls yet", 17f, R.color.sg_text, bold = true, center = true), top = 12)
        c.add(ui.text("Calls you make or receive through the gateway will show up here.", 13f, R.color.sg_text_dim, center = true), top = 4)
        content.add(c, top = 14)
    } else {
        for (e in entries) content.add(callLogRow(e), top = 10)
        content.add(
            ui.text("Swipe a call left or right to remove it.", 12f, R.color.sg_text_dim, center = true),
            top = 8
        )
    }
}

private fun relativeCallTime(t: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - t
    if (diff < 60_000) return "Just now"
    if (diff < 3_600_000) return "${diff / 60_000}m ago"
    val cal = Calendar.getInstance()
    val today = cal.apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
    val cal2 = Calendar.getInstance().apply { timeInMillis = t }
    val day = cal2.get(Calendar.DAY_OF_YEAR)
    val year = cal2.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
    return when {
        day == today && year -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(t)
        today - day == 1 && year -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(t)
    }
}

private fun MainActivity.callLogRow(e: CallHistory.Entry): View {
    val wrap = FrameLayout(this)
    val bg = row()
    bg.gravity = Gravity.CENTER_VERTICAL
    bg.setPadding(ui.dp(20), 0, ui.dp(20), 0)
    bg.background = ui.rounded(ui.color(R.color.sg_danger), 18)
    val trashStart = ui.icon(R.drawable.ic_delete, R.color.sg_on_danger)
    val trashEnd = ui.icon(R.drawable.ic_delete, R.color.sg_on_danger)
    bg.addView(trashStart, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
    val mid = View(this)
    bg.addView(mid, LinearLayout.LayoutParams(0, 1, 1f))
    bg.addView(trashEnd, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
    wrap.addView(bg, FrameLayout.LayoutParams(MATCH, ui.dp(76)))

    val fg = row()
    fg.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
    fg.background = ui.rounded(ui.color(R.color.sg_surface), 18, ui.color(R.color.sg_outline))
    val label = e.name ?: e.number
    fg.addView(avatarCircle(label, 46))
    val col = column()
    col.addView(ui.text(label, 16f, bold = true))
    val typeRow = row()
    val (icon, colorId, typeLabel) = when (e.type) {
        CallHistory.Type.OUTGOING -> Triple(R.drawable.ic_call_made, R.color.sg_text_dim, "Outgoing")
        CallHistory.Type.INCOMING -> Triple(R.drawable.ic_call_received, R.color.sg_success, "Incoming")
        CallHistory.Type.MISSED -> Triple(R.drawable.ic_call_missed, R.color.sg_danger, "Missed")
    }
    typeRow.addView(ui.icon(icon, colorId), LinearLayout.LayoutParams(ui.dp(13), ui.dp(13)))
    typeRow.addView(
        ui.text("$typeLabel \u00b7 ${relativeCallTime(e.time)}", 12f, colorId),
        LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(6) }
    )
    col.add(typeRow, top = 3)
    fg.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(12) })
    val callBtn = circleIcon(R.drawable.ic_call, 40, 18, ui.color(R.color.sg_success), R.color.sg_on_call)
    callBtn.isClickable = true
    callBtn.contentDescription = "Call $label"
    callBtn.setOnClickListener { App.placeCallTo(e.number) }
    fg.addView(callBtn, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginStart = ui.dp(8) })

    wrap.addView(fg, FrameLayout.LayoutParams(MATCH, WRAP))
    attachSwipeToDelete(fg) { CallHistory.remove(this, e.id) }
    return wrap
}

/** Lightweight swipe-to-delete: drag left or right past a threshold to remove the row. */
private fun MainActivity.attachSwipeToDelete(target: View, onDelete: () -> Unit) {
    val slop = ViewConfiguration.get(this).scaledTouchSlop
    val threshold = ui.dp(96)
    var downX = 0f
    var downY = 0f
    var baseX = 0f
    var dragging = false
    var decided = false
    target.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                baseX = v.translationX
                dragging = false
                decided = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!decided) {
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        decided = true
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (Math.abs(dy) > slop) {
                        decided = true
                        dragging = false
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                if (dragging) {
                    v.translationX = baseX + dx
                    v.alpha = (1f - Math.abs(v.translationX) / (v.width.coerceAtLeast(1) * 1.2f)).coerceIn(0.25f, 1f)
                }
                dragging
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging && Math.abs(v.translationX) > threshold) {
                    val dir = if (v.translationX > 0) 1 else -1
                    v.animate().translationX(dir * v.width.toFloat()).alpha(0f).setDuration(180).withEndAction {
                        onDelete()
                    }.start()
                } else if (dragging) {
                    v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                } else if (!decided) {
                    v.performClick()
                }
                dragging
            }
            else -> false
        }
    }
}

// ---- CONTACTS (device contacts, requested lazily; tap to call or message) --------------------

internal fun MainActivity.contactsTab() {
    content.add(ui.text("Contacts", 19f, bold = true), top = 18)
    if (!isGranted(Manifest.permission.READ_CONTACTS)) {
        val c = ui.card(24)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(circleIcon(R.drawable.ic_person, 60, 28, ui.color(R.color.sg_surface_alt), R.color.sg_primary))
        c.add(ui.text("See your contacts here", 17f, R.color.sg_text, bold = true, center = true), top = 12)
        c.add(
            ui.text("Allow contacts access to call or message people through the gateway, just like a normal phone app.", 13f, R.color.sg_text_dim, center = true),
            top = 4
        )
        c.add(ui.button("Allow contacts access", null, Ui.Kind.PRIMARY) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), MainActivity.REQ_CONTACTS)
        }, top = 16)
        content.add(c, top = 14)
        return
    }
    if (contactsLoading) {
        val pb = ProgressBar(this)
        pb.isIndeterminate = true
        content.add(pb, top = 40, height = ui.dp(48))
        return
    }
    val list = ContactsStore.cached()
    if (list.isEmpty()) {
        loadContacts()
        content.add(ui.text("Loading contacts\u2026", 14f, R.color.sg_text_dim, center = true), top = 30)
        return
    }
    for (contact in list) content.add(contactRow(contact), top = 8)
}

private fun MainActivity.contactRow(c: Contact): View {
    val r = row()
    r.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
    r.background = ui.rounded(ui.color(R.color.sg_surface), 16, ui.color(R.color.sg_outline))
    r.addView(avatarCircle(c.name, 42))
    val col = column()
    col.addView(ui.text(c.name, 15f, bold = true))
    col.add(ui.text(c.number, 13f, R.color.sg_text_dim), top = 2)
    r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(12) })
    val call = circleIcon(R.drawable.ic_call, 38, 17, ui.color(R.color.sg_success), R.color.sg_on_call)
    call.isClickable = true
    call.contentDescription = "Call ${c.name}"
    call.setOnClickListener { App.placeCallTo(c.number) }
    r.addView(call, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { marginStart = ui.dp(8) })
    val msg = circleIcon(R.drawable.ic_message, 38, 17, ui.color(R.color.sg_primary), R.color.sg_on_primary)
    msg.isClickable = true
    msg.contentDescription = "Message ${c.name}"
    msg.setOnClickListener {
        App.smsNumber = c.number
        go(App.Screen.SMS)
    }
    r.addView(msg, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { marginStart = ui.dp(8) })
    return r
}

// ---- DIAL (keypad tab) -------------------------------------------------------------------

internal fun MainActivity.dialTab(connected: Boolean) {
    val display = row()
    display.setPadding(ui.dp(16), ui.dp(8), ui.dp(4), ui.dp(8))
    display.background = ui.rounded(ui.color(R.color.sg_surface), 20, ui.color(R.color.sg_outline))
    val number = ui.text(
        if (App.dial.isEmpty()) "Enter number" else App.dial, 28f,
        if (App.dial.isEmpty()) R.color.sg_text_dim else R.color.sg_text, bold = true, center = true
    )
    number.maxLines = 1
    number.ellipsize = android.text.TextUtils.TruncateAt.START
    number.contentDescription = if (App.dial.isEmpty()) "No number entered" else "Number ${App.dial}"
    display.addView(number, LinearLayout.LayoutParams(0, ui.dp(54), 1f))
    val back = FrameLayout(this)
    back.isClickable = true
    back.isFocusable = true
    back.contentDescription = "Delete last digit"
    back.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 24)
    back.addView(ui.icon(R.drawable.ic_backspace, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
    back.setOnClickListener { App.dialBackspace() }
    back.setOnLongClickListener {
        App.dialClear()
        true
    }
    display.addView(back, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
    content.add(display, top = 18)

    val grid = column()
    for (rowKeys in listOf("123", "456", "789", "*0#")) {
        val r = row()
        for (ch in rowKeys) {
            r.addView(
                keyView(ch),
                LinearLayout.LayoutParams(0, ui.dp(60), 1f).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) }
            )
        }
        grid.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    content.add(grid, top = 10)

    val canCall = connected && Proto.cleanNumber(App.dial) != null
    val callBtn = circleIcon(R.drawable.ic_call, 62, 26, ui.color(R.color.sg_call), R.color.sg_on_call)
    callBtn.isClickable = canCall
    callBtn.alpha = if (canCall) 1f else 0.45f
    callBtn.contentDescription = "Call"
    if (canCall) callBtn.setOnClickListener { App.placeCall() }
    val callWrap = FrameLayout(this)
    callWrap.addView(callBtn, FrameLayout.LayoutParams(ui.dp(62), ui.dp(62), Gravity.CENTER))
    content.add(callWrap, top = 16, height = ui.dp(62))
    if (!connected) {
        content.add(ui.text("Connect to a gateway to place a call.", 12f, R.color.sg_text_dim, center = true), top = 8)
    }
}

private fun MainActivity.keyView(ch: Char): View {
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
    k.addView(ui.text(ch.toString(), 24f, bold = true, center = true))
    if (ch == '0') {
        k.addView(ui.text("+", 10f, R.color.sg_text_dim, center = true))
        k.setOnLongClickListener {
            App.dialPress('+')
            true
        }
    }
    k.setOnClickListener { App.dialPress(ch) }
    return k
}

// ---- CALLING (full-screen, native-phone-style caller UI) -------------------------------------

internal fun MainActivity.callingScreen() {
    val ringing = App.incomingNumber != null
    val rawNumber = if (ringing) App.incomingNumber.orEmpty() else App.callNumber
    val displayName = ContactsStore.nameFor(rawNumber) ?: rawNumber

    content.add(View(this), top = 36, height = ui.dp(1))
    val avatarWrap = FrameLayout(this)
    avatarWrap.addView(avatarCircle(displayName, 132), FrameLayout.LayoutParams(ui.dp(132), ui.dp(132), Gravity.CENTER))
    content.add(avatarWrap, top = 8, height = ui.dp(132))

    content.add(ui.text(displayName, 26f, bold = true, center = true), top = 22)
    if (displayName != rawNumber && rawNumber.isNotBlank()) {
        content.add(ui.text(rawNumber, 14f, R.color.sg_text_dim, center = true), top = 4)
    }

    val statusText = when {
        ringing -> "Incoming call"
        App.callUi == App.CallUi.DIALING -> "Calling\u2026"
        App.callUi == App.CallUi.IN_CALL -> "00:00"
        App.callUi == App.CallUi.ENDING -> "Ending\u2026"
        App.callUi == App.CallUi.ENDED -> "Call ended"
        App.callUi == App.CallUi.FAILED -> App.callMessage ?: "Call failed"
        else -> ""
    }
    val statusColor = if (App.callUi == App.CallUi.FAILED && !ringing) R.color.sg_danger else R.color.sg_text_dim
    val statusView = ui.text(statusText, 16f, statusColor, center = true)
    content.add(statusView, top = 10)

    if (App.callUi == App.CallUi.IN_CALL && !ringing) {
        if (callTimerStart == 0L) callTimerStart = System.currentTimeMillis()
        statusView.text = callDurationText()
        callTimerLabel = statusView
    } else {
        callTimerStart = 0L
    }

    when {
        ringing -> {
            val actions = row()
            actions.gravity = Gravity.CENTER
            actions.addWeighted(bigCallButton(R.drawable.ic_call_end, R.color.sg_danger_btn, "Decline") { App.reject() })
            actions.addWeighted(bigCallButton(R.drawable.ic_call, R.color.sg_call, "Answer") { App.answer() }, 24)
            content.add(actions, top = 56)
        }
        App.callUi == App.CallUi.FAILED -> {
            content.add(ui.button("Try again", null, Ui.Kind.PRIMARY, enabled = App.connState == ConnectionManager.State.CONNECTED) {
                App.placeCallTo(App.callNumber)
            }, top = 32)
            content.add(ui.button("Back", null, Ui.Kind.SECONDARY) { App.dismissCall() }, top = 12)
        }
        else -> {
            val grid = row()
            grid.gravity = Gravity.CENTER
            grid.addWeighted(controlPlaceholder(R.drawable.ic_mic_off, "Mute"))
            grid.addWeighted(controlPlaceholder(R.drawable.ic_dialpad, "Keypad"), 16)
            grid.addWeighted(controlPlaceholder(R.drawable.ic_speaker, "Speaker"), 16)
            content.add(grid, top = 44)
            content.add(
                ui.text("Audio controls arrive once call audio can be carried over Wi-Fi.", 12f, R.color.sg_text_dim, center = true),
                top = 10
            )
            val endWrap = FrameLayout(this)
            endWrap.addView(
                bigCallButton(R.drawable.ic_call_end, R.color.sg_danger_btn, "End call", enabled = App.callUi != App.CallUi.ENDING) { App.hangup() },
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
            )
            content.add(endWrap, top = 40, height = ui.dp(110))
        }
    }
    audioNote()
}

internal fun MainActivity.bigCallButton(iconRes: Int, bgColorRes: Int, label: String, sizeDp: Int = 68, enabled: Boolean = true, onClick: () -> Unit): View {
    val col = column()
    col.gravity = Gravity.CENTER
    val circle = FrameLayout(this)
    circle.background = ui.ovalClickableBg(ui.color(bgColorRes))
    circle.isClickable = enabled
    circle.isFocusable = enabled
    circle.alpha = if (enabled) 1f else 0.4f
    circle.contentDescription = label
    circle.addView(ui.icon(iconRes, R.color.sg_on_primary), FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER))
    if (enabled) circle.setOnClickListener { onClick() }
    col.addView(circle, LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp)))
    col.add(ui.text(label, 13f, R.color.sg_text_dim), top = 8)
    return col
}

private fun MainActivity.controlPlaceholder(iconRes: Int, label: String): View {
    val col = column()
    col.gravity = Gravity.CENTER
    val circle = FrameLayout(this)
    circle.background = ui.oval(ui.color(R.color.sg_surface_alt))
    circle.alpha = 0.5f
    circle.addView(ui.icon(iconRes, R.color.sg_text_dim), FrameLayout.LayoutParams(ui.dp(21), ui.dp(21), Gravity.CENTER))
    col.addView(circle, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
    col.add(ui.text(label, 12f, R.color.sg_text_dim), top = 6)
    return col
}

// ---- SETTINGS ---------------------------------------------------------------------------

internal fun MainActivity.settingsScreen() {
    header("Settings", null) { go(App.settingsReturn) }

    val mode = Prefs.appMode(this)
    val modeCard = ui.card(18)
    modeCard.addView(ui.text("Current mode", 12f, R.color.sg_text_dim, bold = true))
    modeCard.add(
        ui.text(
            if (mode == "provider") "Provider \u2014 sharing this phone's SIM" else "Client \u2014 using another phone's SIM",
            17f, bold = true
        ),
        top = 4
    )
    modeCard.add(
        ui.text(
            "Switching modes turns off the current gateway or connection first.",
            13f, R.color.sg_text_dim
        ),
        top = 4
    )
    modeCard.add(ui.button("Switch mode", R.drawable.ic_swap, Ui.Kind.SECONDARY) { confirmSwitchMode() }, top = 14)
    content.add(modeCard, top = 16)

    if (mode == "client") {
        val permCard = ui.card(18)
        permCard.addView(ui.text("Permissions", 15f, bold = true))
        permCard.add(permRow("Read contacts", isGranted(Manifest.permission.READ_CONTACTS)), top = 6)
        content.add(permCard, top = 12)
    }

    content.add(
        ui.button("Diagnostics log", R.drawable.ic_history, Ui.Kind.SECONDARY) {
            App.logReturn = App.Screen.SETTINGS
            go(App.Screen.LOG)
        },
        top = 12
    )

    val about = ui.card(18)
    about.addView(ui.text("About LinkSIM", 15f, bold = true))
    about.add(
        ui.text(
            "LinkSIM lets a phone without a SIM place calls, receive calls, and send texts through another " +
                "phone's SIM over local Wi-Fi. Call audio itself stays on the SIM phone for now \u2014 only call " +
                "control is remoted.",
            13f, R.color.sg_text_dim
        ),
        top = 6
    )
    about.add(ui.text("Version ${versionName()}", 12f, R.color.sg_text_dim), top = 10)
    content.add(about, top = 12)
}

private fun MainActivity.confirmSwitchMode() {
    val current = Prefs.appMode(this)
    val msg = if (current == "provider") {
        "This stops sharing this phone's SIM. Any connected phone will be disconnected."
    } else {
        "This disconnects from the current gateway."
    }
    AlertDialog.Builder(this)
        .setTitle("Switch mode?")
        .setMessage(msg)
        .setPositiveButton("Switch") { _, _ -> App.switchMode() }
        .setNegativeButton("Cancel", null)
        .show()
}
