package com.ezsos.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ezsos.companion.pebble.PebbleMessenger

/** After boot, announce companion and request settings from the watch if needed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appCtx = context.applicationContext
        Log.i(TAG, "Boot completed — COMPANION_PRESENT")
        PebbleMessenger.sendCompanionPresent(appCtx)
        PebbleMessenger.sendSettingsRequest(appCtx)
    }

    companion object {
        private const val TAG = "EzSosBoot"
    }
}
