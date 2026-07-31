package com.ezsos.companion.pebble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import com.ezsos.companion.util.EventLog

/**
 * Only accept PebbleKit AppMessage / connection broadcasts from known Pebble phone apps.
 *
 * Classic PebbleKit delivers watch traffic as public broadcasts; without this check any app
 * on the device could forge `com.getpebble.action.app.RECEIVE` and trigger SOS SMS.
 *
 * This proves the *phone app* sent the intent — not cryptographic proof of the watch button.
 */
object PebbleSenderGuard {
    private const val TAG = "SenderGuard"

    /**
     * Known packages that speak classic PebbleKit intents to third-party companions.
     * Keep in sync with Core Devices / legacy Pebble / Rebble Cobble as they ship.
     */
    private val TRUSTED_PACKAGES = setOf(
        "coredevices.coreapp", // Current Core Devices / Pebble Play Store app
        "com.getpebble.android.basalt", // Classic Pebble Time / Pebble 2 app
        "com.getpebble.android", // Older classic Pebble app
        "io.rebble.cobble" // Rebble Cobble (legacy community app)
    )

    fun isTrustedSender(receiver: BroadcastReceiver, context: Context, intent: Intent?): Boolean {
        val senders = resolveSenderPackages(receiver, context)
        if (senders.isEmpty()) {
            // Ordered broadcasts from Core sometimes yield an empty calling identity on some OEMs /
            // Android versions. If a trusted Pebble phone app is installed, allow rather than
            // dropping SOS_REQUEST (which leaves the watch stuck on "Phone offline").
            val allow = isTrustedPebbleAppInstalled(context)
            if (allow) {
                EventLog.w(TAG, "Sender unresolved; allowing (trusted Pebble app installed)")
            } else {
                EventLog.w(TAG, "Rejecting: unresolved sender and no trusted Pebble app")
            }
            return allow
        }
        val trusted = senders.any { it in TRUSTED_PACKAGES }
        if (!trusted) {
            EventLog.w(TAG, "Rejecting untrusted sender(s)=$senders action=${intent?.action}")
        } else {
            EventLog.i(TAG, "Accepted sender(s)=$senders")
        }
        return trusted
    }

    private fun resolveSenderPackages(receiver: BroadcastReceiver, context: Context): Set<String> {
        val packages = linkedSetOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            receiver.sentFromPackage?.let { packages.add(it) }
        }

        val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val sentUid = receiver.sentFromUid
            if (sentUid != Process.INVALID_UID) sentUid else Binder.getCallingUid()
        } else {
            Binder.getCallingUid()
        }

        // Ignore our own UID (should not be the Pebble app path for RECEIVE).
        if (uid > 0 && uid != Process.myUid()) {
            try {
                val forUid = context.packageManager.getPackagesForUid(uid)
                if (forUid != null) {
                    packages.addAll(forUid)
                }
            } catch (e: Exception) {
                EventLog.w(TAG, "getPackagesForUid($uid) failed: ${e.message}")
            }
        }

        return packages
    }

    /** True if at least one allowlisted Pebble phone app is installed. */
    fun isTrustedPebbleAppInstalled(context: Context): Boolean {
        return TRUSTED_PACKAGES.any { isPackageInstalled(context, it) }
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
