package com.ezsos.companion.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-app diagnostic log (also mirrors to logcat). Survives across Activities for the process lifetime.
 */
object EventLog {
    private const val TAG = "EzSosEventLog"
    private const val MAX_ENTRIES = 250

    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append('I', tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append('W', tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            append('E', tag, "$message (${throwable.javaClass.simpleName}: ${throwable.message})")
        } else {
            Log.e(tag, message)
            append('E', tag, message)
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
        notifyListeners()
    }

    fun snapshot(): String {
        synchronized(lock) {
            if (entries.isEmpty()) return "(no events yet — trigger SOS from the watch)"
            return entries.joinToString("\n")
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun append(level: Char, tag: String, message: String) {
        val line = "${timeFormat.format(Date())} $level/$tag: $message"
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(line)
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            try {
                listener()
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed", t)
            }
        }
    }
}
