package me.laumss.notipal.links

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostHooks
import me.laumss.notipal.host.HostNotice
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.HostProbe
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.MonitorPhase
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.host.PenOwner
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.ToolbarHooks
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.WindowHooks
import java.io.File

object LinkHooks {

    @Volatile var lexiQuestion: ((text: String) -> Unit)? = null
}

object PluginLinks {
    fun install() {
        LexiLink.install()
    }
}

object LexiLink {
    private const val TAG = "LexiLink"

    const val ACTION_WINDOW_STATE = "me.laumss.lexi.WINDOW_STATE"
    const val ACTION_STATUS = "me.laumss.notipal.LEXI_STATUS"
    const val ACTION_AI_COMPOSE = "me.laumss.lexi.AI_COMPOSE"
    const val ACTION_YIELD = "me.laumss.notipal.LEXI_YIELD"
    const val ACTION_AREAS = "me.laumss.notipal.LEXI_AREAS"

    private const val EXTERNAL_KEY = "ext:lexi"

    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    private var listening = false

    @Volatile private var open = false
    @Volatile private var fullScreen = false
    @Volatile private var merges = false
    private var lastRect = Rect()

    private val holds = LinkedHashSet<String>()

    private var sentAreas: List<Rect>? = null

    fun isOpen(): Boolean = open

    fun isFullScreen(): Boolean = open && fullScreen

    fun isSmallOpen(): Boolean = open && !fullScreen

    internal fun install() {
        HostHooks.lexiSmallOpen = { isSmallOpen() }
        HostHooks.publishAreasToLexi = { publishAreas(it) }
        WindowHooks.lexiOpen = { isOpen() }
        WindowHooks.lexiHold = { hold(it) }
        WindowHooks.lexiRelease = { release(it) }
        ToolbarHooks.lexiFullScreen = { isFullScreen() }
        if (!listening) {
            listening = true

            HostSession.addNoticeListener { notice ->
                if (notice !is HostNotice.Monitor) return@addNoticeListener
                when (notice.phase) {
                    MonitorPhase.STARTED -> onMonitorChanged(true)
                    MonitorPhase.STOPPED, MonitorPhase.ORPHANED -> onMonitorChanged(false)
                    MonitorPhase.STOPPING -> Unit
                }
            }
        }
        ensureRegistered(App.context)
    }

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
        if (!Main.isMain) { Main.post { hold(owner) }; return }
        holds += owner
        if (open && fullScreen) return
        sendYield(true, owner)
        if (open) {
            Log.i(TAG, "small window gives way to $owner")
            onWindowState(visible = false, full = false, rect = Rect())
        }
    }

    fun release(owner: String) {
        if (!Main.isMain) { Main.post { release(owner) }; return }
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

    private fun ensureRegistered(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_AI_COMPOSE) {
                    val text = intent.getStringExtra("text").orEmpty()
                    Main.post { onCompose(text) }
                    return
                }
                if (intent?.action != ACTION_WINDOW_STATE) return
                val visible = intent.getBooleanExtra("visible", false)
                val full = intent.getBooleanExtra("fullScreen", false)
                val rect = Rect(
                    intent.getIntExtra("left", 0), intent.getIntExtra("top", 0),
                    intent.getIntExtra("right", 0), intent.getIntExtra("bottom", 0),
                )
                Main.post { onWindowState(visible, full, rect) }
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

    private fun onMonitorChanged(running: Boolean) {
        Main.post {
            merges = running
            if (open && !fullScreen) {
                PenGuard.setExternalArea(EXTERNAL_KEY, if (running && !lastRect.isEmpty) Rect(lastRect) else null)
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
        val handler = LinkHooks.lexiQuestion
        if (handler == null) {
            Log.w(TAG, "AI question dropped: no AIRelay handler (${text.length} chars)")
            Dialogs.tip(Strings.get("feature_not_ready"))
            return
        }
        Log.i(TAG, "AI question from Lexi (${text.length} chars)")
        handler(text)
    }

    private fun onWindowState(visible: Boolean, full: Boolean, rect: Rect) {
        val wasOpen = open
        val wasFull = fullScreen
        open = visible
        fullScreen = visible && full
        lastRect = if (visible && !full) Rect(rect) else Rect()
        when {
            !visible -> {
                PenGuard.setExternalArea(EXTERNAL_KEY, null)
                PenGuard.setFullScreenActive(false, PenOwner.LEXI)
            }
            full -> {
                PenGuard.setFullScreenActive(true, PenOwner.LEXI)
                PenGuard.setExternalArea(EXTERNAL_KEY, null)
            }
            else -> {

                if (merges && !rect.isEmpty) PenGuard.setExternalArea(EXTERNAL_KEY, rect)
                PenGuard.setFullScreenActive(false, PenOwner.LEXI)
            }
        }
        if (visible != wasOpen || fullScreen != wasFull) {

            synchronized(this) { sentAreas = null }
            onChanged(visible, fullScreen)
            if (isSmallOpen()) PenGuard.publishToLexi()
        }
        Log.i(TAG, "window visible=$visible fullScreen=$full rect=$rect merges=$merges")
        publishStatus("window state")
    }

    private fun onChanged(open: Boolean, full: Boolean) {
        Main.post {
            if (isOpen() != open || isFullScreen() != (open && full)) return@post
            when {
                open && full -> {
                    Toolbar.putAwayOverlays()
                    Log.i(TAG, "Lexi full screen: overlays hidden")
                }
                open -> {

                    Toolbar.putAwayFloats()
                    Toolbar.yieldFor(Toolbar.REASON_LEXI)
                    Log.i(TAG, "Lexi small window: floats hidden, toolbar collapsed")
                }
                else -> {
                    Toolbar.resume(Toolbar.REASON_LEXI)
                    HostSession.reassertAfterCover("lexi closed")
                    Toolbar.bringBackOverlays("lexi closed")
                    Log.i(TAG, "Lexi closed: restore scheduled")
                }
            }
        }
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

object DocuCraftLink {
    private const val TAG = "DocuCraftLink"
    private const val ACTION_REQUEST = "me.laumss.notipal.DOCUCRAFT_REQUEST"
    private const val ACTION_STATUS = "me.laumss.docucraft.NOTIPAL_STATUS"

    private const val REPLY_WAIT_MS = 800L

    private const val WAKE_WAIT_MS = 15_000L
    private const val RUNNING_GRACE_MS = 2_500L
    private const val WATCH_STEP_MS = 500L
    const val PLUGIN_ID = "ih0tyg5dw2caehtg"
    private val RUNNING_STATES = setOf("mounted", "start", "stop")

    enum class ExportFailure { CANNOT_OPEN, OUTDATED, FAILED }

    private class AnnotatedExport(
        val id: String,
        val input: String,
        val output: String,
        val app: Context,
        val onDone: (ExportFailure?) -> Unit,
    ) {
        var woke = false
        var accepted = false
        var since = 0L
        var runningSince = 0L
    }

    private val main get() = Main.handler
    private var receiver: BroadcastReceiver? = null

    private var waiting: Runnable? = null
    private var export: AnnotatedExport? = null
    private val exportAck = Runnable { onExportAckTimeout() }
    private val exportWatch = Runnable { checkWokenExport() }
    private var showDocuCraft: (() -> Boolean)? = null

    private val abandoned = HashMap<String, File>()

    private var closeViewWhenRegistered = false

    fun installed(): Boolean = HostPlugins.isHostPluginInstalled(PLUGIN_ID)

    fun show(): Boolean = HostPlugins.showHostPluginView(PLUGIN_ID)

    fun openConversion(context: Context, fallback: () -> Unit) {
        val app = context.applicationContext
        if (!ensureRegistered(app)) {
            fallback()
            return
        }
        waiting?.let { main.removeCallbacks(it) }
        val timeout = Runnable {
            waiting = null
            Log.i(TAG, "no reply from DocuCraft in ${REPLY_WAIT_MS}ms: asking the host to show it")
            fallback()
        }
        waiting = timeout
        main.postDelayed(timeout, REPLY_WAIT_MS)
        try {
            app.sendBroadcast(Intent(ACTION_REQUEST).apply {
                setPackage(app.packageName)
                putExtra("cmd", "convert")
            })
            Log.i(TAG, "convert request sent")
        } catch (e: Exception) {
            Log.e(TAG, "convert request failed: ${e.message}", e)
            main.removeCallbacks(timeout)
            waiting = null
            fallback()
        }
    }

    fun exportAnnotated(
        context: Context,
        input: String,
        output: String,
        show: () -> Boolean,
        onDone: (ExportFailure?) -> Unit,
    ): () -> Unit {
        val app = context.applicationContext
        if (!ensureRegistered(app)) {
            onDone(ExportFailure.FAILED)
            return {}
        }
        val request = AnnotatedExport(SystemClock.uptimeMillis().toString(), input, output, app, onDone)
        export = request
        showDocuCraft = show
        closeViewWhenRegistered = false
        sendExport(request)
        main.postDelayed(exportAck, REPLY_WAIT_MS)
        return {
            if (export === request) {
                clearExport()
                File(output).parentFile?.let { abandoned[request.id] = it }
                if (request.woke && !request.accepted) closeViewWhenRegistered = true
            }
        }
    }

    private fun sendExport(request: AnnotatedExport) {
        try {
            request.app.sendBroadcast(Intent(ACTION_REQUEST).apply {
                setPackage(request.app.packageName)
                putExtra("cmd", "export_annotated")
                putExtra("id", request.id)
                putExtra("input", request.input)
                putExtra("output", request.output)
                putExtra("closeView", request.woke)
            })
            Log.i(TAG, "export request ${request.id} sent woke=${request.woke}")
        } catch (e: Exception) {
            Log.e(TAG, "export request failed: ${e.message}", e)
            finishExport(ExportFailure.FAILED)
        }
    }

    private fun onExportAckTimeout() {
        val request = export ?: return
        if (running(request.app) == true) {
            Log.w(TAG, "DocuCraft runs but did not accept the export: build without this entry")
            finishExport(ExportFailure.OUTDATED)
            return
        }
        if (showDocuCraft?.invoke() != true) {
            Log.w(TAG, "DocuCraft did not answer and the host cannot show it")
            finishExport(ExportFailure.CANNOT_OPEN)
            return
        }
        request.woke = true
        request.since = SystemClock.uptimeMillis()
        main.postDelayed(exportWatch, WATCH_STEP_MS)
    }

    private fun checkWokenExport() {
        val request = export ?: return
        if (request.accepted) return
        val now = SystemClock.uptimeMillis()
        if (request.runningSince == 0L && running(request.app) == true) request.runningSince = now
        val silent = if (request.runningSince != 0L) now - request.runningSince >= RUNNING_GRACE_MS
            else now - request.since >= WAKE_WAIT_MS
        if (silent) {
            Log.w(TAG, "DocuCraft sent no status ${now - request.since}ms after the show request")
            finishExport(ExportFailure.OUTDATED)
        } else {
            main.postDelayed(exportWatch, WATCH_STEP_MS)
        }
    }

    private fun clearExport() {
        export = null
        showDocuCraft = null
        main.removeCallbacks(exportAck)
        main.removeCallbacks(exportWatch)
    }

    private fun finishExport(failure: ExportFailure?) {
        val request = export ?: return
        clearExport()
        request.onDone(failure)
    }

    private fun onStatus(app: Context, intent: Intent) {
        val reason = intent.getStringExtra("reason").orEmpty()
        val version = intent.getStringExtra("version").orEmpty()
        val id = intent.getStringExtra("id").orEmpty()
        Log.i(TAG, "DocuCraft status reason=$reason version=$version id=$id")
        when (reason) {
            "opening" -> {
                waiting?.let { main.removeCallbacks(it) }
                waiting = null
            }
            "registered" -> {
                val request = export
                if (request != null && request.woke && !request.accepted) sendExport(request)
                if (closeViewWhenRegistered) {
                    closeViewWhenRegistered = false
                    app.sendBroadcast(Intent(ACTION_REQUEST).apply {
                        setPackage(app.packageName)
                        putExtra("cmd", "close_view")
                    })
                }
            }
            "export_accepted" -> export?.takeIf { it.id == id }?.let {
                it.accepted = true
                main.removeCallbacks(exportAck)
                main.removeCallbacks(exportWatch)
            }
            "export_done" -> {
                abandoned.remove(id)?.deleteRecursively()
                if (export?.id == id) {
                    val ok = intent.getBooleanExtra("ok", false)
                    if (!ok) Log.w(TAG, "DocuCraft export failed: ${intent.getStringExtra("message")}")
                    finishExport(if (ok) null else ExportFailure.FAILED)
                }
            }
        }
    }

    private fun running(context: Context): Boolean? = try {
        val managerClass = Class.forName(HostProbe.HOST_PLUGIN_MANAGER_CLASS, false, context.classLoader)
        val manager = managerClass.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
        val app = managerClass.methods.firstOrNull {
            it.name == "getPluginApp" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }?.invoke(manager, PLUGIN_ID)
        val loading = app?.javaClass?.methods?.firstOrNull { it.name == "isLoading" && it.parameterCount == 0 }?.invoke(app) as? Boolean
        val state = app?.javaClass?.methods?.firstOrNull { it.name == "getState" && it.parameterCount == 0 }
            ?.invoke(app)?.toString()?.lowercase()
        if (app == null) false else if (loading == true) false else state?.let { it in RUNNING_STATES }
    } catch (e: Throwable) {
        Log.w(TAG, "DocuCraft state failed: ${e.message}")
        null
    }

    private fun ensureRegistered(app: Context): Boolean {
        if (receiver != null) return true
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_STATUS) return
                main.post { onStatus(app, intent) }
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, IntentFilter(ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, IntentFilter(ACTION_STATUS))
            }
            receiver = r
            true
        } catch (e: Exception) {
            Log.e(TAG, "receiver registration failed: ${e.message}", e)
            false
        }
    }
}
