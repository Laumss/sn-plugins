package me.laumss.notipal.ui_common.snux

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.ui_common.filepicker.FilePickerScale as HcScale
import kotlin.math.cos
import kotlin.math.sin

object Snux {
    const val INK = Color.BLACK
    const val SOFT = 0xFF222222.toInt()
    const val MUTED = 0xFF555555.toInt()
    const val SUBTLE = 0xFF666666.toInt()
    const val PLACEHOLDER = 0xFF9A9A9A.toInt()
    const val RULE = 0xFF9A9A9A.toInt()

    const val INKHUB_PX = 131.59f / 113f

    const val INKHUB_U = INKHUB_PX / 1.875f

    const val SIDEBAR = 432 * INKHUB_U
    const val SIDE_ITEM = 110 * INKHUB_U
    const val SIDE_SUB = 110 * INKHUB_U
    const val SIDE_PAD = 48 * INKHUB_U
    const val SIDE_ICON = 48 * INKHUB_U
    const val SIDE_ICON_GAP = 16 * INKHUB_U
    const val SIDE_TEXT = 34 * INKHUB_U
    const val SIDE_SUB_INDENT = 64 * INKHUB_U
    const val ROW_MIN = 82
    const val ICON = 30
    const val CHEVRON = 26
    const val PANE_INSET = 32
    const val SEARCH_HEIGHT = 64.59f
    const val SEARCH_BUTTON = 100

    const val F_BODY = 22
    const val F_SECTION = 20
    const val F_META = 18

    fun u(ctx: Context, units: Number): Int = HcScale.px(ctx, units.toFloat() * 1.875f)

    fun px(ctx: Context, inkHubPx: Number): Int = HcScale.px(ctx, inkHubPx.toFloat() * INKHUB_PX)

    fun text(ctx: Context, value: CharSequence, sizeU: Number, color: Int = INK, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(ctx, sizeU).toFloat())
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
        }

    fun oneLine(view: TextView): TextView = view.apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private class DashLine(ctx: Context) : View(ctx) {
        private val paint = Paint().apply { color = INK }
        override fun onDraw(canvas: Canvas) {
            val dash = px(context, 2).toFloat().coerceAtLeast(1f)
            val step = dash + px(context, 4).toFloat().coerceAtLeast(2f)
            val h = height.toFloat().coerceAtLeast(1f)
            var x = 0f
            while (x < width) {
                canvas.drawRect(x, 0f, minOf(x + dash, width.toFloat()), h, paint)
                x += step
            }
        }
    }

    fun dashes(ctx: Context, insetPx: Number = 10 / INKHUB_U): View = DashLine(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ctx, 2).coerceAtLeast(1)).apply {
            leftMargin = px(ctx, insetPx); rightMargin = px(ctx, insetPx)
        }
    }

    fun rule(ctx: Context, color: Int = INK): View = View(ctx).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ctx, 2).coerceAtLeast(1))
    }

    fun sectionLabel(ctx: Context, title: String, topU: Int = 34): TextView =
        text(ctx, title, F_SECTION, MUTED).apply {
            setPadding(0, u(ctx, topU), 0, u(ctx, 8))
        }

    fun listRow(
        ctx: Context,
        title: CharSequence,
        meta: String? = null,
        right: String? = null,
        action: String? = null,
        onAction: () -> Unit = {},
        solid: Boolean = false,
        titleBold: Boolean = false,
        lead: View? = null,
        extra: View? = null,
        onClick: (() -> Unit)? = null,
    ): Row {
        val titleView = text(ctx, title, F_BODY, bold = titleBold).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val metaView = oneLine(text(ctx, meta.orEmpty(), F_META, SUBTLE)).apply {
            setPadding(0, u(ctx, 4), 0, 0)
            visibility = if (meta.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(metaView)
            if (extra != null) addView(extra, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = u(ctx, 6) })
        }
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = u(ctx, ROW_MIN)
            setPadding(0, u(ctx, 14), 0, u(ctx, 14))

            if (lead != null) addView(lead, LinearLayout.LayoutParams(
                lead.layoutParams?.width ?: LinearLayout.LayoutParams.WRAP_CONTENT,
                lead.layoutParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = u(ctx, 18); gravity = Gravity.TOP; topMargin = u(ctx, 2) })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!right.isNullOrEmpty()) addView(oneLine(text(ctx, right, F_META, SUBTLE)).apply {
                setPadding(u(ctx, 16), 0, 0, 0)
            })
            if (action != null) {
                addView(link(ctx, action, onClick = onAction).apply { setPadding(u(ctx, 24), u(ctx, 10), 0, u(ctx, 10)) })
            } else if (onClick != null) {
                addView(LineIcon(ctx, LineIcon.Glyph.CHEVRON, CHEVRON), LinearLayout.LayoutParams(u(ctx, CHEVRON + 8), u(ctx, CHEVRON))
                    .apply { leftMargin = u(ctx, 10) })
            }
            if (onClick != null) setOnClickListener { onClick() }
        }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(line)
            addView(if (solid) rule(ctx) else dashes(ctx))
        }
        return Row(view, titleView, metaView)
    }

    fun link(ctx: Context, label: String, sizeU: Number = F_META, onClick: () -> Unit): TextView =
        text(ctx, label, sizeU).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { onClick() }
        }

    class Row(val view: View, val titleView: TextView, private val metaView: TextView) {
        fun setMeta(value: String) {
            metaView.text = value
            metaView.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    fun iconButton(ctx: Context, glyph: LineIcon.Glyph, sizeU: Int, cellU: Int, description: String, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            contentDescription = description
            addView(LineIcon(ctx, glyph, sizeU), FrameLayout.LayoutParams(u(ctx, sizeU), u(ctx, sizeU), Gravity.CENTER))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(u(ctx, cellU), u(ctx, cellU))
        }
}

class LineIcon(ctx: Context, private val glyph: Glyph, private val sizeU: Number) : View(ctx) {
    enum class Glyph { SEARCH, CLOSE, CLEAR, CHEVRON, BACK, CLOCK, SHOT, RECEIVE, FOLDER, INBOX, CHAT, MIC, STAR, PEN, SEND, TRASH, CROP, INSERT, RESTORE, PLUS, COPY, GEAR }

    var color: Int = Color.BLACK
        set(value) { field = value; invalidate() }

    var inverse = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var filled = false
        set(value) { field = value; invalidate() }

    var weight = 1f
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val box = RectF()

    override fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = Snux.u(context, sizeU).toFloat().coerceAtMost(minOf(width, height).toFloat())
        val k = size / 30f
        val ox = (width - size) / 2f
        val oy = (height - size) / 2f
        val ink = when {
            !isEnabled -> Snux.PLACEHOLDER
            inverse -> Color.WHITE
            else -> color
        }
        fun x(v: Float) = ox + v * k
        fun y(v: Float) = oy + v * k
        fun stroke(w: Float = 2.2f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * k * weight
            paint.color = ink
            canvas.drawPath(path, paint)
            path.reset()
        }
        fun line(vararg p: Float, close: Boolean = false) {
            path.moveTo(x(p[0]), y(p[1]))
            for (i in 2 until p.size step 2) path.lineTo(x(p[i]), y(p[i + 1]))
            if (close) path.close()
        }
        fun circle(cx: Float, cy: Float, r: Float) = path.addCircle(x(cx), y(cy), r * k, Path.Direction.CW)
        fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
            box.set(x(l), y(t), x(r), y(b))
            path.addRoundRect(box, radius * k, radius * k, Path.Direction.CW)
        }
        path.reset()
        when (glyph) {
            Glyph.SEARCH -> {
                circle(13f, 13f, 9f)
                line(20f, 20f, 27.5f, 27.5f)
                stroke()
            }
            Glyph.COPY -> {

                line(5f, 11f, 19f, 11f, 19f, 25f, 5f, 25f, 5f, 11f)
                line(11f, 11f, 11f, 5f, 25f, 5f, 25f, 19f, 19f, 19f)
                stroke()
            }
            Glyph.PLUS -> {
                line(15f, 5f, 15f, 25f)
                line(5f, 15f, 25f, 15f)
                stroke()
            }
            Glyph.CLOSE -> {
                line(6f, 6f, 24f, 24f)
                line(24f, 6f, 6f, 24f)
                stroke(2f)
            }
            Glyph.CLEAR -> {
                circle(15f, 15f, 11f)
                line(11f, 11f, 19f, 19f)
                line(19f, 11f, 11f, 19f)
                stroke(2f)
            }
            Glyph.CHEVRON -> {
                line(10f, 5f, 20f, 15f, 10f, 25f)
                stroke()
            }
            Glyph.BACK -> {
                line(20f, 5f, 10f, 15f, 20f, 25f)
                stroke()
            }
            Glyph.CLOCK -> {
                circle(15f, 15f, 11f)
                line(15f, 8f, 15f, 15f, 20f, 18f)
                stroke()
            }
            Glyph.SHOT -> {

                line(4f, 9f, 4f, 4f, 9f, 4f)
                line(21f, 4f, 26f, 4f, 26f, 9f)
                line(26f, 21f, 26f, 26f, 21f, 26f)
                line(9f, 26f, 4f, 26f, 4f, 21f)
                roundRect(9f, 10f, 21f, 20f, 1f)
                stroke()
            }
            Glyph.RECEIVE -> {
                line(15f, 4f, 15f, 18f)
                line(9f, 12f, 15f, 18f, 21f, 12f)
                line(5f, 20f, 5f, 25f, 25f, 25f, 25f, 20f)
                stroke()
            }
            Glyph.FOLDER -> {
                line(3f, 8f, 12f, 8f, 14f, 11f, 27f, 11f, 27f, 25f, 3f, 25f, close = true)
                stroke()
            }
            Glyph.INBOX -> {
                line(3f, 17f, 7f, 6f, 23f, 6f, 27f, 17f, 27f, 25f, 3f, 25f, close = true)
                line(3f, 17f, 10f, 17f, 12f, 20f, 18f, 20f, 20f, 17f, 27f, 17f)
                stroke()
            }
            Glyph.CHAT -> {
                line(4f, 5f, 26f, 5f, 26f, 20f, 13f, 20f, 7f, 25f, 7f, 20f, 4f, 20f, close = true)
                stroke()
            }
            Glyph.MIC -> {
                roundRect(11f, 3f, 19f, 18f, 4f)
                box.set(x(6f), y(5f), x(24f), y(23f))
                path.addArc(box, 0f, 180f)
                line(15f, 23f, 15f, 27f)
                stroke()
            }
            Glyph.STAR -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 11.5f else 4.9f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val px = 15f + r * cos(a).toFloat()
                    val py = 15.8f + r * sin(a).toFloat()
                    if (i == 0) path.moveTo(x(px), y(py)) else path.lineTo(x(px), y(py))
                }
                path.close()
                if (filled) {
                    paint.style = Paint.Style.FILL
                    paint.color = ink
                    canvas.drawPath(path, paint)
                }
                stroke()
            }
            Glyph.PEN -> {
                line(5f, 25f, 7f, 18f, 20f, 5f, 25f, 10f, 12f, 23f, close = true)
                line(17f, 8f, 22f, 13f)
                stroke()
            }
            Glyph.SEND -> {
                line(4f, 15f, 26f, 5f, 19f, 26f, 15f, 17f, close = true)
                line(15f, 17f, 26f, 5f)
                stroke()
            }
            Glyph.TRASH -> {
                line(4f, 8f, 26f, 8f)
                line(11.5f, 8f, 11.5f, 4.5f, 18.5f, 4.5f, 18.5f, 8f)
                line(7f, 8f, 8.5f, 26f, 21.5f, 26f, 23f, 8f)
                line(12.5f, 12.5f, 12.5f, 21.5f)
                line(17.5f, 12.5f, 17.5f, 21.5f)
                stroke()
            }
            Glyph.CROP -> {
                line(8f, 3f, 8f, 22f, 27f, 22f)
                line(3f, 8f, 22f, 8f, 22f, 27f)
                stroke()
            }
            Glyph.INSERT -> {

                line(15f, 4f, 25f, 4f, 25f, 26f, 15f, 26f)
                line(4f, 15f, 17f, 15f)
                line(12f, 10f, 17f, 15f, 12f, 20f)
                stroke()
            }
            Glyph.GEAR -> {

                fun at(degrees: Double, r: Float, first: Boolean = false) {
                    val a = Math.toRadians(degrees)
                    val px = x(15f + r * Math.cos(a).toFloat())
                    val py = y(15f + r * Math.sin(a).toFloat())
                    if (first) path.moveTo(px, py) else path.lineTo(px, py)
                }
                for (i in 0 until 8) {
                    val a = i * 45.0 - 90.0
                    at(a - 10.0, 9.5f, first = i == 0)
                    at(a - 7.0, 13f)
                    at(a + 7.0, 13f)
                    at(a + 10.0, 9.5f)

                    at(a + 22.5, 9.5f)
                }
                path.close()
                circle(15f, 15f, 4f)
                stroke()
            }
            Glyph.RESTORE -> {

                line(10f, 5f, 5f, 10f, 10f, 15f)
                path.moveTo(x(5f), y(10f))
                path.lineTo(x(18f), y(10f))
                path.cubicTo(x(28f), y(10f), x(28f), y(25f), x(18f), y(25f))
                path.lineTo(x(9f), y(25f))
                stroke()
            }
        }
    }
}
