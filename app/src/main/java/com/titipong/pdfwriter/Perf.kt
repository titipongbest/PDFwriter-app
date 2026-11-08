package com.titipong.pdfwriter

import android.os.SystemClock
import android.util.Log

/** Timing log of page loading (logcat tag PWPERF), used to find where time goes. */
object Perf {
    fun now(): Long = SystemClock.uptimeMillis()
    fun log(msg: String) {
        Log.i("PWPERF", "t=${now()} $msg")
    }
}
