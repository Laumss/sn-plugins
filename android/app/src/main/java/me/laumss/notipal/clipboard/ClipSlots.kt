package me.laumss.notipal.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Bitmaps
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostLogMonitor
import me.laumss.notipal.host.HostPenSignals
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.PageRender
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.toolbar.SelectionHold
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.Panel
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val THUMB_MAX_ROWS = 4
private const val THUMB_ROW_GAP = 0.15f
private const val THUMB_ROW_GAIN = 1.15f

class ClipSlotPanel : Panel("clip-slot-picker") {
    override val fullScreen: Boolean get() = true

    class Slot(val slot: String, val filled: Boolean, val thumb: String?)

    private val ctx get() = App.context
    private var slots: List<Slot> = emptyList()
    private var paste = false
    private var onResult: ((String?) -> Unit)? = null
    private var onClearAll: (() -> Unit)? = null

    private var contentRoot: FrameLayout? = null

    private var viewerLayer: View? = null
    private var cellWidth = 0
    private var thumbHeight = 0

    private fun px(v: Int) = Style.hcDp(v)
    private fun textSp(v: Float) = Style.hcSp(v)

    fun open(slots: List<Slot>, paste: Boolean, onClearAll: () -> Unit, onResult: (String?) -> Unit) {
        Main.post {
            this.slots = slots
            this.paste = paste
            this.onClearAll = onClearAll
            this.onResult = onResult
            Panels.open(this) { shown -> if (!shown) finish(null) }
        }
    }

    private fun finish(slot: String?) {
        val callback = onResult ?: return
        onResult = null
        callback(slot)
    }

    private fun choose(slot: String?) {
        finish(slot)
        hide()
    }

    override fun build(root: LinearLayout) {

        root.setBackgroundColor(Style.clear)
        val frame = FrameLayout(ctx).apply {

            isClickable = true
        }
        contentRoot = frame
        root.addView(frame, LinearLayout.LayoutParams(-1, -1))

        val cardWidth = px(600).coerceAtMost(screenW - px(32))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.boxPx(Style.paper, Style.ink, px(1), px(12).toFloat())
            clipToOutline = true
            setOnClickListener {}
        }
        fun divider() = View(ctx).apply {
            setBackgroundColor(Style.muted)
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        }
        card.addView(TextView(ctx).apply {
            text = Strings.get(if (paste) "clip_pick_paste_title" else "clip_pick_title")
            textSize = Style.hcTitleSp(23f); setTextColor(Style.ink)
            typeface = Style.bold; gravity = Gravity.CENTER
            setPadding(px(16), px(22), px(16), px(22))
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(divider())

        val padH = px(28)
        val gap = px(14)
        val columns = 4
        cellWidth = (cardWidth - padH * 2 - gap * (columns - 1)) / columns
        thumbHeight = (cellWidth * 0.75f).roundToInt()
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, px(20), padH, px(24))
        }
        slots.chunked(columns).forEachIndexed { rowIndex, rowSlots ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowSlots.forEachIndexed { col, slot ->
                row.addView(slotCell(slot), LinearLayout.LayoutParams(cellWidth, -2).apply {
                    if (col > 0) marginStart = gap
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = gap })
        }
        card.addView(grid)
        card.addView(divider())

        val bar = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(28), px(20), px(28), px(20))
        }

        val anyFilled = slots.any { it.filled }
        bar.addView(button(Strings.get("clip_clear_all"), enabled = anyFilled) { askClearAll() }, LinearLayout.LayoutParams(-2, -2))
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(button(Strings.get("cancel")) { choose(null) }, LinearLayout.LayoutParams(-2, -2))
        card.addView(bar)
        frame.addView(card, FrameLayout.LayoutParams(cardWidth, -2, Gravity.CENTER))
    }

    private fun button(label: String, enabled: Boolean = true, onClick: () -> Unit) = TextView(ctx).apply {
        val color = if (enabled) Style.ink else Style.muted
        text = label; textSize = textSp(17f)
        setTextColor(color); gravity = Gravity.CENTER
        minWidth = px(106); minHeight = px(44); setPadding(px(16), 0, px(16), 0)
        background = Style.boxPx(Style.paper, color, px(1))
        if (enabled) setOnClickListener { onClick() }
    }

    private fun askClearAll() {
        val clearAll = onClearAll ?: return
        val count = slots.size
        Log.i(tag, "clear all asked (filled=${slots.count { it.filled }})")
        choose(null)
        Main.post {
            Dialogs.confirm(Strings.get("clip_clear_all_confirm", count), Strings.get("cancel"), Strings.get("clip_clear_all_ok")) { ok ->
                Log.i(tag, "clear all ${if (ok) "confirmed" else "cancelled"}")
                if (ok) clearAll()
            }
        }
    }

    private fun slotCell(slot: Slot): View {
        val width = cellWidth
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            if (paste && !slot.filled) isClickable = true else setOnClickListener { choose(slot.slot) }

            if (slot.filled) setOnLongClickListener { showFull(slot); true }
        }
        val box = FrameLayout(ctx).apply {
            background = Style.boxPx(Style.paper, Style.muted, px(1), px(6).toFloat())
            clipToOutline = true
        }
        val inset = px(6)
        val bitmap = slot.thumb?.let { decodeThumb(it, width - inset * 2, thumbHeight - inset * 2) }
        if (bitmap != null) {
            box.addView(ImageView(ctx).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER
                setPadding(inset, inset, inset, inset)
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            box.addView(TextView(ctx).apply {
                text = Strings.get(if (slot.filled) "clip_slot_no_preview" else "clip_slot_empty")
                textSize = textSp(14f); setTextColor(Style.clipSlotHint); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        cell.addView(box, LinearLayout.LayoutParams(width, thumbHeight))
        cell.addView(TextView(ctx).apply {
            text = slot.slot
            textSize = textSp(17f); setTextColor(Style.ink)
            typeface = Style.bold; gravity = Gravity.CENTER
            setPadding(0, px(8), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return cell
    }

    private fun showFull(slot: Slot) {
        val root = contentRoot ?: return
        if (viewerLayer != null) return
        val layer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.paper)

            isClickable = true
        }
        layer.addView(Widgets.titleBar(ctx, Strings.get("clip_view_title", slot.slot), onClose = { dismissFull() }),
            LinearLayout.LayoutParams(-1, -2))
        val pad = px(32)
        val bitmap = slot.thumb?.let { decodeFull(it, screenW - pad * 2, screenH - pad * 2) }
        val body: View = if (bitmap != null) {
            ImageView(ctx).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
            }
        } else {
            TextView(ctx).apply {
                text = Strings.get("clip_slot_no_preview")
                textSize = textSp(20f); setTextColor(Style.clipSlotHint); gravity = Gravity.CENTER
            }
        }
        body.setOnClickListener { dismissFull() }
        layer.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        viewerLayer = layer
        Log.i(tag, "clip slot ${slot.slot} shown full screen (${bitmap?.let { "${it.width}x${it.height}" } ?: "no image"})")
    }

    private fun dismissFull() {
        viewerLayer?.let { contentRoot?.removeView(it) }
        viewerLayer = null
    }

    private fun decodeFull(path: String, maxW: Int, maxH: Int): Bitmap? = runCatching {
        if (!File(path).isFile || maxW <= 0 || maxH <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = Bitmaps.powerOfTwoSampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH)
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.onFailure { Log.w(tag, "decode clip image failed: ${it.message}") }.getOrNull()

    private fun decodeThumb(path: String, width: Int, height: Int): Bitmap? = runCatching {
        if (!File(path).isFile || width <= 0 || height <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val planned = thumbLayout(bounds.outWidth, bounds.outHeight, width, height)
        var sample = 1
        while (planned.scale * sample * 2 <= 1f) sample *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        try {
            val layout = thumbLayout(src.width, src.height, width, height)
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val rowH = src.height * layout.scale
            val segW = width / layout.scale

            val rows = if (layout.rows == 1) 1 else ceil(src.width / segW).toInt().coerceIn(1, layout.rows)
            val gap = if (rows > 1) rowH * THUMB_ROW_GAP else 0f
            val totalH = rowH * rows + gap * (rows - 1)
            var top = (height - totalH) / 2f
            for (row in 0 until rows) {
                val srcLeft = (row * segW).toInt()
                val srcRight = minOf(src.width, ((row + 1) * segW).toInt())
                if (srcRight <= srcLeft) break
                val dstW = (srcRight - srcLeft) * layout.scale
                val left = if (rows == 1) (width - dstW) / 2f else 0f
                canvas.drawBitmap(src, Rect(srcLeft, 0, srcRight, src.height), RectF(left, top, left + dstW, top + rowH), paint)
                top += rowH + gap
            }
            out
        } finally {
            src.recycle()
        }
    }.onFailure { Log.w(tag, "decode clip thumbnail failed: ${it.message}") }.getOrNull()

    private class ThumbLayout(val rows: Int, val scale: Float)

    private fun thumbLayout(w: Int, h: Int, boxW: Int, boxH: Int): ThumbLayout {
        var best = ThumbLayout(1, minOf(boxW.toFloat() / w, boxH.toFloat() / h))
        for (rows in 2..THUMB_MAX_ROWS) {

            val byHeight = boxH / (h * (rows + (rows - 1) * THUMB_ROW_GAP))
            val byWidth = rows.toFloat() * boxW / w
            val scale = minOf(byHeight, byWidth, 1f)
            if (scale > best.scale * THUMB_ROW_GAIN) best = ThumbLayout(rows, scale)
        }
        return best
    }

    override fun onHide() {
        finish(null)
        slots = emptyList()
        onClearAll = null
        contentRoot = null
        viewerLayer = null
    }
}

object ClipSlots {
    private const val TAG = "ClipSlots"

    @Volatile private var module: ClipboardModule? = null

    fun install() {
        MosaicLink.sketchClipboardHandler = { selection -> onSketchRequest(selection) }
    }

    internal fun attach(owner: ClipboardModule) {
        module = owner
    }

    internal fun detach(owner: ClipboardModule) {
        if (module === owner) module = null
    }

    private fun onSketchRequest(selection: Boolean): Boolean {
        val owner = module ?: return false
        if (App.react?.hasActiveReactInstance() != true) {
            Log.w(TAG, "sketch clipboard request not taken: no active React instance")
            return false
        }
        owner.sketchRequested(selection)
        return true
    }

    private const val SYNC_APPLY_TIMEOUT_S = 10L
    private class SyncWait {
        val latch = CountDownLatch(1)
        @Volatile var ok = false
    }
    private val syncRequests = AtomicInteger()
    private val syncWaits = ConcurrentHashMap<Int, SyncWait>()

    fun applySyncedSlots(clips: JSONObject): Boolean {
        val owner = module ?: return false
        if (App.react?.hasActiveReactInstance() != true) return false
        val id = syncRequests.incrementAndGet()
        val wait = SyncWait()
        syncWaits[id] = wait
        try {
            owner.syncApplyRequested(id, clips.toString())
            if (!wait.latch.await(SYNC_APPLY_TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "clipboard sync table: JS did not answer request=$id")
                return false
            }
            return wait.ok
        } finally {
            syncWaits.remove(id)
        }
    }

    internal fun syncApplied(id: Int, ok: Boolean) {
        val wait = syncWaits[id] ?: return
        wait.ok = ok
        wait.latch.countDown()
    }

    fun pick(slots: List<ClipSlotPanel.Slot>, paste: Boolean, onClearAll: () -> Unit, onResult: (String?) -> Unit) {

        Toolbar.allowRestoreOverHostLasso()
        ClipSlotPanel().open(slots, paste, onClearAll = {
            Log.i(TAG, "clip clear all requested")
            onClearAll()
        }) { slot ->
            Log.i(TAG, "clip slot picker result=$slot")
            onResult(slot)
        }
    }
}

class ClipboardModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalClipboard") {
    private val tag = "NotipalClipboard"

    init {
        ClipSlots.attach(this)
    }

    override fun invalidate() {
        ClipSlots.detach(this)
        super.invalidate()
    }

    internal fun sketchRequested(selection: Boolean) {
        emit("clipboard:sketchRequest", Arguments.createMap().apply { putBoolean("selection", selection) })
    }

    internal fun syncApplyRequested(request: Int, clips: String) {
        emit("clipboard:syncApply", Arguments.createMap().apply {
            putInt("request", request)
            putString("clips", clips)
        })
    }

    @ReactMethod
    fun syncApplied(request: Int, ok: Boolean) = ClipSlots.syncApplied(request, ok)

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun dirs(): WritableMap = Arguments.createMap().apply {
        putString("clipboardDir", Dirs.clipboard.path)
        putString("mosaicDir", Dirs.mosaic.path)
    }

    @ReactMethod
    fun pickSlot(slots: ReadableArray, mode: String, promise: Promise) {
        val list = try {
            (0 until slots.size()).map { i ->
                val o = slots.getMap(i) ?: error("slot $i missing")
                ClipSlotPanel.Slot(
                    o.getString("slot") ?: error("slot $i has no number"),
                    o.hasKey("filled") && !o.isNull("filled") && o.getBoolean("filled"),
                    if (o.hasKey("thumb") && !o.isNull("thumb")) o.getString("thumb")?.takeIf { it.isNotEmpty() } else null,
                )
            }
        } catch (e: Exception) {
            promise.reject("CLIP_SLOTS", e.message, e)
            return
        }
        ClipSlots.pick(list, paste = mode == "paste", onClearAll = {
            emit("clipboard:clearAll", Arguments.createMap())
        }) { slot -> promise.resolve(slot) }
    }

    @ReactMethod
    fun hasHostLasso(promise: Promise) {
        try {
            val app = App.pluginApp()
            promise.resolve(app.javaClass.getMethod("isLassoTrail").invoke(app) == true)
        } catch (error: Throwable) {
            Log.w(tag, "isLassoTrail unavailable: $error")
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun observedLassoPageRect(pageWidth: Int, pageHeight: Int, promise: Promise) {
        val display = HostLogMonitor.getObservedLassoDisplayRect()
        val screenWidth = HostSession.screenWidth
        val screenHeight = HostSession.screenHeight
        if (display == null || screenWidth <= 0 || screenHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            promise.resolve(null)
            return
        }
        promise.resolve(Arguments.createMap().apply {
            putInt("left", (display.left * pageWidth / screenWidth).coerceIn(0, pageWidth))
            putInt("top", (display.top * pageHeight / screenHeight).coerceIn(0, pageHeight))
            putInt("right", (display.right * pageWidth / screenWidth).coerceIn(0, pageWidth))
            putInt("bottom", (display.bottom * pageHeight / screenHeight).coerceIn(0, pageHeight))
        })
    }

    @ReactMethod
    fun resizeInsertedSticker(rect: ReadableMap, promise: Promise) {
        PageRender.resizeLassoRectAfterInsert(reactApplicationContext, rect) { ok, error ->
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(tag, "[CLIP-DBG/NATIVE] resizeInsertedSticker done ok=$ok error=${error ?: "none"}")
            }
            promise.resolve(ok)
        }
    }

    @ReactMethod
    fun clearContours(uuids: ReadableArray, context: ReadableMap, promise: Promise) {
        ClipboardIo.clearContours(uuids, context, promise)
    }

    @ReactMethod
    fun convertSticker(metadata: ReadableArray, context: ReadableMap, promise: Promise) {
        ClipboardIo.convertSticker(metadata, context, promise)
    }

    @ReactMethod
    fun restoreContours(path: String, promise: Promise) {
        ClipboardIo.restoreContours(path, promise)
    }

    @ReactMethod
    fun exportSidecar(
        path: String, uuids: ReadableArray, pageWidth: Int, pageHeight: Int,
        sourceRect: ReadableMap?, targetRect: ReadableMap?, promise: Promise,
    ) {
        ClipboardIo.exportSidecar(path, uuids, pageWidth, pageHeight, sourceRect, targetRect, promise)
    }

    @ReactMethod
    fun stickerInserted() {
        HostPenSignals.onStickerInserted(HostSession.state.document)
    }

    @ReactMethod
    fun beginPaste() = SelectionHold.begin(PASTE_TXN)

    @ReactMethod
    fun finishPaste(selection: Boolean) {
        SelectionHold.park(selection)
        SelectionHold.finish()
    }

    @ReactMethod
    fun copyText(text: String) = ClipboardIo.copyText(text)

    private companion object {

        const val PASTE_TXN = "clip_paste"
    }
}
