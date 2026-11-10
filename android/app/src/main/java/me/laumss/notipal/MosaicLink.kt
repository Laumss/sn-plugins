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
import org.json.JSONObject
import java.util.UUID

internal object MosaicLink {

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

    const val CMD_CLOSE = "close"
    const val CMD_SYNC = "sync"
    const val CMD_PASTE_STROKES = "paste_strokes"

    const val CMD_CLEAR_SELECTION = "clear_selection"

    const val CMD_DELETE_SELECTION = "delete_selection"

    const val CMD_TEXT_CARD = "text_card"

    const val CMD_INBOX = "inbox"

    const val SURFACE_BOARD = "board"

    @Volatile private var boardVisible = false
    @Volatile private var mosaicSurface = SURFACE_BOARD
    @Volatile private var mosaicNoteRef: String? = null

    @Volatile private var chromeMenuOpen = false

    private var textEditing = false

    @Volatile private var motionOn = false

    @Volatile private var padOpen = false
    @Volatile private var quickSwitchOpen = false
    @Volatile private var mosaicToolbarRects: List<Rect> = emptyList()
    @Volatile private var appContext: Context? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var lastSentRect: Rect? = null
    @Volatile private var lastSentOverlayRects: List<Rect> = emptyList()
    @Volatile private var lastSentFullUiAuto = false
    private val paletteHandler = Handler(Looper.getMainLooper())
    private val paletteRequests = mutableMapOf<String, Pair<(JSONObject) -> Unit, Runnable>>()

    fun isBoardVisible(): Boolean = boardVisible

    fun getSurface(): String = mosaicSurface

    fun getNoteRef(): String? = mosaicNoteRef

    fun isChromeMenuOpen(): Boolean = boardVisible && chromeMenuOpen

    fun isPadOpen(): Boolean = padOpen

    fun isQuickSwitchOpen(): Boolean = quickSwitchOpen

    fun isMotion(): Boolean = boardVisible && motionOn

    fun mosaicToolbarRects(): List<Rect> = if (boardVisible) mosaicToolbarRects else emptyList()

    fun onPluginHostHidden() {
        if (!padOpen) return
        padOpen = false
        Log.i(TAG, "pad open=false reason=plugin-host-hidden")
        FloatingToolbarModule.onMosaicPadChanged(false)
    }

    fun ensureRegistered(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_PALETTE_RESULT) {
                    val id = intent.getStringExtra("requestId") ?: return
                    val result = try { JSONObject(intent.getStringExtra("result") ?: "{}") }
                    catch (_: Exception) { paletteError("invalid_result") }
                    finishPaletteRequest(id, result)
                    return
                }
                if (intent?.action == ACTION_BOARD_TOOLBAR_RECTS) {
                    onMosaicToolbarRects(intent)
                    return
                }
                if (intent?.action == ACTION_SELECTION) {
                    val strokes = intent.getIntExtra("strokes", 0)
                    val textCards = intent.getIntExtra("textCards", 0)
                    Log.i(TAG, "selection strokes=$strokes textCards=$textCards reason=${intent.getStringExtra("reason")}")
                    FloatingToolbarModule.onMosaicSelectionChanged(strokes, textCards)
                    return
                }
                if (intent?.action == ACTION_QUICK_SWITCH) {
                    val open = intent.getBooleanExtra("open", false)

                    if (open) {
                        send(app, ACTION_NOTIPAL_STATE) { it.putExtra("session", FloatingToolbarModule.toolbarSessionForMosaic()) }
                    }
                    if (quickSwitchOpen == open) return
                    quickSwitchOpen = open
                    Log.i(TAG, "quick switch open=$open")
                    FloatingToolbarModule.onMosaicQuickSwitchChanged(open)
                    return
                }
                if (intent?.action == ACTION_SKETCH_CLIPBOARD) {
                    val selection = intent.getBooleanExtra("selection", false)
                    val asked = FloatingToolbarModule.onMosaicSketchClipboardRequested(selection)
                    Log.i(TAG, "sketch clipboard request selection=$selection asked=$asked")
                    if (!asked) sendSketchClipboardResult(app, "none")
                    return
                }
                if (intent?.action == ACTION_OPEN_NOTIPAL) {
                    val opened = FloatingToolbarModule.onMosaicOpenRequested()
                    Log.i(TAG, "open request from Mosaic opened=$opened")
                    send(app, ACTION_OPEN_NOTIPAL_ACK) { it.putExtra("opened", opened) }
                    return
                }
                if (intent?.action == ACTION_IMPORT_BOARD) {
                    val requestId = intent.getStringExtra("requestId") ?: return
                    val accepted = FloatingToolbarModule.onMosaicBoardImportRequested(
                        intent.getStringExtra("kind").orEmpty(),
                        intent.getStringExtra("name").orEmpty(),
                        intent.getStringExtra("template").orEmpty(),
                    )
                    Log.i(TAG, "board import request kind=${intent.getStringExtra("kind")} accepted=$accepted")
                    send(app, ACTION_IMPORT_BOARD_ACK) {
                        it.putExtra("requestId", requestId)
                        it.putExtra("accepted", accepted)
                    }
                    return
                }
                if (intent?.action != ACTION_BOARD_STATE) return

                val editingNow = intent.getBooleanExtra("textEditing", false)
                val motionNow = intent.getBooleanExtra("motion", false)
                val folded = intent.getBooleanExtra("chromeMenu", false)
                val menu = if (intent.hasExtra("menu")) intent.getBooleanExtra("menu", false)
                    else folded && !motionNow && !editingNow
                onBoardState(
                    visible = intent.getBooleanExtra("visible", false),
                    surface = intent.getStringExtra("surface"),
                    noteRef = intent.getStringExtra("noteRef"),
                    chromeMenu = menu || editingNow,
                    editing = editingNow,
                    motion = motionNow && !menu && !editingNow,
                    pad = intent.getBooleanExtra("pad", false),
                    reason = intent.getStringExtra("reason") ?: "",
                )
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
        appContext = app
        Log.i(TAG, "board-state receiver registered")
        send(app, ACTION_REQUEST) { it.putExtra("cmd", CMD_SYNC) }
    }

    private fun onBoardState(
        visible: Boolean,
        surface: String?,
        noteRef: String?,
        chromeMenu: Boolean,
        editing: Boolean,
        motion: Boolean,
        pad: Boolean,
        reason: String,
    ) {

        val nextPad = visible && pad
        val boardShown = visible && !pad
        val padChanged = padOpen != nextPad
        padOpen = nextPad
        if (padChanged) Log.i(TAG, "pad open=$nextPad reason=$reason")
        val nextSurface = surface.orEmpty()
        val nextNoteRef = noteRef?.trim()?.takeIf { it.isNotEmpty() }
        val nextMenu = boardShown && chromeMenu
        val menuChanged = chromeMenuOpen != nextMenu
        chromeMenuOpen = nextMenu
        val nextEditing = boardShown && editing

        if (nextEditing && !textEditing) {
            Log.i(TAG, "text editing started reason=$reason")
            FloatingToolbarModule.onMosaicTextEditingOpened()
        }
        textEditing = nextEditing
        val nextMotion = boardShown && motion
        val motionChanged = motionOn != nextMotion
        motionOn = nextMotion
        if (motionChanged) Log.i(TAG, "canvas motion=$nextMotion reason=$reason")
        val changed = boardVisible != boardShown ||
            mosaicSurface != nextSurface ||
            mosaicNoteRef != nextNoteRef
        if (!changed) {
            if (menuChanged) {
                Log.i(TAG, "chrome menu open=$nextMenu reason=$reason")
                FloatingToolbarModule.onMosaicChromeMenuChanged(nextMenu)
            }
            if (motionChanged) FloatingToolbarModule.onMosaicMotionChanged(nextMotion)
            if (padChanged) FloatingToolbarModule.onMosaicPadChanged(nextPad)
            return
        }

        val surfaceChanged = mosaicSurface != nextSurface
        val hidden = boardVisible && !boardShown
        boardVisible = boardShown
        mosaicSurface = nextSurface
        mosaicNoteRef = nextNoteRef
        me.laumss.notipal.bubbles.PaletteBubbleModule.reloadPresets()
        if (!boardShown || surfaceChanged) {
            paletteRequests.keys.toList().forEach { finishPaletteRequest(it, paletteError("surface_changed")) }
        }
        if (!boardShown) {
            lastSentRect = null
            lastSentOverlayRects = emptyList()
            lastSentFullUiAuto = false
            mosaicToolbarRects = emptyList()
            FloatingToolbarModule.onMosaicSelectionChanged(0, 0)
        }
        Log.i(TAG, "board state visible=$boardShown surface=$nextSurface noteRef=$nextNoteRef pad=$nextPad reason=$reason")
        FloatingToolbarModule.onMosaicVisibilityChanged(boardShown, nextSurface, nextNoteRef)
        if (menuChanged) FloatingToolbarModule.onMosaicChromeMenuChanged(nextMenu)
        if (motionChanged) FloatingToolbarModule.onMosaicMotionChanged(nextMotion)
        if (padChanged) FloatingToolbarModule.onMosaicPadChanged(nextPad)

        FloatingPenGuard.republishMosaicSnapshot("mosaic-state:$reason")
        if (hidden) FloatingPenGuard.onMosaicHidden(reason)
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
        FloatingToolbarModule.onMosaicToolbarRectsChanged()
    }

    fun publishToolbarRect(context: Context?, rect: Rect?, force: Boolean = false) {
        if (!boardVisible) return
        val ctx = context?.applicationContext ?: appContext ?: return
        if (!force && rect == lastSentRect) return
        lastSentRect = rect?.let(::Rect)
        send(ctx, ACTION_TOOLBAR_RECT) {
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

    fun publishOverlayRects(context: Context?, rects: List<Rect>, force: Boolean = false) {
        if (!boardVisible) return
        val ctx = context?.applicationContext ?: appContext ?: return
        val normalized = rects.map(::Rect)
        if (!force && normalized == lastSentOverlayRects) return
        lastSentOverlayRects = normalized.map(::Rect)
        send(ctx, ACTION_OVERLAY_RECTS) {
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

    fun publishFullUiAuto(context: Context?, active: Boolean, resend: Boolean = false) {
        if (active == lastSentFullUiAuto && !(active && resend)) return
        if (active && !boardVisible) return
        val ctx = context?.applicationContext ?: appContext ?: return
        lastSentFullUiAuto = active
        send(ctx, ACTION_OVERLAY_DRAG) { it.putExtra("active", active) }
        Log.i(TAG, "fullUiAuto published active=$active resend=$resend")
    }

    fun requestClose(context: Context?) {
        val ctx = context?.applicationContext ?: appContext ?: return
        Log.i(TAG, "close requested")
        send(ctx, ACTION_REQUEST) { it.putExtra("cmd", CMD_CLOSE) }
    }

    fun sendSketchClipboardResult(context: Context?, outcome: String) {
        val ctx = context?.applicationContext ?: appContext ?: return
        Log.i(TAG, "sketch clipboard result=$outcome")
        send(ctx, ACTION_SKETCH_CLIPBOARD_RESULT) { it.putExtra("outcome", outcome) }
    }

    fun requestPasteStrokes(context: Context?) {
        val ctx = context?.applicationContext ?: appContext ?: return
        Log.i(TAG, "paste-strokes requested")
        send(ctx, ACTION_REQUEST) { it.putExtra("cmd", CMD_PASTE_STROKES) }
    }

    private const val INBOX_DIR = "/sdcard/EXPORT/Notipal/mosaic/inbox"

    fun enqueueImage(imagePathSrc: String, source: String): Boolean {
        me.laumss.notipal.net.PluginIdentity.claim()
        return try {
            val dir = inboxDir() ?: return false
            val id = "inkling-${System.currentTimeMillis()}-${(0..9999).random()}"
            val imagePath = java.io.File(dir, "$id.png")
            java.io.File(imagePathSrc).copyTo(imagePath, overwrite = true)
            val request = org.json.JSONObject().apply {
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

    fun newNoteImportFile(): java.io.File? {
        val dir = inboxDir() ?: return null
        return java.io.File(dir, "inkling-note-${System.currentTimeMillis()}-${(0..9999).random()}$NOTE_PAGES_SUFFIX")
    }

    fun enqueueNote(pagesFile: java.io.File, title: String, source: String): Boolean {
        return try {
            val dir = pagesFile.parentFile ?: return false
            val id = importId(pagesFile)
            val request = org.json.JSONObject().apply {
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

    fun newDocImportDir(): java.io.File? {
        val dir = inboxDir() ?: return null
        return java.io.File(dir, "inkling-doc-${System.currentTimeMillis()}-${(0..9999).random()}$DOC_PAGES_SUFFIX")
    }

    fun enqueueDoc(pagesDir: java.io.File, title: String, source: String): Boolean {
        return try {
            val dir = pagesDir.parentFile ?: return false
            val id = importId(pagesDir)
            val request = org.json.JSONObject().apply {
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

    fun enqueueBoard(kind: String, payload: java.io.File, boardName: String, template: String, titles: List<String>, source: String): Boolean {
        return try {
            val dir = payload.parentFile ?: return false
            val id = importId(payload)
            val request = org.json.JSONObject().apply {
                put("version", 1)
                put("id", id)
                put("type", "board")
                put("reportResult", true)
                put("boardKind", kind)
                put(if (kind == "doc") "docPath" else "notePath", payload.absolutePath)
                put("boardName", boardName)
                put("template", template)
                put("titles", org.json.JSONArray(titles))
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

    private fun importId(payload: java.io.File): String = payload.name.substringBeforeLast('.')

    fun noteImportResultFile(payload: java.io.File): java.io.File =
        java.io.File(payload.parentFile, importId(payload) + ".result")

    fun cancelPendingNoteImport(payload: java.io.File): Boolean {
        val id = importId(payload)
        val pending = java.io.File(payload.parentFile, "$id.json")
        val cancelled = java.io.File(payload.parentFile, "$id.cancelled")
        if (!pending.renameTo(cancelled)) return false
        if (payload.isDirectory) payload.deleteRecursively() else MosaicNotePreparation.discard(payload)
        cancelled.delete()
        return true
    }

    private fun inboxDir(): java.io.File? {
        val dir = java.io.File(INBOX_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "inbox mkdir failed: $INBOX_DIR")
            return null
        }
        return dir
    }

    private fun writeRequest(dir: java.io.File, id: String, request: org.json.JSONObject): Boolean {
        val requestPath = java.io.File(dir, "$id.json")
        val tempRequestPath = java.io.File(dir, "$id.json.tmp")
        java.io.FileOutputStream(tempRequestPath).use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
        if (!tempRequestPath.renameTo(requestPath)) {
            tempRequestPath.delete()
            return false
        }
        appContext?.let { ctx ->
            send(ctx, ACTION_REQUEST) { it.putExtra("cmd", CMD_INBOX) }
        }
        return true
    }

    fun requestTextCard(context: Context?, text: String, anchorScreenX: Int, anchorScreenY: Int) {
        val ctx = context?.applicationContext ?: appContext ?: return
        Log.i(TAG, "text-card requested anchor=($anchorScreenX,$anchorScreenY) len=${text.length}")
        send(ctx, ACTION_REQUEST) {
            it.putExtra("cmd", CMD_TEXT_CARD)
            it.putExtra("text", text)
            it.putExtra("anchorScreenX", anchorScreenX)
            it.putExtra("anchorScreenY", anchorScreenY)
        }
    }

    fun requestClearSelection(context: Context?, delete: Boolean) {
        val ctx = context?.applicationContext ?: appContext ?: return
        Log.i(TAG, "clear-selection requested delete=$delete")
        send(ctx, ACTION_REQUEST) { it.putExtra("cmd", if (delete) CMD_DELETE_SELECTION else CMD_CLEAR_SELECTION) }
    }

    private fun paletteError(error: String) = JSONObject().put("success", false).put("error", error)

    private fun finishPaletteRequest(id: String, result: JSONObject) {
        val pending = paletteRequests.remove(id) ?: return
        paletteHandler.removeCallbacks(pending.second)
        pending.first(result)
    }

    fun requestPalette(context: Context, data: JSONObject? = null, result: (JSONObject) -> Unit) {
        paletteHandler.post {
            ensureRegistered(context)
            if (!boardVisible) { result(paletteError("surface_changed")); return@post }
            val id = UUID.randomUUID().toString()
            val timeout = Runnable { finishPaletteRequest(id, paletteError("timeout")) }
            paletteRequests[id] = result to timeout
            paletteHandler.postDelayed(timeout, 6000L)
            send(context.applicationContext, ACTION_REQUEST) {
                it.putExtra("cmd", if (data == null) "palette_info" else "palette_apply")
                it.putExtra("requestId", id)
                if (data != null) it.putExtra("data", data.toString())
            }
        }
    }

    fun applyPalettePreset(context: Context, penId: String, thickness: Int, result: (JSONObject) -> Unit) {
        requestPalette(context) { info ->
            if (!info.optBoolean("success")) { result(info); return@requestPalette }
            requestPalette(context, info.put("penId", penId).put("thickness", thickness), result)
        }
    }

    private inline fun send(ctx: Context, action: String, fill: (Intent) -> Unit) {
        try {
            ctx.sendBroadcast(Intent(action).apply { setPackage(ctx.packageName); fill(this) })
        } catch (e: Exception) {
            Log.e(TAG, "send $action failed: ${e.message}", e)
        }
    }
}
