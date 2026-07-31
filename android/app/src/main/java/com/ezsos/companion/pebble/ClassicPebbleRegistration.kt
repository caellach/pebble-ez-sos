package com.ezsos.companion.pebble

import android.content.Context
import com.ezsos.companion.util.EventLog
import com.getpebble.android.kit.PebbleKit

/**
 * Registers classic PebbleKit receive handlers the way the official docs describe:
 * [PebbleKit.registerReceivedDataHandler] — see
 * https://developer.rebble.io/guides/communication/using-pebblekit-android/
 *
 * Core Devices only emits `com.getpebble.action.app.RECEIVE` when the PBW does **not**
 * declare `companionApp` (otherwise it uses Kit2 bind only). Manifest registration alone
 * is kept for cold-start; this dynamic registration matches the documented path.
 */
object ClassicPebbleRegistration {
    private const val TAG = "ClassicReg"

    @Volatile
    private var receiver: EzSosPebbleReceiver? = null

    @Synchronized
    fun ensureRegistered(context: Context) {
        if (receiver != null) return
        val appCtx = context.applicationContext
        val next = EzSosPebbleReceiver()
        PebbleKit.registerReceivedDataHandler(appCtx, next)
        receiver = next
        EventLog.i(TAG, "registerReceivedDataHandler installed (classic RECEIVE)")
    }
}
