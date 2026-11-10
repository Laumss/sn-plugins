package me.laumss.notipal.ui_common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class SwitchMark(ctx: Context, initial: Boolean) : View(ctx) {
    var on: Boolean = initial
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val stroke = height * 0.07f
        val r = height / 2f
        rect.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        paint.style = Paint.Style.FILL
        paint.color = if (on) Color.BLACK else Color.WHITE
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = Color.BLACK
        canvas.drawRoundRect(rect, r, r, paint)
        val knobR = r - stroke * 2.5f
        val cx = if (on) width - r else r
        paint.style = Paint.Style.FILL
        paint.color = if (on) Color.WHITE else Color.BLACK
        canvas.drawCircle(cx, height / 2f, knobR, paint)
    }
}
