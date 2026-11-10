package me.laumss.notipal

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.laumss.notipal.relay.ui.ChatInputBar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

internal object SystemKeyboard {
    private const val TAG = "SystemKeyboard"
    private const val ACTION_SHOW = "keyboard_show"
    private const val ACTION_HIDE = "keyboard_hide"
    private const val PACKAGE = "com.android.inputmethod.keyboard"

    private const val STILL_THERE_CHECK_MS = 3_000L
    private val LOG_PID = Regex("""^\w/[^(]+\(\s*(\d+)\)""")

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val keyboardPids = ConcurrentHashMap<Int, Boolean>()
    private var receiver: BroadcastReceiver? = null
    @Volatile private var appContext: Context? = null

    @Volatile var shown = false
        private set

    fun ensureRegistered(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { ensureRegistered(context) }
            return
        }
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SHOW -> onShown(intent.getIntExtra("type", -1))
                    ACTION_HIDE -> onHidden("broadcast")
                }
            }
        }
        val filter = IntentFilter(ACTION_SHOW).apply { addAction(ACTION_HIDE) }
        try {

            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "receiver registration failed: ${e.message}", e)
            return
        }
        receiver = r
        appContext = app
    }

    fun addListener(listener: (Boolean) -> Unit) { listeners.add(listener) }

    fun removeListener(listener: (Boolean) -> Unit) { listeners.remove(listener) }

    private fun onShown(type: Int) {
        if (shown) return
        shown = true
        Log.i(TAG, "keyboard shown type=$type")
        FloatingPenGuard.onSystemKeyboard(true)
        listeners.forEach { it(true) }
        main.removeCallbacks(stillThere)
        main.postDelayed(stillThere, STILL_THERE_CHECK_MS)
    }

    private fun onHidden(why: String) {
        main.removeCallbacks(stillThere)
        if (!shown) return
        shown = false
        Log.i(TAG, "keyboard hidden ($why)")
        FloatingPenGuard.onSystemKeyboard(false)
        listeners.forEach { it(false) }
    }

    private val stillThere: Runnable = Runnable {
        val context = appContext

        if (context != null && ChatInputBar.imeVisibleHeight(context) == 0) onHidden("no IME window")
        else if (shown) main.postDelayed(stillThereAgain, STILL_THERE_CHECK_MS)
    }
    private val stillThereAgain: Runnable = Runnable { stillThere.run() }

    fun isKeyboardLogLine(line: String): Boolean {
        if (!line.contains("HandWriteClient")) return false
        val pid = LOG_PID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return keyboardPids[pid] ?: lookUpPid(pid)
    }

    private fun lookUpPid(pid: Int): Boolean {
        val am = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val processes = try { am.runningAppProcesses } catch (_: Exception) { null } ?: return false
        val isKeyboard = processes.any { it.pid == pid && it.processName.startsWith(PACKAGE) }
        keyboardPids[pid] = isKeyboard
        if (isKeyboard) Log.i(TAG, "keyboard process pid=$pid")
        return isKeyboard
    }
}
