package me.laumss.notipal.image

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Bitmaps
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostLogMonitor
import me.laumss.notipal.host.HostNotice
import me.laumss.notipal.host.HostPenSignals
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.HostSignal
import me.laumss.notipal.host.LassoHolds
import me.laumss.notipal.host.MonitorPhase
import me.laumss.notipal.host.Surface
import me.laumss.notipal.lasso.LassoHooks
import me.laumss.notipal.links.DocLinks
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.toolbar.InsertTxns
import me.laumss.notipal.toolbar.SelectionHold
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.ToolTap
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.Panels
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

object ImageHooks {

    @Volatile var documentTap: ((ToolTap) -> Unit)? = null

    @Volatile var pendingScreenshot: () -> File? = { null }

    @Volatile var screenshotUsed: (fileName: String) -> Unit = {}

    @Volatile var crop: ((path: String, onResult: (CropResult) -> Unit) -> Unit)? = null
}

data class CropResult(val offsetX: Int, val offsetY: Int, val width: Int, val height: Int, val filter: Int, val density: Int)

internal object ImageStaging {
    private const val TAG = "ImageStaging"
    private const val PREFIX = "image_insert_"
    private const val KEEP_MS = 10 * 60 * 1000L

    fun newFile(): File {
        val dir = Dirs.hostRender.apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - KEEP_MS
        (dir.listFiles() ?: emptyArray()).filter { isSwept(it.name) && it.lastModified() < cutoff }.forEach {
            if (!it.delete()) Log.w(TAG, "stale staging file not deleted: ${it.path}")
        }

        return File.createTempFile(PREFIX, ".png", dir)
    }

    private fun isSwept(name: String): Boolean =
        name.startsWith(PREFIX) || name.startsWith("picker_") || name.startsWith("attach_") || name.endsWith(".host.png")

    fun prepare(path: String): String {
        val source = File(path)
        if (!source.isFile) throw IOException("Image file is missing: $path")
        val name = source.name.lowercase(Locale.ROOT)
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) return path
        val bitmap = BitmapFactory.decodeFile(path) ?: throw IOException("Image could not be decoded: $path")
        val converted = newFile()
        var complete = false
        try {
            FileOutputStream(converted).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Image conversion failed: $path")
            }
            complete = true
            return converted.absolutePath
        } finally {
            bitmap.recycle()
            if (!complete) converted.delete()
        }
    }
}

object ImageInsert {
    private const val TAG = "ImageInsert"

    const val TXN = "insert_image"

    private const val INSERT_IMAGE_WATCHDOG_MS = 30_000L

    private const val MULTI_INSERT_AWAIT_MAX_MS = 15_000L

    private val EMIT_DELAYS_MS = longArrayOf(0, 300, 750)

    private const val MULTI_START_DELAY_MS = 300L

    private const val INSERT_NEXT_DELAY_MS = 500L

    private const val ROUTE_STUCK_MS = 15_000L

    private const val HOST_FLASH_ACTION = "com.ratta.supernote.launcher.flashscreen"
    private const val HOST_FLASH_DELAY_MS = 500L

    private const val REASON_ROUTE = "image-route"

    private const val SOURCE_INSERT = "inkling-insert-image"
    private const val SOURCE_SCREENSHOT = "inkling-doc-screenshot"

    data class Item(val path: String, val deletePath: String? = null, val fromQueue: Boolean = false)

    private class Request(val id: String, val item: Item)

    @Volatile private var module: ImageModule? = null
    private var installed = false

    private val queue = ArrayDeque<String>()
    private val items = HashMap<String, Item>()
    @Volatile private var queued = 0

    @Volatile private var awaitingSelection = false

    private var active: Request? = null

    val idle: Boolean get() = active == null && queue.isEmpty()

    private val pending = LinkedHashMap<String, Item>()
    private const val PENDING_KEEP = 16

    private var advanceAfterResult = false

    private var guardHeldForResult = false

    private var requestGen = 0L

    private var routePending = false

    private var routeStartedAt = 0L

    @Volatile private var flashArmed = false

    @Volatile private var commitPending = false

    private val awaitTimeout = Runnable {
        if (!awaitingSelection) return@Runnable
        Log.w(TAG, "multi insert: selection never shown; abandon remaining=${queue.size}")
        clearChain("selection timeout")
        HostPenSignals.endInsertImageGuard()
        scheduleRestore("multi insert timeout")
    }

    private val flashRunnable = Runnable { sendHostFlash() }

    fun install() {
        LassoHolds.awaitingNextSelection = { awaitingSelection }
        SelectionHold.addHolder("multi-insert") { queued > 0 }
        LassoHooks.openImagePanel = { ImagePicker.open() }
        if (installed) return
        installed = true
        HostSession.addNoticeListener { notice ->
            when (notice) {
                HostNotice.LassoShown -> onSelectionShown()
                is HostNotice.LassoCancelled -> onLassoCancelled(notice.reason)
                HostNotice.InsertPlacementSettled -> onPlacementSettled()
                is HostNotice.Monitor -> if (notice.phase == MonitorPhase.STOPPING) onMonitorStopping()
                else -> Unit
            }
        }
        HostLogMonitor.addListener { signal ->
            if (signal !is HostSignal.PluginImageCommitted) return@addListener
            Main.post {
                if (HostLogMonitor.isCurrentGeneration(signal.generation)) onImageCommitted()
            }
        }

        InsertTxns.addEndListener { name, ok, reason ->
            if (name != TXN || ok) return@addEndListener
            if (reason == "timeout" || reason.startsWith("superseded")) onTxnLost(reason)
        }
    }

    internal fun attach(owner: ImageModule) {
        module = owner
    }

    internal fun detach(owner: ImageModule) {
        if (module === owner) module = null
    }

    internal fun onToolTap(tap: ToolTap) {
        if (tap.host.surface == Surface.DOCUMENT) {
            val hook = ImageHooks.documentTap
            if (hook == null) {
                Log.w(TAG, "document capture not available yet")
                Dialogs.tip(Strings.get("feature_not_ready"))
                Toolbar.restore("document capture unavailable")
                return
            }
            hook(tap)
            return
        }

        if (routePending && SystemClock.uptimeMillis() - routeStartedAt > ROUTE_STUCK_MS) {
            Log.w(TAG, "route was still pending after ${SystemClock.uptimeMillis() - routeStartedAt}ms; starting over")
            endRoute()
        }
        if (routePending || active != null) {
            Log.i(TAG, "tap ignored: route=$routePending active=${active?.id}")
            return
        }
        routePending = true
        routeStartedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "[INSERT] doc screenshot route pending=true")

        if (!MosaicLink.boardVisible) HostPenSignals.beginInsertImageGuard()
        Toolbar.yieldFor(REASON_ROUTE)
        thread(isDaemon = true, name = "ImageInsert-route") {

            PluginIdentity.claim()
            val next = try {
                ImageHooks.pendingScreenshot()
            } catch (e: Exception) {
                Log.w(TAG, "pending screenshot lookup failed", e)
                null
            }
            Main.post { routeTo(next) }
        }
    }

    private fun routeTo(next: File?) {
        if (next != null) {
            val path = next.absolutePath
            Log.i(TAG, "[INSERT] route: insert next=$path")
            if (MosaicLink.boardVisible) {

                enqueueToMosaic(path, SOURCE_SCREENSHOT, null, fromQueue = true) { ok ->
                    endRoute()
                    if (!ok) Dialogs.tip(Strings.get("image_insert_failed"))
                }
                return
            }
            Main.handler.postDelayed({
                try {
                    request(Item(path, fromQueue = true))
                } catch (e: Exception) {
                    Log.e(TAG, "insert next failed", e)
                    HostPenSignals.endInsertImageGuard()
                    Toolbar.restore("insert next failed")
                }
                endRoute()
            }, INSERT_NEXT_DELAY_MS)
            return
        }
        Log.i(TAG, "[INSERT] route: no insert next, opening picker")
        ImagePicker.open { shown ->
            endRoute()
            if (shown && HostLogMonitor.isSubviewOpen()) ImagePicker.suspendCurrent()
            HostPenSignals.endInsertImageGuard()
            Log.i(TAG, "[INSERT] route picker settled shown=$shown subview=${HostLogMonitor.isSubviewOpen()}")
            if (!shown) scheduleRestore("image panel open failed")
        }
    }

    private fun endRoute() {
        if (!routePending) return
        routePending = false
        Toolbar.resume(REASON_ROUTE)
    }

    internal fun onToolLongPress(tap: ToolTap) {
        if (tap.host.surface == Surface.DOCUMENT) return
        ImagePicker.open()
    }

    fun request(item: Item) {
        if (!Main.isMain) {
            Main.post { request(item) }
            return
        }
        Log.i(TAG, "[INSERT] request: ${item.path}")

        if (MosaicLink.boardVisible) {
            enqueueToMosaic(item.path, if (item.fromQueue) SOURCE_SCREENSHOT else SOURCE_INSERT, item.deletePath, item.fromQueue) { ok ->
                if (!ok) Dialogs.tip(Strings.get("image_insert_failed"))
            }
            return
        }
        HostPenSignals.beginInsertImageGuard()

        guardHeldForResult = false
        flashArmed = true
        val owner = module
        if (owner == null || App.react?.hasActiveReactInstance() != true) {
            abort("image insertion: no React module", showTip = true)
            return
        }
        val req = Request(System.nanoTime().toString(), item)
        active = req
        pending[req.id] = item
        while (pending.size > PENDING_KEEP) pending.remove(pending.keys.first())
        val gen = ++requestGen
        Main.handler.postDelayed({
            if (gen != requestGen) return@postDelayed
            if (active !== req && !HostPenSignals.insertImageFlowActive) return@postDelayed
            abort("image insertion watchdog: ${item.path}", showTip = false)
        }, INSERT_IMAGE_WATCHDOG_MS)
        for (delay in EMIT_DELAYS_MS) {
            Main.handler.postDelayed({
                if (active !== req) return@postDelayed
                Log.i(TAG, "[INSERT] emit image:insert (delay=${delay}ms)")
                (module ?: owner).insertRequested(req.id, item.path)
            }, delay)
        }
    }

    fun startMulti(list: List<Item>) {
        if (list.isEmpty()) return
        if (MosaicLink.boardVisible) {
            thread(isDaemon = true, name = "ImageInsert-mosaic") {

                PluginIdentity.claim()
                for (item in list) {
                    val ok = MosaicLink.enqueueImage(item.path, SOURCE_INSERT)
                    Log.i(TAG, "[INSERT] multi enqueue to Mosaic ok=$ok path=${item.path}")
                    if (!ok) continue
                    cleanUpInserted(item)
                }
                Main.post { Toolbar.restore("mosaic multi insert done") }
            }
            return
        }
        clearChain("new multi insert")
        list.forEach { items[it.path] = it }
        queue.addAll(list.drop(1).map { it.path })
        queued = queue.size
        HostPenSignals.beginInsertImageGuard()
        Main.handler.postDelayed({ insertChained(list.first().path) }, MULTI_START_DELAY_MS)
    }

    private fun insertChained(path: String) {
        awaitingSelection = true
        Main.handler.removeCallbacks(awaitTimeout)
        Main.handler.postDelayed(awaitTimeout, MULTI_INSERT_AWAIT_MAX_MS)
        request(items.remove(path) ?: Item(path))
    }

    fun insertCropped(path: String, crop: CropResult, deletePath: String?) {
        Main.post {
            val toMosaic = MosaicLink.boardVisible
            if (!toMosaic) HostPenSignals.beginInsertImageGuard()

            fun fail(reason: String) {
                Log.e(TAG, "crop failed: $reason")
                Main.post {
                    HostPenSignals.endInsertImageGuard()
                    Toolbar.restore("crop failed")
                }
            }
            thread(isDaemon = true, name = "ImageInsert-crop") {
                PluginIdentity.claim()
                try {
                    val src = BitmapFactory.decodeFile(path) ?: run { fail("decode returned null"); return@thread }

                    val made = arrayListOf(src)
                    val out = try {
                        val cropped = Bitmap.createBitmap(src, crop.offsetX, crop.offsetY, crop.width, crop.height)
                        made += cropped
                        val output = if (crop.filter != Bitmaps.FILTER_NONE || crop.density < 100) {
                            val work = cropped.copy(Bitmap.Config.ARGB_8888, true)
                            made += work
                            if (cropped !== src) {
                                made -= cropped
                                cropped.recycle()
                            }
                            Bitmaps.process(work, crop.filter, crop.density)
                            work
                        } else cropped
                        val file = ImageStaging.newFile()
                        FileOutputStream(file).use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        file
                    } finally {
                        made.distinct().forEach { it.recycle() }
                    }
                    Main.post {
                        if (toMosaic) {
                            enqueueToMosaic(out.path, SOURCE_INSERT, deletePath) { ok ->
                                if (!out.delete()) Log.w(TAG, "staged crop not deleted: ${out.path}")
                                if (!ok) Dialogs.tip(Strings.get("image_insert_failed"))
                            }
                        } else {
                            request(Item(out.path, deletePath = deletePath))
                        }
                    }
                } catch (e: Throwable) {
                    fail(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun enqueueToMosaic(path: String, source: String, deletePath: String?, fromQueue: Boolean = false, after: (Boolean) -> Unit) {
        thread(isDaemon = true, name = "ImageInsert-mosaic") {
            PluginIdentity.claim()
            val ok = MosaicLink.enqueueImage(path, source)
            Log.i(TAG, "[INSERT] enqueue to Mosaic ok=$ok source=$source path=$path")
            if (ok) cleanUpInserted(Item(path, deletePath, fromQueue))
            Main.post {
                try {
                    after(ok)
                } catch (e: Exception) {
                    Log.e(TAG, "[INSERT] after Mosaic enqueue failed", e)
                } finally {
                    Toolbar.restore("mosaic image queued")
                }
            }
        }
    }

    internal fun onJsFinished(requestId: String, success: Boolean) {
        Main.post {
            if (success) pending.remove(requestId)?.let { afterInserted(it) }
            val req = active ?: return@post
            if (req.id != requestId) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT] result for $requestId after it was no longer active success=$success")
                return@post
            }
            active = null
            if (!success) {
                abort("image insertion failed: ${req.item.path}", showTip = true)
                return@post
            }
            val heldForResult = guardHeldForResult
            guardHeldForResult = false
            if (advanceAfterResult) {
                advanceAfterResult = false
                if (!advance("previous result arrived")) {

                    HostPenSignals.endInsertImageGuard()
                    scheduleRestore("multi insert done")
                }
            } else if (heldForResult) {

                HostPenSignals.endInsertImageGuard()
                scheduleRestore("multi insert cleared while waiting")
            }
        }
    }

    private fun onTxnLost(reason: String) {
        val req = active ?: return
        abort("image insertion txn ended ($reason): ${req.item.path}", showTip = false)
    }

    private fun afterInserted(item: Item) {
        if (!item.fromQueue && item.deletePath == null) return
        thread(isDaemon = true, name = "ImageInsert-cleanup") {
            PluginIdentity.claim()
            cleanUpInserted(item)
        }
    }

    private fun cleanUpInserted(item: Item) {
        try {
            if (item.fromQueue) {
                val file = File(item.path)
                if (Dirs.same(file.parent.orEmpty(), ScreenshotDirs.queue.path)) {
                    ImageHooks.screenshotUsed(file.name)
                    Log.i(TAG, "[INSERT] queue file ${item.path} deleted=${file.delete()}")
                } else {
                    Log.w(TAG, "[INSERT] queue delete refused, not a queue file: ${item.path}")
                }
            }
            item.deletePath?.let { if (!File(it).delete()) Log.w(TAG, "inserted image not deleted: $it") }
        } catch (e: Exception) {
            Log.e(TAG, "[INSERT] clean-up after insert failed: ${item.path}", e)
        }
    }

    private fun abort(reason: String, showTip: Boolean) {
        active = null
        advanceAfterResult = false
        guardHeldForResult = false

        flashArmed = false
        clearChain(reason)
        HostPenSignals.endInsertImageGuard()
        Log.w(TAG, "[INSERT] $reason; release guard and restore toolbar")
        if (Toolbar.hasSession()) scheduleRestore(reason)
        if (showTip) Dialogs.tip(Strings.get("image_insert_failed"))
    }

    private fun scheduleRestore(reason: String) {
        Main.handler.postDelayed({ Toolbar.restore(reason) }, HostSession.RESTORE_DELAY_NORMAL)
    }

    fun clearChain(reason: String) {
        DocLinks.clearChain(reason)
        items.clear()
        advanceAfterResult = false
        if (queue.isEmpty() && !awaitingSelection) return
        Log.i(TAG, "multi insert cleared: $reason remaining=${queue.size}")
        queue.clear()
        queued = 0
        awaitingSelection = false
        Main.handler.removeCallbacks(awaitTimeout)
    }

    private fun advance(reason: String): Boolean {
        if (queue.isEmpty()) return false
        if (module == null) {
            clearChain("no image module")
            return false
        }
        val next = queue.removeFirst()
        queued = queue.size
        Log.i(TAG, "multi insert: $reason → next=$next remaining=${queue.size}")
        insertChained(next)
        return true
    }

    private fun onSelectionShown() {
        if (!awaitingSelection) return
        awaitingSelection = false
        Main.handler.removeCallbacks(awaitTimeout)
    }

    private fun onLassoCancelled(reason: String) {
        if (deferAdvance(reason)) return
        if (advance(reason)) return
        if (commitPending) scheduleFlash(reason)
    }

    private fun onPlacementSettled() {
        if (!awaitingSelection) return
        onSelectionShown()
        if (deferAdvance("placed without selection")) return
        advance("placed without selection")
    }

    private fun deferAdvance(reason: String): Boolean {
        if (active == null || queue.isEmpty()) return false
        Log.i(TAG, "multi insert: $reason before the result of ${active?.id}; advance after it")
        advanceAfterResult = true
        guardHeldForResult = true
        HostPenSignals.beginInsertImageGuard()
        return true
    }

    private fun onMonitorStopping() {
        clearChain("foreground monitor stopped")
        endRoute()
    }

    private fun onImageCommitted() {
        if (!flashArmed) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "plugin image committed: not ours, no host flash")
            return
        }
        flashArmed = false
        commitPending = true
        if (!HostLogMonitor.isLassoMenuOpen() && queue.isEmpty() && !awaitingSelection) {
            scheduleFlash("image committed, lasso already closed")
        }
    }

    private fun scheduleFlash(reason: String) {
        commitPending = false
        Main.handler.removeCallbacks(flashRunnable)
        Main.handler.postDelayed(flashRunnable, HOST_FLASH_DELAY_MS)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host flash scheduled in ${HOST_FLASH_DELAY_MS}ms reason=$reason")
    }

    private fun sendHostFlash() {
        if (HostSession.isDocumentContext() || !HostSession.state.inHostApp) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host flash skipped: not on a note page")
            return
        }
        try {
            App.context.sendBroadcast(Intent(HOST_FLASH_ACTION))
            Log.i(TAG, "host flash broadcast sent after image insert")
        } catch (e: Exception) {
            Log.w(TAG, "host flash broadcast failed: ${e.message}")
        }
    }
}

class ImageModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalImage") {

    init {
        ImageInsert.attach(this)
    }

    override fun initialize() {
        super.initialize()
        Tools.handle(Tools.IMAGE, onTap = { ImageInsert.onToolTap(it) }, onLongPress = { ImageInsert.onToolLongPress(it) })
    }

    override fun invalidate() {
        ImageInsert.detach(this)
        super.invalidate()
    }

    internal fun insertRequested(requestId: String, path: String) {
        emit("image:insert", Arguments.createMap().apply {
            putString("requestId", requestId)
            putString("path", path)
        })
    }

    @ReactMethod
    fun prepare(path: String, promise: Promise) {
        thread(isDaemon = true, name = "ImageInsert-prepare") {
            PluginIdentity.claim()
            try {
                val prepared = ImageStaging.prepare(path)
                Log.i("ImageInsert", "[INSERT] prepared image: $path -> $prepared")
                promise.resolve(prepared)
            } catch (e: Exception) {
                promise.reject("IMAGE_PREPARE_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun insertFinished(requestId: String, success: Boolean) = ImageInsert.onJsFinished(requestId, success)
}
