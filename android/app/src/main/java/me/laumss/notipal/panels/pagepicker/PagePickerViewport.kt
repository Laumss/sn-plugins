package me.laumss.notipal.panels.pagepicker

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class PagePickerViewport(ctx: Context, private val onSwipe: (Int) -> Unit) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val turnDistance = PagePickerScale.dp(ctx, 48).toFloat().coerceAtLeast(slop * 2)
    private var downX = 0f
    private var downY = 0f
    private var eligible = false
    private var dragging = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                eligible = event.pointerCount == 1 && event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS &&
                    event.getToolType(0) != MotionEvent.TOOL_TYPE_ERASER
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (eligible && event.pointerCount == 1 && dx > slop && dx > dy * 1.3f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> eligible = false
            MotionEvent.ACTION_CANCEL -> resetGesture()
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!eligible) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) resetGesture()
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val turn = abs(dx) >= turnDistance && abs(dx) > abs(dy) * 1.3f
                resetGesture()
                if (turn) onSwipe(if (dx < 0) 1 else -1)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> resetGesture()
        }
        return true
    }

    private fun resetGesture() {
        eligible = false
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
