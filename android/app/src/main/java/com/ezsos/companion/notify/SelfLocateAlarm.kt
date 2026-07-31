package com.ezsos.companion.notify

import android.content.Context
import com.ezsos.companion.EzSosApp
import com.ezsos.companion.pebble.PebbleMessenger
import com.ezsos.companion.util.EventLog

/**
 * After outbound SOS (`sent` / `check_phone`), optionally alarm this phone + watch
 * so the sender is easier to find.
 */
object SelfLocateAlarm {
    private const val TAG = "SelfLocate"

    fun maybeTrigger(context: Context, statusCode: String) {
        if (statusCode != "sent" && statusCode != "check_phone") {
            return
        }
        val settings = (context.applicationContext as? EzSosApp)?.settingsCache?.getSettings()
        if (settings?.selfLocateAlarm == false) {
            EventLog.i(TAG, "selfLocateAlarm off — skip after STATUS=$statusCode")
            return
        }
        EventLog.i(TAG, "Trigger after STATUS=$statusCode")
        trigger(context)
    }

    fun trigger(context: Context) {
        val appCtx = context.applicationContext
        PebbleMessenger.sendSelfLocateAlert(appCtx)
        AlertNotifier.alertSelfLocate(appCtx)
    }
}
