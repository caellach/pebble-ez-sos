package com.ezsos.companion.pebble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.getpebble.android.kit.Constants

/** On Pebble connect, announce companion and optionally refresh from watch. */
class PebbleConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Constants.INTENT_PEBBLE_CONNECTED) return
        if (!PebbleSenderGuard.isTrustedSender(this, context, intent)) {
            Log.w(TAG, "Ignoring PEBBLE_CONNECTED from untrusted sender")
            return
        }
        val appCtx = context.applicationContext
        Log.i(TAG, "Pebble connected — COMPANION_PRESENT")
        PebbleMessenger.sendCompanionPresent(appCtx)
        PebbleMessenger.sendSettingsRequest(appCtx)
    }

    companion object {
        private const val TAG = "EzSosPebbleConn"
    }
}
