package com.ezsos.companion.settings

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Caches settings JSON relayed from the watch (SETTINGS_JSON / SETTINGS_CHUNK_*).
 */
class SettingsCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val chunkLock = Any()
    private var assembling: Array<String?>? = null
    private var expectedCount: Int = 0

    fun getRawJson(): String? = prefs.getString(KEY_JSON, null)

    fun getSettings(): SosSettings? = SosSettings.fromJson(getRawJson())

    fun isEmpty(): Boolean = getRawJson().isNullOrBlank()

    fun hasEnabledContacts(): Boolean =
        getSettings()?.enabledContacts()?.isNotEmpty() == true

    fun saveJson(json: String) {
        val parsed = SosSettings.fromJson(json) ?: run {
            Log.w(TAG, "Ignoring invalid settings JSON")
            return
        }
        saveSettings(parsed)
    }

    fun saveSettings(settings: SosSettings) {
        prefs.edit().putString(KEY_JSON, settings.toJsonString()).apply()
        synchronized(chunkLock) {
            assembling = null
            expectedCount = 0
        }
        Log.i(TAG, "Settings cached (${settings.contacts.size} contacts)")
        listeners.forEach { it.invoke() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun handleChunk(index: Int, count: Int, data: String) {
        if (count <= 0 || index < 0 || index >= count) {
            Log.w(TAG, "Invalid chunk index=$index count=$count")
            return
        }
        val complete: String?
        synchronized(chunkLock) {
            if (assembling == null || expectedCount != count) {
                assembling = arrayOfNulls(count)
                expectedCount = count
            }
            assembling!![index] = data
            if (assembling!!.any { it == null }) {
                return
            }
            complete = assembling!!.joinToString(separator = "") { it ?: "" }
            assembling = null
            expectedCount = 0
        }
        if (complete != null) {
            saveJson(complete)
        }
    }

    companion object {
        private const val TAG = "EzSosSettings"
        private const val PREFS = "ez_sos_settings"
        private const val KEY_JSON = "settings_json"
    }
}
