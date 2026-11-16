package me.laumss.notipal.host

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.ui_common.DialogHooks
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import android.view.Surface as AndroidSurface

object HostHooks {

    @Volatile var mosaicBoardVisible: () -> Boolean = { false }

    @Volatile var publishToolbarRect: (rect: Rect?, force: Boolean) -> Unit = { _, _ -> }

    @Volatile var publishOverlayRects: (rects: List<Rect>, force: Boolean) -> Unit = { _, _ -> }

    @Volatile var lexiSmallOpen: () -> Boolean = { false }

    @Volatile var publishAreasToLexi: (areas: List<Rect>) -> Unit = {}

    @Volatile var anyPanelShowing: () -> Boolean = { false }

    @Volatile var requestChromeProbe: (reason: String) -> Unit = {}

    @Volatile var isDocumentContext: () -> Boolean = { false }

    @Volatile var hasToolbarSession: () -> Boolean = { false }

    @Volatile var foregroundMonitorRunning: () -> Boolean = { false }

    @Volatile var startForegroundMonitor: (() -> Unit)? = null
    @Volatile var stopForegroundMonitor: (() -> Unit)? = null
}

object PenGuardTiming {

    const val FOLLOW_INTERVAL_MS = 80L

    const val REMOVE_LINGER_MS = 500L

    const val HOST_BOOTSTRAP_FALLBACK_MS = 450L

    const val BINDER_RETRY_DELAY_MS = 1_000L
    const val BINDER_RETRY_ATTEMPTS = 3

    const val GUARD_REASSERT_DELAY_MS = 600L

    const val HOST_SNAPSHOT_REASSERT_DELAY_MS = 120L

    const val FOREGROUND_REASSERT_FOLLOWUP_MS = 1_000L

    const val DOC_WRITABLE_REASSERT_FOLLOWUP_MS = 120L

    const val KEYBOARD_EXPECT_MAX_MS = 1_500L

    const val RELAY_MONITOR_STOP_DELAY_MS = 1_500L

    const val LASSO_RELEASE_GRACE_MS = 300L

    const val ROTATION_DISPLAY_SETTLE_MS = 250L

    const val ROTATION_VIEWS_RETRY_MS = 80L

    const val DOC_CHROME_STALE_LAYOUT_RETRY_MS = 250L

    const val DOC_STICKER_HOLD_MAX_MS = 120_000L

    const val TOOLBAR_PRESS_RELEASE_MS = 180L

    const val TOOLBAR_LONG_PRESS_RELEASE_MS = 350L

    val PEN_LASSO_REARM_DELAYS_MS = longArrayOf(400L, 900L, 1_700L)

    const val PEN_LASSO_KEEP_ALIVE_MS = 800L

    const val PEN_SELECTION_ACTION_TIMEOUT_MS = 20_000L
}

object PenAreaGeometry {

    private const val SENTINEL_EXTENT = 18888

    private const val HOST_FULL_SCREEN_MIN_PX = 1000

    fun clearSentinel(): Rect = Rect(0, 0, SENTINEL_EXTENT, SENTINEL_EXTENT)

    fun emptyTableRect(): Rect = Rect(0, 0, 1, 1)

    fun isClearSentinel(rect: Rect): Boolean =
        rect.left == 0 && rect.top == 0 &&
            rect.right == SENTINEL_EXTENT && rect.bottom == SENTINEL_EXTENT

    fun isWritableReset(rects: List<Rect>): Boolean =
        rects.size == 1 && isClearSentinel(rects[0])

    fun isHostFullScreenRect(rect: Rect): Boolean =
        rect.left <= 0 && rect.top <= 0 && !isClearSentinel(rect) &&
            rect.width() >= HOST_FULL_SCREEN_MIN_PX && rect.height() >= HOST_FULL_SCREEN_MIN_PX

    fun isHostFullScreenSnapshot(rects: List<Rect>): Boolean =
        rects.size == 1 && isHostFullScreenRect(rects[0])

    fun isScreenCovering(rect: Rect, screenWidth: Int, screenHeight: Int): Boolean =
        rect.left <= 0 && rect.top <= 0 && !isClearSentinel(rect) &&
            ((rect.right >= screenWidth && rect.bottom >= screenHeight) || isHostFullScreenRect(rect))

    fun normalize(source: List<Rect>, keepClearSentinel: Boolean): List<Rect> =
        source.asSequence()
            .filter { keepClearSentinel || !isClearSentinel(it) }
            .map(::Rect)
            .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
            .sortedWith(compareBy<Rect>({ it.top }, { it.left }, { it.bottom }, { it.right }))
            .toList()

    fun sameRects(a: List<Rect>, b: List<Rect>): Boolean =
        normalize(a, keepClearSentinel = true) == normalize(b, keepClearSentinel = true)

    fun toNaturalDisplayRect(
        logical: Rect,
        rotation: Int,
        naturalWidth: Int,
        naturalHeight: Int
    ): Rect {
        val transformed = when (rotation) {
            AndroidSurface.ROTATION_90 -> Rect(
                naturalWidth - logical.bottom,
                logical.left,
                naturalWidth - logical.top,
                logical.right
            )
            AndroidSurface.ROTATION_180 -> Rect(
                naturalWidth - logical.right,
                naturalHeight - logical.bottom,
                naturalWidth - logical.left,
                naturalHeight - logical.top
            )
            AndroidSurface.ROTATION_270 -> Rect(
                logical.top,
                naturalHeight - logical.right,
                logical.bottom,
                naturalHeight - logical.left
            )
            else -> Rect(logical)
        }
        transformed.left = transformed.left.coerceIn(0, naturalWidth)
        transformed.right = transformed.right.coerceIn(0, naturalWidth)
        transformed.top = transformed.top.coerceIn(0, naturalHeight)
        transformed.bottom = transformed.bottom.coerceIn(0, naturalHeight)
        return transformed
    }

    fun toLogicalDisplayRect(
        natural: Rect,
        rotation: Int,
        naturalWidth: Int,
        naturalHeight: Int
    ): Rect = when (rotation) {
        AndroidSurface.ROTATION_90 -> Rect(
            natural.top,
            naturalWidth - natural.right,
            natural.bottom,
            naturalWidth - natural.left
        )
        AndroidSurface.ROTATION_180 -> Rect(
            naturalWidth - natural.right,
            naturalHeight - natural.bottom,
            naturalWidth - natural.left,
            naturalHeight - natural.top
        )
        AndroidSurface.ROTATION_270 -> Rect(
            naturalHeight - natural.bottom,
            natural.left,
            naturalHeight - natural.top,
            natural.right
        )
        else -> Rect(natural)
    }
}

object PenDisableClient {

    private const val TAG = "PenDisableClient"

    private val SERVICE_NAME_CANDIDATES = arrayOf("service_myservice", "service.myservice")
    private const val INTERFACE_TOKEN = "android.demo.IMyService"

    const val CODE_DISABLE_AREA_INFO = 1

    enum class Result {
        OK,

        NO_SERVICE,

        FAILED,
    }

    @Volatile private var cachedBinder: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient {
        cachedBinder = null
        Log.w(TAG, "drawPath service binder died; will look it up again on next send")
    }

    private fun serviceBinder(): IBinder? {
        cachedBinder?.let { binder ->
            if (binder.isBinderAlive) return binder
            cachedBinder = null
        }
        val binder = lookupServiceBinder() ?: return null
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "linkToDeath failed: ${e.message}")
        }
        cachedBinder = binder
        return binder
    }

    private fun dropBinder(binder: IBinder) {
        if (cachedBinder !== binder) return
        cachedBinder = null
        try {
            binder.unlinkToDeath(deathRecipient, 0)
        } catch (e: Exception) {

            Log.w(TAG, "unlinkToDeath failed: ${e.message}")
        }
    }

    private fun lookupServiceBinder(): IBinder? {
        for (name in SERVICE_NAME_CANDIDATES) {
            val binder = try {
                val sm = Class.forName("android.os.ServiceManager")
                val get = sm.getMethod("getService", String::class.java)
                get.invoke(null, name) as? IBinder
            } catch (e: Throwable) {
                Log.e(TAG, "getService reflection failed: ${e.javaClass.name}: ${e.message}")
                return null
            }
            if (binder != null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[diag] got binder via \"$name\"")
                return binder
            }
        }
        Log.e(TAG, "drawPath service not found: ${SERVICE_NAME_CANDIDATES.joinToString()}")
        if (BuildConfig.ENABLE_DEBUG) logServiceList()
        return null
    }

    private fun logServiceList() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val list = sm.getMethod("listServices")
            @Suppress("UNCHECKED_CAST")
            val services = list.invoke(null) as? Array<String>
            if (services == null) {
                Log.e(TAG, "[diag] listServices returned null")
                return
            }
            Log.i(TAG, "[diag] listServices total=${services.size}")
            val hits = services.filter {
                it.contains("myservice", ignoreCase = true) || it.contains("demo", ignoreCase = true)
            }
            if (hits.isEmpty()) {
                Log.i(TAG, "[diag] no service name contains 'myservice'/'demo'")
            } else {
                for (name in hits) {
                    Log.i(TAG, "[diag] matched service: $name")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[diag] listServices failed: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    fun sendDisableAreas(
        appName: String,
        rects: List<Rect>,
    ): Result {
        if (rects.isEmpty()) return Result.FAILED
        val code = CODE_DISABLE_AREA_INFO
        val binder = serviceBinder() ?: return Result.NO_SERVICE

        val token = INTERFACE_TOKEN
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(token)
            data.writeString(appName)
            data.writeInt(rects.size)
            for (r in rects) {
                data.writeInt(r.left)
                data.writeInt(r.top)
                data.writeInt(r.width())
                data.writeInt(r.height())
                data.writeInt(0)
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "[diag] payload token=\"$token\" app=\"$appName\" count=${rects.size} " +
                    rects.joinToString(" ") { "[l=${it.left},t=${it.top},w=${it.width()},h=${it.height()},f=0]" })
                Log.i(TAG, "[diag] parcel dataSize=${data.dataSize()} hex=${data.marshall().toHexPreview(96)}")
            }
            val ok = binder.transact(code, data, reply, 0)
            if (BuildConfig.ENABLE_DEBUG) {
                val replyStr = reply.readString()
                val replySize = reply.dataSize()
                val replyPos = reply.dataPosition()
                var extra = ""
                if (replyPos < replySize) {
                    extra = try {
                        " extraInt=${reply.readInt()} (remain=${replySize - reply.dataPosition()}B)"
                    } catch (_: Exception) {
                        " (unreadable remain=${replySize - replyPos}B)"
                    }
                }
                Log.i(TAG, "send code=$code app=\"$appName\" transact=$ok " +
                    "reply=$replyStr replySize=$replySize$extra rects=$rects")
            }
            if (ok) Result.OK else {
                Log.e(TAG, "send rejected code=$code app=\"$appName\" rects=${rects.size}")
                Result.FAILED
            }
        } catch (e: DeadObjectException) {
            dropBinder(binder)
            Log.e(TAG, "send failed, binder dead app=\"$appName\"")
            Result.NO_SERVICE
        } catch (e: Exception) {
            Log.e(TAG, "send failed code=$code app=\"$appName\": ${e.message}", e)
            Result.FAILED
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun ByteArray.toHexPreview(max: Int): String {
        val n = minOf(size, max)
        val sb = StringBuilder(n * 2 + 8)
        for (i in 0 until n) sb.append(String.format("%02x", this[i]))
        if (size > max) sb.append("…(${size}B)")
        return sb.toString()
    }

    fun clearDisableAreas(appName: String): Result =
        sendDisableAreas(appName, listOf(PenAreaGeometry.clearSentinel()))

    fun releaseDisableAreas(appName: String): Result {
        val replaced = sendDisableAreas(appName, listOf(PenAreaGeometry.emptyTableRect()))
        return if (replaced == Result.OK) clearDisableAreas(appName) else replaced
    }

    private const val CODE_PEN_INFO = 2
    private const val CODE_SYNC_BACKGROUND = 6

    fun sendPenInfo(appName: String, type: Int, width: Int, color: Int): Result =
        transactInts(CODE_PEN_INFO, appName, intArrayOf(type, width, color), "penInfo")

    fun syncBackground(appName: String, a6x2: Boolean): Result =
        transactInts(CODE_SYNC_BACKGROUND, appName, intArrayOf(255, if (a6x2) 1 else 0), "syncBackground")

    private fun transactInts(code: Int, appName: String, values: IntArray, what: String): Result {
        val binder = serviceBinder() ?: return Result.NO_SERVICE
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeString(appName)
            for (v in values) data.writeInt(v)
            val ok = binder.transact(code, data, reply, 0)
            val replyStr = try {
                reply.readString()
            } catch (e: Exception) {
                "(unreadable: ${e.message})"
            }
            Log.i(TAG, "$what code=$code app=\"$appName\" values=${values.joinToString()} transact=$ok reply=$replyStr")
            if (ok) Result.OK else Result.FAILED
        } catch (e: DeadObjectException) {
            dropBinder(binder)
            Log.e(TAG, "$what failed, binder dead app=\"$appName\"")
            Result.NO_SERVICE
        } catch (e: Exception) {
            Log.e(TAG, "$what failed code=$code app=\"$appName\": ${e.message}", e)
            Result.FAILED
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

enum class PenGuardScope {

    SYSTEM,

    HOST_UI,

    FLOW,

    PRESS,

    WINDOW,
}

object PenOwner {
    const val HOST_BOOTSTRAP = "host-bootstrap"
    const val ROTATION_SYNC = "rotation-sync"
    const val FOREIGN_ACTIVITY = "foreign-activity"

    const val HOST_TOOLBAR_MENU = "host-toolbar-menu"
    const val HOST_LASSO_MENU = "host-lasso-menu"
    const val NOTE_SUBVIEW = "note-subview"
    const val DOC_TEXT_SELECTION = "doc-text-selection"
    const val PLUGIN_HOST = "plugin-host"

    const val MOSAIC_PAD = "mosaic-pad"

    const val LEXI = "lexi"

    const val INSERT_IMAGE = "insert-image"

    const val CLIP_PASTE = "palette-apply"
    const val DOC_LASSO = "doc-lasso"
    const val DOC_STICKER = "doc-sticker-transition"

    private const val TOOLBAR_PRESS_PREFIX = "toolbar-long-press:"

    private const val PANEL_PREFIX = "panel:"

    private const val OWNED_DIALOG_PREFIX = "owned-dialog:"
    private const val PEN_SELECTION_PREFIX = "pen-selection:"
    const val PEN_LASSO_PREFIX = "pen-lasso:"

    private const val FLOW_MAX_HOLD_MS = 120_000L

    private const val PRESS_MAX_HOLD_MS = 10_000L

    private const val OWNED_DIALOG_MAX_HOLD_MS = 60_000L

    fun panel(name: String) = "$PANEL_PREFIX$name"
    fun dialog(token: Long) = "$OWNED_DIALOG_PREFIX$token"
    fun toolbarPress(toolId: String) = "$TOOLBAR_PRESS_PREFIX$toolId"
    fun penSelection(requestId: String) = "$PEN_SELECTION_PREFIX$requestId"

    fun scopeOf(owner: String): PenGuardScope = when {
        owner == HOST_BOOTSTRAP || owner == ROTATION_SYNC || owner == FOREIGN_ACTIVITY ->
            PenGuardScope.SYSTEM
        owner == HOST_TOOLBAR_MENU || owner == HOST_LASSO_MENU || owner == NOTE_SUBVIEW ||
            owner == DOC_TEXT_SELECTION || owner == PLUGIN_HOST || owner == MOSAIC_PAD || owner == LEXI ||
            owner.startsWith(OWNED_DIALOG_PREFIX) -> PenGuardScope.HOST_UI
        owner == INSERT_IMAGE || owner == CLIP_PASTE ||
            owner == DOC_LASSO || owner == DOC_STICKER -> PenGuardScope.FLOW
        owner.startsWith(TOOLBAR_PRESS_PREFIX) -> PenGuardScope.PRESS
        else -> PenGuardScope.WINDOW
    }

    fun maxHoldMs(owner: String): Long? = when {
        owner == INSERT_IMAGE || owner == CLIP_PASTE -> FLOW_MAX_HOLD_MS
        scopeOf(owner) == PenGuardScope.PRESS -> PRESS_MAX_HOLD_MS
        owner.startsWith(OWNED_DIALOG_PREFIX) -> OWNED_DIALOG_MAX_HOLD_MS
        else -> null
    }
}

object PenGuard {
    const val NOTE_APP_NAME = "note_notipal"
    const val DOC_APP_NAME = "doc_notipal"
    @Volatile private var appName = NOTE_APP_NAME
    private const val PAD_PX = 3
    private const val TAG = "FloatingPenGuard"
    private val main = Main.handler
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val pendingAppClears = ConcurrentLinkedQueue<String>()
    private val rects = linkedMapOf<String, Rect>()

    private val logicalRects = linkedMapOf<String, Rect>()
    private val views = linkedMapOf<String, WeakReference<View>>()

    private val externalLogical = linkedMapOf<String, Rect>()
    private val viewRotationEpochs = linkedMapOf<String, Long>()
    private val generation = AtomicLong()

    private data class PendingSend(
        val clear: Boolean,
        val payload: List<Rect>,
        val appName: String,
        val logLine: String,
        val attempt: Int = 1,

        val replaceTable: Boolean = false
    )
    private val pendingSend = AtomicReference<PendingSend?>(null)

    private val pendingRemovals = linkedMapOf<String, Runnable>()

    private var parkActive = false
    private val parkedKeys = linkedSetOf<String>()
    private var followerRunning = false

    private val fullScreenOwners = CopyOnWriteArraySet<String>()

    private val ownerMaxHoldTimers = HashMap<String, Runnable>()
    private var screenWidth = 1920
    private var screenHeight = 2560

    private val lastGeometry = HashMap<String, IntArray>()
    private var geometryEpoch = 0

    private val ownershipToken = "${android.os.Process.myPid()}-${System.nanoTime()}"
    @Volatile private var ownershipFile: File? = null
    @Volatile private var ownershipLost = false

    @Volatile private var ownershipCheckedModified = 0L

    fun install() {
        DialogHooks.penGuard = { token, active -> setFullScreenActive(active, PenOwner.dialog(token)) }
    }

    fun post(what: String, task: () -> Unit) {
        worker.execute {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "worker task $what failed", e)
            }
        }
    }

    fun claimOwnership(dir: File) {
        val file = File(dir, "pen_guard_owner")
        try {
            file.writeText(ownershipToken)
        } catch (e: Exception) {
            Log.e(TAG, "ownership claim failed: ${e.message}")
        }
        ownershipCheckedModified = file.lastModified()
        ownershipFile = file
        ownershipLost = false
        queueAppContextClear(NOTE_APP_NAME)
        queueAppContextClear(DOC_APP_NAME)
        markHostTableDirty()
        Log.i(TAG, "ownership claimed token=$ownershipToken app=$appName")
    }

    fun setAppContext(document: Boolean) {
        val next = if (document) DOC_APP_NAME else NOTE_APP_NAME
        if (next == appName) return
        val previous = appName
        if (document) {

            resetHostBaseline("enter document")
            pendingSend.set(null)
        }
        appName = next

        queueAppContextClear(previous)
        markHostTableDirty()
        Log.i(TAG, "pen-area app context changed: $previous -> $next")
        val hasSnapshot = synchronized(rects) {
            lastSnapshot.isNotEmpty() || rects.isNotEmpty() || fullScreenOwners.isNotEmpty()
        }
        if (hasSnapshot) submitSnapshot()
    }

    private fun queueAppContextClear(name: String) {
        pendingAppClears.add(name)
        worker.execute {
            if (isOwner() && !writesYielded) clearQueuedAppContexts()
        }
    }

    private fun clearQueuedAppContexts() {
        while (true) {
            val name = pendingAppClears.poll() ?: return
            PenDisableClient.clearDisableAreas(name)
        }
    }

    private fun isOwner(): Boolean {
        if (ownershipLost) return false
        val file = ownershipFile ?: return true
        val modified = file.lastModified()
        if (modified != 0L && modified == ownershipCheckedModified) return true
        val current = try { file.readText() } catch (_: Exception) { return true }
        if (current == ownershipToken) {
            ownershipCheckedModified = modified
            return true
        }
        ownershipLost = true
        Log.e(TAG, "ownership lost to $current; this instance stops writing pen areas")
        main.post {
            fullScreenOwners.clear()
            ownerMaxHoldTimers.values.forEach(main::removeCallbacks)
            ownerMaxHoldTimers.clear()
            main.removeCallbacks(hostBootstrapFallback)
        }
        return false
    }

    private var logicalScreenWidth = 1920
    private var logicalScreenHeight = 2560
    private var displayRotation = AndroidSurface.ROTATION_0
    private var displayStateReady = false
    private var activeRotationEpoch = 0L
    private val hostBootstrapFallback: Runnable = Runnable {
        val useLocalFallback = synchronized(rects) {
            if (hostBaselineReady) {
                false
            } else {

                hostBaselineReady = true
                true
            }
        }
        Log.i(TAG, "host bootstrap safety release localFallback=$useLocalFallback ${debugState()}")
        setFullScreenActive(false, PenOwner.HOST_BOOTSTRAP)
    }

    private fun startHostBootstrapSafety(reason: String) {
        main.removeCallbacks(hostBootstrapFallback)
        Log.i(TAG, "host bootstrap safety start reason=$reason " +
            "delayMs=${PenGuardTiming.HOST_BOOTSTRAP_FALLBACK_MS}")
        setFullScreenActive(true, PenOwner.HOST_BOOTSTRAP)
        main.postDelayed(hostBootstrapFallback, PenGuardTiming.HOST_BOOTSTRAP_FALLBACK_MS)
    }

    fun onDisplayChanged(
        logicalWidth: Int,
        logicalHeight: Int,
        rotation: Int,
        reason: String,
        rotationPending: Boolean = false
    ) {
        if (!Main.isMain) {
            main.post { onDisplayChanged(logicalWidth, logicalHeight, rotation, reason, rotationPending) }
            return
        }
        if (logicalWidth <= 0 || logicalHeight <= 0) return

        val normalizedRotation = rotation.takeIf {
            it in AndroidSurface.ROTATION_0..AndroidSurface.ROTATION_270
        } ?: AndroidSurface.ROTATION_0
        val naturalWidth = if (normalizedRotation == AndroidSurface.ROTATION_90 ||
            normalizedRotation == AndroidSurface.ROTATION_270) logicalHeight else logicalWidth
        val naturalHeight = if (normalizedRotation == AndroidSurface.ROTATION_90 ||
            normalizedRotation == AndroidSurface.ROTATION_270) logicalWidth else logicalHeight
        val changed = logicalScreenWidth != logicalWidth ||
            logicalScreenHeight != logicalHeight ||
            displayRotation != normalizedRotation ||
            !displayStateReady

        logicalScreenWidth = logicalWidth
        logicalScreenHeight = logicalHeight
        displayRotation = normalizedRotation
        screenWidth = naturalWidth
        screenHeight = naturalHeight
        displayStateReady = true
        if (changed) {

            geometryEpoch++
            generation.incrementAndGet()
            pendingSend.set(null)
            synchronized(rects) {
                hostSnapshot.clear()

                hostChromeLogical.clear()
                hostBaselineReady = false
                lastSentSnapshot.clear()
                hostTableDirty = true
            }

            if (parkedKeys.isNotEmpty()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "display change: drop parked=$parkedKeys")
                synchronized(rects) { parkedKeys.forEach { rects.remove(it); logicalRects.remove(it) } }
                parkedKeys.clear()
            }
            parkActive = false
            if (activeRotationEpoch == 0L && !rotationPending &&
                (views.isNotEmpty() || rects.isNotEmpty())) {
                startHostBootstrapSafety("display change: $reason")
            }
        }

        Log.i(TAG, "display state reason=$reason changed=$changed " +
            "logical=${logicalWidth}x${logicalHeight} rotation=$normalizedRotation " +
            "natural=${naturalWidth}x${naturalHeight} baselineReset=$changed " +
            "rotationPending=$rotationPending tracked=${views.keys}")

        views.toMap().forEach { (key, reference) ->
            reference.get()?.let { refresh(key, it) } ?: removeIfCurrent(key, reference)
        }
        if (changed && externalLogical.isNotEmpty()) {
            externalLogical.forEach { (key, logical) -> putExternalLocked(key, logical) }
            submitSnapshot()
        } else if (views.isEmpty() && fullScreenOwners.isNotEmpty() && changed) submitSnapshot()
    }

    fun setExternalArea(key: String, logical: Rect?) {
        if (!Main.isMain) {
            val copy = logical?.let(::Rect)
            main.post { setExternalArea(key, copy) }
            return
        }
        if (logical == null) {
            if (externalLogical.remove(key) == null) return
            synchronized(rects) {
                rects.remove(key)
                logicalRects.remove(key)
            }
        } else {
            if (externalLogical[key] == logical) return
            externalLogical[key] = Rect(logical)
            putExternalLocked(key, logical)
        }
        Log.i(TAG, "external area key=$key logical=$logical")
        submitSnapshot()
    }

    private fun putExternalLocked(key: String, logical: Rect) {
        val padded = Rect(logical.left - PAD_PX, logical.top - PAD_PX, logical.right + PAD_PX, logical.bottom + PAD_PX)
        val natural = PenAreaGeometry.toNaturalDisplayRect(padded, displayRotation, screenWidth, screenHeight)
        synchronized(rects) {
            logicalRects[key] = padded
            if (natural.isEmpty) rects.remove(key) else rects[key] = natural
        }
    }

    private val lastSnapshot = mutableListOf<Rect>()

    private val hostSnapshot = mutableListOf<Rect>()

    private val hostChromeLogical = mutableListOf<Rect>()

    private val lastSentSnapshot = mutableListOf<Rect>()

    private val lastQueuedSnapshot = mutableListOf<Rect>()
    private var lastQueuedAppName: String? = null

    private var hostTableDirty = true

    private var hostBaselineReady = false

    private fun markHostTableDirty() {
        synchronized(rects) { hostTableDirty = true }
    }

    @Volatile private var keyboardShown = false
    @Volatile private var keyboardExpected = false

    @Volatile private var quickSwitchOpen = false
    private val tableYielded get() = keyboardShown || keyboardExpected || quickSwitchOpen

    private val writesYielded get() = tableYielded || HostHooks.mosaicBoardVisible()

    private var ownKeyboard = false

    private val keyboardExpectTimeout = Runnable { endKeyboardExpect("no broadcast came") }

    fun expectKeyboard() {
        main.removeCallbacks(keyboardExpectTimeout)
        main.postDelayed(keyboardExpectTimeout, PenGuardTiming.KEYBOARD_EXPECT_MAX_MS)
        if (keyboardExpected) return
        keyboardExpected = true
        pendingSend.set(null)
        Log.i(TAG, "keyboard expected: table writes hold")
    }

    fun endKeyboardExpect(reason: String) {
        main.removeCallbacks(keyboardExpectTimeout)
        if (!keyboardExpected) return
        keyboardExpected = false
        Log.i(TAG, "keyboard expect over: $reason")
        if (!keyboardShown) resumeTable(reason)
    }

    fun onSystemKeyboard(shown: Boolean) {
        keyboardShown = shown
        main.removeCallbacks(keyboardExpectTimeout)
        keyboardExpected = false
        if (shown) {
            ownKeyboard = HostHooks.anyPanelShowing()

            pendingSend.set(null)
            Log.i(TAG, "keyboard shown: table left to it own=$ownKeyboard ${debugState()}")
            return
        }

        synchronized(rects) {
            lastSentSnapshot.clear()
            lastSentSnapshot.add(Rect(0, 0, screenWidth, screenHeight))
        }
        val own = ownKeyboard
        ownKeyboard = false

        if (own && !hasActiveRects() && !HostHooks.mosaicBoardVisible()) {
            markHostTableDirty()
            Log.i(TAG, "keyboard hidden, nothing of ours left: table handed back")
            submitSnapshot(keyboardLeft = true)
            return
        }
        resumeTable("keyboard hidden")
    }

    fun setQuickSwitchOpen(open: Boolean) {
        if (quickSwitchOpen == open) return
        quickSwitchOpen = open
        if (open) {

            pendingSend.set(null)
            Log.i(TAG, "quick switch open: table left to it ${debugState()}")
        } else resumeTable("quick switch closed")
    }

    private fun resumeTable(reason: String) {
        markHostTableDirty()
        if (!hasActiveRects()) {
            dropIdleGuardState(reason)
            return
        }
        Log.i(TAG, "table back from the keyboard: $reason")
        submitSnapshot()
        HostPenSignals.scheduleReassert(reason)
    }

    fun noteHostTableRewritten(): Unit = markHostTableDirty()

    fun hasActiveRects(): Boolean = synchronized(rects) {
        fullScreenOwners.isNotEmpty() || rects.isNotEmpty()
    }

    fun dropIdleGuardState(reason: String) {
        if (!Main.isMain) {
            main.post { dropIdleGuardState(reason) }
            return
        }
        synchronized(rects) {

            if (rects.isNotEmpty() || fullScreenOwners.isNotEmpty()) return
            if (lastSnapshot.isEmpty()) return
            lastSnapshot.clear()
            Log.i(TAG, "idle guard dropped reason=$reason ${debugStateLocked()}")
        }
    }

    fun beginHostBootstrap(reason: String) {
        val reused = synchronized(rects) {
            if (hostSnapshot.isEmpty()) {
                hostBaselineReady = false
                false
            } else {
                hostBaselineReady = true
                true
            }
        }
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "host bootstrap ${if (reused) "reusing baseline" else "pending"} " +
                "reason=$reason ${debugState()}")
        }
        if (reused) {
            main.removeCallbacks(hostBootstrapFallback)
            main.post {
                setFullScreenActive(false, PenOwner.HOST_BOOTSTRAP)
                submitSnapshot()
            }
        } else {
            startHostBootstrapSafety(reason)
        }
    }

    fun resetHostBaseline(reason: String) {
        synchronized(rects) {
            hostSnapshot.clear()
            hostChromeLogical.clear()
            hostBaselineReady = false
            hostTableDirty = true
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host baseline reset reason=$reason")
    }

    fun setHostChromeAreas(logical: List<Rect>, reason: String) {
        if (!Main.isMain) {
            val copy = logical.map(::Rect)
            main.post { setHostChromeAreas(copy, reason) }
            return
        }
        val next = PenAreaGeometry.normalize(logical, keepClearSentinel = false)
        val changed = synchronized(rects) {
            if (next == hostChromeLogical) false else {
                hostChromeLogical.clear()
                hostChromeLogical.addAll(next.map(::Rect))
                true
            }
        }
        if (!changed) return
        Log.i(TAG, "host chrome areas=$next reason=$reason")
        if (hasActiveRects()) submitSnapshot()
    }

    fun updateHostPenAreas(incoming: List<Rect>, commitBaseline: Boolean = true): Boolean {

        if (tableYielded) {
            markHostTableDirty()
            return false
        }
        val raw = PenAreaGeometry.normalize(incoming, keepClearSentinel = true)
        var transientFullScreen = false
        var writableReset = false
        var baselineCommitted = false
        var becameReady = false
        var baselineInvalidated = false
        val accepted = synchronized(rects) {
            if (PenAreaGeometry.sameRects(raw, lastSentSnapshot)) return@synchronized false
            hostTableDirty = true
            writableReset = PenAreaGeometry.isWritableReset(raw)
            if (writableReset) {

                hostSnapshot.clear()
                hostBaselineReady = false
                baselineInvalidated = true
            }
            val normalized = raw.filterNot(PenAreaGeometry::isClearSentinel)
            transientFullScreen = normalized.size == 1 && isScreenCovering(normalized[0])
            if (commitBaseline && !writableReset && !transientFullScreen && normalized.isNotEmpty()) {

                if (appName != DOC_APP_NAME && normalized.size == 1 &&
                    hostSnapshot.contains(normalized[0]) && hostChromeLogical.isEmpty()) {
                    val bottom = hostSnapshot.map {
                        PenAreaGeometry.toLogicalDisplayRect(it, displayRotation, screenWidth, screenHeight)
                    }.filter {
                        it.top >= logicalScreenHeight * 4 / 5 &&
                            it.bottom <= logicalScreenHeight && it.height() > 0
                    }
                    hostChromeLogical.addAll(bottom.map(::Rect))
                }
                hostSnapshot.clear()
                hostSnapshot.addAll(normalized.map(::Rect))
                baselineCommitted = true
                becameReady = !hostBaselineReady
                hostBaselineReady = true
            }
            true
        }
        if (baselineInvalidated) {

            pendingSend.set(null)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "host baseline invalidated by writable reset")
            }
        }
        if (BuildConfig.ENABLE_DEBUG) {
            val status = when {
                !accepted -> "ignored-self-echo"
                writableReset -> "accepted-writable-reset"
                transientFullScreen -> "accepted-transient-full-screen"
                !commitBaseline -> "accepted-transient-ui"
                baselineCommitted -> "accepted-baseline"
                else -> "accepted-no-baseline"
            }
            Log.i(TAG, "host snapshot $status incoming=$raw ${debugState()}")
        }
        if (becameReady) {
            main.post {

                main.removeCallbacks(hostBootstrapFallback)
                cancelMaxHold(PenOwner.HOST_BOOTSTRAP)
                fullScreenOwners.remove(PenOwner.HOST_BOOTSTRAP)
                submitSnapshot()
            }
        }
        return accepted
    }

    fun debugState(): String = synchronized(rects) { debugStateLocked() }

    private fun debugStateLocked(): String =
        "generation=${generation.get()} hostReady=$hostBaselineReady dirty=$hostTableDirty " +
            "owners=$fullScreenOwners " +
            "tracked=${rects.mapValues { Rect(it.value) }} " +
            "inkling=${lastSnapshot.map(::Rect)} host=${hostSnapshot.map(::Rect)} " +
            "chrome=${hostChromeLogical.map(::Rect)} " +
            "lastSent=${lastSentSnapshot.map(::Rect)}"

    fun reassertDocumentAreas(reason: String) {
        if (appName == DOC_APP_NAME && hasActiveRects()) reassert(reason)
    }

    private fun canPublishDocumentLocalAreas(inkling: List<Rect>): Boolean =
        appName == DOC_APP_NAME && inkling.isNotEmpty()

    fun reassert(reason: String) {
        if (writesYielded) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "reassert skipped: table yielded reason=$reason")
            return
        }
        if (!hasActiveRects()) {
            dropIdleGuardState("reassert:$reason")
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "reassert skipped: no live Inkling rects reason=$reason ${debugState()}")
            }
            return
        }
        val state = synchronized(rects) {
            val inkling = lastSnapshot.map(::Rect)
            Triple(inkling, mergeWithHost(inkling), hostBaselineReady)
        }
        val inklingSnapshot = state.first
        val outgoing = state.second
        val baselineReady = state.third
        if (inklingSnapshot.isEmpty()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "reassert skipped: empty Inkling snapshot reason=$reason ${debugState()}")
            }
            return
        }
        if (!baselineReady && !isFullScreenSnapshot(inklingSnapshot) &&
            !canPublishDocumentLocalAreas(inklingSnapshot)) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "reassert deferred: host baseline pending reason=$reason ${debugState()}")
            }
            return
        }
        val version = generation.incrementAndGet()
        Log.i(TAG, "reassert queued reason=$reason version=$version " +
            "inkling=$inklingSnapshot outgoing=$outgoing")
        enqueueSend(PendingSend(clear = false, payload = outgoing, appName = appName,
            logLine = "reassert sending reason=$reason version=$version outgoing=$outgoing"))
    }

    private fun enqueueSend(send: PendingSend) {
        synchronized(rects) {
            lastQueuedSnapshot.clear()
            lastQueuedSnapshot.addAll(send.payload.map(::Rect))
            lastQueuedAppName = send.appName
            hostTableDirty = false
        }
        pendingSend.set(send)
        worker.execute { drainPendingSend() }
    }

    private fun drainPendingSend() {
        val current = pendingSend.getAndSet(null)
        if (current == null) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "send coalesced: slot already drained")
            return
        }
        if (!isOwner()) return
        if (writesYielded) {
            markHostTableDirty()
            return
        }
        clearQueuedAppContexts()
        synchronized(rects) {
            lastSentSnapshot.clear()
            lastSentSnapshot.addAll(current.payload.map(::Rect))
        }
        Log.i(TAG, if (current.attempt == 1) current.logLine
            else "retry attempt=${current.attempt} ${current.logLine}")
        val result = try {
            when {
                current.replaceTable -> PenDisableClient.releaseDisableAreas(current.appName)
                current.clear -> PenDisableClient.clearDisableAreas(current.appName)
                else -> PenDisableClient.sendDisableAreas(current.appName, current.payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "send failed: ${e.message}")
            PenDisableClient.Result.FAILED
        }
        when (result) {
            PenDisableClient.Result.OK -> Unit
            PenDisableClient.Result.NO_SERVICE -> {
                markHostTableDirty()

                val retry = current.copy(attempt = current.attempt + 1)
                if (current.attempt < PenGuardTiming.BINDER_RETRY_ATTEMPTS &&
                    pendingSend.compareAndSet(null, retry)) {
                    worker.schedule(Runnable { drainPendingSend() },
                        PenGuardTiming.BINDER_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                } else if (current.attempt >= PenGuardTiming.BINDER_RETRY_ATTEMPTS) {
                    Log.e(TAG, "send abandoned after ${current.attempt} attempts: drawPath service unavailable")
                }
            }
            PenDisableClient.Result.FAILED -> markHostTableDirty()
        }
    }

    fun beginRotationSync(epoch: Long, reason: String) {
        if (!Main.isMain) {
            main.post { beginRotationSync(epoch, reason) }
            return
        }
        activeRotationEpoch = epoch
        main.removeCallbacks(hostBootstrapFallback)
        cancelMaxHold(PenOwner.HOST_BOOTSTRAP)
        fullScreenOwners.remove(PenOwner.HOST_BOOTSTRAP)
        viewRotationEpochs.clear()
        Log.i(TAG, "rotation enter epoch=$epoch reason=$reason")
        val changed = fullScreenOwners.add(PenOwner.ROTATION_SYNC)
        Log.i(TAG, "fullScreen=true owner=${PenOwner.ROTATION_SYNC} active=true owners=$fullScreenOwners")
        if (changed) submitSnapshot()
    }

    fun refreshAllForRotation(epoch: Long): Boolean {
        if (!Main.isMain) return false
        if (activeRotationEpoch != epoch) return false
        views.toMap().forEach { (key, reference) ->
            reference.get()?.let { refreshInternal(key, it, epoch) }
                ?: removeIfCurrent(key, reference)
        }
        val ready = views.keys.all { viewRotationEpochs[it] == epoch }
        Log.i(TAG, "views ready epoch=$epoch ready=$ready tracked=${views.keys} " +
            "viewEpochs=$viewRotationEpochs")
        return ready
    }

    fun cancelRotationSync(reason: String) {
        if (!Main.isMain) {
            main.post { cancelRotationSync(reason) }
            return
        }
        if (activeRotationEpoch == 0L && PenOwner.ROTATION_SYNC !in fullScreenOwners) return
        val epoch = activeRotationEpoch
        activeRotationEpoch = 0L
        viewRotationEpochs.clear()
        Log.i(TAG, "rotation sync end epoch=$epoch reason=$reason")
        setFullScreenActive(false, PenOwner.ROTATION_SYNC)
    }

    private fun mergeWithHost(inkling: List<Rect>): List<Rect> =
        PenAreaGeometry.normalize(
            hostSnapshot.map(::Rect) +
                hostChromeLogical.map {
                    PenAreaGeometry.toNaturalDisplayRect(it, displayRotation, screenWidth, screenHeight)
                } +
                inkling,
            keepClearSentinel = false
        )

    fun logicalToNatural(logical: Rect): Rect =
        PenAreaGeometry.toNaturalDisplayRect(logical, displayRotation, screenWidth, screenHeight)

    private fun isFullScreenSnapshot(snapshot: List<Rect>): Boolean =
        snapshot.size == 1 && isScreenCovering(snapshot[0])

    private fun isScreenCovering(rect: Rect): Boolean =
        PenAreaGeometry.isScreenCovering(rect, screenWidth, screenHeight)

    private val follower = object : Runnable {
        override fun run() {
            if (views.isEmpty()) {
                followerRunning = false
                return
            }
            views.toMap().forEach { (key, reference) ->
                reference.get()?.let { refresh(key, it) } ?: removeIfCurrent(key, reference)
            }
            main.postDelayed(this, PenGuardTiming.FOLLOW_INTERVAL_MS)
        }
    }

    fun track(key: String, view: View) {
        views[key] = WeakReference(view)
        viewRotationEpochs.remove(key)

        if (Main.isMain) {
            pendingRemovals.remove(key)?.let(main::removeCallbacks)
            parkedKeys.remove(key)
            lastGeometry.remove(key)
        } else main.post {
            pendingRemovals.remove(key)?.let(main::removeCallbacks)
            parkedKeys.remove(key)
            lastGeometry.remove(key)
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = refresh(key, v)
            override fun onViewDetachedFromWindow(v: View) = removeIfCurrent(key, v)
        }
        view.addOnAttachStateChangeListener(listener)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> refresh(key, v) }
        view.post { refresh(key, view) }
        if (!followerRunning) {
            followerRunning = true
            main.post(follower)
        }
    }

    fun refresh(key: String, view: View) = refreshInternal(key, view, null)

    private fun refreshInternal(key: String, view: View, rotationEpoch: Long?) {
        if (!Main.isMain) {
            main.post { refreshInternal(key, view, rotationEpoch) }
            return
        }
        if (views[key]?.get() !== view) return

        if (!view.isAttachedToWindow) {
            markRotationEpoch(key, rotationEpoch)
            return
        }
        if (!view.isShown) {
            releaseRect(key)
            markRotationEpoch(key, rotationEpoch)
            return
        }
        pendingRemovals.remove(key)?.let(main::removeCallbacks)
        parkedKeys.remove(key)
        val screen = IntArray(2).also(view::getLocationOnScreen)

        if (rotationEpoch == null && displayStateReady) {
            val cached = lastGeometry[key]
            if (cached != null && cached[0] == screen[0] && cached[1] == screen[1] &&
                cached[2] == view.width && cached[3] == view.height &&
                cached[4] == geometryEpoch && synchronized(rects) { rects.containsKey(key) }) {
                if (key == "toolbar") publishParkedToolbar()
                return
            }
        }

        val metrics = view.resources.displayMetrics
        val display = view.display
        val effectiveLogicalWidth = if (displayStateReady) logicalScreenWidth else metrics.widthPixels
        val effectiveLogicalHeight = if (displayStateReady) logicalScreenHeight else metrics.heightPixels
        val rotation = if (displayStateReady) displayRotation
            else display?.rotation ?: readUserRotation(view)
        val effectiveNaturalWidth = if (displayStateReady) screenWidth else metrics.widthPixels
        val effectiveNaturalHeight = if (displayStateReady) screenHeight else metrics.heightPixels
        if (!displayStateReady) {
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        if (view.width <= 0 || view.height <= 0) return

        val logicalRect = Rect(
            (screen[0] - PAD_PX).coerceAtLeast(0),
            (screen[1] - PAD_PX).coerceAtLeast(0),
            (screen[0] + view.width + PAD_PX).coerceAtMost(effectiveLogicalWidth),
            (screen[1] + view.height + PAD_PX).coerceAtMost(effectiveLogicalHeight)
        )
        if (logicalRect.isEmpty) {
            markRotationEpoch(key, rotationEpoch)
            return
        }
        val drawPathRect = PenAreaGeometry.toNaturalDisplayRect(
            logicalRect,
            rotation,
            effectiveNaturalWidth,
            effectiveNaturalHeight
        )
        if (drawPathRect.isEmpty) {
            markRotationEpoch(key, rotationEpoch)
            return
        }
        if (views[key]?.get() !== view) return
        val changed = synchronized(rects) {
            logicalRects[key] = Rect(logicalRect)
            if (rects[key] == drawPathRect) false else {
                rects[key] = Rect(drawPathRect)
                true
            }
        }
        lastGeometry[key] = intArrayOf(screen[0], screen[1], view.width, view.height, geometryEpoch)
        markRotationEpoch(key, rotationEpoch)

        if (key == "toolbar") HostHooks.publishToolbarRect(logicalRect, false)
        if (!changed) return
        if (BuildConfig.ENABLE_DEBUG) {
            val window = IntArray(2).also(view::getLocationInWindow)
            Log.i(TAG, "refresh key=$key screen=${screen[0]},${screen[1]} " +
                "window=${window[0]},${window[1]} size=${view.width}x${view.height} " +
                "logicalDisplay=${effectiveLogicalWidth}x${effectiveLogicalHeight} " +
                "naturalDisplay=${effectiveNaturalWidth}x${effectiveNaturalHeight} " +
                "displayRotation=${display?.rotation} drawPathRotation=$rotation " +
                "logicalRect=$logicalRect drawPathRect=$drawPathRect")
        }
        submitSnapshot()
    }

    private fun markRotationEpoch(key: String, rotationEpoch: Long?) {
        if (rotationEpoch != null && activeRotationEpoch == rotationEpoch) viewRotationEpochs[key] = rotationEpoch
    }

    private fun readUserRotation(view: View): Int = try {
        Settings.System.getInt(
            view.context.contentResolver,
            Settings.System.USER_ROTATION,
            AndroidSurface.ROTATION_0
        )
    } catch (_: Exception) {
        AndroidSurface.ROTATION_0
    }

    private fun publishParkedToolbar() {
        val rect = synchronized(rects) { logicalRects["toolbar"]?.let(::Rect) } ?: return
        HostHooks.publishToolbarRect(rect, false)
    }

    fun remove(key: String) {
        if (!Main.isMain) {
            main.post { remove(key) }
            return
        }
        views.remove(key)
        viewRotationEpochs.remove(key)
        lastGeometry.remove(key)
        releaseRect(key)
    }

    private fun releaseRect(key: String) {
        if (key == "toolbar") HostHooks.publishToolbarRect(null, false)
        if (!synchronized(rects) { rects.containsKey(key) }) return
        if (parkActive) {
            pendingRemovals.remove(key)?.let(main::removeCallbacks)
            parkedKeys.add(key)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "remove key=$key parked")
            return
        }
        if (pendingRemovals.containsKey(key)) return
        val r = Runnable {
            pendingRemovals.remove(key)
            val changed = synchronized(rects) {
                logicalRects.remove(key)
                rects.remove(key) != null
            }
            if (!changed) return@Runnable
            Log.i(TAG, "remove key=$key")
            submitSnapshot()
        }
        pendingRemovals[key] = r
        main.postDelayed(r, PenGuardTiming.REMOVE_LINGER_MS)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "remove key=$key linger=${PenGuardTiming.REMOVE_LINGER_MS}ms")
        }
    }

    fun beginPark(reason: String) {
        if (!Main.isMain) {
            main.post { beginPark(reason) }
            return
        }
        if (!parkActive && BuildConfig.ENABLE_DEBUG) Log.i(TAG, "park begin reason=$reason")
        parkActive = true

        for ((key, r) in pendingRemovals) { main.removeCallbacks(r); parkedKeys.add(key) }
        pendingRemovals.clear()
    }

    fun endPark(reason: String, flush: Boolean = false) {
        if (!Main.isMain) {
            main.post { endPark(reason, flush) }
            return
        }
        if (!parkActive && parkedKeys.isEmpty()) return
        parkActive = false
        val released = parkedKeys.filter { flush || views[it]?.get() == null }
        parkedKeys.clear()
        if (released.isEmpty()) return
        val changed = synchronized(rects) {
            released.count { rects.remove(it).also { _ -> logicalRects.remove(it) } != null }
        } > 0
        Log.i(TAG, "park end reason=$reason flush=$flush released=$released")
        if (changed) submitSnapshot()
    }

    private fun removeIfCurrent(key: String, view: View) {
        if (!Main.isMain) {
            main.post { removeIfCurrent(key, view) }
            return
        }
        if (views[key]?.get() !== view) return
        remove(key)
    }

    private fun removeIfCurrent(key: String, reference: WeakReference<View>) {
        if (!Main.isMain) {
            main.post { removeIfCurrent(key, reference) }
            return
        }
        if (views[key] !== reference) return
        remove(key)
    }

    fun setFullScreenActive(active: Boolean, owner: String) {
        if (!Main.isMain) {
            main.post { setFullScreenActive(active, owner) }
            return
        }
        if (active) armMaxHold(owner) else cancelMaxHold(owner)
        val changed = if (active) fullScreenOwners.add(owner) else fullScreenOwners.remove(owner)
        if (!changed) return
        Log.i(TAG, "fullScreen=${fullScreenOwners.isNotEmpty()} owner=$owner active=$active owners=$fullScreenOwners")

        submitSnapshot(restoringHost = !active &&
            (owner == PenOwner.PLUGIN_HOST || owner == PenOwner.MOSAIC_PAD || owner == PenOwner.LEXI))
    }

    fun releaseScopes(reason: String, vararg scopes: PenGuardScope) {
        if (!Main.isMain) {
            val copy = scopes.copyOf()
            main.post { releaseScopes(reason, *copy) }
            return
        }
        val released = fullScreenOwners.filter { PenOwner.scopeOf(it) in scopes }
        if (released.isEmpty()) return
        released.forEach {
            cancelMaxHold(it)
            fullScreenOwners.remove(it)
        }
        Log.i(TAG, "fullScreen=${fullScreenOwners.isNotEmpty()} released=$released " +
            "scopes=${scopes.joinToString()} reason=$reason owners=$fullScreenOwners")
        submitSnapshot()
    }

    private fun armMaxHold(owner: String) {
        val limit = PenOwner.maxHoldMs(owner) ?: return
        ownerMaxHoldTimers.remove(owner)?.let(main::removeCallbacks)
        val timer = Runnable {
            ownerMaxHoldTimers.remove(owner)
            if (owner !in fullScreenOwners) return@Runnable
            Log.w(TAG, "fullScreen owner=$owner exceeded max hold ${limit}ms; releasing")
            setFullScreenActive(false, owner)
        }
        ownerMaxHoldTimers[owner] = timer
        main.postDelayed(timer, limit)
    }

    private fun cancelMaxHold(owner: String) {
        ownerMaxHoldTimers.remove(owner)?.let(main::removeCallbacks)
    }

    private fun publishOverlayRectsToMosaic(force: Boolean = false) {
        val out = synchronized(rects) {
            val hasFullScreen = fullScreenOwners.any { it != PenOwner.ROTATION_SYNC }
            if (hasFullScreen) {
                listOf(Rect(0, 0, logicalScreenWidth, logicalScreenHeight))
            } else {
                logicalRects.entries
                    .filter { it.key != "toolbar" }
                    .map { Rect(it.value) }
                    .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
            }
        }
        HostHooks.publishOverlayRects(out, force)
    }

    fun onMosaicHidden(reason: String) {
        markHostTableDirty()
        if (hasActiveRects()) reassert("mosaic hidden:$reason")
    }

    fun publishToLexi() {
        if (!HostHooks.lexiSmallOpen()) return
        val out = synchronized(rects) {
            val hasFullScreen = fullScreenOwners.any { it != PenOwner.ROTATION_SYNC && it != PenOwner.LEXI }
            if (hasFullScreen) {
                listOf(Rect(0, 0, logicalScreenWidth, logicalScreenHeight))
            } else {
                logicalRects.entries
                    .filter { !it.key.startsWith("ext:") }
                    .map { Rect(it.value) }
                    .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
            }
        }
        HostHooks.publishAreasToLexi(out)
    }

    fun republishMosaicSnapshot(reason: String) {
        if (!Main.isMain) {
            main.post { republishMosaicSnapshot(reason) }
            return
        }
        if (!HostHooks.mosaicBoardVisible()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "Mosaic snapshot republish skipped: board hidden reason=$reason")
            }
            return
        }

        val toolbarRect = synchronized(rects) {
            logicalRects["toolbar"]?.let(::Rect)
        }
        HostHooks.publishToolbarRect(toolbarRect, true)
        publishOverlayRectsToMosaic(force = true)
        Log.i(TAG, "Mosaic snapshot republished reason=$reason toolbar=$toolbarRect")
    }

    private fun submitSnapshot(restoringHost: Boolean = false, keyboardLeft: Boolean = false) {
        val state = synchronized(rects) {

            val hadFullScreen = isFullScreenSnapshot(lastSnapshot) ||
                lastSentSnapshot.any(::isScreenCovering)
            val inkling = if (fullScreenOwners.isNotEmpty()) {
                listOf(Rect(0, 0, screenWidth, screenHeight))
            } else {
                PenAreaGeometry.normalize(rects.values.map(::Rect), keepClearSentinel = false)
            }
            lastSnapshot.clear()
            lastSnapshot.addAll(inkling.map(::Rect))
            Pair(
                Triple(inkling, mergeWithHost(inkling), hostBaselineReady),
                hadFullScreen
            )
        }

        if (HostHooks.mosaicBoardVisible()) publishOverlayRectsToMosaic()
        publishToLexi()

        if (writesYielded) {
            markHostTableDirty()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "snapshot held: table yielded inkling=${state.first.first}")
            return
        }

        val inklingSnapshot = state.first.first
        val outgoing = state.first.second
        val baselineReady = state.first.third
        val leavingFullScreen = !isFullScreenSnapshot(inklingSnapshot) && state.second
        if (restoringHost && !isFullScreenSnapshot(inklingSnapshot) &&
            (inklingSnapshot.isEmpty() || !baselineReady)) {

            pendingSend.set(null)
            synchronized(rects) {
                lastQueuedSnapshot.clear()
                lastSentSnapshot.clear()
                hostTableDirty = true
            }
            Log.i(TAG, "host restore handoff inkling=$inklingSnapshot baselineReady=$baselineReady")
            return
        }
        if (!baselineReady && !isFullScreenSnapshot(inklingSnapshot) && !leavingFullScreen &&
            !canPublishDocumentLocalAreas(inklingSnapshot)) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "snapshot deferred: host baseline pending " +
                    "inkling=$inklingSnapshot outgoing=$outgoing ${debugState()}")
            }
            return
        }

        val clear = outgoing.isEmpty()

        val replaceTable = clear && (baselineReady || keyboardLeft)
        val payload = if (clear) listOf(PenAreaGeometry.clearSentinel()) else outgoing
        val sendAppName = appName

        val duplicate = synchronized(rects) {
            !hostTableDirty && lastQueuedAppName == sendAppName &&
                PenAreaGeometry.sameRects(payload, lastQueuedSnapshot)
        }
        if (duplicate) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "snapshot unchanged, send skipped outgoing=$outgoing")
            }
            return
        }
        val version = generation.incrementAndGet()
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "snapshot queued version=$version inkling=$inklingSnapshot " +
                "outgoing=$outgoing leavingFullScreen=$leavingFullScreen")
        }
        val mode = when {
            leavingFullScreen && !clear -> "exit-full-screen-local"
            !baselineReady && canPublishDocumentLocalAreas(inklingSnapshot) -> "document-local"
            outgoing.isEmpty() -> "clear"
            inklingSnapshot.isEmpty() -> "host-only"
            else -> "merged"
        }
        enqueueSend(PendingSend(clear = clear, payload = payload, appName = sendAppName,
            logLine = "snapshot sending version=$version mode=$mode replaceTable=$replaceTable " +
                "inkling=$inklingSnapshot outgoing=$outgoing payload=$payload",
            replaceTable = replaceTable))
    }
}

object HostPenSignals {
    private const val TAG = "HostPenSignals"
    private val main = Main.handler

    private fun onMain(block: () -> Unit) {
        if (Main.isMain) block() else main.post(block)
    }

    @Volatile private var pendingReassertReason = "unknown"

    private val reassertRunnable: Runnable = Runnable {
        val reason = pendingReassertReason
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "guard reassert fired reason=$reason active=${PenGuard.hasActiveRects()} " +
                "guard=${PenGuard.debugState()}")
        }
        PenGuard.reassert(reason)
    }

    private val foregroundFollowupRunnable: Runnable = Runnable {
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "guard reassert followup fired guard=${PenGuard.debugState()}")
        }
        PenGuard.reassert("foreground change followup")
    }

    fun scheduleReassert(
        reason: String,
        allowEmptyAtSchedule: Boolean = false,
        delayMs: Long = PenGuardTiming.GUARD_REASSERT_DELAY_MS
    ) {
        val active = PenGuard.hasActiveRects()
        if (!active && !allowEmptyAtSchedule) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "guard reassert not scheduled: no active rects reason=$reason " +
                    "guard=${PenGuard.debugState()}")
            }
            return
        }
        pendingReassertReason = reason
        main.removeCallbacks(reassertRunnable)
        main.postDelayed(reassertRunnable, delayMs)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "guard reassert scheduled reason=$reason active=$active " +
                "allowEmpty=$allowEmptyAtSchedule delayMs=$delayMs " +
                "guard=${PenGuard.debugState()}")
        }
    }

    fun scheduleForegroundReassert(fgKey: String) {
        if (!PenGuard.hasActiveRects()) return
        scheduleReassert(
            reason = "foreground changed: $fgKey",
            delayMs = PenGuardTiming.HOST_SNAPSHOT_REASSERT_DELAY_MS
        )
        main.removeCallbacks(foregroundFollowupRunnable)
        main.postDelayed(foregroundFollowupRunnable, PenGuardTiming.FOREGROUND_REASSERT_FOLLOWUP_MS)
    }

    fun cancelScheduledReasserts() {
        main.removeCallbacks(reassertRunnable)
        main.removeCallbacks(foregroundFollowupRunnable)
        main.removeCallbacks(docWritableFollowupRunnable)
    }

    @Volatile private var lassoWritableResetPending = false

    fun clearLassoWritableReset() {
        lassoWritableResetPending = false
    }

    fun onNativePenAreas(
        rects: List<Rect>,
        rotationActive: Boolean,
        onInsertPlacementSettled: () -> Unit
    ) {
        val isWritableReset = PenAreaGeometry.isWritableReset(rects)
        val isFullScreenSnapshot = PenAreaGeometry.isHostFullScreenSnapshot(rects)
        if (isWritableReset && HostLogMonitor.isLassoMenuOpen()) {
            lassoWritableResetPending = true
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "lasso close candidate: writable reset observed")
            }
        } else if (lassoWritableResetPending && !isFullScreenSnapshot) {

            lassoWritableResetPending = false
            HostLogMonitor.closeLassoMenuFromFallback("writable reset completed")
        }

        val transientHostUi = HostLogMonitor.isToolbarMenuOpen() ||
            HostLogMonitor.isLassoMenuOpen() ||
            HostLogMonitor.isSubviewOpen()
        val accepted = PenGuard.updateHostPenAreas(
            incoming = rects,
            commitBaseline = !transientHostUi && !rotationActive
        )
        if (rotationActive) {

            if (accepted && isWritableReset) {
                rotationBaselineCandidate = null
            } else if (accepted && !isFullScreenSnapshot && !transientHostUi && rects.isNotEmpty()) {
                rotationBaselineCandidate = rects.map(::Rect)
            }
        } else {
            rotationBaselineCandidate = null
        }
        val guardActive = PenGuard.hasActiveRects()
        if (accepted && !isWritableReset && !isFullScreenSnapshot && !transientHostUi && !rotationActive) {
            HostHooks.requestChromeProbe("host native pen snapshot")
        }
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "host native pen snapshot count=${rects.size} rects=$rects " +
                "accepted=$accepted transientUi=$transientHostUi " +
                "writableReset=$isWritableReset guardActive=$guardActive " +
                "guard=${PenGuard.debugState()}")
        }

        if (rotationActive) {

            if (accepted) {
                PenGuard.reassert("rotation full-screen hold")
                scheduleReassert(
                    reason = "rotation full-screen hold followup",
                    delayMs = PenGuardTiming.HOST_SNAPSHOT_REASSERT_DELAY_MS
                )
            }
        } else if (accepted && guardActive && !isWritableReset) {

            PenGuard.reassert("host native pen snapshot immediate")
            scheduleReassert(
                reason = "host native pen snapshot",
                delayMs = PenGuardTiming.HOST_SNAPSHOT_REASSERT_DELAY_MS
            )
        } else if (accepted && isWritableReset) {

            cancelScheduledReasserts()
            if (!guardActive) {
                PenGuard.dropIdleGuardState("writable reset, no live rects")
            } else {
                PenGuard.reassertDocumentAreas("document writable reset")
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "writable reset: stale reassert cancelled; document local areas republished when active")
            }
        }

        if (insertImageFlowActive && !HostLogMonitor.isLassoMenuOpen()) {
            if (isWritableReset) {
                insertPlacementResetSeen = true
            } else if (insertPlacementResetSeen && accepted &&
                !isFullScreenSnapshot && rects.isNotEmpty()) {
                main.post {
                    if (!insertImageFlowActive) return@post
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "insert placement settled (reset→baseline fallback)")
                    }
                    endInsertImageGuard()
                    onInsertPlacementSettled()
                }
            }
        }
    }

    fun onHostTableRebuilt(rotationActive: Boolean) {
        PenGuard.noteHostTableRewritten()
        if (!rotationActive) scheduleReassert("host pen table rebuild")
        else {
            PenGuard.reassert("rotation full-screen hold (host rebuild)")
            scheduleReassert("rotation full-screen hold (host rebuild) followup")
        }
    }

    @Volatile private var rotationBaselineCandidate: List<Rect>? = null

    fun onRotationSettled() {
        val candidate = rotationBaselineCandidate ?: return
        rotationBaselineCandidate = null
        val accepted = PenGuard.updateHostPenAreas(candidate, commitBaseline = true)
        Log.i(TAG, "rotation settled: host table from the sync as baseline accepted=$accepted rects=$candidate")
    }

    fun resetRotationBaseline() {
        rotationBaselineCandidate = null
    }

    private val lassoMenuReleaseRunnable: Runnable = Runnable {
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "host lasso menu full-screen release after grace")
        }
        PenGuard.setFullScreenActive(false, PenOwner.HOST_LASSO_MENU)
    }

    fun scheduleLassoMenuRelease() {
        main.removeCallbacks(lassoMenuReleaseRunnable)
        main.postDelayed(lassoMenuReleaseRunnable, PenGuardTiming.LASSO_RELEASE_GRACE_MS)
    }

    fun cancelLassoMenuRelease() {
        main.removeCallbacks(lassoMenuReleaseRunnable)
    }

    @Volatile var insertImageFlowActive = false
        private set

    @Volatile private var insertPlacementResetSeen = false

    fun beginInsertImageGuard() {
        PenGuard.setFullScreenActive(true, PenOwner.INSERT_IMAGE)
        insertImageFlowActive = true
        insertPlacementResetSeen = false
    }

    fun endInsertImageGuard() {
        if (!insertImageFlowActive) return
        insertImageFlowActive = false
        insertPlacementResetSeen = false
        PenGuard.setFullScreenActive(false, PenOwner.INSERT_IMAGE)
    }

    @Volatile private var docLassoHoldArmedAt = 0L
    @Volatile private var docStickerHoldArmedAt = 0L

    private val docStickerHoldTimeoutRunnable: Runnable = Runnable { releaseDocStickerHold("timeout") }

    fun armDocLassoHold(reason: String, confirmed: Boolean = false): Unit = onMain {
        if (!confirmed) return@onMain
        docLassoHoldArmedAt = SystemClock.elapsedRealtime()
        PenGuard.setFullScreenActive(true, PenOwner.DOC_LASSO)
        PenGuard.reassert("doc selection confirmed")
        Log.i(TAG, "doc lasso hold armed reason=$reason confirmed=$confirmed")
    }

    fun releaseDocLassoHold(reason: String): Unit = onMain {
        if (docLassoHoldArmedAt == 0L) return@onMain
        docLassoHoldArmedAt = 0L
        PenGuard.setFullScreenActive(false, PenOwner.DOC_LASSO)
        Log.i(TAG, "doc lasso hold released reason=$reason")
    }

    fun onStickerInserted(document: Boolean): Unit = onMain {
        if (document) armDocStickerHold("sticker inserted (doc)")
    }

    private fun armDocStickerHold(reason: String) {
        docStickerHoldArmedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(docStickerHoldTimeoutRunnable)
        main.postDelayed(docStickerHoldTimeoutRunnable, PenGuardTiming.DOC_STICKER_HOLD_MAX_MS)
        PenGuard.setFullScreenActive(true, PenOwner.DOC_STICKER)
        Log.i(TAG, "doc sticker hold armed reason=$reason")
    }

    fun releaseDocStickerHold(reason: String): Unit = onMain {
        if (docStickerHoldArmedAt == 0L) return@onMain
        docStickerHoldArmedAt = 0L
        main.removeCallbacks(docStickerHoldTimeoutRunnable)
        PenGuard.setFullScreenActive(false, PenOwner.DOC_STICKER)
        Log.i(TAG, "doc sticker hold released reason=$reason")
    }

    fun onDocWriteAreaRebuilt(): Unit = onMain {
        HostHooks.requestChromeProbe("document write-area rebuilt")
        if (docLassoHoldArmedAt != 0L || docStickerHoldArmedAt != 0L) {
            PenGuard.reassert("doc selection write-area rebuilt")
        } else {
            PenGuard.reassertDocumentAreas("document write-area rebuilt")
        }
    }

    private val docWritableFollowupRunnable: Runnable = Runnable {
        PenGuard.reassertDocumentAreas("document writable followup")
    }

    fun onDocWritableEnabled(): Unit = onMain {
        PenGuard.reassertDocumentAreas("document writable enabled")
        main.removeCallbacks(docWritableFollowupRunnable)
        main.postDelayed(docWritableFollowupRunnable, PenGuardTiming.DOC_WRITABLE_REASSERT_FOLLOWUP_MS)
    }

    fun resetForMonitorStop(reason: String) {
        cancelScheduledReasserts()
        cancelLassoMenuRelease()
        lassoWritableResetPending = false
        endInsertImageGuard()
        PenGuard.setFullScreenActive(false, PenOwner.CLIP_PASTE)
        releaseDocStickerHold(reason)
        releaseDocLassoHold(reason)
    }
}

object SystemKeyboard {
    private const val TAG = "SystemKeyboard"
    private const val ACTION_SHOW = "keyboard_show"
    private const val ACTION_HIDE = "keyboard_hide"
    private const val PACKAGE = "com.android.inputmethod.keyboard"

    private const val STILL_THERE_CHECK_MS = 3_000L
    private val LOG_PID = Regex("""^\w/[^(]+\(\s*(\d+)\)""")

    private val main = Main.handler
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val keyboardPids = ConcurrentHashMap<Int, Boolean>()
    private var receiver: BroadcastReceiver? = null
    @Volatile private var appContext: Context? = null

    @Volatile var shown = false
        private set

    fun ensureRegistered(context: Context) {
        if (!Main.isMain) {
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
        PenGuard.onSystemKeyboard(true)
        listeners.forEach { it(true) }
        main.removeCallbacks(stillThere)
        main.postDelayed(stillThere, STILL_THERE_CHECK_MS)
    }

    private fun onHidden(why: String) {
        main.removeCallbacks(stillThere)
        if (!shown) return
        shown = false
        Log.i(TAG, "keyboard hidden ($why)")
        PenGuard.onSystemKeyboard(false)
        listeners.forEach { it(false) }
    }

    private val stillThere: Runnable = Runnable {
        val context = appContext

        if (context != null && imeVisibleHeight(context) == 0) onHidden("no IME window")
        else if (shown) main.postDelayed(stillThereAgain, STILL_THERE_CHECK_MS)
    }
    private val stillThereAgain: Runnable = Runnable { stillThere.run() }

    private val visibleHeightMethod by lazy {
        runCatching { InputMethodManager::class.java.getMethod("getInputMethodWindowVisibleHeight") }.getOrNull()
    }

    fun imeVisibleHeight(context: Context): Int {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return -1
        return runCatching { visibleHeightMethod?.invoke(imm) as? Int }.getOrNull() ?: -1
    }

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
