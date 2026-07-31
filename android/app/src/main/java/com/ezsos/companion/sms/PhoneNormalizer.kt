package com.ezsos.companion.sms

/**
 * Normalize phone numbers to digits; keep leading country code when present.
 * Leading '+' is dropped but country-code digits remain.
 */
object PhoneNormalizer {
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val digits = buildString {
            for (ch in trimmed) {
                if (ch.isDigit()) append(ch)
            }
        }
        return digits
    }

    /**
     * Match sender against an enabled contact phone.
     * Compares full digit strings, or suffix match when one number is longer
     * (handles missing country code on either side) with a minimum of 7 digits.
     */
    fun matches(senderRaw: String?, contactRaw: String?): Boolean {
        val sender = normalize(senderRaw)
        val contact = normalize(contactRaw)
        if (sender.length < 7 || contact.length < 7) {
            return sender.isNotEmpty() && sender == contact
        }
        if (sender == contact) return true
        return sender.endsWith(contact) || contact.endsWith(sender)
    }
}
