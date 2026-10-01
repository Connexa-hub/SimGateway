package com.connexa.simgateway

import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

private const val MATCH = MainActivity.MATCH
private const val WRAP = MainActivity.WRAP

/**
 * Dedicated search screen for contacts and recents. Back icon up top; the input field sits at
 * the BOTTOM, right above the keyboard, with recent searches shown as a grid above it when empty.
 */
internal fun MainActivity.appSearchScreen() {
    val topBar = row()
    val back = FrameLayout(this)
    back.isClickable = true
    back.isFocusable = true
    back.contentDescription = "Back"
    back.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 24)
    back.addView(ui.icon(R.drawable.ic_arrow_back, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
    back.setOnClickListener { App.closeAppSearch() }
    topBar.addView(back, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
    topBar.addView(ui.text("Search", 18f, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(6) })
    content.add(topBar, top = 4)

    val query = App.appSearchQuery.trim()
    if (query.isEmpty()) {
        if (App.recentSearches.isNotEmpty()) {
            val header = row()
            header.addView(ui.text("Recent searches", 13f, R.color.sg_text_dim, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            val clear = ui.text("Clear", 13f, R.color.sg_primary, bold = true)
            clear.isClickable = true
            clear.setOnClickListener { App.clearRecentSearches() }
            header.addView(clear)
            content.add(header, top = 20)

            val grid = column()
            var rowLayout: LinearLayout? = null
            for ((i, term) in App.recentSearches.withIndex()) {
                if (i % 2 == 0) {
                    rowLayout = row()
                    grid.addView(rowLayout, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                val chip = row()
                chip.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                chip.background = ui.rounded(ui.color(R.color.sg_surface_alt), 14)
                chip.isClickable = true
                chip.addView(ui.icon(R.drawable.ic_search, R.color.sg_text_dim), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)))
                val label = ui.text(term, 13f, R.color.sg_text)
                label.maxLines = 1
                label.ellipsize = android.text.TextUtils.TruncateAt.END
                chip.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(8) })
                chip.setOnClickListener {
                    App.appSearchQuery = term
                    App.changed()
                }
                rowLayout?.addWeighted(chip, if (i % 2 == 1) 8 else 0)
            }
            content.add(grid, top = 10)
        } else {
            val c = ui.card(24)
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(circleIcon(R.drawable.ic_search, 56, 26, ui.color(R.color.sg_surface_alt), R.color.sg_text_dim))
            c.add(ui.text("Search contacts and recents", 15f, R.color.sg_text_dim, center = true), top = 10)
            content.add(c, top = 30)
        }
    } else {
        val q = query.lowercase()
        val contactMatches = ContactsStore.cached().filter { it.name.lowercase().contains(q) || it.number.contains(q) }
        val recentMatches = CallHistory.merged(this).filter {
            (it.name ?: "").lowercase().contains(q) || it.number.contains(q)
        }.take(10)

        if (contactMatches.isEmpty() && recentMatches.isEmpty()) {
            content.add(ui.text("No results for \u201c$query\u201d", 14f, R.color.sg_text_dim, center = true), top = 30)
        } else {
            if (contactMatches.isNotEmpty()) {
                content.add(ui.text("Contacts", 13f, R.color.sg_text_dim, bold = true), top = 18)
                for (c in contactMatches) content.add(searchContactRow(c), top = 8)
            }
            if (recentMatches.isNotEmpty()) {
                content.add(ui.text("Recents", 13f, R.color.sg_text_dim, bold = true), top = 18)
                for (e in recentMatches) content.add(searchRecentRow(e), top = 8)
            }
        }
    }

    val spacer = View(this)
    content.add(spacer, top = 16, height = ui.dp(64))

    val inputBar = row()
    inputBar.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
    inputBar.background = ui.rounded(ui.color(R.color.sg_surface_alt), 18)
    inputBar.addView(ui.icon(R.drawable.ic_search, R.color.sg_text_dim), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
    val field = input("Search contacts and recents", App.appSearchQuery, InputType.TYPE_CLASS_TEXT, 1) {
        App.appSearchQuery = it
        render()
    }
    field.background = null
    field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    field.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
            App.commitAppSearch(field.text.toString())
            true
        } else false
    }
    inputBar.addView(field, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(10) })
    field.requestFocus()
    field.setSelection(field.text.length)
    searchBarSlot.addView(inputBar, FrameLayout.LayoutParams(MATCH, WRAP))
}

private fun MainActivity.searchContactRow(c: Contact): View {
    val r = row()
    r.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
    r.isClickable = true
    r.addView(contactAvatar(c.name, c.photoUri, 38))
    val col = column()
    col.addView(ui.text(c.name, 14f, bold = true))
    col.add(ui.text(c.number, 12f, R.color.sg_text_dim), top = 1)
    r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(10) })
    r.setOnClickListener {
        App.commitAppSearch(App.appSearchQuery)
        App.openContact = c
        App.appSearchQuery = ""
        go(App.Screen.CONTACT_INFO)
    }
    return r
}

private fun MainActivity.searchRecentRow(e: CallHistory.Entry): View {
    val r = row()
    r.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
    r.isClickable = true
    val label = e.name ?: e.number
    r.addView(avatarForNumber(label, e.number, 38))
    val col = column()
    col.addView(ui.text(label, 14f, bold = true))
    col.add(ui.text(e.number, 12f, R.color.sg_text_dim), top = 1)
    r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(10) })
    r.setOnClickListener {
        App.commitAppSearch(App.appSearchQuery)
        App.appSearchQuery = ""
        App.placeCallTo(e.number)
    }
    return r
}
