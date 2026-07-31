package com.ezsos.companion.settings

import org.json.JSONArray
import org.json.JSONObject

data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    val enabled: Boolean
)

/**
 * [phoneAlertMode]: off | notification | fullscreen — phone alarm when a contact sends SOS.
 * Watch ignores unknown JSON fields when storing settings blobs.
 */
data class SosSettings(
    val triggerMode: String = "confirm",
    val holdMs: Int = DEFAULT_HOLD_MS,
    val watchAlarmSound: Boolean = true,
    val selfLocateAlarm: Boolean = true,
    val messagePrefix: String = DEFAULT_MESSAGE_PREFIX,
    val phoneAlertMode: String = MODE_NOTIFICATION,
    val authWindowMinutes: Int = DEFAULT_AUTH_WINDOW_MINUTES,
    val contacts: List<Contact> = emptyList()
) {
    fun enabledContacts(): List<Contact> =
        contacts.filter { it.enabled && it.phone.isNotBlank() }

    fun toJsonString(): String {
        val root = JSONObject()
        root.put("triggerMode", triggerMode)
        root.put("holdMs", normalizeHoldMs(holdMs))
        root.put("watchAlarmSound", watchAlarmSound)
        root.put("selfLocateAlarm", selfLocateAlarm)
        root.put("messagePrefix", normalizeMessagePrefix(messagePrefix))
        root.put("phoneAlertMode", phoneAlertMode)
        root.put("authWindowMinutes", normalizeAuthWindowMinutes(authWindowMinutes))
        val arr = JSONArray()
        for (c in contacts) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("phone", c.phone)
                    .put("enabled", c.enabled)
            )
        }
        root.put("contacts", arr)
        return root.toString()
    }

    companion object {
        const val MODE_OFF = "off"
        const val MODE_NOTIFICATION = "notification"
        const val MODE_FULLSCREEN = "fullscreen"

        const val DEFAULT_HOLD_MS = 1500
        val HOLD_MS_PRESETS = listOf(1000, 1500, 2000, 3000)

        const val DEFAULT_AUTH_WINDOW_MINUTES = 15
        val AUTH_WINDOW_PRESETS = listOf(5, 15, 30, 60)

        /** Required start of every SOS SMS body. */
        const val REQUIRED_PREFIX = "EZ SOS: "
        const val DEFAULT_MESSAGE_BODY = "I need help."
        const val DEFAULT_MESSAGE_PREFIX = REQUIRED_PREFIX + DEFAULT_MESSAGE_BODY

        fun normalizeHoldMs(raw: Int?): Int {
            val value = raw ?: DEFAULT_HOLD_MS
            if (value in HOLD_MS_PRESETS) return value
            // Snap to nearest preset when migrating odd values.
            return HOLD_MS_PRESETS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_HOLD_MS
        }

        fun normalizeAuthWindowMinutes(raw: Int?): Int {
            val value = raw ?: DEFAULT_AUTH_WINDOW_MINUTES
            if (value in AUTH_WINDOW_PRESETS) return value
            return AUTH_WINDOW_PRESETS.minByOrNull { kotlin.math.abs(it - value) }
                ?: DEFAULT_AUTH_WINDOW_MINUTES
        }

        fun normalizePhoneAlertMode(raw: String?): String {
            return when (raw) {
                MODE_OFF, MODE_NOTIFICATION, MODE_FULLSCREEN -> raw
                else -> MODE_NOTIFICATION
            }
        }

        /** Ensure the stored/sent prefix always begins with [REQUIRED_PREFIX]. */
        fun normalizeMessagePrefix(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            val body = when {
                trimmed.startsWith("EZ SOS:", ignoreCase = true) ->
                    trimmed.substringAfter(':').trimStart()
                else -> trimmed
            }.ifBlank { DEFAULT_MESSAGE_BODY }
            return REQUIRED_PREFIX + body
        }

        /** Editable portion after the fixed "EZ SOS: " label. */
        fun messageBodyForEdit(raw: String?): String {
            return normalizeMessagePrefix(raw).removePrefix(REQUIRED_PREFIX)
        }

        /**
         * Inbound SMS must look like an EZ SOS outbound message (fixed prefix),
         * not any text from an enabled contact.
         */
        fun isInboundSosBody(raw: String?): Boolean {
            val trimmed = raw?.trim().orEmpty()
            return trimmed.startsWith("EZ SOS:")
        }

        fun fromJson(raw: String?): SosSettings? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                val mode = obj.optString("triggerMode", "confirm").ifBlank { "confirm" }
                val holdMs = normalizeHoldMs(
                    if (obj.has("holdMs")) obj.optInt("holdMs", DEFAULT_HOLD_MS) else DEFAULT_HOLD_MS
                )
                val watchAlarmSound = if (obj.has("watchAlarmSound")) obj.optBoolean("watchAlarmSound", true) else true
                val selfLocateAlarm = if (obj.has("selfLocateAlarm")) obj.optBoolean("selfLocateAlarm", true) else true
                val prefix = normalizeMessagePrefix(
                    obj.optString("messagePrefix", DEFAULT_MESSAGE_PREFIX)
                )
                val phoneAlert = normalizePhoneAlertMode(obj.optString("phoneAlertMode", MODE_NOTIFICATION))
                val authWindowMinutes = normalizeAuthWindowMinutes(
                    if (obj.has("authWindowMinutes")) obj.optInt("authWindowMinutes", DEFAULT_AUTH_WINDOW_MINUTES)
                    else DEFAULT_AUTH_WINDOW_MINUTES
                )
                val contactsJson = obj.optJSONArray("contacts") ?: JSONArray()
                val contacts = mutableListOf<Contact>()
                for (i in 0 until contactsJson.length()) {
                    val c = contactsJson.optJSONObject(i) ?: continue
                    contacts.add(
                        Contact(
                            id = c.optString("id", "local-$i"),
                            name = c.optString("name", ""),
                            phone = c.optString("phone", ""),
                            enabled = c.optBoolean("enabled", true)
                        )
                    )
                }
                SosSettings(
                    triggerMode = when (mode) {
                        "single", "confirm", "hold" -> mode
                        else -> "confirm"
                    },
                    holdMs = holdMs,
                    watchAlarmSound = watchAlarmSound,
                    selfLocateAlarm = selfLocateAlarm,
                    messagePrefix = prefix,
                    phoneAlertMode = phoneAlert,
                    authWindowMinutes = authWindowMinutes,
                    contacts = contacts
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
