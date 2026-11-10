package me.laumss.notipal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File

internal object DocuCraftLink {
    private const val TAG = "DocuCraftLink"
    private const val ACTION_REQUEST = "me.laumss.notipal.DOCUCRAFT_REQUEST"
    private const val ACTION_STATUS = "me.laumss.docucraft.NOTIPAL_STATUS"

    private const val REPLY_WAIT_MS = 800L

    private const val WAKE_WAIT_MS = 15_000L
    private const val RUNNING_GRACE_MS = 2_500L
    private const val WATCH_STEP_MS = 500L
    private const val HOST_PLUGIN_MANAGER_CLASS = "com.ratta.supernote.pluginhost.manager.PluginManager"
    private const val DOCUCRAFT_PLUGIN_ID = "ih0tyg5dw2caehtg"
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

    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null

    private var waiting: Runnable? = null
    private var export: AnnotatedExport? = null
    private val exportAck = Runnable { onExportAckTimeout() }
    private val exportWatch = Runnable { checkWokenExport() }
    private var showDocuCraft: (() -> Boolean)? = null

    private val abandoned = HashMap<String, File>()

    private var closeViewWhenRegistered = false

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
        val managerClass = Class.forName(HOST_PLUGIN_MANAGER_CLASS, false, context.classLoader)
        val manager = managerClass.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
        val app = managerClass.methods.firstOrNull {
            it.name == "getPluginApp" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }?.invoke(manager, DOCUCRAFT_PLUGIN_ID)
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
