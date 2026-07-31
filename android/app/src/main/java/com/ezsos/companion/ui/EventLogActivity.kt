package com.ezsos.companion.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ezsos.companion.R
import com.ezsos.companion.util.EventLog
import com.google.android.material.appbar.MaterialToolbar

class EventLogActivity : AppCompatActivity() {
    private lateinit var eventLogText: TextView

    private val logListener: () -> Unit = { runOnUiThread { refreshLog() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_log)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        eventLogText = findViewById(R.id.eventLogText)
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { EventLog.clear() }
        EventLog.addListener(logListener)
        refreshLog()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        EventLog.removeListener(logListener)
        super.onDestroy()
    }

    private fun refreshLog() {
        if (!::eventLogText.isInitialized) return
        eventLogText.text = EventLog.snapshot()
    }
}
