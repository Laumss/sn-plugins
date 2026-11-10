package me.laumss.notipal.panels.pagepicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal class PagePickerBar(
    ctx: Context,
    title: String,
    closeLabel: String,
    actionLabel: String,
    onClose: () -> Unit,
    onAction: () -> Unit,
) {
    val view: View
    val actionButton: TextView

    init {
        fun textPx(v: Int): Float = PagePickerScale.px(ctx, v).toFloat()
        val horizontalPad = PagePickerScale.px(ctx, 52)
        val bar = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, PagePickerScale.px(ctx, 135))
        }
        bar.addView(TextView(ctx).apply {
            text = closeLabel
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(40))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPad, 0, horizontalPad, 0)
            setOnClickListener { onClose() }
        }, FrameLayout.LayoutParams(-2, -1, Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(TextView(ctx).apply {
            text = title
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(44))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        actionButton = TextView(ctx).apply {
            text = actionLabel
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(40))
            setSingleLine(true)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(horizontalPad, 0, horizontalPad, 0)
            setOnClickListener { if (isEnabled) onAction() }
        }
        bar.addView(actionButton, FrameLayout.LayoutParams(-2, -1, Gravity.END or Gravity.CENTER_VERTICAL))
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(View(ctx).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, 1))
        }
    }

    fun setActionEnabled(enabled: Boolean) {
        actionButton.isEnabled = enabled
        actionButton.setTextColor(if (enabled) Color.BLACK else Color.rgb(170, 170, 170))
    }

    companion object {

        fun underlinedLink(ctx: Context, label: String, onClick: () -> Unit): TextView =
            object : TextView(ctx) {
                private val underline = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val textLayout = layout ?: return
                    if (textLayout.lineCount == 0 || text.isEmpty()) return
                    underline.color = currentTextColor
                    underline.strokeWidth = PagePickerScale.px(context, 2).toFloat().coerceAtLeast(1f)
                    val y = baseline + paint.fontMetrics.descent + PagePickerScale.px(context, 4)
                    canvas.drawLine(compoundPaddingLeft + textLayout.getLineLeft(0), y,
                        compoundPaddingLeft + textLayout.getLineRight(0), y, underline)
                }
            }.apply {
                text = label
                textSize = PagePickerScale.noteSp(ctx, 34)
                setTextColor(Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, PagePickerScale.px(ctx, 10))
                setOnClickListener { onClick() }
            }
    }
}
