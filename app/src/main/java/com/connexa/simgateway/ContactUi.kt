package com.connexa.simgateway

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

private const val WRAP = MainActivity.WRAP
private const val MATCH = MainActivity.MATCH

// ---- CONTACT INFO ---------------------------------------------------------------------------

internal fun MainActivity.contactInfoScreen() {
    val c = App.openContact
    if (c == null) {
        go(App.Screen.CLIENT)
        return
    }

    val top = row()
    val back = FrameLayout(this)
    back.isClickable = true
    back.isFocusable = true
    back.contentDescription = "Back"
    back.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 24)
    back.addView(ui.icon(R.drawable.ic_arrow_back, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
    back.setOnClickListener { go(App.Screen.CLIENT) }
    top.addView(back, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
    val spacer = View(this)
    top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
    val edit = FrameLayout(this)
    edit.isClickable = true
    edit.isFocusable = true
    edit.contentDescription = "Edit contact"
    edit.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 20)
    edit.addView(ui.icon(R.drawable.ic_edit, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(21), ui.dp(21), Gravity.CENTER))
    edit.setOnClickListener {
        App.editingContact = c
        go(App.Screen.CONTACT_EDIT)
    }
    top.addView(edit, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    val overflow = FrameLayout(this)
    overflow.isClickable = true
    overflow.isFocusable = true
    overflow.contentDescription = "More options"
    overflow.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 20)
    overflow.addView(ui.icon(R.drawable.ic_more_vert, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
    overflow.setOnClickListener {
        val menu = android.widget.PopupMenu(this, overflow)
        menu.menu.add("Delete contact")
        menu.setOnMenuItemClickListener {
            confirmDeleteContact(c)
            true
        }
        menu.show()
    }
    top.addView(overflow, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    content.add(top)

    val avatarWrap = FrameLayout(this)
    avatarWrap.addView(avatarCircle(c.name, 100), FrameLayout.LayoutParams(ui.dp(100), ui.dp(100), Gravity.CENTER))
    content.add(avatarWrap, top = 12, height = ui.dp(100))
    content.add(ui.text(c.name, 22f, bold = true, center = true), top = 14)
    content.add(ui.text(c.number, 15f, R.color.sg_text_dim, center = true), top = 4)

    val actions = row()
    actions.gravity = Gravity.CENTER
    actions.addWeighted(bigCallButton(R.drawable.ic_call, R.color.sg_call, "Call") { App.placeCallTo(c.number) })
    actions.addWeighted(bigCallButton(R.drawable.ic_message, R.color.sg_primary, "Message") { App.openConversation(c.number) }, 24)
    content.add(actions, top = 28)

    // Share card: substitute for a QR card (see chat) -- opens the OS share sheet with a vCard.
    val shareCard = ui.card(18)
    shareCard.isClickable = true
    val shareRow = row()
    shareRow.addView(circleIcon(R.drawable.ic_share, 40, 18, ui.color(R.color.sg_surface_alt), R.color.sg_primary))
    val shareCol = column()
    shareCol.addView(ui.text("Share contact", 15f, bold = true))
    shareCol.add(ui.text("Send ${c.name}'s number to someone else", 13f, R.color.sg_text_dim), top = 2)
    shareRow.addView(shareCol, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(12) })
    shareCard.addView(shareRow)
    shareCard.setOnClickListener { shareContact(c) }
    content.add(shareCard, top = 28)
}

private fun MainActivity.shareContact(c: Contact) {
    val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:${c.name}\nTEL;TYPE=CELL:${c.number}\nEND:VCARD"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/x-vcard"
        putExtra(Intent.EXTRA_TEXT, "${c.name}: ${c.number}")
        putExtra(Intent.EXTRA_SUBJECT, c.name)
        putExtra("android.intent.extra.STREAM_TEXT", vcard)
    }
    try {
        startActivity(Intent.createChooser(intent, "Share ${c.name}"))
    } catch (e: Exception) {
        App.showNotice("Nothing on this phone can share a contact.")
    }
}

private fun MainActivity.confirmDeleteContact(c: Contact) {
    AlertDialog.Builder(this)
        .setTitle("Delete ${c.name}?")
        .setMessage("This removes the contact from this phone.")
        .setPositiveButton("Delete") { _, _ ->
            if (isGranted(Manifest.permission.WRITE_CONTACTS)) {
                if (ContactsStore.deleteContact(this, c.contactId)) {
                    App.openContact = null
                    loadContacts()
                    go(App.Screen.CLIENT)
                } else {
                    App.showNotice("Couldn't delete that contact.")
                }
            } else {
                requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS), MainActivity.REQ_CONTACTS)
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

// ---- CONTACT EDIT / ADD -----------------------------------------------------------------

internal fun MainActivity.contactEditScreen() {
    val editing = App.editingContact
    header(if (editing != null) "Edit contact" else "New contact", null) {
        go(if (editing != null) App.Screen.CONTACT_INFO else App.Screen.CLIENT)
    }

    if (!isGranted(Manifest.permission.WRITE_CONTACTS)) {
        val c = ui.card(20)
        c.addView(ui.text("Contacts access needed", 16f, bold = true))
        c.add(ui.text("Allow contacts access to add or edit contacts on this phone.", 13f, R.color.sg_text_dim), top = 4)
        c.add(ui.button("Allow", null, Ui.Kind.PRIMARY) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), MainActivity.REQ_CONTACTS)
        }, top = 12)
        content.add(c, top = 16)
        return
    }

    val nameField = input("Name", editing?.name ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, 1) {}
    content.add(ui.text("Name", 13f, R.color.sg_text_dim, bold = true), top = 16)
    content.add(nameField, top = 6)

    val numberField = input("Phone number", editing?.number ?: "", InputType.TYPE_CLASS_PHONE, 1) {}
    content.add(ui.text("Phone number", 13f, R.color.sg_text_dim, bold = true), top = 14)
    content.add(numberField, top = 6)

    content.add(
        ui.button(if (editing != null) "Save changes" else "Add contact", null, Ui.Kind.PRIMARY) {
            val name = nameField.text.toString().trim()
            val number = numberField.text.toString().trim()
            if (name.isEmpty() || Proto.cleanNumber(number) == null) {
                App.showNotice("Enter a name and a valid number.")
                return@button
            }
            val ok = if (editing != null) {
                ContactsStore.updateContact(this, editing.contactId, name, number)
            } else {
                ContactsStore.addContact(this, name, number)
            }
            if (ok) {
                loadContacts()
                App.editingContact = null
                go(App.Screen.CLIENT)
            } else {
                App.showNotice("Couldn't save that contact.")
            }
        },
        top = 20
    )
    if (editing != null) {
        content.add(ui.button("Cancel", null, Ui.Kind.SECONDARY) {
            go(App.Screen.CONTACT_INFO)
        }, top = 10)
    }
}
