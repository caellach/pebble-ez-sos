package com.ezsos.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ezsos.companion.EzSosApp
import com.ezsos.companion.R
import com.ezsos.companion.pebble.PebbleMessenger
import com.ezsos.companion.settings.Contact
import com.ezsos.companion.settings.SosSettings
import com.ezsos.companion.sms.PhoneNormalizer
import com.google.android.material.appbar.MaterialToolbar
import java.util.UUID

/**
 * Source-of-truth editor for contacts, message prefix, trigger mode, and phone alert.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var contactsContainer: LinearLayout
    private lateinit var messagePrefix: EditText
    private lateinit var triggerModeGroup: RadioGroup
    private lateinit var holdDurationGroup: RadioGroup
    private lateinit var watchAlarmSound: CheckBox
    private lateinit var selfLocateAlarm: CheckBox
    private lateinit var phoneAlertGroup: RadioGroup
    private lateinit var authWindowGroup: RadioGroup

    private data class ContactRow(
        val id: String,
        val root: android.view.View,
        val name: EditText,
        val phone: EditText,
        val enabled: CheckBox
    )

    private val rows = mutableListOf<ContactRow>()

    private val readContactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showDedupedContactPicker()
        else Toast.makeText(this, R.string.contacts_permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        contactsContainer = findViewById(R.id.contactsContainer)
        messagePrefix = findViewById(R.id.messagePrefix)
        triggerModeGroup = findViewById(R.id.triggerModeGroup)
        holdDurationGroup = findViewById(R.id.holdDurationGroup)
        watchAlarmSound = findViewById(R.id.watchAlarmSound)
        selfLocateAlarm = findViewById(R.id.selfLocateAlarm)
        phoneAlertGroup = findViewById(R.id.phoneAlertGroup)
        authWindowGroup = findViewById(R.id.authWindowGroup)

        findViewById<Button>(R.id.btnAddContact).setOnClickListener {
            addRow(Contact(id = UUID.randomUUID().toString(), name = "", phone = "", enabled = true))
        }
        findViewById<Button>(R.id.btnPickContact).setOnClickListener {
            ensureContactsPermissionAndPick()
        }
        findViewById<Button>(R.id.btnResetMessagePrefix).setOnClickListener {
            messagePrefix.setText(SosSettings.DEFAULT_MESSAGE_BODY)
        }
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            saveAndPush()
        }

        loadFromCache()
    }

    private fun loadFromCache() {
        val settings = (application as EzSosApp).settingsCache.getSettings()
            ?: SosSettings()
        messagePrefix.setText(SosSettings.messageBodyForEdit(settings.messagePrefix))
        when (settings.triggerMode) {
            "single" -> findViewById<RadioButton>(R.id.modeSingle).isChecked = true
            "hold" -> findViewById<RadioButton>(R.id.modeHold).isChecked = true
            else -> findViewById<RadioButton>(R.id.modeConfirm).isChecked = true
        }
        when (SosSettings.normalizeHoldMs(settings.holdMs)) {
            1000 -> findViewById<RadioButton>(R.id.hold1s).isChecked = true
            2000 -> findViewById<RadioButton>(R.id.hold2s).isChecked = true
            3000 -> findViewById<RadioButton>(R.id.hold3s).isChecked = true
            else -> findViewById<RadioButton>(R.id.hold15s).isChecked = true
        }
        watchAlarmSound.isChecked = settings.watchAlarmSound
        selfLocateAlarm.isChecked = settings.selfLocateAlarm
        when (settings.phoneAlertMode) {
            SosSettings.MODE_OFF -> findViewById<RadioButton>(R.id.phoneAlertOff).isChecked = true
            SosSettings.MODE_FULLSCREEN -> findViewById<RadioButton>(R.id.phoneAlertFullscreen).isChecked = true
            else -> findViewById<RadioButton>(R.id.phoneAlertNotification).isChecked = true
        }
        when (SosSettings.normalizeAuthWindowMinutes(settings.authWindowMinutes)) {
            5 -> findViewById<RadioButton>(R.id.authWindow5m).isChecked = true
            30 -> findViewById<RadioButton>(R.id.authWindow30m).isChecked = true
            60 -> findViewById<RadioButton>(R.id.authWindow60m).isChecked = true
            else -> findViewById<RadioButton>(R.id.authWindow15m).isChecked = true
        }
        contactsContainer.removeAllViews()
        rows.clear()
        if (settings.contacts.isEmpty()) {
            addRow(Contact(id = UUID.randomUUID().toString(), name = "", phone = "", enabled = true))
        } else {
            settings.contacts.forEach { addRow(it) }
        }
    }

    private fun addRow(contact: Contact) {
        val root = LayoutInflater.from(this).inflate(R.layout.item_contact, contactsContainer, false)
        val name = root.findViewById<EditText>(R.id.contactName)
        val phone = root.findViewById<EditText>(R.id.contactPhone)
        val enabled = root.findViewById<CheckBox>(R.id.contactEnabled)
        name.setText(contact.name)
        phone.setText(contact.phone)
        enabled.isChecked = contact.enabled
        val row = ContactRow(contact.id, root, name, phone, enabled)
        root.findViewById<Button>(R.id.btnRemoveContact).setOnClickListener {
            contactsContainer.removeView(root)
            rows.remove(row)
        }
        rows.add(row)
        contactsContainer.addView(root)
    }

    private fun collectSettings(): SosSettings {
        val mode = when (triggerModeGroup.checkedRadioButtonId) {
            R.id.modeSingle -> "single"
            R.id.modeHold -> "hold"
            else -> "confirm"
        }
        val holdMs = when (holdDurationGroup.checkedRadioButtonId) {
            R.id.hold1s -> 1000
            R.id.hold2s -> 2000
            R.id.hold3s -> 3000
            else -> 1500
        }
        val phoneAlert = when (phoneAlertGroup.checkedRadioButtonId) {
            R.id.phoneAlertOff -> SosSettings.MODE_OFF
            R.id.phoneAlertFullscreen -> SosSettings.MODE_FULLSCREEN
            else -> SosSettings.MODE_NOTIFICATION
        }
        val authWindowMinutes = when (authWindowGroup.checkedRadioButtonId) {
            R.id.authWindow5m -> 5
            R.id.authWindow30m -> 30
            R.id.authWindow60m -> 60
            else -> 15
        }
        val contacts = rows.map { row ->
            Contact(
                id = row.id,
                name = row.name.text?.toString().orEmpty().trim(),
                phone = row.phone.text?.toString().orEmpty().trim(),
                enabled = row.enabled.isChecked
            )
        }.filter { it.phone.isNotBlank() || it.name.isNotBlank() }

        val prefix = SosSettings.normalizeMessagePrefix(
            SosSettings.REQUIRED_PREFIX + (messagePrefix.text?.toString().orEmpty())
        )

        return SosSettings(
            triggerMode = mode,
            holdMs = holdMs,
            watchAlarmSound = watchAlarmSound.isChecked,
            selfLocateAlarm = selfLocateAlarm.isChecked,
            messagePrefix = prefix,
            phoneAlertMode = phoneAlert,
            authWindowMinutes = authWindowMinutes,
            contacts = contacts
        )
    }

    private fun saveAndPush() {
        val settings = collectSettings()
        (application as EzSosApp).settingsCache.saveSettings(settings)
        PebbleMessenger.pushSettings(this, settings)
        PebbleMessenger.sendCompanionPresent(this)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun ensureContactsPermissionAndPick() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) showDedupedContactPicker()
        else readContactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    /**
     * System ACTION_PICK often lists the same person 2–3× (Google + WhatsApp + SIM raw
     * contacts) even when they share one phone number. Build our own list keyed by
     * normalized digits so each number appears once.
     */
    private fun showDedupedContactPicker() {
        val people = loadDedupedPhoneContacts()
        if (people.isEmpty()) {
            Toast.makeText(this, R.string.contacts_none_found, Toast.LENGTH_SHORT).show()
            return
        }
        val nameCounts = people.groupingBy { it.name.lowercase() }.eachCount()
        val labels = people.map { entry ->
            if ((nameCounts[entry.name.lowercase()] ?: 0) > 1) {
                "${entry.name} — ${entry.phone}"
            } else {
                entry.name
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.contacts_pick_title)
            .setItems(labels) { _, which ->
                val entry = people[which]
                addPickedContact(entry.name, entry.phone)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private data class DedupedContact(val name: String, val phone: String)

    private fun loadDedupedPhoneContacts(): List<DedupedContact> {
        val byPhone = linkedMapOf<String, DedupedContact>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val number = if (numberIdx >= 0) cursor.getString(numberIdx).orEmpty().trim() else ""
                if (number.isBlank()) continue
                val key = PhoneNormalizer.normalize(number)
                if (key.length < 7) continue
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                val existing = byPhone[key]
                if (existing == null) {
                    byPhone[key] = DedupedContact(
                        name = name.ifBlank { number },
                        phone = number
                    )
                } else if (existing.name == existing.phone && name.isNotBlank()) {
                    // Prefer a real display name over a bare number.
                    byPhone[key] = DedupedContact(name = name, phone = existing.phone)
                }
            }
        }
        return byPhone.values.sortedBy { it.name.lowercase() }
    }

    private fun addPickedContact(name: String, phone: String) {
        val normalized = PhoneNormalizer.normalize(phone)
        if (normalized.isNotEmpty()) {
            val exists = rows.any { PhoneNormalizer.matches(it.phone.text?.toString(), phone) }
            if (exists) {
                Toast.makeText(this, R.string.contacts_already_added, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val empty = rows.firstOrNull {
            it.name.text.isNullOrBlank() && it.phone.text.isNullOrBlank()
        }
        if (empty != null) {
            empty.name.setText(name)
            empty.phone.setText(phone)
            empty.enabled.isChecked = true
        } else {
            addRow(
                Contact(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    phone = phone,
                    enabled = true
                )
            )
        }
    }
}
