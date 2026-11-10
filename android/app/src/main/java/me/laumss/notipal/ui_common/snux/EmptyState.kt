package me.laumss.notipal.ui_common.snux

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

@SuppressLint("ViewConstructor")
internal class EmptyState(ctx: Context) : LinearLayout(ctx) {

    private companion object {
        const val ICON = 128
        const val TOP = 424
    }

    private val iconBox = android.widget.FrameLayout(ctx)
    private val title = Snux.text(ctx, "", TITLE_U, bold = true).apply { gravity = Gravity.CENTER }
    private val detail = Snux.text(ctx, "", DETAIL_U).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.3f)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Color.WHITE)
        val side = Snux.px(ctx, 48)
        setPadding(side, Snux.px(ctx, TOP), side, 0)
        addView(iconBox, LayoutParams(Snux.px(ctx, ICON), Snux.px(ctx, ICON)))
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Snux.px(ctx, 40) })
        addView(detail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Snux.px(ctx, 24) })
    }

    fun set(glyph: LineIcon.Glyph?, title: String, detail: String = "") {
        iconBox.removeAllViews()
        iconBox.visibility = if (glyph == null) View.GONE else View.VISIBLE
        if (glyph != null) {
            val size = Snux.px(context, ICON)
            iconBox.addView(LineIcon(context, glyph, ICON * Snux.INKHUB_U).apply { weight = 0.64f },
                android.widget.FrameLayout.LayoutParams(size, size))
        }
        this.title.text = title
        this.detail.text = detail
        this.detail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = minOf(Snux.px(context, TOP), h / 5)
        if (top != paddingTop) post { setPadding(paddingLeft, top, paddingRight, 0) }
    }
}

private const val TITLE_U = 34 * Snux.INKHUB_U
private const val DETAIL_U = 30 * Snux.INKHUB_U
