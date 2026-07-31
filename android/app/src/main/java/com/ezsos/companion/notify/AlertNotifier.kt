package com.ezsos.companion.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ezsos.companion.EzSosApp
import com.ezsos.companion.R
import com.ezsos.companion.settings.SosSettings
import com.ezsos.companion.ui.FullScreenAlarmActivity
import com.ezsos.companion.ui.MainActivity
import com.ezsos.companion.util.EventLog

/**
 * Phone-side alarm for inbound peer SOS or outbound self-locate.
 * Mode from [SosSettings.phoneAlertMode].
 */
object AlertNotifier {
    const val CHANNEL_ID = "ez_sos_inbound"
    private const val TAG = "PhoneAlarm"
    private const val NOTIFICATION_ID = 1001

    fun alertPhone(context: Context, contactName: String, modeOverride: String? = null) {
        alert(
            context = context,
            selfLocate = false,
            contactName = contactName,
            modeOverride = modeOverride,
        )
    }

    fun alertSelfLocate(context: Context, modeOverride: String? = null) {
        alert(
            context = context,
            selfLocate = true,
            contactName = "",
            modeOverride = modeOverride,
        )
    }

    private fun alert(
        context: Context,
        selfLocate: Boolean,
        contactName: String,
        modeOverride: String?,
    ) {
        val appCtx = context.applicationContext
        val mode = modeOverride
            ?: (appCtx as? EzSosApp)?.settingsCache?.getSettings()?.phoneAlertMode
            ?: SosSettings.MODE_NOTIFICATION
        when (SosSettings.normalizePhoneAlertMode(mode)) {
            SosSettings.MODE_OFF -> EventLog.i(TAG, "Phone alert off — skip")
            SosSettings.MODE_FULLSCREEN -> {
                EventLog.i(TAG, if (selfLocate) "Self-locate fullscreen" else "Phone alert fullscreen for $contactName")
                startFullScreen(appCtx, contactName, selfLocate)
                showNotification(appCtx, contactName, fullScreen = true, selfLocate = selfLocate)
            }
            else -> {
                EventLog.i(TAG, if (selfLocate) "Self-locate notification" else "Phone alert notification for $contactName")
                showNotification(appCtx, contactName, fullScreen = false, selfLocate = selfLocate)
            }
        }
    }

    fun showNotification(
        context: Context,
        contactName: String,
        fullScreen: Boolean,
        selfLocate: Boolean = false,
    ) {
        val appCtx = context.applicationContext
        val open = Intent(appCtx, if (fullScreen) FullScreenAlarmActivity::class.java else MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (fullScreen) {
                putExtra(FullScreenAlarmActivity.EXTRA_CONTACT_NAME, contactName)
                putExtra(FullScreenAlarmActivity.EXTRA_SELF_LOCATE, selfLocate)
            }
        }
        val contentPending = PendingIntent.getActivity(
            appCtx,
            if (selfLocate) 2 else 0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val title = if (selfLocate) {
            appCtx.getString(R.string.self_locate_notification_title)
        } else {
            appCtx.getString(R.string.inbound_notification_title)
        }
        val body = if (selfLocate) {
            appCtx.getString(R.string.self_locate_notification_body)
        } else {
            appCtx.getString(R.string.inbound_notification_body, contactName)
        }

        val builder = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentPending)
            .setSound(alarmUri)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .setOngoing(fullScreen)

        if (fullScreen) {
            val fullScreenPending = PendingIntent.getActivity(
                appCtx,
                if (selfLocate) 3 else 1,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(fullScreenPending, true)
        }

        try {
            NotificationManagerCompat.from(appCtx).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            EventLog.e(TAG, "Notification blocked (POST_NOTIFICATIONS?)", e)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun startFullScreen(context: Context, contactName: String, selfLocate: Boolean) {
        val intent = Intent(context, FullScreenAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(FullScreenAlarmActivity.EXTRA_CONTACT_NAME, contactName)
            putExtra(FullScreenAlarmActivity.EXTRA_SELF_LOCATE, selfLocate)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            EventLog.e(TAG, "Failed to start full-screen alarm", e)
        }
    }

    /** Ensure channel uses alarm audio attributes (called from Application). */
    fun channelAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
