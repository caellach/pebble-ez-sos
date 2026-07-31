package com.ezsos.companion.pebble

import android.content.Context
import com.ezsos.companion.EzSosApp
import com.ezsos.companion.MessageKeys
import com.ezsos.companion.sos.SosHandler
import com.ezsos.companion.util.EventLog

/**
 * Shared inbound AppMessage dispatch for classic PebbleKit and PebbleKit 2.
 */
object IncomingAppMessages {
    private const val TAG = "AppMsgIn"

    fun handle(
        context: Context,
        source: String,
        has: (Int) -> Boolean,
        string: (Int) -> String?,
        int: (Int) -> Int?,
    ) {
        EventLog.i(TAG, "Inbound via $source keys=${describe(has)}")

        val app = context.applicationContext as? EzSosApp
        val cache = app?.settingsCache
        if (cache == null) {
            EventLog.e(TAG, "No EzSosApp/settingsCache")
            return
        }

        if (has(MessageKeys.SOS_REQUEST)) {
            EventLog.i(TAG, "SOS_REQUEST → SosHandler")
            SosHandler.handleSosRequest(context.applicationContext)
            return
        }

        if (has(MessageKeys.SETTINGS_JSON)) {
            val json = string(MessageKeys.SETTINGS_JSON)
            if (json != null) {
                EventLog.i(TAG, "SETTINGS_JSON (${json.length} chars)")
                cache.saveJson(json)
            }
            return
        }

        if (has(MessageKeys.SETTINGS_CHUNK_INDEX) &&
            has(MessageKeys.SETTINGS_CHUNK_COUNT) &&
            has(MessageKeys.SETTINGS_CHUNK_DATA)
        ) {
            val index = int(MessageKeys.SETTINGS_CHUNK_INDEX)
            val count = int(MessageKeys.SETTINGS_CHUNK_COUNT)
            val data = string(MessageKeys.SETTINGS_CHUNK_DATA)
            if (index != null && count != null && data != null) {
                EventLog.i(TAG, "SETTINGS_CHUNK ${index + 1}/$count (${data.length} chars)")
                cache.handleChunk(index, count, data)
            }
            return
        }

        EventLog.w(TAG, "No handled keys in message from $source")
    }

    private fun describe(has: (Int) -> Boolean): String {
        val keys = mutableListOf<String>()
        if (has(MessageKeys.SOS_REQUEST)) keys.add("SOS_REQUEST")
        if (has(MessageKeys.STATUS)) keys.add("STATUS")
        if (has(MessageKeys.TRIGGER_MODE)) keys.add("TRIGGER_MODE")
        if (has(MessageKeys.SETTINGS_JSON)) keys.add("SETTINGS_JSON")
        if (has(MessageKeys.SETTINGS_REQUEST)) keys.add("SETTINGS_REQUEST")
        if (has(MessageKeys.INBOUND_ALERT)) keys.add("INBOUND_ALERT")
        if (has(MessageKeys.SETTINGS_CHUNK_INDEX)) keys.add("CHUNK_INDEX")
        if (has(MessageKeys.SETTINGS_CHUNK_DATA)) keys.add("CHUNK_DATA")
        if (has(MessageKeys.SETTINGS_CHUNK_COUNT)) keys.add("CHUNK_COUNT")
        if (has(MessageKeys.COMPANION_PRESENT)) keys.add("COMPANION_PRESENT")
        if (has(MessageKeys.HOLD_MS)) keys.add("HOLD_MS")
        if (has(MessageKeys.WATCH_ALARM_SOUND)) keys.add("WATCH_ALARM_SOUND")
        if (has(MessageKeys.SELF_LOCATE_ALERT)) keys.add("SELF_LOCATE_ALERT")
        return if (keys.isEmpty()) "(none)" else keys.joinToString(",")
    }
}
