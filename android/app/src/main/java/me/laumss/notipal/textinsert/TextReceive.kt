package me.laumss.notipal.textinsert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import me.laumss.notipal.common.App
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.FloatWindow
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.WindowSupport
import kotlin.math.roundToInt

object TextReceive {
    fun receiving(): Boolean = TextBubble.showing || Toolbar.isActive(Tools.TEXT_RECEIVE)
}

internal object TextBubble : FloatWindow("text") {
    override val tag: String get() = TAG
    private const val TAG = "TextBubble"

    private const val PROGRAMMATIC_GAP_X_DP = 12
    private const val PROGRAMMATIC_GAP_Y_DP = 12
    private const val BUBBLE_W_DP = 56
    private const val BUBBLE_H_DP = 38
    private const val LONG_PRESS_MS = 650L

    private var pageWidth = 1404
    private var pageHeight = 1872

    @Volatile var module: TextModule? = null

    private var capsule: CapsuleView? = null

    var pending: Boolean = false
        private set
    private var pageFull: Boolean = false

    override val longPressMs: Long get() = if (pending) LONG_PRESS_MS else 0L

    fun open() {
        if (showing) return
        show(content())
    }

    fun openAt(pageX: Int, pageY: Int) {
        val (screenW, screenH) = Style.screenSize()
        val density = App.context.resources.displayMetrics.density
        val scale = WindowSupport.bubbleScale()
        val insertionX = if (pageWidth > 0) (pageX.toFloat() * screenW / pageWidth).toInt() else pageX
        val insertionY = if (pageHeight > 0) (pageY.toFloat() * screenH / pageHeight).toInt() else pageY
        val gapX = (PROGRAMMATIC_GAP_X_DP * density * scale).roundToInt()
        val gapY = (PROGRAMMATIC_GAP_Y_DP * density * scale).roundToInt()
        val x = insertionX - widthPx() - gapX
        val y = insertionY - heightPx() - gapY
        Log.i(TAG, "openAt page=($pageX,$pageY) insertion=($insertionX,$insertionY) window=($x,$y)")
        moveTo(x, y)
        if (!showing) show(content()) else emitCoords("layout")
    }

    fun close() {
        if (!showing) return
        hide()
    }

    fun setState(pending: Boolean, pageFull: Boolean) {
        this.pending = pending
        this.pageFull = pageFull
        capsule?.let {
            it.pending = pending
            it.pageFull = pageFull
        }
    }

    fun setPageSize(width: Int, height: Int) {
        if (width > 0) pageWidth = width
        if (height > 0) pageHeight = height
    }

    private fun widthPx(): Int =
        (BUBBLE_W_DP * App.context.resources.displayMetrics.density * WindowSupport.bubbleScale()).roundToInt()

    private fun heightPx(): Int =
        (BUBBLE_H_DP * App.context.resources.displayMetrics.density * WindowSupport.bubbleScale()).roundToInt()

    private fun content(): View {
        val density = App.context.resources.displayMetrics.density * WindowSupport.bubbleScale()
        return CapsuleView(App.context, density).also {
            it.pending = pending
            it.pageFull = pageFull
            it.layoutParams = LinearLayout.LayoutParams(widthPx(), heightPx())
            capsule = it
        }
    }

    override fun clampDrag(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Point {
        val maxX = (screenW * 3 / 5 - w).coerceAtLeast(0)
        return Point(x.coerceIn(0, maxX), WindowSupport.clampToBand(screenH, y, h))
    }

    override fun onAttached(view: View) {

        view.post { if (attached) emitCoords("layout") }
    }

    override fun onTap() = emitCoords("tap")

    override fun acceptsLongPress(): Boolean = pending

    override fun onLongPress() = emitCoords("longPress")

    override fun onMoved(x: Int, y: Int) = emitCoords("drag")

    override fun closeForRotation() {
        val was = showing
        hide()
        if (was) {
            Log.i(TAG, "closed by window change")
            module?.emit("text:bubbleClosed", Arguments.createMap())
        }
    }

    private fun emitCoords(kind: String) {
        val owner = module ?: return
        val pos = position ?: return
        val v = windowView ?: return
        val (screenW, screenH) = Style.screenSize()
        val sx = pos.x.toFloat()
        val sy = pos.y.toFloat()
        val bubbleH = v.height
        val bubbleW = v.width
        val sBottom = sy + bubbleH
        val ry = if (screenH > 0) pageHeight.toFloat() / screenH else 1f
        val rx = if (screenW > 0) pageWidth.toFloat() / screenW else 1f
        owner.emit("text:bubble", Arguments.createMap().apply {
            putString("kind", kind)
            putDouble("screenX", sx.toDouble())
            putDouble("screenY", sy.toDouble())
            putDouble("screenBottomY", sBottom.toDouble())
            putInt("pageX", (sx * rx).toInt())
            putInt("pageY", (sy * ry).toInt())
            putInt("pageBottomY", (sBottom * ry).toInt())
            putInt("bubbleWidth", bubbleW)
            putInt("bubbleHeight", bubbleH)
        })
    }
}

private class CapsuleView(ctx: Context, private val density: Float) : View(ctx) {

    var pending: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var pageFull: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Style.textBubbleInk
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val shape = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val inset = borderPaint.strokeWidth / 2f
        shape.set(inset, inset, width - inset, height - inset)
        val r = shape.height() / 2f
        bgPaint.color = if (pending) Style.textBubbleInk else Style.paper
        canvas.drawRoundRect(shape, r, r, bgPaint)
        canvas.drawRoundRect(shape, r, r, borderPaint)

        val iconPx = (r * 1.6f).toInt().coerceAtLeast(1)
        val asset = if (pending) "icons/ic_bubble_text_ready.xml" else "icons/ic_bubble_text_idle.xml"
        val icon = Style.iconBitmap(asset, iconPx, if (pending) Style.paper else Style.textBubbleInk) ?: return
        canvas.drawBitmap(icon, cx - icon.width / 2f, cy - icon.height / 2f, iconPaint)
        if (pageFull) drawPageFullBadge(canvas, r)
    }

    private fun drawPageFullBadge(canvas: Canvas, r: Float) {
        val br = r * 0.42f
        val bx = shape.right - br
        val by = shape.top + br
        bgPaint.color = Style.textBubbleInk
        canvas.drawCircle(bx, by, br, bgPaint)
        badgeStroke.color = Style.paper
        badgeStroke.strokeWidth = 2f * density
        canvas.drawCircle(bx, by, br, badgeStroke)
        canvas.drawLine(bx - br * 0.5f, by, bx + br * 0.5f, by, badgeStroke)
    }
}

class TextModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalText") {
    private val layout = TextLayoutEngine()
    private val metrics = TextboxMetrics()

    init {
        TextBubble.module = this
    }

    override fun initialize() {
        super.initialize()

        Main.post {
            if (TextBubble.module !== this) return@post
            TextBubble.close()
            TextBubble.setState(pending = false, pageFull = false)
            Toolbar.exitMode(Tools.TEXT_RECEIVE)
        }
    }

    override fun invalidate() {
        if (TextBubble.module === this) TextBubble.module = null

        Main.post { if (TextBubble.module == null) TextBubble.close() }
        super.invalidate()
    }

    @ReactMethod
    fun showBubble() = Main.post { TextBubble.open() }

    @ReactMethod
    fun showBubbleAt(pageX: Double, pageY: Double) = Main.post { TextBubble.openAt(pageX.roundToInt(), pageY.roundToInt()) }

    @ReactMethod
    fun hideBubble() = Main.post { TextBubble.close() }

    @ReactMethod
    fun setBubbleState(pending: Boolean, pageFull: Boolean) = Main.post { TextBubble.setState(pending, pageFull) }

    @ReactMethod
    fun setPageSize(width: Double, height: Double) = Main.post { TextBubble.setPageSize(width.roundToInt(), height.roundToInt()) }

    @ReactMethod
    fun calculateLayout(params: ReadableMap, promise: Promise) = layout.calculateLayout(params, promise)

    @ReactMethod
    fun splitTextForHeight(options: ReadableMap, promise: Promise) = metrics.splitTextForHeight(options, promise)
}
