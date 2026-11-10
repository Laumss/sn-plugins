package me.laumss.notipal.panels

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import me.laumss.notipal.FloatingDragRefresh
import me.laumss.notipal.FloatingPenGuard
import me.laumss.notipal.TouchInput
import me.laumss.notipal.bubbles.BubbleWindowSupport
import me.laumss.notipal.ui_common.ScreenScale
import me.laumss.notipal.ui_common.UiUtils
import com.facebook.react.bridge.ReactApplicationContext
import kotlin.math.abs

@SuppressLint("ViewConstructor")
internal class RelayMiniBubble(
    private val ctx: ReactApplicationContext,
    private val wm: WindowManager,
    private val screenSize: () -> Pair<Int, Int>,
    private val onTap: () -> Unit,
) : FrameLayout(ctx) {
    companion object {
        private const val TAG = "RelayMiniBubble"

        private const val SIZE_PX = 112
        private const val BORDER_PX = 2
        private const val ICON_PX = 56
        private const val DOT_PX = 22
        const val PEN_KEY = "airRelay_bubble"
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE
    }

    private val size = ScreenScale.px(ctx, SIZE_PX)
    val params: WindowManager.LayoutParams = BubbleWindowSupport.overlayParams(size, size).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
    }

    var shown = false
        private set

    private var marked = false
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var dragging = false
    private var startX = 0
    private var startY = 0
    private var startRawX = 0f
    private var startRawY = 0f

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(ScreenScale.px(ctx, BORDER_PX).coerceAtLeast(1), Color.BLACK)
        }
        val icon = ScreenScale.px(ctx, ICON_PX)
        addView(ImageView(ctx).apply {
            setImageDrawable(UiUtils.loadAssetIcon(ctx, "icons/icon_ai_spark.xml", icon, Color.BLACK))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(icon, icon, Gravity.CENTER))
        contentDescription = "AIRelay"
        setWillNotDraw(false)
    }

    fun show(anchorRight: Int, anchorTop: Int) {
        params.x = clampX(if (lastX != Int.MIN_VALUE) lastX else anchorRight - size)
        params.y = clampY(if (lastY != Int.MIN_VALUE) lastY else anchorTop)
        attach()
    }

    fun attach() {
        if (shown) return
        try {
            wm.addView(this, params)
            shown = true
            FloatingPenGuard.track(PEN_KEY, this)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}", e)
        }
    }

    fun detach() {
        FloatingDragRefresh.endNow(this)
        dragging = false
        if (!shown) return
        shown = false
        FloatingPenGuard.remove(PEN_KEY)
        try { wm.removeView(this) } catch (e: Exception) { Log.w(TAG, "remove: ${e.message}") }
    }

    fun setMarked(value: Boolean) {
        if (marked == value) return
        marked = value
        invalidate()
    }

    fun refit() {
        params.x = clampX(params.x)
        params.y = clampY(params.y)
        relayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!marked) return
        val r = ScreenScale.px(ctx, DOT_PX) / 2f
        canvas.drawCircle(width - r * 1.6f, r * 1.6f, r, dotPaint)
    }

    private fun clampX(x: Int) = x.coerceIn(-size / 2, screenSize().first - size / 2)
    private fun clampY(y: Int) = y.coerceIn(-size / 2, screenSize().second - size / 2)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = params.x
                startY = params.y
                startRawX = ev.rawX
                startRawY = ev.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - startRawX
                val dy = ev.rawY - startRawY
                if (!dragging && TouchInput.isFinger(ev) && (abs(dx) > BubbleWindowSupport.DRAG_THRESHOLD_PX ||
                        abs(dy) > BubbleWindowSupport.DRAG_THRESHOLD_PX)) {
                    dragging = true
                    FloatingDragRefresh.begin(this, params.x, params.y)
                }
                if (dragging) {
                    params.x = clampX(startX + dx.toInt())
                    params.y = clampY(startY + dy.toInt())
                    if (!FloatingDragRefresh.move(this, params.x, params.y)) relayout()
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                endDrag()
                Log.i(TAG, "bubble dragged to ${params.x},${params.y}")
            } else {
                val dx = ev.rawX - startRawX
                val dy = ev.rawY - startRawY

                if (abs(dx) <= BubbleWindowSupport.DRAG_THRESHOLD_PX && abs(dy) <= BubbleWindowSupport.DRAG_THRESHOLD_PX) {
                    post { onTap() }
                }
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) endDrag()
        }
        return true
    }

    private fun endDrag() {
        dragging = false
        relayout()
        FloatingDragRefresh.end(this)
        lastX = params.x
        lastY = params.y
    }

    private fun relayout() {
        if (!shown) return
        try { wm.updateViewLayout(this, params) } catch (e: Exception) { Log.w(TAG, "relayout: ${e.message}") }
    }
}
