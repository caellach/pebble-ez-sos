package com.ezsos.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ezsos.companion.EzSosApp
import com.ezsos.companion.R
import com.ezsos.companion.WatchUuid
import com.ezsos.companion.notify.AlertNotifier
import com.ezsos.companion.notify.SelfLocateAlarm
import com.ezsos.companion.pebble.ClassicPebbleRegistration
import com.ezsos.companion.pebble.PebbleMessenger
import com.ezsos.companion.pebble.PebbleSenderGuard
import com.ezsos.companion.settings.SosSettings
import com.ezsos.companion.sos.SosHandler
import com.ezsos.companion.util.EventLog

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var settingsText: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!FirstRunActivity.isComplete(this)) {
            startActivity(Intent(this, FirstRunActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        statusText = findViewById(R.id.statusText)
        settingsText = findViewById(R.id.settingsText)

        findViewById<Button>(R.id.btnEditSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnRequestSettings).setOnClickListener {
            EventLog.i(TAG, "User tapped pull settings from watch")
            PebbleMessenger.sendSettingsRequest(this)
            refreshStatus()
        }
        findViewById<Button>(R.id.btnTestWatchAlarm).setOnClickListener {
            EventLog.i(TAG, "Test watch alarm")
            PebbleMessenger.sendInboundAlert(this)
            Toast.makeText(this, R.string.toast_test_watch, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTestPhoneAlarm).setOnClickListener {
            EventLog.i(TAG, "Test phone alarm")
            val mode = (application as EzSosApp).settingsCache.getSettings()?.phoneAlertMode
                ?: SosSettings.MODE_NOTIFICATION
            if (mode == SosSettings.MODE_OFF) {
                Toast.makeText(this, R.string.toast_phone_alarm_off, Toast.LENGTH_LONG).show()
            } else {
                AlertNotifier.alertPhone(
                    this,
                    getString(R.string.alarm_test_contact),
                    modeOverride = mode
                )
            }
        }
        findViewById<Button>(R.id.btnTestSelfLocate).setOnClickListener {
            EventLog.i(TAG, "Test self-locate alarm")
            SelfLocateAlarm.trigger(this)
            Toast.makeText(this, R.string.toast_test_self_locate, Toast.LENGTH_SHORT).show()
        }

        (application as EzSosApp).settingsCache.addListener(settingsListener)
        handleDeepLink(intent)
        EventLog.i(TAG, "MainActivity created")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_overflow, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_event_log -> {
                startActivity(Intent(this, EventLogActivity::class.java))
                true
            }
            R.id.menu_permissions -> {
                requestNeededPermissions()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        ClassicPebbleRegistration.ensureRegistered(this)
        EventLog.i(TAG, "onResume — sending COMPANION_PRESENT")
        PebbleMessenger.sendCompanionPresent(this)
        refreshStatus()
    }

    override fun onDestroy() {
        (application as? EzSosApp)?.settingsCache?.removeListener(settingsListener)
        super.onDestroy()
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "ezsos") return
        when (data.host) {
            "settings", "configure" -> {
                EventLog.i(TAG, "Deep link → settings ($data)")
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            "sos" -> {
                EventLog.i(TAG, "Deep link → SOS ($data)")
                SosHandler.handleSosRequest(applicationContext)
            }
        }
    }

    private val settingsListener: () -> Unit = { runOnUiThread { refreshStatus() } }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val pebbleAppLabel = when {
            PebbleSenderGuard.isPackageInstalled(this, "coredevices.coreapp") ->
                getString(R.string.pebble_app_core)
            PebbleSenderGuard.isPackageInstalled(this, "com.getpebble.android.basalt") ||
                PebbleSenderGuard.isPackageInstalled(this, "com.getpebble.android") ->
                getString(R.string.pebble_app_basalt)
            PebbleSenderGuard.isTrustedPebbleAppInstalled(this) ->
                getString(R.string.pebble_app_other)
            else -> getString(R.string.pebble_app_missing)
        }
        val smsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val locOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        statusText.text = getString(
            R.string.main_status,
            pebbleAppLabel,
            if (smsOk) getString(R.string.yes) else getString(R.string.no),
            if (locOk) getString(R.string.yes) else getString(R.string.no),
            WatchUuid.STRING
        )

        val settings = (application as EzSosApp).settingsCache.getSettings()
        val enabled = settings?.enabledContacts()?.size ?: 0
        val total = settings?.contacts?.size ?: 0
        val phoneMode = settings?.phoneAlertMode ?: SosSettings.MODE_NOTIFICATION
        settingsText.text = if (settings == null) {
            getString(R.string.settings_none)
        } else {
            getString(
                R.string.settings_summary,
                enabled,
                total,
                settings.triggerMode,
                phoneModeLabel(phoneMode)
            )
        }
    }

    private fun phoneModeLabel(mode: String): String {
        return when (mode) {
            SosSettings.MODE_OFF -> getString(R.string.phone_alert_off)
            SosSettings.MODE_FULLSCREEN -> getString(R.string.phone_alert_fullscreen)
            else -> getString(R.string.phone_alert_notification)
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        EventLog.i(TAG, "Permission request: missing=${missing.joinToString()}")
        if (missing.isEmpty()) {
            Toast.makeText(this, R.string.toast_permissions_ok, Toast.LENGTH_SHORT).show()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    companion object {
        private const val TAG = "Main"
    }
}
