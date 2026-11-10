package me.laumss.notipal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

internal object LexiLink {
    private const val TAG = "LexiLink"

    const val ACTION_WINDOW_STATE = "me.laumss.lexi.WINDOW_STATE"
    const val ACTION_STATUS = "me.laumss.notipal.LEXI_STATUS"
    const val ACTION_AI_COMPOSE = "me.laumss.lexi.AI_COMPOSE"
    const val ACTION_YIELD = "me.laumss.notipal.LEXI_YIELD"
    const val ACTION_AREAS = "me.laumss.notipal.LEXI_AREAS"
    private const val EXTERNAL_KEY = "ext:lexi"

    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    @Volatile private var open = false
    @Volatile private var fullScreen = false
    @Volatile private var merges = false
    private var lastRect = Rect()

    private val holds = LinkedHashSet<String>()

    fun isOpen(): Boolean = open

    fun isFullScreen(): Boolean = open && fullScreen

    fun isSmallOpen(): Boolean = open && !fullScreen

    private var sentAreas: List<Rect>? = null

    fun publishAreas(areas: List<Rect>) {
        if (!isSmallOpen()) return
        val ctx = appContext ?: return
        synchronized(this) {
            if (sentAreas == areas) return
            sentAreas = areas.map(::Rect)
        }
        try {
            ctx.sendBroadcast(Intent(ACTION_AREAS).apply {
                setPackage(ctx.packageName)
                putExtra("rects", areas.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.toIntArray())
            })
            Log.i(TAG, "areas to Lexi: $areas")
        } catch (e: Exception) {
            Log.e(TAG, "send areas failed: ${e.message}", e)
        }
    }

    fun hold(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { hold(owner) }; return }
        holds += owner
        if (open && fullScreen) return
        sendYield(true, owner)
        if (open) {
            Log.i(TAG, "small window gives way to $owner")
            onWindowState(visible = false, full = false, rect = Rect())
        }
    }

    fun release(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { release(owner) }; return }
        if (!holds.remove(owner) || holds.isNotEmpty()) return
        sendYield(false, owner)
    }

    private fun sendYield(away: Boolean, owner: String) {
        val ctx = appContext ?: return
        try {
            ctx.sendBroadcast(Intent(ACTION_YIELD).apply {
                setPackage(ctx.packageName)
                putExtra("yield", away)
            })
            Log.i(TAG, "yield=$away ($owner)")
        } catch (e: Exception) {
            Log.e(TAG, "send yield failed: ${e.message}", e)
        }
    }

    fun ensureRegistered(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_AI_COMPOSE) {
                    val text = intent.getStringExtra("text").orEmpty()
                    main.post { onCompose(text) }
                    return
                }
                if (intent?.action != ACTION_WINDOW_STATE) return
                val visible = intent.getBooleanExtra("visible", false)
                val full = intent.getBooleanExtra("fullScreen", false)
                val rect = Rect(
                    intent.getIntExtra("left", 0), intent.getIntExtra("top", 0),
                    intent.getIntExtra("right", 0), intent.getIntExtra("bottom", 0),
                )
                main.post { onWindowState(visible, full, rect) }
            }
        }
        val filter = IntentFilter(ACTION_WINDOW_STATE).apply { addAction(ACTION_AI_COMPOSE) }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
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
        Log.i(TAG, "receiver registered")
        publishStatus("registered")
    }

    fun onMonitorChanged(running: Boolean) {
        main.post {
            merges = running
            if (open && !fullScreen) {
                FloatingPenGuard.setExternalArea(EXTERNAL_KEY, if (running && !lastRect.isEmpty) Rect(lastRect) else null)
            }
            publishStatus(if (running) "monitor started" else "monitor stopped")
        }
    }

    private fun onCompose(text: String) {
        if (text.isBlank()) return

        if (open) {
            Log.i(TAG, "AI question before the window close: taken as closed")
            onWindowState(visible = false, full = false, rect = Rect())
        }
        val toolbar = FloatingToolbarModule.currentInstance
        if (toolbar == null) {
            Log.w(TAG, "AI question dropped: no toolbar module")
            return
        }
        Log.i(TAG, "AI question from Lexi (${text.length} chars)")
        toolbar.relayLexiQuestion(text)
    }

    private fun onWindowState(visible: Boolean, full: Boolean, rect: Rect) {
        val wasOpen = open
        val wasFull = fullScreen
        open = visible
        fullScreen = visible && full
        lastRect = if (visible && !full) Rect(rect) else Rect()
        when {
            !visible -> {
                FloatingPenGuard.setExternalArea(EXTERNAL_KEY, null)
                FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.LEXI)
            }
            full -> {
                FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.LEXI)
                FloatingPenGuard.setExternalArea(EXTERNAL_KEY, null)
            }
            else -> {

                if (merges && !rect.isEmpty) FloatingPenGuard.setExternalArea(EXTERNAL_KEY, rect)
                FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.LEXI)
            }
        }
        if (visible != wasOpen || fullScreen != wasFull) {

            synchronized(this) { sentAreas = null }
            FloatingToolbarModule.onLexiChanged(visible, fullScreen)
            if (isSmallOpen()) FloatingPenGuard.publishToLexi()
        }
        Log.i(TAG, "window visible=$visible fullScreen=$full rect=$rect merges=$merges")
        publishStatus("window state")
    }

    private fun publishStatus(reason: String) {
        val ctx = appContext ?: return
        try {
            ctx.sendBroadcast(Intent(ACTION_STATUS).apply {
                setPackage(ctx.packageName)
                putExtra("merges", merges)
                putExtra("reason", reason)

                putExtra("translate", true)
            })
        } catch (e: Exception) {
            Log.e(TAG, "send status failed: ${e.message}", e)
        }
    }
}
