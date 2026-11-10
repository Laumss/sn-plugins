package me.laumss.notipal.relay

import android.os.SystemClock
import android.util.Log

internal object RelayOpenTrace {
    private const val TAG = "AIRelayOpen"
    private const val IDLE = 0L

    @Volatile private var startedAt = IDLE

    fun begin(what: String) {
        startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "+0ms $what")
    }

    fun mark(what: String) {
        val start = startedAt
        if (start != IDLE) Log.i(TAG, "+${SystemClock.elapsedRealtime() - start}ms $what")
    }

    fun markOrBegin(what: String) {
        if (startedAt == IDLE) begin(what) else mark(what)
    }

    fun end(what: String) {
        mark(what)
        startedAt = IDLE
    }
}
