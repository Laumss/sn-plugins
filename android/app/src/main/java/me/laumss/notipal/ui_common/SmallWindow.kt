package me.laumss.notipal.ui_common

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.host.SystemKeyboard
import kotlin.math.roundToInt

open class SmallWindow(
    key: String,
    private val bubbleIcon: String,
    private val bubbleStrokeDeltaPx: Float = 0f,
) : FloatWindow(key) {
    override val tag: String get() = "SmallWindow"

    companion object {
        private const val WIDTH_RATIO = 0.56f
        private const val HEIGHT_RATIO = 0.456f
        private const val MARGIN_DP = 24

        private const val KEEP_PX = 120

        const val BORDER_PX = 2

        const val BUBBLE_PX = 112
        const val BUBBLE_ICON_PX = 56

        private const val CANDIDATE_STRIP_DP = 80.5f
        private const val IME_SLACK_DP = 4

        private val KEYBOARD_LOOKS_MS = longArrayOf(0L, 150L, 400L, 800L)
        private const val COLLAPSED = "collapsed"
        private const val FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    override val dragByBody: Boolean get() = false
    override val stowsUnderPanels: Boolean get() = false
    override val rememberPosition: Boolean get() = false

    private var body: View? = null
    private var bubble: Bubble? = null
    private var marked = false

    var typing = false
        private set

    private val onSystemKeyboard: (Boolean) -> Unit = { shown ->
        val view = windowView
        if (!shown) endTyping()
        else if (view != null) KEYBOARD_LOOKS_MS.forEach { ms -> view.postDelayed({ refreshKeyboard("keyboard+$ms") }, ms) }
    }
    private var keyboardSeen = false
    private var keys = 0
    private var measured = ""
    private var restY: Int? = null
    private var restH: Int? = null

    var underInputHeight: () -> Int = { 0 }

    protected open fun onTypingEnded() {}

    var collapsed = false
        private set

    val up: Boolean get() = showing && !collapsed

    fun setBody(view: View) {
        Main.assertMain()
        body = view

        if (showing) show(view)
    }

    fun open() {
        Main.assertMain()
        val view = body ?: return
        if (collapsed) {
            expand()
            return
        }
        show(view)
    }

    fun close() {
        Main.assertMain()
        endTyping()

        bubble?.hide()
        collapsed = false
        hide()
    }

    fun setDragHandle(handle: View) {
        handle.setOnTouchListener { _, ev -> if (collapsed) false else handleDragTouch(ev) }
    }

    override fun windowSize(content: View, screenW: Int, screenH: Int): Pair<Int, Int> {
        val short = minOf(screenW, screenH)
        val long = maxOf(screenW, screenH)
        val w = (short * WIDTH_RATIO).roundToInt()
        val h = (long * HEIGHT_RATIO).roundToInt()
        Log.i(tag, "mini size ${w}x$h for screen ${screenW}x$screenH")
        return w to h
    }

    override fun buildParams(w: Int, h: Int): WindowManager.LayoutParams = WindowSupport.overlayParams(w, h).apply {
        flags = FLAGS

        screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }

    override fun defaultPosition(w: Int, h: Int, screenW: Int, screenH: Int): Point {
        val margin = Style.dp(MARGIN_DP)
        return Point(screenW - w - margin, screenH - h - margin)
    }

    private var placedW = 0
    private var placedH = 0

    override fun clamp(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Point {
        if (screenW != placedW || screenH != placedH) {
            placedW = screenW
            placedH = screenH
            return Point(x.coerceIn(0, (screenW - w).coerceAtLeast(0)), y.coerceIn(0, (screenH - h).coerceAtLeast(0)))
        }
        val keepX = Style.px(KEEP_PX).coerceAtMost(w)
        val keepY = Style.px(KEEP_PX).coerceAtMost(h)
        return Point(x.coerceIn(keepX - w, screenW - keepX), y.coerceIn(0, (screenH - keepY).coerceAtLeast(0)))
    }

    override fun onMoved(x: Int, y: Int) {

        restY = null
        Log.i(tag, "mini dragEnd x=$x y=$y")
    }

    fun collapse() {
        Main.assertMain()

        val at = position ?: return
        val view = windowView ?: return
        if (!showing || collapsed) return
        endTyping()
        val right = at.x + view.width
        val top = at.y
        collapsed = true

        val b = bubble ?: Bubble("$key.bubble").also { bubble = it }
        b.anchorRight = right
        b.anchorTop = top
        b.showBubble(marked)
        stow(COLLAPSED)
        Log.i(tag, "mini collapsed to bubble")
        onCollapsedChanged(true)
    }

    fun expand() {
        Main.assertMain()
        if (!collapsed) return
        collapsed = false

        unstow(COLLAPSED)
        val b = bubble
        Main.post { if (!collapsed) b?.hide() }
        Log.i(tag, "mini expanded from bubble")
        onCollapsedChanged(false)
    }

    protected open fun onCollapsedChanged(collapsed: Boolean) {}

    protected open fun onBubbleTap() = expand()

    fun putAway(reason: String) {
        Main.assertMain()
        endTyping()
        stow(reason)
        bubble?.stow(reason)
    }

    fun bringBack(reason: String) {
        Main.assertMain()
        unstow(reason)
        bubble?.unstow(reason)
    }

    fun setMarked(value: Boolean) {
        marked = value
        bubble?.setMarked(value)
    }

    fun beginTyping(focusInput: () -> Unit) {
        Main.assertMain()
        val view = windowView ?: return
        if (typing) return
        typing = true
        keyboardSeen = false
        keys = 0
        measured = ""
        Panels.setScreenOwner(this, true)
        SystemKeyboard.addListener(onSystemKeyboard)

        if (!SystemKeyboard.shown) PenGuard.expectKeyboard()
        updateParams { it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() }
        Log.i(tag, "mini typing begins")
        view.postDelayed({
            if (!typing || windowView !== view) return@postDelayed
            focusInput()
        }, 150)
        KEYBOARD_LOOKS_MS.forEach { ms -> view.postDelayed({ refreshKeyboard("mini-typing+$ms") }, 350 + ms) }
    }

    fun endTyping() {
        if (!typing) return
        typing = false
        keyboardSeen = false
        SystemKeyboard.removeListener(onSystemKeyboard)
        Panels.setScreenOwner(this, false)
        PenGuard.endKeyboardExpect("typing ended")
        onTypingEnded()
        val y = restY
        val h = restH
        restY = null
        restH = null
        updateParams { lp ->
            if (y != null) lp.y = y
            if (h != null) lp.height = h
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        Log.i(tag, "mini typing ends y=${currentParams?.y}")
    }

    fun refreshKeyboard(after: String) {
        val view = windowView ?: return
        val lp = currentParams ?: return
        if (!typing) return
        val density = view.resources.displayMetrics.density
        val screenH = Style.screenSize().second
        val visible = Rect().also(view::getWindowVisibleDisplayFrame)
        val framed = (screenH - visible.bottom).coerceAtLeast(0)
        val reported = SystemKeyboard.imeVisibleHeight(view.context)
        val seen = 120 * density
        if (reported > seen) keys = if (keys > 0) minOf(keys, reported) else reported
        val keyArea = if (framed > seen) framed else keys.takeIf { reported > seen } ?: 0
        val actualY = IntArray(2).also(view::getLocationOnScreen)[1]
        val now = "lp.y=${lp.y} actualY=$actualY reported=$reported framed=$framed keys=$keyArea"
        if (now != measured) {
            measured = now
            Log.i(tag, "mini measure $now after=$after")
        }
        if (keyArea <= 0) {

            if (keyboardSeen && !SystemKeyboard.shown) endTyping()
            return
        }
        keyboardSeen = true
        setKeyboardInset(keyArea)
    }

    fun setKeyboardInset(px: Int) {
        val view = windowView ?: return
        val lp = currentParams ?: return
        if (!typing || px <= 0) return
        val density = view.resources.displayMetrics.density
        val screenH = Style.screenSize().second
        val strip = (CANDIDATE_STRIP_DP * density).roundToInt()
        val room = screenH - px - strip + underInputHeight() + Style.dp(IME_SLACK_DP)
        val baseY = restY ?: lp.y.also { restY = it }
        val baseH = restH ?: lp.height
        val y = minOf(baseY, room - baseH).coerceAtLeast(0)
        val h = minOf(baseH, room - y).coerceAtLeast(Style.px(KEEP_PX))
        if (y == lp.y && h == lp.height) return
        if (h != baseH) restH = baseH
        updateParams {
            it.y = y
            it.height = h
        }
        Log.i(tag, "mini above keyboard y=$y h=$h keys=$px strip=$strip")
    }

    override fun onDetached() {
        endTyping()
    }

    override fun closeForRotation() = close()

    override fun onRotation() {
        endTyping()
        super.onRotation()
        bubble?.onRotation()
    }

    private inner class Bubble(key: String) : FloatWindow(key) {
        override val tag: String get() = "SmallWindowBubble"
        override val rememberPosition: Boolean get() = false
        override val stowsUnderPanels: Boolean get() = false

        override val closeOnRotation: Boolean get() = false

        override val penTapNeedsStill: Boolean get() = true

        var anchorRight = 0
        var anchorTop = 0
        private val size get() = Style.px(BUBBLE_PX)
        private var face: Face? = null

        fun showBubble(marked: Boolean) {
            val view = Face(App.context).also { face = it }
            view.marked = marked
            view.layoutParams = FrameLayout.LayoutParams(size, size)
            show(view)
        }

        fun setMarked(value: Boolean) {
            face?.let {
                if (it.marked != value) {
                    it.marked = value
                    it.invalidate()
                }
            }
        }

        override fun windowSize(content: View, screenW: Int, screenH: Int): Pair<Int, Int> = size to size

        override fun buildParams(w: Int, h: Int): WindowManager.LayoutParams = WindowSupport.overlayParams(w, h).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }

        override fun defaultPosition(w: Int, h: Int, screenW: Int, screenH: Int): Point = Point(anchorRight - w, anchorTop)

        private var placedW = 0
        private var placedH = 0

        override fun clamp(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Point {
            if (screenW != placedW || screenH != placedH) {
                placedW = screenW
                placedH = screenH
                return Point(x.coerceIn(0, (screenW - w).coerceAtLeast(0)), y.coerceIn(0, (screenH - h).coerceAtLeast(0)))
            }
            return Point(x.coerceIn(-w / 2, screenW - w / 2), y.coerceIn(-h / 2, screenH - h / 2))
        }

        override fun onTap() = this@SmallWindow.onBubbleTap()
    }

    private inner class Face(ctx: Context) : FrameLayout(ctx) {
        var marked = false
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.ink }

        init {
            background = Style.oval(Style.paper, Style.ink, Style.px(BORDER_PX).coerceAtLeast(1))
            val icon = Style.px(BUBBLE_ICON_PX)
            addView(ImageView(ctx).apply {
                setImageDrawable(Style.iconPx(bubbleIcon, icon, Style.ink, bubbleStrokeDeltaPx))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LayoutParams(icon, icon, Gravity.CENTER))
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!marked) return
            val r = Style.px(DOT_PX) / 2f
            canvas.drawCircle(width - r * 1.6f, r * 1.6f, r, dotPaint)
        }
    }
}

private const val DOT_PX = 22
