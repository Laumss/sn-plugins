package me.laumss.notipal.mosaic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Cancelable
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.host.HostHooks
import me.laumss.notipal.host.PenGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

sealed class MosaicEvent {

    data class BoardState(val visible: Boolean, val surface: String, val noteRef: String?, val reason: String) : MosaicEvent()

    data class ChromeMenu(val open: Boolean) : MosaicEvent()

    data class Motion(val on: Boolean) : MosaicEvent()

    object TextEditingOpened : MosaicEvent()

    data class Pad(val open: Boolean) : MosaicEvent()

    data class QuickSwitch(val open: Boolean) : MosaicEvent()

    object ToolbarRects : MosaicEvent()

    data class Selection(val strokes: Int, val textCards: Int) : MosaicEvent()

    object PenState : MosaicEvent()
}

object MosaicLink {

    private const val TAG = "MosaicLink"

    const val ACTION_BOARD_STATE = "me.laumss.mosaic.BOARD_STATE"
    const val ACTION_TOOLBAR_RECT = "me.laumss.mosaic.INKLING_TOOLBAR_RECT"

    const val ACTION_OVERLAY_RECTS = "me.laumss.mosaic.INKLING_OVERLAY_RECTS"

    const val ACTION_OVERLAY_DRAG = "me.laumss.mosaic.INKLING_OVERLAY_DRAG"
    const val ACTION_REQUEST = "me.laumss.mosaic.INKLING_REQUEST"

    const val ACTION_BOARD_TOOLBAR_RECTS = "me.laumss.mosaic.BOARD_TOOLBAR_RECTS"
    const val ACTION_PALETTE_RESULT = "me.laumss.mosaic.PALETTE_RESULT"

    const val ACTION_SELECTION = "me.laumss.mosaic.SELECTION"

    const val ACTION_IMPORT_BOARD = "me.laumss.mosaic.IMPORT_BOARD"

    const val ACTION_IMPORT_BOARD_ACK = "me.laumss.mosaic.IMPORT_BOARD_ACK"

    const val ACTION_QUICK_SWITCH = "me.laumss.mosaic.QUICK_SWITCH"

    const val ACTION_OPEN_NOTIPAL = "me.laumss.mosaic.OPEN_NOTIPAL"
    const val ACTION_OPEN_NOTIPAL_ACK = "me.laumss.mosaic.OPEN_NOTIPAL_ACK"

    const val ACTION_NOTIPAL_STATE = "me.laumss.mosaic.NOTIPAL_STATE"

    const val ACTION_SKETCH_CLIPBOARD = "me.laumss.mosaic.SKETCH_CLIPBOARD"
    const val ACTION_SKETCH_CLIPBOARD_RESULT = "me.laumss.mosaic.SKETCH_CLIPBOARD_RESULT"

    const val ACTION_PEN_STATE = "me.laumss.mosaic.PEN_STATE"

    const val ACTION_INK_FLUSHED = "me.laumss.mosaic.INK_FLUSHED"

    const val CMD_SYNC = "sync"

    const val CMD_PEN_USE = "pen_use"

    const val CMD_PEN_RELEASE = "pen_release"
    const val CMD_PASTE_STROKES = "paste_strokes"

    const val CMD_CLEAR_SELECTION = "clear_selection"

    const val CMD_DELETE_SELECTION = "delete_selection"

    const val CMD_TEXT_CARD = "text_card"

    const val CMD_INBOX = "inbox"

    const val CMD_FLUSH_INK = "flush_ink"

    private const val INK_FLUSH_TIMEOUT_MS = 1500L

    private const val CAPTURE_TIMEOUT_MS = 5000L

    private const val PALETTE_TIMEOUT_MS = 6000L

    const val SURFACE_BOARD = "board"

    const val PLUGIN_ID = "mosaic20260730ab"
    const val PACKAGE = "me.laumss.mosaic"
    const val ACTIVITY = "me.laumss.mosaic.MainActivity"

    @Volatile var boardVisible = false
        private set

    @Volatile var surface = SURFACE_BOARD
        private set

    @Volatile var noteRef: String? = null
        private set

    @Volatile private var chromeMenu = false

    private var textEditing = false

    @Volatile private var motionOn = false

    @Volatile var padOpen = false
        private set

    @Volatile private var translucent = false

    @Volatile var quickSwitchOpen = false
        private set

    @Volatile private var mosaicToolbarRects: List<Rect> = emptyList()

    @Volatile var selectionStrokes = 0
        private set
    @Volatile var selectionTextCards = 0
        private set

    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var lastSentRect: Rect? = null
    @Volatile private var lastSentOverlayRects: List<Rect> = emptyList()
    @Volatile private var lastSentFullUiAuto = false
    private val paletteRequests = mutableMapOf<String, Pair<(JSONObject) -> Unit, Runnable>>()

    private class InkFlushWait {
        val latch = CountDownLatch(1)
        @Volatile var flushed = false
        @Volatile var imagePath: String? = null
    }
    private val inkFlushWaits = ConcurrentHashMap<String, InkFlushWait>()

    private val listeners = CopyOnWriteArrayList<(MosaicEvent) -> Unit>()

    @Volatile var openRequestHandler: (() -> Boolean)? = null

    @Volatile var boardImportHandler: ((kind: String, name: String, template: String) -> Boolean)? = null

    @Volatile var sketchClipboardHandler: ((selection: Boolean) -> Boolean)? = null

    val chromeMenuOpen: Boolean get() = boardVisible && chromeMenu

    val boardTranslucent: Boolean get() = boardVisible && translucent

    val motion: Boolean get() = boardVisible && motionOn

    fun toolbarRects(): List<Rect> = if (boardVisible) mosaicToolbarRects else emptyList()

    fun addListener(l: (MosaicEvent) -> Unit): Cancelable {
        listeners.add(l)
        return Cancelable { listeners.remove(l) }
    }

    private fun dispatch(event: MosaicEvent) {
        for (l in listeners) {
            try {
                l(event)
            } catch (e: Exception) {
                Log.e(TAG, "listener failed: $event", e)
            }
        }
    }

    fun onPluginHostHidden() {
        if (!padOpen) return
        padOpen = false
        Log.i(TAG, "pad open=false reason=plugin-host-hidden")
        dispatch(MosaicEvent.Pad(false))
    }

    fun ensureRegistered() {
        HostHooks.mosaicBoardVisible = { boardVisible }
        HostHooks.publishToolbarRect = { rect, force -> publishToolbarRect(rect, force) }
        HostHooks.publishOverlayRects = { rects, force -> publishOverlayRects(rects, force) }
        if (receiver != null) return
        val app = appContext()
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                try {
                    if (intent != null) onBroadcast(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "broadcast ${intent?.action} failed", e)
                }
            }
        }
        val filter = IntentFilter(ACTION_BOARD_STATE).apply {
            addAction(ACTION_PALETTE_RESULT)
            addAction(ACTION_BOARD_TOOLBAR_RECTS)
            addAction(ACTION_SELECTION)
            addAction(ACTION_IMPORT_BOARD)
            addAction(ACTION_QUICK_SWITCH)
            addAction(ACTION_OPEN_NOTIPAL)
            addAction(ACTION_SKETCH_CLIPBOARD)
            addAction(ACTION_INK_FLUSHED)
            addAction(ACTION_PEN_STATE)
        }
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
        Log.i(TAG, "board-state receiver registered")
        send(ACTION_REQUEST) { it.putExtra("cmd", CMD_SYNC) }
    }

    private fun onBroadcast(intent: Intent) {
        when (intent.action) {
            ACTION_INK_FLUSHED -> {
                val wait = inkFlushWaits[intent.getStringExtra("requestId") ?: return] ?: return
                wait.flushed = intent.getBooleanExtra("flushed", false)
                wait.imagePath = intent.getStringExtra("imagePath")
                wait.latch.countDown()
            }
            ACTION_PALETTE_RESULT -> {
                val id = intent.getStringExtra("requestId") ?: return
                val result = try {
                    JSONObject(intent.getStringExtra("result") ?: "{}")
                } catch (e: Exception) {
                    Log.w(TAG, "palette result unreadable: ${e.message}")
                    paletteError("invalid_result")
                }
                finishPaletteRequest(id, result)
            }
            ACTION_BOARD_TOOLBAR_RECTS -> onMosaicToolbarRects(intent)
            ACTION_SELECTION -> {
                val strokes = intent.getIntExtra("strokes", 0)
                val textCards = intent.getIntExtra("textCards", 0)
                Log.i(TAG, "selection strokes=$strokes textCards=$textCards reason=${intent.getStringExtra("reason")}")
                onSelection(strokes, textCards)
            }
            ACTION_QUICK_SWITCH -> {
                val open = intent.getBooleanExtra("open", false)

                if (open) {
                    val session = HostHooks.hasToolbarSession()
                    send(ACTION_NOTIPAL_STATE) { it.putExtra("session", session) }
                }
                if (quickSwitchOpen == open) return
                quickSwitchOpen = open
                Log.i(TAG, "quick switch open=$open")

                PenGuard.setQuickSwitchOpen(open)
                dispatch(MosaicEvent.QuickSwitch(open))
            }
            ACTION_SKETCH_CLIPBOARD -> {
                val selection = intent.getBooleanExtra("selection", false)
                val asked = sketchClipboardHandler?.invoke(selection) == true
                Log.i(TAG, "sketch clipboard request selection=$selection asked=$asked")
                if (!asked) sendSketchClipboardResult("none")
            }
            ACTION_PEN_STATE -> {
                val penId = intent.getStringExtra("penId")
                val thickness = intent.getIntExtra("thickness", 0)
                val next = if (intent.getBooleanExtra("external", false) && penId != null && thickness > 0) {
                    ExternalPen(penId, thickness)
                } else {
                    null
                }
                Log.i(TAG, "pen state external=$next")
                setExternalPen(next)
            }
            ACTION_OPEN_NOTIPAL -> {
                val opened = openRequestHandler?.invoke() == true
                Log.i(TAG, "open request from Mosaic opened=$opened")
                send(ACTION_OPEN_NOTIPAL_ACK) { it.putExtra("opened", opened) }
            }
            ACTION_IMPORT_BOARD -> {
                val requestId = intent.getStringExtra("requestId") ?: return
                val accepted = boardImportHandler?.invoke(
                    intent.getStringExtra("kind").orEmpty(),
                    intent.getStringExtra("name").orEmpty(),
                    intent.getStringExtra("template").orEmpty(),
                ) == true
                Log.i(TAG, "board import request kind=${intent.getStringExtra("kind")} accepted=$accepted")
                send(ACTION_IMPORT_BOARD_ACK) {
                    it.putExtra("requestId", requestId)
                    it.putExtra("accepted", accepted)
                }
            }
            ACTION_BOARD_STATE -> {

                val editingNow = intent.getBooleanExtra("textEditing", false)
                val motionNow = intent.getBooleanExtra("motion", false)
                val folded = intent.getBooleanExtra("chromeMenu", false)
                val menu = if (intent.hasExtra("menu")) intent.getBooleanExtra("menu", false)
                    else folded && !motionNow && !editingNow
                val translucentNow = intent.getBooleanExtra("translucent", false)
                if (translucentNow != translucent) Log.i(TAG, "board translucent=$translucentNow")
                translucent = translucentNow
                onBoardState(
                    visible = intent.getBooleanExtra("visible", false),
                    nextSurfaceRaw = intent.getStringExtra("surface"),
                    nextNoteRefRaw = intent.getStringExtra("noteRef"),
                    menuOrEditing = menu || editingNow,
                    editing = editingNow,
                    moving = motionNow && !menu && !editingNow,
                    pad = intent.getBooleanExtra("pad", false),
                    reason = intent.getStringExtra("reason") ?: "",
                )
            }
        }
    }

    private fun onSelection(strokes: Int, textCards: Int) {
        if (selectionStrokes == strokes && selectionTextCards == textCards) return
        selectionStrokes = strokes
        selectionTextCards = textCards
        dispatch(MosaicEvent.Selection(strokes, textCards))
    }

    private fun onBoardState(
        visible: Boolean,
        nextSurfaceRaw: String?,
        nextNoteRefRaw: String?,
        menuOrEditing: Boolean,
        editing: Boolean,
        moving: Boolean,
        pad: Boolean,
        reason: String,
    ) {

        val nextPad = visible && pad
        val boardShown = visible && !pad
        val padChanged = padOpen != nextPad
        padOpen = nextPad
        if (padChanged) Log.i(TAG, "pad open=$nextPad reason=$reason")
        val nextSurface = nextSurfaceRaw.orEmpty()
        val nextNoteRef = nextNoteRefRaw?.trim()?.takeIf { it.isNotEmpty() }
        val nextMenu = boardShown && menuOrEditing
        val menuChanged = chromeMenu != nextMenu
        chromeMenu = nextMenu
        val nextEditing = boardShown && editing

        if (nextEditing && !textEditing) {
            Log.i(TAG, "text editing started reason=$reason")
            dispatch(MosaicEvent.TextEditingOpened)
        }
        textEditing = nextEditing
        val nextMotion = boardShown && moving
        val motionChanged = motionOn != nextMotion
        motionOn = nextMotion
        if (motionChanged) Log.i(TAG, "canvas motion=$nextMotion reason=$reason")
        val changed = boardVisible != boardShown || surface != nextSurface || noteRef != nextNoteRef
        if (!changed) {
            if (menuChanged) {
                Log.i(TAG, "chrome menu open=$nextMenu reason=$reason")
                dispatch(MosaicEvent.ChromeMenu(nextMenu))
            }
            if (motionChanged) dispatch(MosaicEvent.Motion(nextMotion))
            if (padChanged) dispatch(MosaicEvent.Pad(nextPad))
            return
        }

        val surfaceChanged = surface != nextSurface
        val hidden = boardVisible && !boardShown
        boardVisible = boardShown
        surface = nextSurface
        noteRef = nextNoteRef
        if (!boardShown || surfaceChanged) {
            paletteRequests.keys.toList().forEach { finishPaletteRequest(it, paletteError("surface_changed")) }
        }

        if (!boardShown) setExternalPen(null)
        if (!boardShown) {
            lastSentRect = null
            lastSentOverlayRects = emptyList()
            lastSentFullUiAuto = false
            mosaicToolbarRects = emptyList()
            onSelection(0, 0)
        }
        Log.i(TAG, "board state visible=$boardShown surface=$nextSurface noteRef=$nextNoteRef pad=$nextPad reason=$reason")
        dispatch(MosaicEvent.BoardState(boardShown, nextSurface, nextNoteRef, reason))
        if (menuChanged) dispatch(MosaicEvent.ChromeMenu(nextMenu))
        if (motionChanged) dispatch(MosaicEvent.Motion(nextMotion))
        if (padChanged) dispatch(MosaicEvent.Pad(nextPad))

        PenGuard.republishMosaicSnapshot("mosaic-state:$reason")
        if (hidden) PenGuard.onMosaicHidden(reason)
    }

    private fun onMosaicToolbarRects(intent: Intent) {
        val count = intent.getIntExtra("count", 0)
        val l = intent.getIntArrayExtra("left")
        val t = intent.getIntArrayExtra("top")
        val r = intent.getIntArrayExtra("right")
        val b = intent.getIntArrayExtra("bottom")
        val next = if (count <= 0 || l == null || t == null || r == null || b == null ||
            l.size < count || t.size < count || r.size < count || b.size < count
        ) emptyList() else (0 until count).map { Rect(l[it], t[it], r[it], b[it]) }.filter { !it.isEmpty }
        if (next == mosaicToolbarRects) return
        mosaicToolbarRects = next
        Log.i(TAG, "mosaic toolbar rects=$next reason=${intent.getStringExtra("reason")}")
        dispatch(MosaicEvent.ToolbarRects)
    }

    fun publishToolbarRect(rect: Rect?, force: Boolean = false) {
        if (!boardVisible) return
        if (!force && rect == lastSentRect) return
        lastSentRect = rect?.let(::Rect)
        send(ACTION_TOOLBAR_RECT) {
            if (rect == null) {
                it.putExtra("present", false)
            } else {
                it.putExtra("present", true)
                it.putExtra("left", rect.left)
                it.putExtra("top", rect.top)
                it.putExtra("right", rect.right)
                it.putExtra("bottom", rect.bottom)
            }
        }
        Log.i(TAG, "toolbar rect published rect=$rect")
    }

    fun publishOverlayRects(rects: List<Rect>, force: Boolean = false) {
        if (!boardVisible) return
        val normalized = rects.map(::Rect)
        if (!force && normalized == lastSentOverlayRects) return
        lastSentOverlayRects = normalized.map(::Rect)
        send(ACTION_OVERLAY_RECTS) {
            it.putExtra("count", normalized.size)
            val l = IntArray(normalized.size)
            val t = IntArray(normalized.size)
            val r = IntArray(normalized.size)
            val b = IntArray(normalized.size)
            normalized.forEachIndexed { i, rect ->
                l[i] = rect.left; t[i] = rect.top; r[i] = rect.right; b[i] = rect.bottom
            }
            it.putExtra("left", l)
            it.putExtra("top", t)
            it.putExtra("right", r)
            it.putExtra("bottom", b)
        }
        Log.i(TAG, "overlay rects published count=${normalized.size} rects=$normalized")
    }

    fun publishFullUiAuto(active: Boolean, resend: Boolean = false) {
        if (active == lastSentFullUiAuto && !(active && resend)) return
        if (active && !boardVisible) return
        lastSentFullUiAuto = active
        send(ACTION_OVERLAY_DRAG) { it.putExtra("active", active) }
        Log.i(TAG, "fullUiAuto published active=$active resend=$resend")
    }

    fun sendSketchClipboardResult(outcome: String) {
        Log.i(TAG, "sketch clipboard result=$outcome")
        send(ACTION_SKETCH_CLIPBOARD_RESULT) { it.putExtra("outcome", outcome) }
    }

    fun requestPasteStrokes() {
        Log.i(TAG, "paste-strokes requested")
        send(ACTION_REQUEST) { it.putExtra("cmd", CMD_PASTE_STROKES) }
    }

    private val inbox: File get() = File(Dirs.mosaic, "inbox")

    fun enqueueImage(imagePathSrc: String, source: String): Boolean {
        PluginIdentity.claim()
        return try {
            val dir = inboxDir() ?: return false
            val id = "inkling-${System.currentTimeMillis()}-${(0..9999).random()}"
            val imagePath = File(dir, "$id.png")
            File(imagePathSrc).copyTo(imagePath, overwrite = true)
            val request = JSONObject().apply {
                put("version", 1)
                put("id", id)
                put("imagePath", imagePath.absolutePath)
                put("createdAt", System.currentTimeMillis())
                put("source", source)
            }
            if (!writeRequest(dir, id, request)) {
                imagePath.delete()
                return false
            }
            Log.i(TAG, "image queued id=$id source=$source src=$imagePathSrc")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enqueueImage failed: ${e.message}", e)
            false
        }
    }

    fun newNoteImportFile(): File? {
        val dir = inboxDir() ?: return null
        return File(dir, "inkling-note-${System.currentTimeMillis()}-${(0..9999).random()}$NOTE_PAGES_SUFFIX")
    }

    fun enqueueNote(pagesFile: File, title: String, source: String): Boolean {
        return try {
            val dir = pagesFile.parentFile ?: return false
            val id = importId(pagesFile)
            val request = JSONObject().apply {
                put("version", 1)
                put("id", id)
                put("type", "note")
                put("reportResult", true)
                put("notePath", pagesFile.absolutePath)
                put("title", title)
                put("createdAt", System.currentTimeMillis())
                put("source", source)
            }
            if (!writeRequest(dir, id, request)) return false
            Log.i(TAG, "note queued id=$id source=$source bytes=${pagesFile.length()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enqueueNote failed: ${e.message}", e)
            false
        }
    }

    private const val NOTE_PAGES_SUFFIX = ".pages"
    private const val DOC_PAGES_SUFFIX = ".docpages"

    fun newDocImportDir(): File? {
        val dir = inboxDir() ?: return null
        return File(dir, "inkling-doc-${System.currentTimeMillis()}-${(0..9999).random()}$DOC_PAGES_SUFFIX")
    }

    fun enqueueDoc(pagesDir: File, title: String, source: String): Boolean {
        return try {
            val dir = pagesDir.parentFile ?: return false
            val id = importId(pagesDir)
            val request = JSONObject().apply {
                put("version", 1)
                put("id", id)
                put("type", "doc")
                put("reportResult", true)
                put("docPath", pagesDir.absolutePath)
                put("title", title)
                put("createdAt", System.currentTimeMillis())
                put("source", source)
            }
            if (!writeRequest(dir, id, request)) return false
            Log.i(TAG, "doc queued id=$id source=$source pages=${pagesDir.list()?.count { it.endsWith(".hd.png") }}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enqueueDoc failed: ${e.message}", e)
            false
        }
    }

    fun enqueueBoard(kind: String, payload: File, boardName: String, template: String, titles: List<String>, source: String): Boolean {
        return try {
            val dir = payload.parentFile ?: return false
            val id = importId(payload)
            val request = JSONObject().apply {
                put("version", 1)
                put("id", id)
                put("type", "board")
                put("reportResult", true)
                put("boardKind", kind)
                put(if (kind == "doc") "docPath" else "notePath", payload.absolutePath)
                put("boardName", boardName)
                put("template", template)
                put("titles", JSONArray(titles))
                put("createdAt", System.currentTimeMillis())
                put("source", source)
            }
            if (!writeRequest(dir, id, request)) return false
            Log.i(TAG, "board queued id=$id kind=$kind pages=${titles.size} source=$source")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enqueueBoard failed: ${e.message}", e)
            false
        }
    }

    private fun importId(payload: File): String = payload.name.substringBeforeLast('.')

    fun noteImportResultFile(payload: File): File = File(payload.parentFile, importId(payload) + ".result")

    fun cancelPendingNoteImport(payload: File): Boolean {
        val id = importId(payload)
        val pending = File(payload.parentFile, "$id.json")
        val cancelled = File(payload.parentFile, "$id.cancelled")
        if (!pending.renameTo(cancelled)) return false
        if (payload.isDirectory) payload.deleteRecursively() else discardNotePayload(payload)
        cancelled.delete()
        return true
    }

    fun discardNotePayload(source: File) {
        val stage = File(source.parentFile, source.nameWithoutExtension + ".prepared")
        if (source.name.startsWith("inkling-note-") && source.extension == "pages") stage.deleteRecursively()
        source.delete()
    }

    private fun inboxDir(): File? {
        val dir = inbox
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "inbox mkdir failed: $dir")
            return null
        }
        return dir
    }

    private fun writeRequest(dir: File, id: String, request: JSONObject): Boolean {
        val requestPath = File(dir, "$id.json")
        val tempRequestPath = File(dir, "$id.json.tmp")
        FileOutputStream(tempRequestPath).use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
        if (!tempRequestPath.renameTo(requestPath)) {
            tempRequestPath.delete()
            return false
        }
        send(ACTION_REQUEST) { it.putExtra("cmd", CMD_INBOX) }
        return true
    }

    fun requestTextCard(text: String, anchorScreenX: Int, anchorScreenY: Int) {
        Log.i(TAG, "text-card requested anchor=($anchorScreenX,$anchorScreenY) len=${text.length}")
        send(ACTION_REQUEST) {
            it.putExtra("cmd", CMD_TEXT_CARD)
            it.putExtra("text", text)
            it.putExtra("anchorScreenX", anchorScreenX)
            it.putExtra("anchorScreenY", anchorScreenY)
        }
    }

    fun requestClearSelection(delete: Boolean) {
        Log.i(TAG, "clear-selection requested delete=$delete")
        send(ACTION_REQUEST) { it.putExtra("cmd", if (delete) CMD_DELETE_SELECTION else CMD_CLEAR_SELECTION) }
    }

    fun waitBeforeScreencap(fallbackMs: Long, reason: String) {
        if (requestInkFlush(null, false, INK_FLUSH_TIMEOUT_MS, reason) == null) {
            try {
                Thread.sleep(fallbackMs)
            } catch (e: InterruptedException) {
                Log.i(TAG, "screencap wait interrupted reason=$reason")
            }
        }
    }

    fun captureRegion(screen: Rect, jpeg: Boolean, reason: String): String? =
        requestInkFlush(screen, jpeg, CAPTURE_TIMEOUT_MS, reason)?.imagePath

    private fun requestInkFlush(capture: Rect?, jpeg: Boolean, timeoutMs: Long, reason: String): InkFlushWait? {
        if (!boardVisible || !App.ready) return null
        val id = UUID.randomUUID().toString()
        val wait = InkFlushWait()
        inkFlushWaits[id] = wait
        val start = SystemClock.uptimeMillis()
        send(ACTION_REQUEST) {
            it.putExtra("cmd", CMD_FLUSH_INK)
            it.putExtra("requestId", id)
            if (capture != null) {
                it.putExtra("capture", intArrayOf(capture.left, capture.top, capture.right, capture.bottom))
                it.putExtra("captureFormat", if (jpeg) "jpeg" else "png")
            }
        }
        val replied = try {
            wait.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        inkFlushWaits.remove(id)
        Log.i(TAG, "ink flush reason=$reason replied=$replied flushed=${wait.flushed} " +
            "capture=${capture != null} image=${wait.imagePath} waitedMs=${SystemClock.uptimeMillis() - start}")
        return wait
    }

    private fun paletteError(error: String) = JSONObject().put("success", false).put("error", error)

    private fun finishPaletteRequest(id: String, result: JSONObject) {
        val pending = paletteRequests.remove(id) ?: return
        Main.handler.removeCallbacks(pending.second)
        pending.first(result)
    }

    fun requestPalette(data: JSONObject? = null, result: (JSONObject) -> Unit) =
        request(if (data == null) "palette_info" else "palette_apply", data, result)

    fun usePen(penId: String, thickness: Int, result: (JSONObject) -> Unit) =
        request(CMD_PEN_USE, JSONObject().put("penId", penId).put("thickness", thickness), result)

    fun releasePen(result: (JSONObject) -> Unit) = request(CMD_PEN_RELEASE, null, result)

    private fun request(cmd: String, data: JSONObject?, result: (JSONObject) -> Unit) {
        Main.post {
            ensureRegistered()
            if (!boardVisible) { result(paletteError("surface_changed")); return@post }
            val id = UUID.randomUUID().toString()
            val timeout = Runnable { finishPaletteRequest(id, paletteError("timeout")) }
            paletteRequests[id] = result to timeout
            Main.handler.postDelayed(timeout, PALETTE_TIMEOUT_MS)
            send(ACTION_REQUEST) {
                it.putExtra("cmd", cmd)
                it.putExtra("requestId", id)
                if (data != null) it.putExtra("data", data.toString())
            }
        }
    }

    fun applyPalettePreset(penId: String, thickness: Int, result: (JSONObject) -> Unit) {
        requestPalette { info ->
            if (!info.optBoolean("success")) { result(info); return@requestPalette }
            requestPalette(info.put("penId", penId).put("thickness", thickness), result)
        }
    }

    data class ExternalPen(val penId: String, val thickness: Int)

    var externalPen: ExternalPen? = null
        private set

    private fun setExternalPen(next: ExternalPen?) {
        if (externalPen == next) return
        externalPen = next
        dispatch(MosaicEvent.PenState)
    }

    fun send(action: String, extras: Bundle) {
        send(action) { it.putExtras(extras) }
    }

    private fun appContext(): Context = App.context.applicationContext ?: App.context

    private inline fun send(action: String, fill: (Intent) -> Unit) {
        if (!App.ready) {
            Log.w(TAG, "send $action dropped: no context yet")
            return
        }
        try {
            val ctx = appContext()
            ctx.sendBroadcast(Intent(action).apply { setPackage(ctx.packageName); fill(this) })
        } catch (e: Exception) {
            Log.e(TAG, "send $action failed: ${e.message}", e)
        }
    }
}

class MosaicModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalMosaic") {

    override fun initialize() {
        super.initialize()
        MosaicImport.attach(this)
    }

    override fun invalidate() {
        MosaicImport.detach(this)
        super.invalidate()
    }

    @ReactMethod
    fun pasteStrokes() {
        MosaicLink.requestPasteStrokes()
    }

    @ReactMethod
    fun clearSelection(delete: Boolean) {
        MosaicLink.requestClearSelection(delete)
    }

    @ReactMethod
    fun textCard(text: String, anchorScreenX: Int, anchorScreenY: Int) {
        MosaicLink.requestTextCard(text, anchorScreenX, anchorScreenY)
    }

    @ReactMethod
    fun finishSketchClipboard(outcome: String) {
        MosaicLink.sendSketchClipboardResult(outcome)
    }
}

object PenSizeSpec {

    const val NEEDLE = 10
    const val BALL = 16
    const val CALLIGRAPHY = 15
    const val MARKER = 11

    const val PENCIL = 21
    val PENCIL_LABELS = arrayOf("HB", "2B", "3B", "4B", "5B", "6B", "7B", "8B")

    private val ALL_LABELS = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.5f, 2.0f)
    private const val BALL_START = 2
    private const val CALLI_START = 4

    private class DeviceTable(val needle: IntArray, val marker: Int)

    private val MANTA = DeviceTable(
        needle = intArrayOf(200, 300, 400, 500, 600, 700, 900, 1000, 1100, 1200, 1800, 2400),
        marker = 3800,
    )

    private val A5X = DeviceTable(
        needle = intArrayOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000),
        marker = 3000,
    )

    private val NOMAD = DeviceTable(
        needle = intArrayOf(200, 300, 400, 500, 700, 800, 1000, 1100, 1200, 1300, 1800, 2400),
        marker = 3800,
    )

    private val staticTable: DeviceTable = when {
        Build.MODEL.contains("Manta", ignoreCase = true) -> MANTA
        Build.BOARD == "A5X2" -> MANTA
        Build.MODEL == "Supernote A5 X" -> A5X
        Build.MODEL == "Supernote A6 X" || Build.MODEL == "Supernote Nomad" -> NOMAD
        else -> MANTA
    }
    @Volatile private var runtimeTable: DeviceTable? = null

    fun configureRuntime(deviceType: Int?, pageWidth: Int?, pageHeight: Int?) {
        runtimeTable = when (deviceType) {

            5 -> MANTA
            4 -> NOMAD
            3 -> A5X
            2 -> NOMAD
            else -> when {
                pageWidth == 1920 && pageHeight == 2560 -> MANTA
                pageWidth == 2560 && pageHeight == 3414 -> NOMAD
                else -> null
            }
        }
    }

    private fun table(): DeviceTable = runtimeTable ?: staticTable

    private fun startIndex(penType: Int?): Int = when (penType) {
        BALL -> BALL_START
        CALLIGRAPHY, PENCIL -> CALLI_START
        else -> 0
    }

    fun extraLabels(penType: Int?): FloatArray = when (penType) {
        PENCIL -> FloatArray(0)
        BALL -> floatArrayOf(3.0f, 5.0f)
        CALLIGRAPHY -> floatArrayOf(3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 7.5f)
        MARKER -> floatArrayOf(4.0f, 5.0f, 6.0f)
        else -> floatArrayOf(3.0f)
    }

    fun extraSteps(penType: Int?): IntArray = extraLabels(penType).map { (it * 1000).roundToInt() }.toIntArray()

    private const val MARKER_LABEL = 3.0f

    fun stepsFor(penType: Int?): IntArray = when (penType) {
        MARKER -> intArrayOf(table().marker) + extraSteps(MARKER)
        else -> table().needle.copyOfRange(startIndex(penType), table().needle.size) + extraSteps(penType)
    }

    fun labelsFor(penType: Int?): FloatArray = when (penType) {
        MARKER -> floatArrayOf(MARKER_LABEL) + extraLabels(MARKER)
        else -> ALL_LABELS.copyOfRange(startIndex(penType), ALL_LABELS.size) + extraLabels(penType)
    }

    fun stepIndex(penType: Int?, raw: Int): Int {
        val steps = stepsFor(penType)
        var best = 0
        for (i in steps.indices) {
            if (abs(steps[i] - raw) < abs(steps[best] - raw)) best = i
        }
        return best
    }

    fun snap(penType: Int?, raw: Int): Int = stepsFor(penType)[stepIndex(penType, raw)]

    fun label(penType: Int?, raw: Int): String {
        if (penType == PENCIL) return PENCIL_LABELS[stepIndex(penType, raw)]
        val labels = labelsFor(penType)
        if (labels.isEmpty()) return ""
        return formatLabel(labels[stepIndex(penType, raw)])
    }

    fun formatLabel(value: Float): String = "%.1f".format(value)

    fun rawForLabel(penType: Int?, labelValue: Float): Int {
        val labels = labelsFor(penType)
        if (labels.isEmpty()) return table().marker
        var best = 0
        for (i in labels.indices) {
            if (abs(labels[i] - labelValue) < abs(labels[best] - labelValue)) best = i
        }
        return stepsFor(penType)[best]
    }

    fun convert(fromPen: Int?, toPen: Int?, raw: Int): Int {
        if (fromPen == toPen) return snap(toPen, raw)
        val fromLabels = labelsFor(fromPen)
        if (fromLabels.isEmpty()) return snap(toPen, raw)
        return rawForLabel(toPen, fromLabels[stepIndex(fromPen, raw)])
    }

    fun penIconAsset(penType: Int, colorKey: String = "black"): String {
        val color = when (colorKey) {
            "ghost", "white" -> "white"
            "lightGray" -> "light_gray"
            "darkGray" -> "dark_gray"
            else -> "black"
        }
        return when (penType) {
            BALL -> "icons/pen/ic_ink_pen_${color}_2_svg.xml"
            CALLIGRAPHY -> "icons/pen/ic_calligraphy_pen_${color}_right_svg.xml"
            MARKER -> "icons/pen/ic_mark_pen_${color}_svg.xml"
            else -> "icons/pen/ic_needle_pen_${color}_3_svg.xml"
        }
    }
}

object MosaicPaletteSpec {

    const val PRESETS_KEY = "mosaic_palette_presets"
    const val TIERS_KEY = "mosaic_palette_tier_prefs"

    fun penType(id: String): Int = when (id) {
        "ball" -> PenSizeSpec.CALLIGRAPHY
        "needle" -> PenSizeSpec.NEEDLE
        "pencil" -> PenSizeSpec.PENCIL
        else -> PenSizeSpec.MARKER
    }

    val penIds = listOf("ball", "needle", "pencil", "marker", "mk-lightGray", "mk-darkGray")

    fun color(id: String): String = when (id) {
        "mk-lightGray" -> "lightGray"
        "mk-darkGray" -> "darkGray"
        else -> "black"
    }

    fun normalize(id: String): String = if (id in penIds) id else "ball"

    fun iconAsset(id: String): String = when (id) {
        "mk-lightGray" -> "icons/pen/ic_mosaic_mark_pen_light_gray_svg.xml"
        "pencil" -> "icons/pen/ic_mosaic_pencil_svg.xml"
        "mk-darkGray" -> "icons/pen/ic_mosaic_mark_pen_dark_gray_svg.xml"
        else -> PenSizeSpec.penIconAsset(penType(id), color(id))
    }
}
