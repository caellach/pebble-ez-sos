package com.ezsos.companion.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ezsos.companion.EzSosApp
import com.ezsos.companion.notify.AlertNotifier
import com.ezsos.companion.pebble.PebbleMessenger
import com.ezsos.companion.settings.SosSettings
import com.ezsos.companion.sos.SosAuthToken
import com.ezsos.companion.util.EventLog

/**
 * Match SMS from enabled contacts whose body starts with "EZ SOS:" and includes a
 * valid fresh `ez.` auth token (peer SOS); alarm watch and phone.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val app = context.applicationContext as? EzSosApp ?: return
        val settings = app.settingsCache.getSettings() ?: return
        val enabled = settings.enabledContacts()
        if (enabled.isEmpty()) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Multipart: concatenate bodies; usually one sender per broadcast.
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        if (!SosSettings.isInboundSosBody(body)) {
            EventLog.i(TAG, "SMS ignored (body missing EZ SOS: prefix)")
            return
        }
        val window = SosSettings.normalizeAuthWindowMinutes(settings.authWindowMinutes)
        if (!SosAuthToken.validateBody(body, windowMinutes = window)) {
            EventLog.i(TAG, "SMS ignored (auth token missing/invalid/expired)")
            return
        }

        val senders = messages.mapNotNull { it.originatingAddress }.distinct()
        if (senders.isEmpty()) return

        var matchedName: String? = null
        for (sender in senders) {
            val match = enabled.firstOrNull { PhoneNormalizer.matches(sender, it.phone) }
            if (match != null) {
                matchedName = match.name.ifBlank { match.phone }
                break
            }
        }
        if (matchedName == null) {
            EventLog.i(TAG, "SMS ignored (EZ SOS body, but no enabled contact match)")
            return
        }

        EventLog.i(TAG, "Inbound SOS from contact: $matchedName")
        PebbleMessenger.sendInboundAlert(context.applicationContext)
        AlertNotifier.alertPhone(context.applicationContext, matchedName)
    }

    companion object {
        private const val TAG = "SmsRx"
    }
}
