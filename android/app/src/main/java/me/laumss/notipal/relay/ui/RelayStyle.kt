package me.laumss.notipal.relay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import me.laumss.notipal.ui_common.snux.Snux
import kotlin.math.roundToInt

internal const val INK = Color.BLACK
internal const val PAPER = Color.WHITE
internal const val MUTED = 0xFF888888.toInt()
internal const val FAINT = 0xFFE0E0E0.toInt()

internal const val BAND = 0xFFDCDCDC.toInt()

internal const val LINE = 0xFFB5B5B5.toInt()

internal const val MINI_TEXT_SCALE = 0.85f

internal const val DETAIL_TEXT_SCALE = 0.8f

internal const val DETAIL_FONT_BUMP_SP = 3

internal fun detailFont(ctx: Context): Int = snuxFont(ctx, Snux.F_BODY * DETAIL_TEXT_SCALE) + DETAIL_FONT_BUMP_SP

internal const val OUTLINE_U = 1.24f

internal fun dpPx(ctx: Context, dp: Number): Int = (dp.toFloat() * ctx.resources.displayMetrics.density).roundToInt()

internal fun spPx(ctx: Context, sp: Number): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), ctx.resources.displayMetrics)

internal fun snuxFont(ctx: Context, units: Number): Int {
    val scaled = ctx.resources.displayMetrics.density * ctx.resources.configuration.fontScale
    return (Snux.u(ctx, units) / scaled).roundToInt().coerceAtLeast(1)
}

internal fun weightedTypeface(weight: Int): Typeface =
    if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false) else Typeface.DEFAULT_BOLD

internal fun TextView.lineHeightPx(px: Float) {
    setLineSpacing(px - paint.getFontMetricsInt(null), 1f)
}

internal fun metaLabel(ctx: Context, text: String): TextView = Snux.text(ctx, text, Snux.F_META, MUTED, bold = true)

internal fun emptyLine(ctx: Context, text: String): TextView =
    Snux.text(ctx, text, 30 * Snux.INKHUB_U, MUTED, bold = true).apply {
        gravity = Gravity.CENTER
        lineHeightPx(Snux.u(ctx, 30 * Snux.INKHUB_U * 1.3f).toFloat())
    }

@SuppressLint("ViewConstructor")
internal class EinkButton(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit) : TextView(ctx) {
    private val fill = GradientDrawable()

    init {
        text = label
        gravity = Gravity.CENTER
        maxLines = 1
        typeface = weightedTypeface(600)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Snux.u(ctx, 21.25f).toFloat())
        minWidth = Snux.u(ctx, 132.5f)
        minHeight = Snux.u(ctx, 55)
        setPadding(Snux.u(ctx, 20), 0, Snux.u(ctx, 20), 0)
        background = fill
        setPrimary(primary)
        setOnClickListener { onClick() }
    }

    fun setPrimary(primary: Boolean) {
        fill.setColor(if (primary) INK else PAPER)
        fill.setStroke(if (primary) 0 else Snux.u(context, OUTLINE_U).coerceAtLeast(1), INK)
        setTextColor(if (primary) PAPER else INK)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}
