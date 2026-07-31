package com.ezsos.companion.sos

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ezsos.companion.EzSosApp
import com.ezsos.companion.notify.SelfLocateAlarm
import com.ezsos.companion.pebble.PebbleMessenger
import com.ezsos.companion.settings.SettingsCache
import com.ezsos.companion.settings.SosSettings
import com.ezsos.companion.util.EventLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles SOS_REQUEST from the watch: refresh settings if needed, GPS, silent SMS, STATUS.
 */
object SosHandler {
    private const val TAG = "Sos"
    private const val SETTINGS_WAIT_MS = 2_000L

    private val inFlight = AtomicBoolean(false)

    fun handleSosRequest(context: Context) {
        val appCtx = context.applicationContext
        if (!inFlight.compareAndSet(false, true)) {
            EventLog.w(TAG, "SOS already in flight — re-sending accepted")
            PebbleMessenger.sendStatus(appCtx, "accepted")
            return
        }

        EventLog.i(TAG, "SOS start — sending STATUS accepted")
        PebbleMessenger.sendStatus(appCtx, "accepted")

        val cache = (appCtx as? EzSosApp)?.settingsCache
        if (cache == null) {
            EventLog.e(TAG, "No settings cache — STATUS check_phone")
            finishWithStatus(appCtx, "check_phone")
            return
        }

        ensureSettings(appCtx, cache) {
            runSos(appCtx, cache)
        }
    }

    private fun finishWithStatus(context: Context, code: String) {
        PebbleMessenger.sendStatus(context, code)
        SelfLocateAlarm.maybeTrigger(context, code)
        inFlight.set(false)
    }

    private fun ensureSettings(context: Context, cache: SettingsCache, ready: () -> Unit) {
        if (cache.hasEnabledContacts()) {
            val n = cache.getSettings()?.enabledContacts()?.size ?: 0
            EventLog.i(TAG, "Settings OK ($n enabled contacts)")
            ready()
            return
        }

        EventLog.w(TAG, "No enabled contacts in cache — SETTINGS_REQUEST, wait ${SETTINGS_WAIT_MS}ms")
        PebbleMessenger.sendSettingsRequest(context)

        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        lateinit var timeout: Runnable
        lateinit var listener: () -> Unit
        listener = {
            if (cache.hasEnabledContacts() && finished.compareAndSet(false, true)) {
                cache.removeListener(listener)
                main.removeCallbacks(timeout)
                EventLog.i(TAG, "Contacts arrived during wait")
                ready()
            }
        }
        timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                cache.removeListener(listener)
                EventLog.w(TAG, "Settings wait timed out")
                ready()
            }
        }
        cache.addListener(listener)
        main.postDelayed(timeout, SETTINGS_WAIT_MS)
    }

    private fun runSos(context: Context, cache: SettingsCache) {
        try {
            val settings = cache.getSettings()
            val contacts = settings?.enabledContacts().orEmpty()
            if (contacts.isEmpty()) {
                EventLog.w(TAG, "Still no contacts — STATUS no_contacts")
                finishWithStatus(context, "no_contacts")
                return
            }

            EventLog.i(TAG, "Requesting location for ${contacts.size} contact(s)")
            LocationHelper.getCurrentLocation(context) { loc ->
                try {
                    if (loc == null) {
                        EventLog.w(TAG, "No location — STATUS no_gps")
                        finishWithStatus(context, "no_gps")
                        return@getCurrentLocation
                    }
                    EventLog.i(TAG, "Location ok lat=${loc.lat} lon=${loc.lon}")
                    val prefix = SosSettings.normalizeMessagePrefix(settings?.messagePrefix)
                    val body = buildMessageBody(prefix, loc.lat, loc.lon)
                    if (!SmsSender.canSend(context)) {
                        EventLog.e(TAG, "SEND_SMS not granted — STATUS check_phone")
                        finishWithStatus(context, "check_phone")
                    } else if (!SmsSender.sendToAll(context, contacts, body)) {
                        EventLog.e(TAG, "SMS send failed — STATUS check_phone")
                        finishWithStatus(context, "check_phone")
                    } else {
                        EventLog.i(TAG, "SMS queued — STATUS sent")
                        finishWithStatus(context, "sent")
                    }
                } catch (e: Exception) {
                    EventLog.e(TAG, "SOS location callback failed", e)
                    finishWithStatus(context, "check_phone")
                }
            }
        } catch (e: Exception) {
            EventLog.e(TAG, "SOS failed", e)
            finishWithStatus(context, "check_phone")
        }
    }

    fun buildMessageBody(prefix: String, lat: Double, lon: Double): String {
        val latStr = lat.toString()
        val lonStr = lon.toString()
        val tokenLine = SosAuthToken.buildLine()
        return "$prefix\nLat: $latStr, Lon: $lonStr\nhttps://maps.google.com/?q=$latStr,$lonStr\n$tokenLine"
    }
}
