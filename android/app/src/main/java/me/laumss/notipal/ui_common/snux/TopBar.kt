package me.laumss.notipal.ui_common.snux

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

object TopBar {

    class Handle(
        val view: View,
        private val title: TextView,
        private val action: TextView,
        private val closeCell: FrameLayout,
    ) {
        private var shownGlyph: LineIcon.Glyph? = null

        fun setTitle(text: String) {
            title.text = text
        }

        fun setCloseGlyph(glyph: LineIcon.Glyph) {
            if (glyph == shownGlyph) return
            shownGlyph = glyph
            val ctx = closeCell.context
            closeCell.removeAllViews()
            closeCell.addView(icon(ctx, glyph), iconParams(ctx))
        }

        fun setAction(label: String?, enabled: Boolean = true) {
            action.visibility = if (label == null) View.GONE else View.VISIBLE
            if (action.text.toString() != label.orEmpty()) action.text = label.orEmpty()
            action.isEnabled = enabled
            action.alpha = if (enabled) 1f else 0.4f
        }
    }

    fun build(
        ctx: Context,
        closeLabel: String,
        onClose: () -> Unit,
        onAction: () -> Unit = {},
        actionLabels: List<String> = emptyList(),
        closeGlyph: LineIcon.Glyph = LineIcon.Glyph.CLOSE,
        trailing: List<View> = emptyList(),
    ): Handle {
        val barHeight = Snux.px(ctx, BAR_HEIGHT)
        val pad = Snux.px(ctx, 48)
        fun textPx(v: Int): Float = Snux.px(ctx, v).toFloat()

        val bar = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
        }

        val closeCell = FrameLayout(ctx).apply {
            contentDescription = closeLabel
            addView(icon(ctx, closeGlyph), iconParams(ctx))
            setOnClickListener { onClose() }
        }
        bar.addView(closeCell, FrameLayout.LayoutParams(Snux.px(ctx, CELL), FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = Snux.px(ctx, 24) })

        val title = TextView(ctx).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(40))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        bar.addView(title, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        val action = TextView(ctx).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(34))
            isSingleLine = true
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
            if (actionLabels.isNotEmpty()) {
                minWidth = ceil(actionLabels.maxOf { paint.measureText(it) }).toInt() + pad * 2
            }
            visibility = View.GONE
            setOnClickListener { onAction() }
        }

        val end = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            trailing.forEach { cell ->
                (cell.parent as? ViewGroup)?.removeView(cell)
                addView(cell)
            }
            addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        bar.addView(end, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL)
            .apply { marginEnd = Snux.px(ctx, 24) })

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barHeight))
            addView(Snux.rule(ctx))
        }
        return Handle(wrapper, title, action, closeCell)
    }

    fun iconCell(ctx: Context, glyph: LineIcon.Glyph, description: String, onClick: () -> Unit): FrameLayout =
        viewCell(ctx, icon(ctx, glyph), description, onClick)

    fun viewCell(ctx: Context, icon: View, description: String, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            contentDescription = description
            addView(icon, iconParams(ctx))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(Snux.px(ctx, CELL), LinearLayout.LayoutParams.MATCH_PARENT)
        }

    fun iconPx(ctx: Context): Int = Snux.px(ctx, ICON)

    private const val BAR_HEIGHT = 114
    private const val CELL = 96
    private const val ICON = 48

    private fun icon(ctx: Context, glyph: LineIcon.Glyph) = LineIcon(ctx, glyph, ICON * Snux.INKHUB_U)

    private fun iconParams(ctx: Context) =
        FrameLayout.LayoutParams(Snux.px(ctx, ICON), Snux.px(ctx, ICON), Gravity.CENTER)
}
