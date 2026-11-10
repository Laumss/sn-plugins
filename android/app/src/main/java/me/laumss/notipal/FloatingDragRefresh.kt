package me.laumss.notipal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import me.laumss.notipal.ui_common.ScreenScale
import java.util.WeakHashMap

internal object FloatingDragRefresh {
    private const val TAG = "InklingDragRefresh"

    private const val AUTO_HOLD_MS = 1400L

    private const val FRAME_STROKE_PX = 4
    private const val FRAME_DASH_PX = 6

    private val handler = Handler(Looper.getMainLooper())
    private val activeDrags = WeakHashMap<View, DragState>()

    private class DragState(
        val windowManager: WindowManager,
        var proxyRoot: FrameLayout?,
        val frame: DashFrame?,
        val owner: String,

        var autoHeld: Boolean,

        var release: Runnable? = null,
        var followsLayoutParams: Boolean = false,
        var layoutOriginX: Int = 0,
        var layoutOriginY: Int = 0,
        var screenOriginX: Int = 0,
        var screenOriginY: Int = 0,
        var xDirection: Int = 1,
        var yDirection: Int = 1,

        val beganAt: Long = SystemClock.uptimeMillis(),
        var lastMoveAt: Long = beganAt,
        var lastStepPx: Int = 0,
        var moves: Int = 0
    )

    private class DashEdge(context: Context, private val thickness: Int) : View(context) {
        private val paint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.BLACK
            isAntiAlias = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val horizontal = w >= h
            val length = if (horizontal) w else h
            var pos = 0
            while (pos < length) {
                val end = minOf(pos + FRAME_DASH_PX, length).toFloat()
                if (horizontal) canvas.drawRect(pos.toFloat(), 0f, end, thickness.toFloat(), paint)
                else canvas.drawRect(0f, pos.toFloat(), thickness.toFloat(), end, paint)
                pos += FRAME_DASH_PX * 2
            }
        }
    }

    private class DashFrame(
        context: Context,
        root: FrameLayout,
        private val stroke: Int,
        width: Int,
        height: Int
    ) {
        private val edges = List(4) { DashEdge(context, stroke) }
        val rect = Rect(0, 0, width, height)
        private var shown = true

        init {
            val sizes = listOf(width to stroke, width to stroke, stroke to height, stroke to height)
            edges.forEachIndexed { i, edge -> root.addView(edge, FrameLayout.LayoutParams(sizes[i].first, sizes[i].second)) }
        }

        fun moveTo(x: Int, y: Int) {
            rect.offsetTo(x, y)
            val now = strips(rect)
            edges.forEachIndexed { i, edge ->
                edge.x = now[i].left.toFloat()
                edge.y = now[i].top.toFloat()
            }
        }

        fun hide() {
            if (!shown) return
            shown = false
            edges.forEach { it.visibility = View.INVISIBLE }
        }

        private fun strips(r: Rect): List<Rect> = listOf(
            Rect(r.left, r.top, r.right, r.top + stroke),
            Rect(r.left, r.bottom - stroke, r.right, r.bottom),
            Rect(r.left, r.top, r.left + stroke, r.bottom),
            Rect(r.right - stroke, r.top, r.right, r.bottom)
        )
    }

    fun begin(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        begin(view, location[0], location[1])
    }

    fun begin(view: View, params: WindowManager.LayoutParams) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        begin(view, location[0], location[1])

        val absoluteGravity = Gravity.getAbsoluteGravity(params.gravity, view.layoutDirection)
        synchronized(activeDrags) {
            activeDrags[view]?.apply {
                followsLayoutParams = true
                layoutOriginX = params.x
                layoutOriginY = params.y
                screenOriginX = location[0]
                screenOriginY = location[1]
                xDirection = if (
                    (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
                ) -1 else 1
                yDirection = if (
                    (absoluteGravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
                ) -1 else 1
            }
        }
    }

    fun begin(view: View, screenX: Int, screenY: Int) {
        val existing = synchronized(activeDrags) { activeDrags[view] }
        var inheritedAuto = false
        if (existing != null) {
            val pendingRelease = existing.release
            if (pendingRelease == null) {
                move(view, screenX, screenY)
                return
            }

            handler.removeCallbacks(pendingRelease)
            synchronized(activeDrags) {
                if (activeDrags[view] === existing) activeDrags.remove(view)
            }
            view.alpha = 1f
            inheritedAuto = existing.autoHeld
            existing.autoHeld = false
            destroyProxy(view, existing)
        }

        val owner = "drag@" + Integer.toHexString(System.identityHashCode(view))
        val autoHeld = inheritedAuto || EinkAuto.acquire(view.context, owner, false)

        if (autoHeld && !inheritedAuto) FloatingPenGuard.reassert("drag begin fullUiAuto")
        val state = createProxy(view, screenX, screenY, owner, autoHeld)
        synchronized(activeDrags) { activeDrags[view] = state }

        if (state.frame != null) {
            view.alpha = 0f
            Log.i(
                TAG,
                "dragFrame begin auto=$autoHeld inherited=$inheritedAuto screen=$screenX,$screenY " +
                    "size=${view.width}x${view.height} view=${view.javaClass.name}"
            )
        } else {
            Log.w(TAG, "dragFrame fallback auto=$autoHeld view=${view.javaClass.name}")
        }
    }

    fun move(view: View, screenX: Int, screenY: Int): Boolean {
        val state = synchronized(activeDrags) { activeDrags[view] } ?: return false
        val frame = state.frame ?: return false
        if (frame.rect.left == screenX && frame.rect.top == screenY) return true
        state.lastStepPx = Math.hypot((screenX - frame.rect.left).toDouble(), (screenY - frame.rect.top).toDouble()).toInt()
        state.lastMoveAt = SystemClock.uptimeMillis()
        state.moves++
        frame.moveTo(screenX, screenY)
        return true
    }

    fun move(view: View, params: WindowManager.LayoutParams): Boolean {
        val state = synchronized(activeDrags) { activeDrags[view] } ?: return false
        if (!state.followsLayoutParams) return false
        return move(
            view,
            state.screenOriginX + (params.x - state.layoutOriginX) * state.xDirection,
            state.screenOriginY + (params.y - state.layoutOriginY) * state.yDirection
        )
    }

    fun end(view: View) {
        val state = synchronized(activeDrags) { activeDrags[view] } ?: return
        state.release?.let { handler.removeCallbacks(it) }
        val now = SystemClock.uptimeMillis()
        Log.i(
            TAG,
            "dragFrame end sinceBegin=${now - state.beganAt}ms sinceLastMove=${now - state.lastMoveAt}ms " +
                "lastStep=${state.lastStepPx}px moves=${state.moves} auto=${state.autoHeld}"
        )

        view.alpha = 1f
        view.postInvalidateOnAnimation()
        afterFirstDraw(view) {
            state.frame?.hide()
            Log.i(TAG, "dragFrame window drawn, frame cleared ${SystemClock.uptimeMillis() - now}ms after end")
        }

        lateinit var release: Runnable
        release = Runnable {
            val owned = synchronized(activeDrags) {
                if (activeDrags[view]?.release === release) {
                    activeDrags.remove(view)
                    true
                } else {
                    false
                }
            }
            if (owned) destroyProxy(view, state)
        }
        state.release = release
        handler.postDelayed(release, AUTO_HOLD_MS)
    }

    fun endNow(view: View) {
        val state = synchronized(activeDrags) { activeDrags.remove(view) } ?: return
        state.release?.let { handler.removeCallbacks(it) }
        view.alpha = 1f
        destroyProxy(view, state)
    }

    private fun createProxy(
        view: View,
        screenX: Int,
        screenY: Int,
        owner: String,
        autoHeld: Boolean
    ): DragState {
        val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return DragState(wm, null, null, owner, autoHeld)

        var root: FrameLayout? = null
        try {
            root = FrameLayout(view.context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                clipChildren = false
                clipToPadding = false
            }
            val stroke = ScreenScale.px(view.context, FRAME_STROKE_PX).coerceAtLeast(1)
            val frame = DashFrame(view.context, root, stroke, width, height)
            frame.moveTo(screenX, screenY)
            val displayMetrics = view.resources.displayMetrics
            root.measure(
                View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(displayMetrics.heightPixels, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            wm.addView(root, lp)
            Log.i(
                TAG,
                "dragFrame attached root=${root.width}x${root.height} frame=${width}x$height " +
                    "rootAttached=${root.isAttachedToWindow}"
            )
            return DragState(wm, root, frame, owner, autoHeld)
        } catch (e: Exception) {
            root?.let { runCatching { wm.removeViewImmediate(it) } }
            Log.w(TAG, "dragFrame create exception view=${view.javaClass.simpleName}: ${e.message}")
            return DragState(wm, null, null, owner, autoHeld)
        }
    }

    private fun removeProxyWindow(state: DragState) {
        val root = state.proxyRoot ?: return
        state.proxyRoot = null
        runCatching { state.windowManager.removeViewImmediate(root) }
            .onFailure { Log.w(TAG, "dragFrame remove exception: ${it.message}") }
    }

    private fun afterFirstDraw(view: View, action: () -> Unit) {
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var fired = false
            override fun onDraw() {
                if (fired) return
                fired = true
                val self = this
                view.post {
                    val vto = view.viewTreeObserver
                    if (vto.isAlive) vto.removeOnDrawListener(self)
                    action()
                }
            }
        }
        observer.addOnDrawListener(listener)
    }

    private fun releaseAuto(view: View, state: DragState, reason: String) {
        if (!state.autoHeld) return
        state.autoHeld = false
        EinkAuto.release(view.context, state.owner)
        FloatingPenGuard.reassert("$reason fullUiAuto")
    }

    private fun destroyProxy(view: View, state: DragState) {
        removeProxyWindow(state)
        releaseAuto(view, state, "drag released")
        Log.i(TAG, "dragFrame released view=${view.javaClass.name}")
    }
}
