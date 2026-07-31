package com.ezsos.companion.pebble

import android.content.Context
import android.content.Intent
import android.os.Build
import com.ezsos.companion.WatchUuid
import com.ezsos.companion.util.EventLog
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import java.util.UUID

/**
 * Classic PebbleKit AppMessage receive — matches the Rebble / PebbleKit Android docs:
 * https://developer.rebble.io/guides/communication/using-pebblekit-android/
 *
 * Registered dynamically via [ClassicPebbleRegistration] and also declared in the manifest
 * for cold-start. Core Devices emits `com.getpebble.action.app.RECEIVE` only when the
 * watchapp does not declare `companionApp` (Kit2 bind path otherwise).
 */
class EzSosPebbleReceiver : PebbleKit.PebbleDataReceiver(WatchUuid.value) {
    override fun onReceive(context: Context, intent: Intent) {
        EventLog.i(TAG, "Classic broadcast ${intent.action} uuidExtra=${uuidExtra(intent)}")
        if (!PebbleSenderGuard.isTrustedSender(this, context, intent)) {
            EventLog.w(TAG, "Dropping classic AppMessage: sender not allowlisted")
            return
        }
        super.onReceive(context, intent)
    }

    override fun receiveData(context: Context, transactionId: Int, data: PebbleDictionary) {
        // Docs: companions must ACK or the watch times out.
        PebbleKit.sendAckToPebble(context, transactionId)
        EventLog.i(TAG, "Classic ACK txn=$transactionId keys=${data.size()}")

        IncomingAppMessages.handle(
            context = context,
            source = "classic",
            has = { key -> data.contains(key) },
            string = { key -> data.getString(key) },
            int = { key ->
                data.getInteger(key)?.toInt()
                    ?: data.getUnsignedIntegerAsLong(key)?.toInt()
            },
        )
    }

    private fun uuidExtra(intent: Intent): UUID? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("uuid", UUID::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("uuid") as? UUID
        }
    }

    companion object {
        private const val TAG = "ClassicRx"
    }
}
