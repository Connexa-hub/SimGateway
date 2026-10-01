package com.connexa.simgateway

import android.Manifest
import android.app.AlertDialog
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val MATCH = MainActivity.MATCH
private const val WRAP = MainActivity.WRAP

// ---- top-level client shell ---------------------------------------------------------------

internal fun MainActivity.clientScreen() {
    val connected = App.connState == ConnectionManager.State.CONNECTED
    statusHeader(
        online = connected,
        showSearch = true,
        menuItems = listOf(
            "Messages" to { go(App.Screen.MESSAGES) },
            "Settings" to {
                App.settingsReturn = App.Screen.CLIENT
                go(App.Screen.SETTINGS)
            }
        )
    )

    if (!connected) {
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

    if (App.pickingRecipient) {
        App.clientTab = App.ClientTab.CONTACTS
        val c = ui.card(14)
        c.background = ui.rounded(ui.color(R.color.sg_surface), 16, ui.color(R.color.sg_primary), 2)
        c.addView(ui.text("Choose a contact to message", 14f, R.color.sg_text, bold = true))
        content.add(c, top = 12)
    }

    segmentedToggle()

    content.add(
        ui.text(if (App.clientTab == App.ClientTab.CONTACTS) "Contacts" else "Recents", 24f, bold = true),
        top = 18
    )

    when (App.clientTab) {
        App.ClientTab.RECENTS -> recentsTab()
        App.ClientTab.CONTACTS -> contactsTab()
    }

    if (App.pickingRecipient) return
    when (App.clientTab) {
        App.ClientTab.RECENTS -> setFab(R.drawable.ic_dialpad, "Open dialpad") { App.toggleDialSheet() }
        App.ClientTab.CONTACTS -> setFab(R.drawable.ic_plus, "Add contact") {
            App.beginEditContact(null)
        }
    }
}

/** Top-left switch-style toggle between Recents and Contacts (rounded-rectangle pill). */
internal fun MainActivity.segmentedToggle() {
    val wrap = row()
    wrap.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
    wrap.background = ui.rounded(ui.color(R.color.sg_surface_alt), 16)
    for (tab in listOf(App.ClientTab.RECENTS to "Recents", App.ClientTab.CONTACTS to "Contacts")) {
        val active = App.clientTab == tab.first
        val item = column()
        item.gravity = Gravity.CENTER
        item.setPadding(0, ui.dp(9), 0, ui.dp(9))
        item.isClickable = true
        item.isFocusable = true
        item.background = if (active) ui.rounded(ui.color(R.color.sg_surface), 13) else ui.clickableBg(android.graphics.Color.TRANSPARENT, 13)
        item.addView(ui.text(tab.second, 14f, if (active) R.color.sg_primary else R.color.sg_text_dim, bold = active, center = true))
        item.setOnClickListener {
            App.clientTab = tab.first
            App.changed()
        }
        wrap.addWeighted(item)
    }
    content.add(wrap, top = 14)
}

// ---- RECENTS (native-style call log: device Call Log merged with app-tracked calls) --------

internal fun MainActivity.recentsTab() {
    if (!isGranted(Manifest.permission.READ_CALL_LOG)) {
        val c = ui.card(14)
        c.addView(ui.text("See this phone's full call history", 14f, bold = true))
        c.add(ui.text("Allow call log access to include calls made outside LinkSIM here too.", 12f, R.color.sg_text_dim), top = 3)
        c.add(ui.button("Allow", null, Ui.Kind.SECONDARY) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), MainActivity.REQ_CALL_LOG)
        }, top = 10)
        content.add(c, top = 16)
    }
    val filterBar = row()
    for (f in listOf(App.RecentsFilter.ALL to "All", App.RecentsFilter.MISSED to "Missed")) {
        val active = App.recentsFilter == f.first
        val item = column()
        item.gravity = Gravity.CENTER
        item.isClickable = true
        item.isFocusable = true
        val label = ui.text(f.second, 14f, if (active) R.color.sg_primary else R.color.sg_text_dim, bold = active)
        item.addView(label)
        val underline = View(this)
        underline.setBackgroundColor(ui.color(if (active) R.color.sg_primary else android.R.color.transparent))
        item.add(underline, top = 6, height = ui.dp(2))
        item.setOnClickListener {
            App.recentsFilter = f.first
            App.changed()
        }
        item.setPadding(ui.dp(4), ui.dp(6), ui.dp(16), 0)
        filterBar.addView(item, LinearLayout.LayoutParams(WRAP, WRAP))
    }
    content.add(filterBar, top = 16)

    var entries = CallHistory.merged(this)
    if (App.recentsFilter == App.RecentsFilter.MISSED) entries = entries.filter { it.type == CallHistory.Type.MISSED }

    if (entries.isEmpty()) {
        val c = ui.card(24)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(circleIcon(R.drawable.ic_history, 60, 28, ui.color(R.color.sg_surface_alt), R.color.sg_text_dim))
        val emptyText = if (App.recentsFilter == App.RecentsFilter.MISSED) "No missed calls" else "No calls yet"
        c.add(ui.text(emptyText, 17f, R.color.sg_text, bold = true, center = true), top = 12)
        c.add(ui.text("Calls made or received on this phone show up here.", 13f, R.color.sg_text_dim, center = true), top = 4)
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
    fg.addView(avatarForNumber(label, e.number, 46))
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
    attachSwipeToDelete(fg) { CallHistory.removeMerged(this, e.id) }
    return wrap
}

/** Lightweight swipe-to-delete: drag left or right past a threshold to remove the row. */
internal fun MainActivity.attachSwipeToDelete(target: View, onDelete: () -> Unit) {
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

// ---- CONTACTS (device contacts, alphabetical, requested lazily) ----------------------------

internal fun MainActivity.contactsTab() {
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
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), MainActivity.REQ_CONTACTS)
        }, top = 16)
        content.add(c, top = 16)
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
    var lastLetter = ""
    for (contact in list) {
        val letter = contact.name.trim().take(1).uppercase().ifEmpty { "#" }
        if (letter != lastLetter) {
            lastLetter = letter
            content.add(ui.text(letter, 13f, R.color.sg_primary, bold = true), top = 18)
        }
        content.add(contactRow(contact), top = 8)
    }
}

private fun MainActivity.contactRow(c: Contact): View {
    val r = row()
    r.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
    r.background = ui.rounded(ui.color(R.color.sg_surface), 16, ui.color(R.color.sg_outline))
    val onTap: () -> Unit = {
        if (App.pickingRecipient) {
            App.pickingRecipient = false
            App.openConversation(c.number)
        } else {
            App.openContact = c
            go(App.Screen.CONTACT_INFO)
        }
    }
    val avatar = contactAvatar(c.name, c.photoUri, 42)
    avatar.isClickable = true
    avatar.contentDescription = "${c.name} details"
    avatar.setOnClickListener { onTap() }
    r.addView(avatar)
    val infoCol = column()
    infoCol.isClickable = true
    infoCol.setOnClickListener { onTap() }
    infoCol.addView(ui.text(c.name, 15f, bold = true))
    infoCol.add(ui.text(c.number, 13f, R.color.sg_text_dim), top = 2)
    r.addView(infoCol, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(12) })
    if (App.pickingRecipient) return r
    val call = circleIcon(R.drawable.ic_call, 38, 17, ui.color(R.color.sg_success), R.color.sg_on_call)
    call.isClickable = true
    call.contentDescription = "Call ${c.name}"
    call.setOnClickListener { App.placeCallTo(c.number) }
    r.addView(call, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { marginStart = ui.dp(8) })
    val msg = circleIcon(R.drawable.ic_message, 38, 17, ui.color(R.color.sg_primary), R.color.sg_on_primary)
    msg.isClickable = true
    msg.contentDescription = "Message ${c.name}"
    msg.setOnClickListener { App.openConversation(c.number) }
    r.addView(msg, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { marginStart = ui.dp(8) })
    return r
}

// ---- DIAL SHEET (rounded-circle FAB swipes this up over Recents; doesn't replace the screen) ---

internal fun MainActivity.renderDialSheet() {
    dialSheetSlot.removeAllViews()
    if (!App.dialSheetOpen) {
        dialSheetShown = false
        return
    }
    val connected = App.connState == ConnectionManager.State.CONNECTED

    val scrim = FrameLayout(this)
    scrim.setBackgroundColor(android.graphics.Color.argb(140, 0, 0, 0))
    scrim.isClickable = true
    scrim.setOnClickListener { App.closeDialSheet() }
    dialSheetSlot.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

    val panel = column()
    panel.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(20))
    panel.background = ui.roundedTop(ui.color(R.color.sg_surface), 24)

    val handle = View(this)
    handle.background = ui.rounded(ui.color(R.color.sg_outline), 3)
    val handleLp = LinearLayout.LayoutParams(ui.dp(36), ui.dp(4))
    handleLp.gravity = Gravity.CENTER_HORIZONTAL
    panel.addView(handle, handleLp)

    val display = row()
    display.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
    val number = ui.text(
        if (App.dial.isEmpty()) "Enter number" else App.dial, 24f,
        if (App.dial.isEmpty()) R.color.sg_text_dim else R.color.sg_text, bold = true, center = true
    )
    number.maxLines = 1
    number.ellipsize = android.text.TextUtils.TruncateAt.START
    display.addView(number, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
    val back = FrameLayout(this)
    back.isClickable = true
    back.isFocusable = true
    back.contentDescription = "Delete last digit"
    back.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 24)
    back.addView(ui.icon(R.drawable.ic_backspace, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
    back.setOnClickListener { App.dialBackspace() }
    back.setOnLongClickListener {
        App.dialClear()
        true
    }
    display.addView(back, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
    panel.add(display, top = 10)

    if (App.dial.isNotEmpty()) {
        val digits = App.dial.filter { it.isDigit() }
        val matches = (ContactsStore.cached().map { Triple(it.name, it.number, it.number) } +
            CallHistory.merged(this).map { Triple(it.name ?: it.number, it.number, it.number) })
            .distinctBy { it.second }
            .filter { digits.isNotEmpty() && (it.second.filter { c -> c.isDigit() }.contains(digits) || it.first.contains(App.dial, ignoreCase = true)) }
            .take(5)
        if (matches.isNotEmpty()) {
            val matchList = column()
            for ((name, num, _) in matches) {
                val row = row()
                row.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
                row.isClickable = true
                row.addView(avatarForNumber(name, num, 34))
                val col = column()
                col.addView(ui.text(name, 14f, bold = true))
                col.add(ui.text(num, 12f, R.color.sg_text_dim), top = 1)
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(10) })
                row.setOnClickListener { App.placeCallTo(num) }
                matchList.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            val scrollWrap = ScrollView(this)
            scrollWrap.addView(matchList, ViewGroup.LayoutParams(MATCH, WRAP))
            panel.add(scrollWrap, top = 6, height = ui.dp(if (matches.size > 3) 220 else matches.size * 62))
        }
    }

    val grid = column()
    for (rowKeys in listOf("123", "456", "789", "*0#")) {
        val r = row()
        for (ch in rowKeys) {
            r.addView(
                keyView(ch),
                LinearLayout.LayoutParams(0, ui.dp(54), 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
            )
        }
        grid.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    panel.add(grid, top = 8)

    val canCall = connected && Proto.cleanNumber(App.dial) != null
    val callBtn = circleIcon(R.drawable.ic_call, 56, 24, ui.color(R.color.sg_call), R.color.sg_on_call)
    callBtn.isClickable = canCall
    callBtn.alpha = if (canCall) 1f else 0.45f
    callBtn.contentDescription = "Call"
    if (canCall) callBtn.setOnClickListener { App.placeCall() }
    val callWrap = FrameLayout(this)
    callWrap.addView(callBtn, FrameLayout.LayoutParams(ui.dp(56), ui.dp(56), Gravity.CENTER))
    panel.add(callWrap, top = 12, height = ui.dp(56))
    if (!connected) {
        panel.add(ui.text("Connect to a gateway to place a call.", 12f, R.color.sg_text_dim, center = true), top = 6)
    }

    dialSheetSlot.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

    if (!dialSheetShown) {
        dialSheetShown = true
        panel.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                panel.viewTreeObserver.removeOnGlobalLayoutListener(this)
                panel.translationY = panel.height.toFloat()
                panel.animate().translationY(0f).setDuration(220).start()
            }
        })
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
        permCard.add(permRow("Contacts (view & edit)", isGranted(Manifest.permission.READ_CONTACTS) && isGranted(Manifest.permission.WRITE_CONTACTS)), top = 6)
        permCard.add(permRow("Call log", isGranted(Manifest.permission.READ_CALL_LOG)))
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
