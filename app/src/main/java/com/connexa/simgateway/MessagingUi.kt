package com.connexa.simgateway

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val WRAP = MainActivity.WRAP
private const val MATCH = MainActivity.MATCH

// ---- MESSAGES (thread list, Google-Messages style) ------------------------------------------

internal fun MainActivity.messagesScreen() {
    val connected = App.connState == ConnectionManager.State.CONNECTED
    header("Messages", null) { go(App.Screen.CLIENT) }
    noticeBanner()

    var threads = Messages.threads(this)
    if (App.threadSearchQuery.isNotBlank()) {
        val q = App.threadSearchQuery.trim().lowercase()
        threads = threads.filter { (it.name ?: it.number).lowercase().contains(q) || it.number.contains(q) }
    }

    val searchRow = row()
    val e = input("Search messages", App.threadSearchQuery, InputType.TYPE_CLASS_TEXT, 1) {
        App.threadSearchQuery = it
        render()
    }
    searchRow.addView(e, LinearLayout.LayoutParams(0, WRAP, 1f))
    content.add(searchRow, top = 12)

    if (threads.isEmpty()) {
        val c = ui.card(24)
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(circleIcon(R.drawable.ic_message, 60, 28, ui.color(R.color.sg_surface_alt), R.color.sg_text_dim))
        c.add(ui.text("No conversations yet", 17f, R.color.sg_text, bold = true, center = true), top = 12)
        c.add(
            ui.text("Messages sent or received through the gateway phone show up here.", 13f, R.color.sg_text_dim, center = true),
            top = 4
        )
        content.add(c, top = 16)
    } else {
        for (t in threads) content.add(threadRow(t), top = 10)
    }

    if (!connected) {
        content.add(
            ui.text("Connect to a gateway to send new messages.", 12f, R.color.sg_text_dim, center = true),
            top = 12
        )
    }

    setFab(R.drawable.ic_start_chat, "Start chat") {
        App.pickingRecipient = true
        go(App.Screen.CLIENT)
    }
}

private fun relativeThreadTime(t: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - t
    if (diff < 60_000) return "now"
    if (diff < 3_600_000) return "${diff / 60_000}m"
    val cal = Calendar.getInstance()
    val today = cal.apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
    val cal2 = Calendar.getInstance().apply { timeInMillis = t }
    val day = cal2.get(Calendar.DAY_OF_YEAR)
    val year = cal2.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
    return when {
        day == today && year -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(t)
        today - day == 1 && year -> "Yst"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(t)
    }
}

private fun MainActivity.threadRow(t: Messages.Thread): View {
    val wrap = FrameLayout(this)
    val bg = row()
    bg.gravity = Gravity.CENTER_VERTICAL
    bg.setPadding(ui.dp(20), 0, ui.dp(20), 0)
    bg.background = ui.rounded(ui.color(R.color.sg_danger), 18)
    bg.addView(ui.icon(R.drawable.ic_delete, R.color.sg_on_danger), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
    val mid = View(this)
    bg.addView(mid, LinearLayout.LayoutParams(0, 1, 1f))
    bg.addView(ui.icon(R.drawable.ic_delete, R.color.sg_on_danger), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
    wrap.addView(bg, FrameLayout.LayoutParams(MATCH, ui.dp(80)))

    val fg = row()
    fg.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
    fg.background = ui.rounded(ui.color(R.color.sg_surface), 18, ui.color(R.color.sg_outline))
    fg.isClickable = true
    val label = t.name ?: t.number
    fg.addView(avatarCircle(label, 48))
    val col = column()
    val nameRow = row()
    nameRow.addView(
        ui.text(label, 16f, bold = t.unread > 0),
        LinearLayout.LayoutParams(0, WRAP, 1f)
    )
    nameRow.addView(ui.text(relativeThreadTime(t.lastTime), 12f, R.color.sg_text_dim))
    col.addView(nameRow)
    val preview = ui.text(t.lastText, 13f, if (t.unread > 0) R.color.sg_text else R.color.sg_text_dim, bold = t.unread > 0)
    preview.maxLines = 1
    preview.ellipsize = android.text.TextUtils.TruncateAt.END
    col.add(preview, top = 3)
    fg.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(12) })
    if (t.unread > 0) {
        val badge = ui.text(t.unread.toString(), 11f, R.color.sg_on_primary, bold = true, center = true)
        val badgeWrap = FrameLayout(this)
        badgeWrap.background = ui.oval(ui.color(R.color.sg_primary))
        badgeWrap.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        fg.addView(badgeWrap, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginStart = ui.dp(8) })
    }
    fg.setOnClickListener { App.openConversation(t.number) }

    wrap.addView(fg, FrameLayout.LayoutParams(MATCH, WRAP))
    attachSwipeToDelete(fg) { App.deleteConversation(t.number) }
    return wrap
}

// ---- CONVERSATION (bubble thread, Google-Messages style) -------------------------------------

internal fun MainActivity.conversationScreen() {
    val name = ContactsStore.nameFor(App.conversationNumber) ?: App.conversationNumber
    header(name, if (name != App.conversationNumber) App.conversationNumber else null) { go(App.Screen.MESSAGES) }

    val entries = Messages.conversation(this, App.conversationNumber)
    if (entries.isEmpty()) {
        content.add(
            ui.text("No messages yet. Say hello \u2014 it's sent through the gateway phone's SIM.", 13f, R.color.sg_text_dim, center = true),
            top = 24
        )
    } else {
        var lastDay = -1
        for (m in entries) {
            val cal = Calendar.getInstance().apply { timeInMillis = m.time }
            val day = cal.get(Calendar.DAY_OF_YEAR)
            if (day != lastDay) {
                lastDay = day
                content.add(
                    ui.text(SimpleDateFormat("MMM d", Locale.getDefault()).format(m.time), 11f, R.color.sg_text_dim, center = true),
                    top = 14
                )
            }
            content.add(bubble(m), top = 6)
        }
    }

    val status = App.messageStatus
    if (status != null) {
        val c = ui.card(12)
        c.background = ui.rounded(ui.color(R.color.sg_surface), 14, ui.color(R.color.sg_danger), 2)
        c.addView(ui.text(status, 13f, R.color.sg_text))
        content.add(c, top = 10)
    }

    val inputRow = row()
    inputRow.gravity = Gravity.CENTER_VERTICAL
    val attach = FrameLayout(this)
    attach.background = ui.oval(ui.color(R.color.sg_surface_alt))
    attach.alpha = 0.5f
    attach.contentDescription = "Attach (needs MMS support, not available yet)"
    attach.addView(ui.icon(R.drawable.ic_attach, R.color.sg_text_dim), FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
    inputRow.addView(attach, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))

    val field = input("Text message", App.draftText, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 1) {
        App.draftText = it
    }
    field.background = ui.rounded(ui.color(R.color.sg_surface_alt), 20)
    inputRow.addView(field, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(8) })

    val connected = App.connState == ConnectionManager.State.CONNECTED
    val send = circleIcon(R.drawable.ic_send, 42, 18, ui.color(R.color.sg_primary), R.color.sg_on_primary)
    send.isClickable = connected && !App.sendingMessage
    send.alpha = if (connected) 1f else 0.4f
    send.setOnClickListener {
        currentFocus?.clearFocus()
        App.sendMessage()
    }
    inputRow.addView(send, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)).apply { marginStart = ui.dp(8) })
    content.add(inputRow, top = 16)

    if (!connected) {
        content.add(ui.text("Connect to a gateway to send this message.", 12f, R.color.sg_text_dim, center = true), top = 8)
    } else {
        content.add(
            ui.text("Photo and file attachments need MMS support, which isn't available yet.", 11f, R.color.sg_text_dim, center = true),
            top = 8
        )
    }
}

private fun MainActivity.bubble(m: Messages.Entry): View {
    val outer = FrameLayout(this)
    val bubble = column()
    bubble.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
    val out = m.direction == Messages.Direction.OUT
    bubble.background = if (out) {
        ui.rounded(ui.color(R.color.sg_primary), 16)
    } else {
        ui.rounded(ui.color(R.color.sg_surface_alt), 16)
    }
    bubble.addView(ui.text(m.text, 15f, if (out) R.color.sg_on_primary else R.color.sg_text))
    bubble.add(
        ui.text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(m.time), 10f, if (out) R.color.sg_on_primary else R.color.sg_text_dim),
        top = 4
    )
    val lp = FrameLayout.LayoutParams(WRAP, WRAP)
    lp.gravity = if (out) Gravity.END else Gravity.START
    lp.leftMargin = if (out) ui.dp(56) else 0
    lp.rightMargin = if (out) 0 else ui.dp(56)
    outer.addView(bubble, lp)
    return outer
}
