package me.laumss.notipal.ui_common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Cancelable
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.common.TouchInput
import me.laumss.notipal.host.DisplayChange
import me.laumss.notipal.host.EinkAuto
import me.laumss.notipal.host.ForegroundActivity
import me.laumss.notipal.host.HostHooks
import me.laumss.notipal.host.HostLogMonitor
import me.laumss.notipal.host.HostNotice
import me.laumss.notipal.host.HostPenSignals
import me.laumss.notipal.host.HostProbe
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.MonitorPhase
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.host.PenGuardTiming
import me.laumss.notipal.mosaic.MosaicLink
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

open class TouchSinkLayout(context: Context) : LinearLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_STYLUS)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}

object WindowHooks {

    @Volatile var overlaysSuspended: () -> Boolean = { false }

    @Volatile var lexiOpen: () -> Boolean = { false }

    @Volatile var lexiHold: (owner: String) -> Unit = {}
    @Volatile var lexiRelease: (owner: String) -> Unit = {}
}

internal object WindowSupport {

    const val DRAG_THRESHOLD_PX = 10

    private const val V_MARGIN_RATIO = 0.06f

    fun windowManager(): WindowManager = App.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Suppress("DEPRECATION")
    fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    fun clampToBand(screenH: Int, desired: Int, viewH: Int): Int {
        val minY = (screenH * V_MARGIN_RATIO).roundToInt()
        val maxY = ((screenH * (1f - V_MARGIN_RATIO)).roundToInt() - viewH).coerceAtLeast(minY)
        return desired.coerceIn(minY, maxY)
    }

    fun bubbleScale(): Float = maxOf(0.86f, Style.factor())
}

open class FloatWindow(val key: String) {
    protected open val tag: String get() = "FloatWindow"

    val penKey: String get() = "float:$key"

    private var root: TouchSinkLayout? = null
    private var content: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wanted = false
    private val stowReasons = LinkedHashSet<String>()

    private var startX = 0
    private var startY = 0
    private var startRawX = 0f
    private var startRawY = 0f
    private var dragging = false
    private var pressByFinger = true
    private var dragScreenW = 0
    private var dragScreenH = 0
    private var longPressFired = false
    private val longPress = Runnable {
        if (dragging || root == null || !acceptsLongPress()) return@Runnable
        longPressFired = true
        onLongPress()
    }

    val showing: Boolean get() = wanted

    val attached: Boolean get() = root != null

    protected val windowView: View? get() = root

    val position: Point? get() = params?.takeIf { root != null }?.let { Point(it.x, it.y) }

    protected open val rememberPosition: Boolean get() = true

    protected open val longPressMs: Long get() = 0L
    protected open val longPressByPen: Boolean get() = false

    protected open val dragByBody: Boolean get() = true

    open val stowsUnderPanels: Boolean get() = true

    open val closeOnRotation: Boolean get() = true

    open val closeOnSurfaceChange: Boolean get() = closeOnRotation

    protected open fun windowSize(content: View, screenW: Int, screenH: Int): Pair<Int, Int> {
        naturalSize?.let { return it }
        val lp = content.layoutParams
        val size = if (lp != null && lp.width > 0 && lp.height > 0) {
            lp.width to lp.height
        } else {
            content.measure(
                View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
            )
            content.measuredWidth to content.measuredHeight
        }
        naturalSize = size
        return size
    }

    private var naturalSize: Pair<Int, Int>? = null

    protected open val penTapNeedsStill: Boolean get() = false

    protected open fun defaultPosition(w: Int, h: Int, screenW: Int, screenH: Int): Point = Point(56, 56)

    protected open fun clamp(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Point =
        Point(x.coerceIn(0, (screenW - w).coerceAtLeast(0)), WindowSupport.clampToBand(screenH, y, h))

    protected open fun clampDrag(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Point =
        clamp(x, y, w, h, screenW, screenH)

    protected open fun buildParams(w: Int, h: Int): WindowManager.LayoutParams = WindowSupport.overlayParams(w, h)

    open fun onTap() {}
    open fun onLongPress() {}

    protected open fun acceptsLongPress(): Boolean = true

    open fun onMoved(x: Int, y: Int) {}

    protected open fun onPressBegin(finger: Boolean) {}
    protected open fun onPressEnd(longPressed: Boolean) {}

    protected open fun onAttached(view: View) {}
    protected open fun onDetached() {}

    fun show(content: View) {
        Main.assertMain()
        if (root != null) detach()
        if (this.content !== content) naturalSize = null
        this.content = content
        wanted = true
        FloatWindows.register(this)
        stowReasons.addAll(FloatWindows.reasonsFor(this))
        attachIfAllowed()

        if (root == null) Log.i(tag, "$key wanted but not shown: ${blockedBy()}")
    }

    fun blockedBy(): String =
        "stow=$stowReasons global=${FloatWindows.reasonsFor(this)} overlaysSuspended=${WindowHooks.overlaysSuspended()}"

    fun hide() {
        Main.assertMain()
        wanted = false
        stowReasons.clear()
        detach()
        content = null
        naturalSize = null
        FloatWindows.unregister(this)
    }

    fun stow(reason: String) {
        Main.assertMain()
        if (!wanted) return
        if (stowReasons.add(reason) && root != null) {
            detach()
            Log.i(tag, "$key stowed reason=$reason")
        }
    }

    fun unstow(reason: String) {
        Main.assertMain()
        if (!stowReasons.remove(reason)) return
        attachIfAllowed()
    }

    internal fun reattach() = attachIfAllowed()

    private fun attachIfAllowed() {
        if (!wanted || root != null || stowReasons.isNotEmpty()) return

        if (FloatWindows.reasonsFor(this).isNotEmpty()) return
        if (WindowHooks.overlaysSuspended()) return
        val body = content ?: return
        attach(body)
    }

    private fun attach(body: View) {
        val (screenW, screenH) = Style.screenSize()
        val (w, h) = windowSize(body, screenW, screenH)
        val lp = buildParams(w, h)
        val saved = savedPosition()
        val want = saved ?: defaultPosition(w, h, screenW, screenH)
        val at = clamp(want.x, want.y, w, h, screenW, screenH)
        lp.x = at.x
        lp.y = at.y
        (body.parent as? android.view.ViewGroup)?.removeView(body)
        val container = TouchSinkLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        if (dragByBody) container.setOnTouchListener { _, ev -> handleDragTouch(ev) }
        try {
            WindowSupport.windowManager().addView(container, lp)
        } catch (e: Exception) {
            Log.e(tag, "$key addView failed: ${e.message}", e)
            return
        }
        root = container
        params = lp
        PenGuard.track(penKey, container)
        Log.i(tag, "$key shown at ${lp.x},${lp.y} ${w}x$h")
        onAttached(container)
    }

    private fun detach() {
        val view = root ?: return

        Log.i(tag, "$key detached dragging=$dragging")
        Main.handler.removeCallbacks(longPress)
        DragFrame.endNow(view)
        dragging = false
        root = null
        params = null
        PenGuard.remove(penKey)
        try {
            WindowSupport.windowManager().removeView(view)
        } catch (e: Exception) {
            Log.w(tag, "$key removeView: ${e.message}")
        }
        view.removeAllViews()
        onDetached()
    }

    private fun savedPosition(): Point? {
        if (!rememberPosition) return memoryPosition
        val prefs = Prefs.of("window")
        if (!prefs.contains("$key.x")) return null
        return Point(prefs.getInt("$key.x", 0), prefs.getInt("$key.y", 0))
    }

    private var memoryPosition: Point? = null

    private fun savePosition(x: Int, y: Int) {
        memoryPosition = Point(x, y)
        if (rememberPosition) Prefs.of("window").edit().putInt("$key.x", x).putInt("$key.y", y).apply()
    }

    fun moveTo(x: Int, y: Int) {
        Main.assertMain()
        val view = root
        val lp = params
        if (view == null || lp == null) {
            savePosition(x, y)
            return
        }
        val (screenW, screenH) = Style.screenSize()
        val w = view.width.takeIf { it > 0 } ?: lp.width
        val h = view.height.takeIf { it > 0 } ?: lp.height
        val at = clamp(x, y, w, h, screenW, screenH)
        lp.x = at.x
        lp.y = at.y
        savePosition(lp.x, lp.y)
        relayout()
    }

    protected fun updateParams(change: (WindowManager.LayoutParams) -> Unit) {
        val lp = params ?: return
        change(lp)
        relayout()
    }

    protected val currentParams: WindowManager.LayoutParams? get() = params

    private fun relayout() {
        val view = root ?: return
        val lp = params ?: return
        try {
            WindowSupport.windowManager().updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(tag, "$key relayout: ${e.message}")
        }
    }

    protected fun handleDragTouch(ev: MotionEvent): Boolean {
        val view = root ?: return false
        val lp = params ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = lp.x
                startY = lp.y
                startRawX = ev.rawX
                startRawY = ev.rawY
                dragging = false
                longPressFired = false
                Main.handler.removeCallbacks(longPress)
                val finger = TouchInput.isFinger(ev)
                pressByFinger = finger
                onPressBegin(finger)
                if (longPressMs > 0 && (finger || longPressByPen)) Main.handler.postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!TouchInput.isFinger(ev)) return true
                val dx = ev.rawX - startRawX
                val dy = ev.rawY - startRawY
                if (!dragging && (abs(dx) > WindowSupport.DRAG_THRESHOLD_PX || abs(dy) > WindowSupport.DRAG_THRESHOLD_PX)) {
                    dragging = true
                    Main.handler.removeCallbacks(longPress)
                    val (screenW, screenH) = Style.screenSize()
                    dragScreenW = screenW
                    dragScreenH = screenH
                    DragFrame.begin(view, lp.x, lp.y)
                }
                if (dragging) {
                    val w = view.width.takeIf { it > 0 } ?: lp.width
                    val h = view.height.takeIf { it > 0 } ?: lp.height
                    val at = clampDrag(startX + dx.toInt(), startY + dy.toInt(), w, h, dragScreenW, dragScreenH)
                    lp.x = at.x
                    lp.y = at.y
                    if (!DragFrame.move(view, lp.x, lp.y)) relayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                Main.handler.removeCallbacks(longPress)
                val wasDragging = dragging
                val longPressed = longPressFired
                dragging = false
                longPressFired = false
                if (wasDragging) {
                    relayout()
                    DragFrame.end(view)
                    savePosition(lp.x, lp.y)
                    Log.i(tag, "$key dragged to ${lp.x},${lp.y}")
                    onMoved(lp.x, lp.y)
                } else if (up && !longPressed) {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    val still = abs(dx) <= WindowSupport.DRAG_THRESHOLD_PX && abs(dy) <= WindowSupport.DRAG_THRESHOLD_PX

                    if (pressByFinger || !penTapNeedsStill || still) {
                        Main.post { if (root === view) onTap() }
                    }
                }
                onPressEnd(longPressed)
            }
            else -> return false
        }
        return true
    }

    open fun closeForRotation() = hide()

    open fun onRotation() {
        val view = root ?: return
        val lp = params ?: return
        DragFrame.endNow(view)
        dragging = false
        val (screenW, screenH) = Style.screenSize()
        val w = view.width.takeIf { it > 0 } ?: lp.width
        val h = view.height.takeIf { it > 0 } ?: lp.height
        val at = clamp(lp.x, lp.y, w, h, screenW, screenH)
        lp.x = at.x
        lp.y = at.y
        savePosition(lp.x, lp.y)
        relayout()
        PenGuard.refresh(penKey, view)
    }
}

object FloatWindows {
    private const val TAG = "FloatWindows"
    private const val PANEL = "panel"
    private val live = LinkedHashSet<FloatWindow>()

    private val reasons = LinkedHashMap<String, FloatWindow?>()

    internal fun register(w: FloatWindow) { live += w }
    internal fun unregister(w: FloatWindow) { live -= w }

    internal fun reasonsFor(w: FloatWindow): List<String> =
        reasons.filter { (reason, except) -> except !== w && applies(reason, w) }.keys.toList()

    private fun applies(reason: String, w: FloatWindow): Boolean = reason != PANEL || w.stowsUnderPanels

    fun stowAll(reason: String, except: FloatWindow? = null) {
        Main.assertMain()
        reasons[reason] = except
        for (w in live.toList()) if (w !== except && applies(reason, w)) w.stow(reason)
    }

    fun unstowAll(reason: String) {
        Main.assertMain()
        if (!reasons.containsKey(reason)) return
        reasons.remove(reason)
        for (w in live.toList()) w.unstow(reason)
    }

    fun reattach() {
        Main.assertMain()
        for (w in live.toList()) w.reattach()
    }

    fun hasReason(reason: String): Boolean = reasons.containsKey(reason)

    fun anyAttached(): Boolean = live.any { it.attached }

    fun onRotation() {
        Main.assertMain()
        for (w in live.toList()) w.onRotation()
    }

    fun closeForRotation(): List<String> {
        Main.assertMain()
        val closed = live.filter { it.closeOnRotation }
        closed.forEach { it.closeForRotation() }
        if (closed.isNotEmpty()) Log.i(TAG, "rotation: floats closed ${closed.map { it.key }}")
        return closed.map { it.key }
    }

    fun closeForSurfaceChange(reason: String, all: Boolean = false) {
        Main.assertMain()
        val closed = live.filter { all || it.closeOnSurfaceChange }
        closed.forEach { it.closeForRotation() }
        if (closed.isNotEmpty()) Log.i(TAG, "surface floats closed reason=$reason ${closed.map { it.key }}")
    }

    internal fun onPanelsActive(active: Boolean) {
        if (active) stowAll(PANEL) else unstowAll(PANEL)
    }
}

data class RotationOutcome(
    val change: DisplayChange,

    val floatsClosed: List<String>,

    val onHandwritingPage: Boolean,

    val panelClosed: Boolean,
)

object WindowRotation {
    private const val TAG = "WindowRotation"
    private const val FOREGROUND_MAX_RETRIES = 4

    @Volatile private var epoch = 0L
    @Volatile private var startForegroundKey: String? = null

    private var foregroundChecked = false

    private var foregroundRetries = 0

    private val listeners = ArrayList<(RotationOutcome) -> Unit>()
    private val displaySettled = Runnable { onDisplaySettled(epoch) }
    private var installed = false

    val active: Boolean get() = epoch != 0L

    fun addListener(l: (RotationOutcome) -> Unit): Cancelable {
        listeners += l
        return Cancelable { listeners -= l }
    }

    fun install() {
        HostHooks.anyPanelShowing = { Panels.anyShowing() }
        DialogHooks.armLogMonitor = { token -> HostLogMonitor.armOwnedDialog(token) }
        HostSession.rotationActive = { epoch != 0L }
        HostSession.rotationSyncWanted = { change -> syncWanted(change) }
        HostSession.overlayOwnsScreen = { Panels.anyShowing() || LassoLayer.anyShowing }
        if (installed) return
        installed = true
        HostSession.addNoticeListener { notice -> onNotice(notice) }
    }

    private fun handwriting(f: ForegroundActivity?): Boolean = f != null && HostProbe.isHandwritingForeground(f)

    private fun syncWanted(change: DisplayChange): Boolean =
        change.changed && change.oldRotation >= 0 && handwriting(change.foreground) &&
            (epoch != 0L || HostSession.toolbarWindowUp()) && PenGuard.hasActiveRects()

    private fun onNotice(notice: HostNotice) {
        when (notice) {
            is HostNotice.Display -> onDisplay(notice.change, notice.rotationSync)
            is HostNotice.Tick -> if (!HostProbe.isHandwritingForeground(notice.foreground) && epoch != 0L) {
                cancel("left handwriting page")
            }
            is HostNotice.Monitor -> if (notice.phase == MonitorPhase.STOPPING) cancel("foreground monitor stopped")
            is HostNotice.Module -> if (!notice.attached) cancel("module invalidated")
            else -> Unit
        }
    }

    private fun onDisplay(change: DisplayChange, startSync: Boolean) {
        val supportsHandwriting = handwriting(change.foreground)
        val foregroundKey = change.foreground?.key
        if (startSync) {
            epoch += 1L
            foregroundChecked = false
            foregroundRetries = 0
            HostPenSignals.resetRotationBaseline()
            startForegroundKey = foregroundKey
            Main.handler.removeCallbacks(displaySettled)
            PenGuard.beginRotationSync(epoch, change.reason)
            Main.handler.postDelayed(displaySettled, PenGuardTiming.ROTATION_DISPLAY_SETTLE_MS)
            Log.i(TAG, "rotation state DISPLAY_SETTLING epoch=$epoch foreground=$foregroundKey")
        } else if (epoch != 0L) {
            cancel("orientation changed outside handwriting page")
        } else if (supportsHandwriting && !HostSession.toolbarWindowUp()) {
            Log.i(TAG, "rotation sync skipped: toolbar window hidden reason=${change.reason}")
        }

        val lastKey = change.lastForegroundKey
        val wasOnPage = lastKey == "${HostProbe.NOTE_PACKAGE}/${HostProbe.NOTE_INSIDE_PAGES_ACTIVITY}" ||
            lastKey?.startsWith("${HostProbe.DOC_PACKAGE}/") == true
        val onPage = change.oldRotation >= 0 && change.oldRotation != change.rotation && (supportsHandwriting || wasOnPage)
        val closed = if (onPage) FloatWindows.closeForRotation() else emptyList()
        val panelClosed = Panels.onRotation()
        FloatWindows.onRotation()
        val outcome = RotationOutcome(change, closed, onPage, panelClosed)
        for (l in listeners.toList()) {
            try {
                l(outcome)
            } catch (e: Exception) {
                Log.e(TAG, "rotation listener failed", e)
            }
        }
    }

    private fun onDisplaySettled(forEpoch: Long) {
        if (forEpoch == 0L || forEpoch != epoch) return
        if (!foregroundChecked) {
            val foreground = HostProbe.foregroundActivity()
            val currentKey = foreground?.key
            if (currentKey != startForegroundKey) {
                when {

                    foreground == null && foregroundRetries < FOREGROUND_MAX_RETRIES -> {
                        foregroundRetries += 1
                        Log.i(TAG, "display settled epoch=$forEpoch: no resumed activity yet, retry=$foregroundRetries")
                        Main.handler.postDelayed(displaySettled, PenGuardTiming.ROTATION_DISPLAY_SETTLE_MS)
                        return
                    }

                    foreground != null && HostProbe.isHandwritingForeground(foreground) -> {
                        Log.i(TAG, "display settled epoch=$forEpoch: foreground $startForegroundKey → $currentKey, still handwriting")
                        startForegroundKey = currentKey
                    }
                    else -> {
                        cancel("foreground changed during display settle")
                        return
                    }
                }
            }
            foregroundChecked = true
        }
        val viewsReady = PenGuard.refreshAllForRotation(forEpoch)
        Log.i(TAG, "display settled epoch=$forEpoch viewsReady=$viewsReady")
        if (!viewsReady) {
            Main.handler.postDelayed(displaySettled, PenGuardTiming.ROTATION_VIEWS_RETRY_MS)
            return
        }

        Log.i(TAG, "rotation state DONE epoch=$forEpoch")
        HostPenSignals.onRotationSettled()
        cancel("rotation complete")
        HostPenSignals.scheduleReassert("rotation complete")
    }

    fun cancel(reason: String) {
        Main.handler.removeCallbacks(displaySettled)
        epoch = 0L
        startForegroundKey = null
        foregroundChecked = false
        foregroundRetries = 0
        HostPenSignals.resetRotationBaseline()
        PenGuard.cancelRotationSync(reason)
    }
}

internal object DragFrame {
    private const val TAG = "InklingDragRefresh"

    private const val AUTO_HOLD_MS = 1400L

    private const val FRAME_STROKE_PX = 4
    private const val FRAME_DASH_PX = 6

    private val handler get() = Main.handler
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

        val seeThrough = MosaicLink.boardTranslucent
        val autoHeld = inheritedAuto || (!seeThrough && EinkAuto.acquire(owner, false))

        if (autoHeld && !inheritedAuto) PenGuard.reassert("drag begin fullUiAuto")
        val state = createProxy(view, screenX, screenY, owner, autoHeld)
        synchronized(activeDrags) { activeDrags[view] = state }

        if (state.frame != null) {
            view.alpha = 0f
            Log.i(
                TAG,
                "dragFrame begin auto=$autoHeld inherited=$inheritedAuto mosaicSeeThrough=$seeThrough screen=$screenX,$screenY " +
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
            val stroke = Style.px(FRAME_STROKE_PX).coerceAtLeast(1)
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
            root?.let { failed -> runCatching { wm.removeViewImmediate(failed) }.onFailure { Log.w(TAG, "dragFrame cleanup: ${it.message}") } }
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
        EinkAuto.release(state.owner)
        PenGuard.reassert("$reason fullUiAuto")
    }

    private fun destroyProxy(view: View, state: DragState) {
        removeProxyWindow(state)
        releaseAuto(view, state, "drag released")
        Log.i(TAG, "dragFrame released view=${view.javaClass.name}")
    }
}
