package me.laumss.notipal.panels.pagepicker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class PagePickerCell(ctx: Context, widthPx: Int, thumbnailHeightPx: Int, label: String) : FrameLayout(ctx) {
    val image = ImageView(ctx)
    val pageLabel = TextView(ctx)

    init {
        val inset = topInset(ctx)
        val labelHeight = PagePickerScale.dp(ctx, 20)
        layoutParams = android.widget.LinearLayout.LayoutParams(widthPx, totalHeight(ctx, thumbnailHeightPx))
        setBackgroundColor(Color.WHITE)
        image.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(PagePickerScale.px(ctx, 1).coerceAtLeast(1), Color.rgb(150, 150, 150))
            }
            val border = PagePickerScale.px(ctx, 1).coerceAtLeast(1)
            setPadding(border, border, border, border)
        }
        addView(image, LayoutParams(thumbnailHeightPx / 4 * 3, thumbnailHeightPx, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = inset
        })
        pageLabel.apply {
            text = label
            textSize = PagePickerScale.sp(ctx, 10.5f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        addView(pageLabel, LayoutParams(LayoutParams.MATCH_PARENT, labelHeight, Gravity.TOP).apply {
            topMargin = inset + thumbnailHeightPx
        })
    }

    fun setPageSelected(selected: Boolean) {
        isSelected = selected
        setBackgroundColor(if (selected) Color.BLACK else Color.WHITE)
        pageLabel.setTextColor(if (selected) Color.WHITE else Color.BLACK)
    }
    companion object {
        fun inset(ctx: Context): Int = PagePickerScale.dp(ctx, 8)
        fun topInset(ctx: Context): Int = PagePickerScale.dp(ctx, 12)
        fun bottomInset(ctx: Context): Int = PagePickerScale.dp(ctx, 7.5f)
        fun footerHeight(ctx: Context): Int = PagePickerScale.dp(ctx, 29)
        fun totalHeight(ctx: Context, thumbnailHeightPx: Int): Int =
            thumbnailHeightPx + footerHeight(ctx) + topInset(ctx) + bottomInset(ctx)
    }

}
