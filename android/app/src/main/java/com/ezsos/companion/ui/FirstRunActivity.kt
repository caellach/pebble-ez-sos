package com.ezsos.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ezsos.companion.R
import com.google.android.material.appbar.MaterialToolbar

/**
 * First-run screen: SMS/location permissions and iOS unsupported notice.
 */
class FirstRunActivity : AppCompatActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        finishFirstRun()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_first_run)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        findViewById<TextView>(R.id.firstRunBody).text = getString(R.string.first_run_body)
        findViewById<Button>(R.id.btnContinue).setOnClickListener {
            requestNeededPermissions()
        }
        findViewById<Button>(R.id.btnSkip).setOnClickListener {
            finishFirstRun()
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
        if (missing.isEmpty()) {
            finishFirstRun()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun finishFirstRun() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val PREFS = "ez_sos_onboarding"
        const val KEY_DONE = "first_run_done"

        fun isComplete(activity: AppCompatActivity): Boolean {
            return activity.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)
        }
    }
}
