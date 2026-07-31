package com.ezsos.companion.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.ezsos.companion.settings.Contact
import com.ezsos.companion.util.EventLog

object SmsSender {
    private const val TAG = "Sms"

    fun canSend(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context.applicationContext,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPhoneState(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context.applicationContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Send [body] to every contact. Returns true only if all sends succeed.
     */
    fun sendToAll(context: Context, contacts: List<Contact>, body: String): Boolean {
        if (!canSend(context) || contacts.isEmpty()) return false
        if (!hasPhoneState(context)) {
            // Many OEMs (esp. dual-SIM) call TelephonyManager.getGroupIdLevel1() inside
            // sendTextMessage; that requires READ_PHONE_STATE or we get SecurityException.
            EventLog.e(TAG, "READ_PHONE_STATE missing — SMS will fail on many devices")
        }
        val sms = smsManager(context)
        var allOk = true
        for (contact in contacts) {
            val phone = contact.phone.trim()
            if (phone.isEmpty()) {
                EventLog.w(TAG, "Empty phone for ${contact.name}")
                allOk = false
                continue
            }
            try {
                val parts = sms.divideMessage(body)
                if (parts.size == 1) {
                    sms.sendTextMessage(phone, null, body, null, null)
                } else {
                    sms.sendMultipartTextMessage(phone, null, parts, null, null)
                }
                EventLog.i(TAG, "Queued SMS to ${contact.name} (${parts.size} part(s))")
            } catch (e: SecurityException) {
                EventLog.e(TAG, "SMS SecurityException for ${contact.name} — grant Phone permission", e)
                allOk = false
            } catch (e: Exception) {
                EventLog.e(TAG, "SMS failed for ${contact.name}", e)
                allOk = false
            }
        }
        return allOk
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager {
        val appCtx = context.applicationContext
        val subId = try {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } catch (_: Exception) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }

        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    appCtx.getSystemService(SmsManager::class.java)
                        ?.createForSubscriptionId(subId)
                        ?: SmsManager.getSmsManagerForSubscriptionId(subId)
                } else {
                    SmsManager.getSmsManagerForSubscriptionId(subId)
                }
            } catch (e: Exception) {
                EventLog.w(TAG, "createForSubscriptionId($subId) failed: ${e.message}")
                SmsManager.getDefault()
            }
        }

        return SmsManager.getDefault()
    }
}
