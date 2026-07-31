package com.ezsos.companion.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ezsos.companion.util.EventLog
import java.util.concurrent.atomic.AtomicBoolean

data class LatLon(val lat: Double, val lon: Double)

object LocationHelper {
    private const val TAG = "Location"
    private const val TIMEOUT_MS = 8_000L
    private const val MAX_AGE_MS = 120_000L

    fun getCurrentLocation(context: Context, callback: (LatLon?) -> Unit) {
        val appCtx = context.applicationContext
        val fine = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            EventLog.w(TAG, "No location permission")
            callback(null)
            return
        }

        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            EventLog.w(TAG, "No LocationManager")
            callback(null)
            return
        }

        val last = bestLastKnown(lm)
        if (last != null && System.currentTimeMillis() - last.time <= MAX_AGE_MS) {
            EventLog.i(TAG, "Using last-known (age=${System.currentTimeMillis() - last.time}ms)")
            callback(LatLon(last.latitude, last.longitude))
            return
        }

        EventLog.i(TAG, "Waiting for fresh location (timeout=${TIMEOUT_MS}ms)")
        val done = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                finish(LatLon(location.latitude, location.longitude))
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            fun finish(result: LatLon?) {
                if (!done.compareAndSet(false, true)) return
                try {
                    lm.removeUpdates(this)
                } catch (_: Exception) {
                }
                main.removeCallbacksAndMessages(null)
                if (result == null) {
                    EventLog.w(TAG, "Location finish=null")
                } else {
                    EventLog.i(TAG, "Location fix received")
                }
                callback(result)
            }
        }

        main.postDelayed({
            if (!done.compareAndSet(false, true)) return@postDelayed
            try {
                lm.removeUpdates(listener)
            } catch (_: Exception) {
            }
            val fallback = bestLastKnown(lm)
            EventLog.w(TAG, "Location timeout; fallback=${fallback != null}")
            callback(fallback?.let { LatLon(it.latitude, it.longitude) })
        }, TIMEOUT_MS)

        try {
            val providers = buildList {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
            if (providers.isEmpty()) {
                EventLog.w(TAG, "No enabled location providers")
                listener.finish(last?.let { LatLon(it.latitude, it.longitude) })
                return
            }
            EventLog.i(TAG, "Providers=$providers")
            for (provider in providers) {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            EventLog.e(TAG, "SecurityException requesting updates")
            listener.finish(null)
        } catch (e: Exception) {
            EventLog.e(TAG, "requestLocationUpdates failed", e)
            listener.finish(last?.let { LatLon(it.latitude, it.longitude) })
        }
    }

    private fun bestLastKnown(lm: LocationManager): Location? {
        val candidates = mutableListOf<Location>()
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { candidates.add(it) }
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { candidates.add(it) }
        } catch (_: SecurityException) {
            return null
        }
        return candidates.maxByOrNull { it.time }
    }
}
