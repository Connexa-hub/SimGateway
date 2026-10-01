package com.connexa.simgateway

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.provider.ContactsContract
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
    edit.setOnClickListener { App.beginEditContact(c) }
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
    avatarWrap.addView(contactAvatar(c.name, c.photoUri, 100), FrameLayout.LayoutParams(ui.dp(100), ui.dp(100), Gravity.CENTER))
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

// ---- CONTACT EDIT / ADD (Google Contacts style) ------------------------------------------

internal fun MainActivity.contactEditScreen() {
    val editing = App.editingContact

    if (!isGranted(Manifest.permission.WRITE_CONTACTS)) {
        header(if (editing != null) "Edit contact" else "New contact", null) {
            go(if (editing != null) App.Screen.CONTACT_INFO else App.Screen.CLIENT)
        }
        val c = ui.card(20)
        c.addView(ui.text("Contacts access needed", 16f, bold = true))
        c.add(ui.text("Allow contacts access to add or edit contacts on this phone.", 13f, R.color.sg_text_dim), top = 4)
        c.add(ui.button("Allow", null, Ui.Kind.PRIMARY) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), MainActivity.REQ_CONTACTS)
        }, top = 12)
        content.add(c, top = 16)
        return
    }

    // Top bar: X to cancel, checkmark to save -- matches the real Google Contacts edit screen.
    val top = row()
    val close = FrameLayout(this)
    close.isClickable = true
    close.isFocusable = true
    close.contentDescription = "Cancel"
    close.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 24)
    close.addView(ui.icon(R.drawable.ic_arrow_back, R.color.sg_text), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
    close.setOnClickListener { go(if (editing != null) App.Screen.CONTACT_INFO else App.Screen.CLIENT) }
    top.addView(close, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
    top.addView(
        ui.text(if (editing != null) "Edit contact" else "New contact", 18f, bold = true),
        LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(4) }
    )
    val save = FrameLayout(this)
    save.isClickable = true
    save.isFocusable = true
    save.contentDescription = "Save"
    save.background = ui.clickableBg(android.graphics.Color.TRANSPARENT, 20)
    save.addView(ui.icon(R.drawable.ic_check_circle, R.color.sg_primary), FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
    save.setOnClickListener { saveContactDraft(editing) }
    top.addView(save, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    content.add(top)

    // Photo with camera badge
    val avatarWrap = FrameLayout(this)
    val preview = App.editingPhotoUri?.let { circularBitmap(it, 92) }
    val avatarView: View = if (preview != null) {
        val iv = android.widget.ImageView(this)
        iv.setImageBitmap(preview)
        iv
    } else {
        contactAvatar(
            (App.editDraftFirst + " " + App.editDraftLast).trim().ifEmpty { "?" },
            editing?.photoUri, 92
        )
    }
    avatarView.isClickable = true
    avatarView.contentDescription = "Change photo"
    avatarView.setOnClickListener { pickContactPhoto() }
    avatarWrap.addView(avatarView, FrameLayout.LayoutParams(ui.dp(92), ui.dp(92), Gravity.CENTER))
    val badge = circleIcon(R.drawable.ic_camera, 30, 14, ui.color(R.color.sg_primary), R.color.sg_on_primary)
    avatarWrap.addView(badge, FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER or Gravity.BOTTOM or Gravity.END).apply {
        rightMargin = ui.dp(6)
    })
    content.add(avatarWrap, top = 16, height = ui.dp(92))

    // Name row: person icon + first/last fields
    val nameRow = row()
    nameRow.addView(ui.icon(R.drawable.ic_person, R.color.sg_text_dim), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { topMargin = ui.dp(14) })
    val nameCol = column()
    val firstField = input("First name", App.editDraftFirst, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, 1) {
        App.editDraftFirst = it
    }
    firstField.background = null
    nameCol.addView(firstField)
    val firstDivider = View(this)
    firstDivider.setBackgroundColor(ui.color(R.color.sg_outline))
    nameCol.add(firstDivider, top = 2, height = 1)
    val lastField = input("Last name", App.editDraftLast, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, 1) {
        App.editDraftLast = it
    }
    lastField.background = null
    nameCol.add(lastField, top = 10)
    val lastDivider = View(this)
    lastDivider.setBackgroundColor(ui.color(R.color.sg_outline))
    nameCol.add(lastDivider, top = 2, height = 1)
    nameRow.addView(nameCol, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(16) })
    content.add(nameRow, top = 24)

    // Phone row: phone icon + number field + type chips
    val phoneRow = row()
    phoneRow.addView(ui.icon(R.drawable.ic_call, R.color.sg_text_dim), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { topMargin = ui.dp(14) })
    val phoneCol = column()
    val numberField = input("Phone number", App.editDraftNumber, InputType.TYPE_CLASS_PHONE, 1) {
        App.editDraftNumber = it
    }
    numberField.background = null
    phoneCol.addView(numberField)
    val phoneDivider = View(this)
    phoneDivider.setBackgroundColor(ui.color(R.color.sg_outline))
    phoneCol.add(phoneDivider, top = 2, height = 1)

    val chips = row()
    val types = listOf(
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE to "Mobile",
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME to "Home",
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK to "Work"
    )
    for ((typeVal, label) in types) {
        val active = App.editDraftType == typeVal
        val chip = ui.text(label, 12f, if (active) R.color.sg_on_primary else R.color.sg_text_dim, bold = active, center = true)
        chip.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
        chip.background = if (active) ui.rounded(ui.color(R.color.sg_primary), 12) else ui.rounded(ui.color(R.color.sg_surface_alt), 12)
        chip.isClickable = true
        chip.setOnClickListener {
            App.editDraftType = typeVal
            render()
        }
        chips.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ui.dp(8) })
    }
    phoneCol.add(chips, top = 10)
    phoneRow.addView(phoneCol, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(16) })
    content.add(phoneRow, top = 20)
}

private fun MainActivity.pickContactPhoto() {
    val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(intent, MainActivity.REQ_PICK_PHOTO)
    } catch (e: Exception) {
        App.showNotice("No photo picker app found on this phone.")
    }
}

private fun MainActivity.saveContactDraft(editing: Contact?) {
    val first = App.editDraftFirst.trim()
    val last = App.editDraftLast.trim()
    val number = App.editDraftNumber.trim()
    if ((first.isEmpty() && last.isEmpty()) || Proto.cleanNumber(number) == null) {
        App.showNotice("Enter a name and a valid number.")
        return
    }
    val photoBytes = App.editingPhotoUri?.let { ContactsStore.readPhotoBytes(this, it) }
    val ok = if (editing != null) {
        ContactsStore.updateContact(this, editing.contactId, first, last, number, App.editDraftType, photoBytes)
    } else {
        ContactsStore.addContact(this, first, last, number, App.editDraftType, photoBytes)
    }
    if (ok) {
        loadContacts()
        App.editingContact = null
        App.editingPhotoUri = null
        go(App.Screen.CLIENT)
    } else {
        App.showNotice("Couldn't save that contact.")
    }
}
