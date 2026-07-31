package com.ezsos.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ezsos.companion.notify.AlertNotifier
import com.ezsos.companion.pebble.ClassicPebbleRegistration
import com.ezsos.companion.settings.SettingsCache
import com.ezsos.companion.util.EventLog
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.client.DefaultPebbleSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EzSosApp : Application() {
    lateinit var settingsCache: SettingsCache
        private set

    lateinit var pebbleSender: DefaultPebbleSender
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsCache = SettingsCache(this)
        pebbleSender = DefaultPebbleSender(this)
        EventLog.i(TAG, "EzSosApp onCreate")
        createNotificationChannel()
        // Docs path: dynamic PebbleDataReceiver (also kept in the manifest for cold start).
        ClassicPebbleRegistration.ensureRegistered(this)
        logKit2PickerState()
    }

    /**
     * Kit2 outbound still uses the picker; log eligibility for diagnostics.
     * Inbound SOS uses classic RECEIVE when the PBW has no companionApp declaration.
     */
    private fun logKit2PickerState() {
        appScope.launch(Dispatchers.IO) {
            val picker = DefaultPebbleAndroidAppPicker.getInstance(this@EzSosApp)
            picker.enableAutoSelect = true
            val eligible = picker.getAllEligibleApps()
            val selected = picker.getCurrentlySelectedApp()
            EventLog.i(
                TAG,
                "Kit2 picker eligible=[${eligible.joinToString()}] selected=${selected ?: "(none)"}"
            )
        }
    }

    override fun onTerminate() {
        // Emulator/tests only; not called on real devices.
        runCatching { pebbleSender.close() }
        super.onTerminate()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            AlertNotifier.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                AlertNotifier.channelAudioAttributes()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "App"

        @Volatile
        lateinit var instance: EzSosApp
            private set
    }
}
