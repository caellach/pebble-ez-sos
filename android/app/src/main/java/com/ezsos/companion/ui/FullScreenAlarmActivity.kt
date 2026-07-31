package com.ezsos.companion.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ezsos.companion.R
import com.ezsos.companion.notify.AlertNotifier

/**
 * Full-screen phone alarm for an incoming SOS from a contact (and Test phone alarm).
 */
class FullScreenAlarmActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turnScreenOnAndUnlock()
        setContentView(R.layout.activity_full_screen_alarm)

        val name = intent.getStringExtra(EXTRA_CONTACT_NAME)
            ?.ifBlank { null }
            ?: getString(R.string.alarm_contact_unknown)
        val selfLocate = intent.getBooleanExtra(EXTRA_SELF_LOCATE, false)

        if (selfLocate) {
            findViewById<TextView>(R.id.alarmTitle).text = getString(R.string.self_locate_fullscreen_title)
            findViewById<TextView>(R.id.alarmSubtitle).text =
                getString(R.string.self_locate_fullscreen_body)
        } else {
            findViewById<TextView>(R.id.alarmTitle).text = getString(R.string.alarm_fullscreen_title)
            findViewById<TextView>(R.id.alarmSubtitle).text =
                getString(R.string.alarm_fullscreen_body, name)
        }
        findViewById<Button>(R.id.btnDismissAlarm).setOnClickListener { dismiss() }

        startAlarmEffects()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        stopAlarmEffects()
        super.onDestroy()
    }

    private fun dismiss() {
        stopAlarmEffects()
        AlertNotifier.cancel(this)
        finish()
    }

    private fun turnScreenOnAndUnlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguard = getSystemService(KeyguardManager::class.java)
            keyguard?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startAlarmEffects() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            player = MediaPlayer().apply {
                setDataSource(this@FullScreenAlarmActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            // Sound optional if OEM blocks it.
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        val pattern = longArrayOf(0, 600, 300, 600, 300, 600)
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        }
    }

    private fun stopAlarmEffects() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    companion object {
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_SELF_LOCATE = "self_locate"
    }
}
