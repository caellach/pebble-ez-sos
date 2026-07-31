package com.ezsos.companion.pebble

import com.ezsos.companion.WatchUuid
import com.ezsos.companion.util.EventLog
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * PebbleKit Android 2 listener — Core binds this while EZ SOS is open on the watch.
 * Classic RECEIVE broadcasts often never arrive on modern Android; this is the supported path.
 */
class EzSosPebbleKit2Service : BasePebbleListenerService() {
    override fun onCreate() {
        super.onCreate()
        EventLog.i(TAG, "Kit2 service onCreate (Core bound or started)")
        coroutineScope.launch(Dispatchers.IO) {
            val picker = DefaultPebbleAndroidAppPicker.getInstance(this@EzSosPebbleKit2Service)
            val selected = picker.getCurrentlySelectedApp()
            val eligible = picker.getAllEligibleApps()
            EventLog.i(
                TAG,
                "Kit2 bind-time picker selected=${selected ?: "(none)"} eligible=[${eligible.joinToString()}]"
            )
        }
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        EventLog.i(TAG, "Kit2 onMessageReceived uuid=$watchappUUID watch=${watch.value} items=${data.size}")
        if (watchappUUID != WatchUuid.value) {
            EventLog.w(TAG, "Ignoring message for other UUID $watchappUUID")
            return ReceiveResult.Ack
        }

        IncomingAppMessages.handle(
            context = applicationContext,
            source = "kit2",
            has = { key -> data.containsKey(key.toUInt()) },
            string = { key -> (data[key.toUInt()] as? PebbleDictionaryItem.Text)?.value },
            int = { key ->
                when (val item = data[key.toUInt()]) {
                    is PebbleDictionaryItem.Int32 -> item.value
                    is PebbleDictionaryItem.UInt32 -> item.value.toInt()
                    is PebbleDictionaryItem.Int16 -> item.value.toInt()
                    is PebbleDictionaryItem.UInt16 -> item.value.toInt()
                    is PebbleDictionaryItem.Int8 -> item.value.toInt()
                    is PebbleDictionaryItem.UInt8 -> item.value.toInt()
                    else -> null
                }
            },
        )
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        EventLog.i(TAG, "Kit2 onAppOpened uuid=$watchappUUID watch=${watch.value}")
        if (watchappUUID == WatchUuid.value) {
            PebbleMessenger.sendCompanionPresent(applicationContext)
        }
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        EventLog.i(TAG, "Kit2 onAppClosed uuid=$watchappUUID watch=${watch.value}")
    }

    companion object {
        private const val TAG = "Kit2Rx"
    }
}
