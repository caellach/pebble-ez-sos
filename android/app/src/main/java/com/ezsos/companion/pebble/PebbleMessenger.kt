package com.ezsos.companion.pebble

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ezsos.companion.EzSosApp
import com.ezsos.companion.MessageKeys
import com.ezsos.companion.WatchUuid
import com.ezsos.companion.settings.SosSettings
import com.ezsos.companion.util.EventLog
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PebbleMessenger {
    private const val TAG = "PebbleTx"
    private const val CHUNK_SIZE = 800
    private const val CHUNK_GAP_MS = 80L
    /** Pause before re-sending INBOUND_ALERT so a cold-launched watchapp can register AppMessage. */
    private const val INBOUND_ALERT_RETRY_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun sendStatus(context: Context, code: String) {
        sendMap(context, mapOf(MessageKeys.STATUS.toUInt() to PebbleDictionaryItem.Text(code)), "STATUS=$code")
    }

    fun sendSettingsRequest(context: Context) {
        sendMap(
            context,
            mapOf(MessageKeys.SETTINGS_REQUEST.toUInt() to PebbleDictionaryItem.UInt8(1u)),
            "SETTINGS_REQUEST"
        )
    }

    fun sendCompanionPresent(context: Context) {
        sendMap(
            context,
            mapOf(MessageKeys.COMPANION_PRESENT.toUInt() to PebbleDictionaryItem.UInt8(1u)),
            "COMPANION_PRESENT"
        )
    }

    /**
     * Push canonical settings to the watch (TRIGGER_MODE on first chunk + SETTINGS_CHUNK_*).
     */
    fun pushSettings(context: Context, settings: SosSettings) {
        val appCtx = context.applicationContext
        val json = settings.toJsonString()
        val chunks = splitChunks(json, CHUNK_SIZE)
        val count = chunks.size
        val main = Handler(Looper.getMainLooper())
        EventLog.i(TAG, "Push settings ($count chunk(s), ${json.length} chars)")

        fun sendIndex(index: Int) {
            if (index >= count) {
                EventLog.i(TAG, "Settings push complete")
                return
            }
            val map = linkedMapOf(
                MessageKeys.SETTINGS_CHUNK_INDEX.toUInt() to PebbleDictionaryItem.Int32(index),
                MessageKeys.SETTINGS_CHUNK_COUNT.toUInt() to PebbleDictionaryItem.Int32(count),
                MessageKeys.SETTINGS_CHUNK_DATA.toUInt() to PebbleDictionaryItem.Text(chunks[index]),
            )
            if (index == 0) {
                map[MessageKeys.TRIGGER_MODE.toUInt()] = PebbleDictionaryItem.Text(settings.triggerMode)
                map[MessageKeys.HOLD_MS.toUInt()] =
                    PebbleDictionaryItem.Int32(SosSettings.normalizeHoldMs(settings.holdMs))
                map[MessageKeys.WATCH_ALARM_SOUND.toUInt()] =
                    PebbleDictionaryItem.UInt8(if (settings.watchAlarmSound) 1u else 0u)
            }
            sendMap(appCtx, map, "SETTINGS_CHUNK ${index + 1}/$count") {
                main.postDelayed({ sendIndex(index + 1) }, CHUNK_GAP_MS)
            }
        }

        sendIndex(0)
    }

    /**
     * Launch the watchapp and fire [MessageKeys.INBOUND_ALERT].
     *
     * Cold-start race: if the watchapp was not already open, the first AppMessage often
     * arrives before its inbox is registered, so only the launch succeeds. Sending again
     * after a short pause covers that case; a warm watch already in alarm ignores the
     * duplicate (alarm UI is idempotent).
     */
    fun sendInboundAlert(context: Context): Boolean {
        val appCtx = context.applicationContext
        scope.launch {
            startApp(appCtx)
            val payload =
                mapOf(MessageKeys.INBOUND_ALERT.toUInt() to PebbleDictionaryItem.UInt8(1u))
            sendMapAwait(appCtx, payload, "INBOUND_ALERT")
            delay(INBOUND_ALERT_RETRY_MS)
            sendMapAwait(appCtx, payload, "INBOUND_ALERT retry")
        }
        return true
    }

    /**
     * Launch the watchapp and fire [MessageKeys.SELF_LOCATE_ALERT] (outbound self-locate).
     * Same cold-start retry as inbound.
     */
    fun sendSelfLocateAlert(context: Context): Boolean {
        val appCtx = context.applicationContext
        scope.launch {
            startApp(appCtx)
            val payload =
                mapOf(MessageKeys.SELF_LOCATE_ALERT.toUInt() to PebbleDictionaryItem.UInt8(1u))
            sendMapAwait(appCtx, payload, "SELF_LOCATE_ALERT")
            delay(INBOUND_ALERT_RETRY_MS)
            sendMapAwait(appCtx, payload, "SELF_LOCATE_ALERT retry")
        }
        return true
    }

    /**
     * Classic PebbleKit connection query. Unreliable with Core Devices' phone app.
     */
    fun isWatchConnected(context: Context): Boolean {
        return try {
            PebbleKit.isWatchConnected(context.applicationContext)
        } catch (_: Exception) {
            false
        }
    }

    private fun sendMap(
        context: Context,
        data: Map<UInt, PebbleDictionaryItem>,
        label: String,
        onComplete: (() -> Unit)? = null,
    ) {
        scope.launch {
            try {
                sendMapAwait(context, data, label)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private suspend fun sendMapAwait(
        context: Context,
        data: Map<UInt, PebbleDictionaryItem>,
        label: String,
    ) {
        val appCtx = context.applicationContext
        val kit2Result = tryKit2Send(appCtx, data)
        if (kit2Result != null) {
            EventLog.i(TAG, "Sent $label via kit2 → $kit2Result")
            if (kit2Result is TransmissionResult.Success) return
            // Fall through to classic on recoverable failures (e.g. legacy phone app).
            if (kit2Result is TransmissionResult.FailedNoPermissions ||
                kit2Result is TransmissionResult.FailedDifferentAppOpen
            ) {
                // Still try classic; Core with companionApp declared may reject classic.
            }
        }

        try {
            val dict = PebbleDictionary()
            for ((key, item) in data) {
                val k = key.toInt()
                when (item) {
                    is PebbleDictionaryItem.Text -> dict.addString(k, item.value)
                    is PebbleDictionaryItem.UInt8 -> dict.addUint8(k, item.value.toByte())
                    is PebbleDictionaryItem.Int8 -> dict.addInt8(k, item.value)
                    is PebbleDictionaryItem.Int32 -> dict.addInt32(k, item.value)
                    is PebbleDictionaryItem.UInt32 -> dict.addUint32(k, item.value.toInt())
                    is PebbleDictionaryItem.Int16 -> dict.addInt16(k, item.value)
                    is PebbleDictionaryItem.UInt16 -> dict.addUint16(k, item.value.toShort())
                    is PebbleDictionaryItem.Bytes -> dict.addBytes(k, item.value)
                }
            }
            PebbleKit.sendDataToPebble(appCtx, WatchUuid.value, dict)
            EventLog.i(TAG, "Sent $label via classic")
        } catch (e: Exception) {
            EventLog.e(TAG, "Failed to send $label", e)
        }
    }

    private suspend fun tryKit2Send(
        context: Context,
        data: Map<UInt, PebbleDictionaryItem>,
    ): TransmissionResult? {
        val sender = (context.applicationContext as? EzSosApp)?.pebbleSender ?: return null
        return try {
            val results = withContext(Dispatchers.IO) {
                sender.sendDataToPebble(WatchUuid.value, data)
            }
            if (results.isNullOrEmpty()) {
                EventLog.w(TAG, "Kit2 send returned empty results")
                null
            } else {
                // Prefer Success if any watch accepted; otherwise first failure.
                results.values.firstOrNull { it is TransmissionResult.Success }
                    ?: results.values.first()
            }
        } catch (e: Exception) {
            EventLog.e(TAG, "Kit2 send threw", e)
            null
        }
    }

    private suspend fun startApp(context: Context) {
        val sender = (context.applicationContext as? EzSosApp)?.pebbleSender
        if (sender != null) {
            try {
                val results = withContext(Dispatchers.IO) {
                    sender.startAppOnTheWatch(WatchUuid.value)
                }
                EventLog.i(TAG, "startAppOnTheWatch via kit2 → $results")
                return
            } catch (e: Exception) {
                EventLog.w(TAG, "Kit2 startApp failed: ${e.message}")
            }
        }
        try {
            PebbleKit.startAppOnPebble(context, WatchUuid.value)
            EventLog.i(TAG, "startAppOnPebble via classic")
        } catch (e: Exception) {
            EventLog.e(TAG, "classic startApp failed", e)
        }
    }

    private fun splitChunks(text: String, size: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val end = minOf(i + size, text.length)
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }
}
