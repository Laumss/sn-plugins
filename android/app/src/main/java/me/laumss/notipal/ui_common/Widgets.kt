package me.laumss.notipal.ui_common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Strings
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

object Widgets {
    private const val TAG = "Widgets"

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun outlinedButton(ctx: Context, label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label; textSize = Style.sp(17f); setTextColor(Style.ink)
            gravity = Gravity.CENTER
            minWidth = Style.dp(106); minHeight = Style.dp(44)
            setPadding(Style.dp(16), 0, Style.dp(16), 0)
            background = Style.box(Style.paper, Style.ink, strokeDp = 1f)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = Style.dp(32) }
            setOnClickListener { onClick() }
        }

    fun filledButton(ctx: Context, label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label; textSize = Style.sp(17f); setTextColor(Style.paper)
            gravity = Gravity.CENTER
            minWidth = Style.dp(106); minHeight = Style.dp(44)
            setPadding(Style.dp(16), 0, Style.dp(16), 0)
            background = Style.box(Style.ink)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            setOnClickListener { onClick() }
        }

    fun panelHeader(ctx: Context, title: String): LinearLayout {
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Style.dp(16), Style.dp(22), Style.dp(16), Style.dp(22))
        }
        bar.addView(TextView(ctx).apply {
            text = title
            textSize = 23f * Style.factor(); setTextColor(Style.ink)
            typeface = Style.bold
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        wrapper.addView(bar)
        wrapper.addView(divider(ctx))
        return wrapper
    }

    fun divider(ctx: Context, dark: Boolean = true): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            setBackgroundColor(if (dark) Style.ink else Style.divider)
        }

    fun empty(ctx: Context, title: String, hint: String? = null): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (hint == null) {
                setPadding(Style.dp(30), Style.dp(60), Style.dp(30), 0)
                addView(TextView(ctx).apply {
                    text = title; textSize = Style.sp(14f)
                    setTextColor(Style.emptyText); gravity = Gravity.CENTER
                })
            } else {
                setPadding(Style.dp(20), Style.dp(60), Style.dp(20), 0)
                addView(TextView(ctx).apply {
                    text = title; textSize = Style.sp(15f)
                    setTextColor(Style.emptyText)
                    typeface = Style.bold
                    gravity = Gravity.CENTER
                })
                addView(TextView(ctx).apply {
                    text = hint; textSize = Style.sp(12f)
                    setTextColor(Style.faintText)
                    gravity = Gravity.CENTER
                    setPadding(Style.dp(20), Style.dp(8), Style.dp(20), 0)
                })
            }
        }

    fun bottomBar(
        ctx: Context,
        leftButtons: List<View> = emptyList(),
        leftFlex: View? = null,
        rightButtons: List<View>,
    ): LinearLayout {
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(divider(ctx))
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Style.dp(28)
            setPadding(p, p, p, p)
        }
        for (btn in leftButtons) bar.addView(btn)
        bar.addView(leftFlex ?: View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        for (btn in rightButtons) bar.addView(btn)
        wrapper.addView(bar)
        return wrapper
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }

    fun tabBar(ctx: Context, labels: List<String>, initial: Int = 0, sideMarginDp: Int = 28, onSelect: (Int) -> Unit): TabBar =
        TabBar(ctx, labels, sideMarginDp, onSelect).apply { if (initial != 0) setSelection(initial) }

    fun switch(ctx: Context, initial: Boolean): SwitchMark = SwitchMark(ctx, initial)

    fun select(ctx: Context, label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit): SelectField =
        SelectField(ctx, label, options, selected, onSelect)

    fun slider(ctx: Context): InkSeekBar = InkSeekBar(ctx)

    fun pager(ctx: Context, onPage: (Int) -> Unit): Pager = Pager(ctx, onPage)

    fun titleBar(
        ctx: Context,
        title: String,
        onClose: () -> Unit,
        actions: List<BarAction> = emptyList(),
        closeLabel: String = Strings.get("close"),
        closeGlyph: LineIcon.Glyph = LineIcon.Glyph.CLOSE,
        actionLabels: List<String> = emptyList(),
        onAction: () -> Unit = {},
    ): TitleBar = TitleBar(ctx, closeLabel, onClose, onAction, actionLabels, closeGlyph, actions).apply { setTitle(title) }

    fun iconCell(ctx: Context, glyph: LineIcon.Glyph, description: String, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            contentDescription = description
            addView(TitleBar.icon(ctx, glyph), TitleBar.iconParams())
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(Style.inkPx(TitleBar.CELL), MATCH)
        }

    fun panelBar(
        ctx: Context,
        style: BarStyle,
        left: List<BarCell> = emptyList(),
        center: List<BarCell> = emptyList(),
        right: List<BarCell> = emptyList(),
    ): PanelBarHandle = PanelBarBuilder(ctx, style).build(left, center, right)

    fun text(ctx: Context, value: CharSequence, sizeU: Number, color: Int = Style.ink, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.u(sizeU).toFloat())
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
        }

    fun oneLine(view: TextView): TextView = view.apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    fun dashes(ctx: Context, insetPx: Number = 10 / Style.INKHUB_U): View {
        val dash = Style.inkPx(2).toFloat().coerceAtLeast(1f)
        val gap = Style.inkPx(4).toFloat().coerceAtLeast(2f)
        val thick = Style.inkPx(2).coerceAtLeast(1)
        return DashLine(ctx, dash, gap, thick.toFloat(), antiAlias = false).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, thick).apply {
                leftMargin = Style.inkPx(insetPx); rightMargin = Style.inkPx(insetPx)
            }
        }
    }

    fun rule(ctx: Context, color: Int = Style.ink): View = View(ctx).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(MATCH, Style.inkPx(2).coerceAtLeast(1))
    }

    fun sectionLabel(ctx: Context, title: String, topU: Int = 34): TextView =
        text(ctx, title, Style.F_SECTION, Style.muted).apply {
            setPadding(0, Style.u(topU), 0, Style.u(8))
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
    ): ListRow {
        val titleView = text(ctx, title, Style.F_BODY, bold = titleBold).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val metaView = oneLine(text(ctx, meta.orEmpty(), Style.F_META, Style.subtle)).apply {
            setPadding(0, Style.u(4), 0, 0)
            visibility = if (meta.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(metaView)
            if (extra != null) addView(extra, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Style.u(6) })
        }
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Style.u(Style.ROW_MIN)
            setPadding(0, Style.u(14), 0, Style.u(14))

            if (lead != null) addView(lead, LinearLayout.LayoutParams(
                lead.layoutParams?.width ?: WRAP,
                lead.layoutParams?.height ?: WRAP,
            ).apply { rightMargin = Style.u(18); gravity = Gravity.TOP; topMargin = Style.u(2) })
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (!right.isNullOrEmpty()) addView(oneLine(text(ctx, right, Style.F_META, Style.subtle)).apply {
                setPadding(Style.u(16), 0, 0, 0)
            })
            if (action != null) {
                addView(link(ctx, action, onClick = onAction).apply { setPadding(Style.u(24), Style.u(10), 0, Style.u(10)) })
            } else if (onClick != null) {
                addView(LineIcon(ctx, LineIcon.Glyph.CHEVRON, Style.CHEVRON_U),
                    LinearLayout.LayoutParams(Style.u(Style.CHEVRON_U + 8), Style.u(Style.CHEVRON_U)).apply { leftMargin = Style.u(10) })
            }
            if (onClick != null) setOnClickListener { onClick() }
        }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(line)
            addView(if (solid) rule(ctx) else dashes(ctx))
        }
        return ListRow(view, titleView, metaView)
    }

    fun link(ctx: Context, label: String, sizeU: Number = Style.F_META, onClick: () -> Unit): TextView =
        text(ctx, label, sizeU).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { onClick() }
        }

    fun iconButton(ctx: Context, glyph: LineIcon.Glyph, sizeU: Int, cellU: Int, description: String, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            contentDescription = description
            addView(LineIcon(ctx, glyph, sizeU), FrameLayout.LayoutParams(Style.u(sizeU), Style.u(sizeU), Gravity.CENTER))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(Style.u(cellU), Style.u(cellU))
        }

    fun metaLabel(ctx: Context, value: String): TextView = text(ctx, value, Style.F_META, Style.hint, bold = true)

    fun emptyLine(ctx: Context, value: String): TextView =
        text(ctx, value, 30 * Style.INKHUB_U, Style.hint, bold = true).apply {
            gravity = Gravity.CENTER
            setLineHeightPx(Style.u(30 * Style.INKHUB_U * 1.3f).toFloat())
        }

    fun qrCard(ctx: Context, payload: String, title: String, desc: String, link: String, wrapLink: Boolean = false): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Style.dp(16), Style.dp(16), Style.dp(20), Style.dp(16))
            background = Style.box(Style.paper, Style.faintText, strokeDp = 1f, radiusDp = 6f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                leftMargin = Style.dp(24); rightMargin = Style.dp(24); topMargin = Style.dp(16)
            }
        }
        val qr = QrCode.encode(payload)
        if (qr != null) {
            val scale = maxOf(2, Style.dp(150) / (qr.size + 4))
            val bmp = qr.toBitmap(scale, quiet = 2)
            card.addView(ImageView(ctx).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(bmp.width, bmp.height).apply { rightMargin = Style.dp(20) }
            })
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = Style.sp(18f)
            typeface = Style.bold
            setTextColor(Style.ink)
        })
        col.addView(TextView(ctx).apply {
            text = desc
            textSize = Style.sp(14f)
            setTextColor(Style.subtle)
            setPadding(0, Style.dp(6), 0, 0)
            setLineSpacing(Style.dp(2).toFloat(), 1f)
        })
        col.addView(TextView(ctx).apply {
            text = link
            textSize = Style.sp(14f)
            setTextColor(Style.ink)
            setPadding(0, Style.dp(10), 0, 0)
            if (wrapLink) {
                typeface = Typeface.MONOSPACE
            } else {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
        })
        card.addView(col)
        return card
    }

    internal fun logPopupError(e: Exception) = Log.w(TAG, "popup dismiss failed", e)
}

fun TextView.setLineHeightPx(px: Float) {
    setLineSpacing(px - paint.getFontMetricsInt(null), 1f)
}

@SuppressLint("ViewConstructor")
class DashLine(ctx: Context, private val dash: Float, private val gap: Float, private val thick: Float, antiAlias: Boolean = false) : View(ctx) {
    private val paint = Paint().apply {
        color = Style.ink
        style = Paint.Style.FILL
        isAntiAlias = antiAlias
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val t = thick.coerceAtMost(h).coerceAtLeast(1f)
        val top = (h - t) / 2f
        val step = dash + gap
        var x = 0f
        while (x < width) {
            canvas.drawRect(x, top, minOf(x + dash, width.toFloat()), top + t, paint)
            x += step
        }
    }
}

class FrozenView private constructor(private val image: ImageView) {

    fun remove() {
        (image.parent as? ViewGroup)?.removeView(image)
        image.setImageDrawable(null)
    }

    companion object {
        private const val TAG = "FrozenView"

        fun over(view: View, paper: Int?): FrozenView? {
            val parent = view.parent as? FrameLayout ?: return null
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0 || !view.isShown) return null
            val started = SystemClock.uptimeMillis()
            val bitmap = try {
                Bitmap.createBitmap(w, h, if (paper != null) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "no memory for a ${w}x$h snapshot: ${e.message}")
                return null
            }
            val canvas = Canvas(bitmap)
            if (paper != null) canvas.drawColor(paper)
            view.draw(canvas)
            val image = ImageView(view.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(bitmap)
            }
            parent.addView(image, FrameLayout.LayoutParams(w, h).apply {
                leftMargin = view.left
                topMargin = view.top
            })
            Log.i(TAG, "froze ${w}x$h in ${SystemClock.uptimeMillis() - started}ms")
            return FrozenView(image)
        }
    }
}

class MultiSelectState {
    var isActive = false
        private set

    private val paths = linkedSetOf<String>()

    val count: Int get() = paths.size
    val selectedPaths: List<String> get() = paths.toList()

    fun activate(seed: String? = null) {
        isActive = true
        seed?.let { paths.add(it) }
    }

    fun deactivate() {
        isActive = false
        paths.clear()
    }

    fun toggle(path: String): Boolean =
        if (path in paths) {
            paths.remove(path); false
        } else {
            paths.add(path); true
        }

    fun isSelected(path: String): Boolean = path in paths

    fun clear() = deactivate()
}

@SuppressLint("ViewConstructor")
class TabBar(
    ctx: Context,
    private val labels: List<String>,
    sideMarginDp: Int,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(ctx) {
    private var selected = 0
    private val labelViews = ArrayList<TextView>()
    private val indicators = ArrayList<View>()

    init {
        orientation = VERTICAL
        val side = Style.dp(sideMarginDp)
        val bar = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Style.dp(56)).apply { leftMargin = side; rightMargin = side }
        }
        for ((idx, label) in labels.withIndex()) {
            val cell = FrameLayout(ctx).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    if (selected != idx) {
                        selected = idx
                        updateStyles()
                        onSelect(idx)
                    }
                }
            }
            val tv = TextView(ctx).apply {
                text = label
                textSize = Style.sp(17f)
                gravity = Gravity.CENTER
                isSingleLine = true
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            val indicator = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(Style.dp(64), Style.dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                setBackgroundColor(Style.ink)
            }
            cell.addView(tv)
            cell.addView(indicator)
            labelViews.add(tv)
            indicators.add(indicator)
            bar.addView(cell)
        }
        addView(bar)
        addView(View(ctx).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply { leftMargin = side; rightMargin = side }
            setBackgroundColor(Style.divider)
        })
        updateStyles()
    }

    fun setSelection(i: Int) {
        selected = i.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        updateStyles()
    }

    private fun updateStyles() {
        for (i in labels.indices) {
            val active = i == selected
            labelViews.getOrNull(i)?.apply {
                typeface = if (active) Style.bold else Style.normal
                setTextColor(if (active) Style.ink else Style.hint)
            }
            indicators.getOrNull(i)?.visibility = if (active) View.VISIBLE else View.INVISIBLE
        }
    }
}

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
        paint.color = if (on) Style.ink else Style.paper
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = Style.ink
        canvas.drawRoundRect(rect, r, r, paint)
        val knobR = r - stroke * 2.5f
        val cx = if (on) width - r else r
        paint.style = Paint.Style.FILL
        paint.color = if (on) Style.paper else Style.ink
        canvas.drawCircle(cx, height / 2f, knobR, paint)
    }
}

@SuppressLint("ViewConstructor")
class SelectField(
    ctx: Context,
    label: String,
    private val options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) : LinearLayout(ctx) {
    companion object {
        private val activePopups = ArrayList<PopupWindow>()

        fun dismissAllPopups() {
            val copy = activePopups.toList()
            activePopups.clear()
            copy.forEach {
                try {
                    it.dismiss()
                } catch (e: Exception) {
                    Widgets.logPopupError(e)
                }
            }
        }
    }

    var selected: Int = selected
        private set

    private val box: LinearLayout
    private val value: TextView
    private val caret: TextView
    private val list: LinearLayout
    private val dots = ArrayList<View>(options.size)
    private var popup: PopupWindow? = null

    init {
        orientation = VERTICAL
        if (label.isNotEmpty()) {
            addView(TextView(ctx).apply {
                text = label
                textSize = Style.sp(15.5f)
                setTextColor(Style.ink)
                setPadding(0, 0, 0, Style.dp(8))
            })
        }
        value = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.textPx(34))
            setTextColor(Style.ink)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        list = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setBackgroundColor(Style.paper)
        }
        options.forEachIndexed { index, option ->
            if (index > 0) list.addView(DashLine(ctx, Style.px(10).toFloat(), Style.px(8).toFloat(),
                Style.px(2).toFloat().coerceAtLeast(1f)).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Style.px(3))
            })
            val dot = View(ctx).apply {
                layoutParams = LayoutParams(Style.px(16), Style.px(16))
                background = Style.oval(Style.ink)
                visibility = View.INVISIBLE
            }
            dots.add(dot)
            list.addView(LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Style.px(88)
                setPadding(Style.px(30), 0, Style.px(30), 0)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                addView(TextView(ctx).apply {
                    text = option
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.textPx(34))
                    setTextColor(Style.ink)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(dot)

                setOnClickListener {
                    popup?.dismiss()
                    onSelect(index)
                }
            })
        }
        caret = TextView(ctx).apply {
            text = "▼"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.textPx(22))
            setTextColor(Style.ink)
        }
        box = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Style.px(88)
            setPadding(Style.px(30), 0, Style.px(30), 0)
            background = Style.boxPx(Style.paper, Style.ink, Style.px(3))
            addView(value)
            addView(caret)
            setOnClickListener { togglePopup() }
        }
        addView(box)
        setValue(selected)
    }

    fun setValue(selected: Int) {
        this.selected = selected
        val index = selected.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        dots.forEachIndexed { i, dot -> dot.visibility = if (i == index) View.VISIBLE else View.INVISIBLE }
        options.getOrNull(index)?.let { value.text = it }
    }

    private fun togglePopup() {
        if (popup?.isShowing == true) {
            popup?.dismiss()
            return
        }
        dismissAllPopups()
        val boxW = box.width
        if (boxW <= 0) return
        val gap = Style.dp(4)
        val maxH = Style.dp(400)
        list.measure(
            MeasureSpec.makeMeasureSpec(boxW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val contentH = minOf(list.measuredHeight, maxH)
        val stroke = Style.px(3)
        val border = FrameLayout(context).apply {
            background = Style.boxPx(Style.paper, Style.ink, stroke)
            setPadding(stroke, stroke, stroke, stroke)
        }
        (list.parent as? ViewGroup)?.removeView(list)
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scroll.addView(list)
        border.addView(scroll)
        val popupH = contentH + stroke * 2
        val window = PopupWindow(border, boxW, popupH, true).apply {
            setBackgroundDrawable(Style.clearDrawable())
            isOutsideTouchable = true
            animationStyle = 0
            setOnDismissListener {
                activePopups.remove(this)
                if (popup === this) popup = null
                caret.text = "▼"
            }
        }
        popup = window
        activePopups.add(window)
        caret.text = "▲"
        val loc = IntArray(2)
        box.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        val below = screenH - (loc[1] + box.height) - gap
        val above = loc[1] - gap
        if (below < popupH && above > below) {
            window.showAtLocation(box, Gravity.NO_GRAVITY, loc[0], above - popupH)
        } else {
            window.showAsDropDown(box, 0, gap)
        }
    }
}

class InkSeekBar(context: Context) : SeekBar(context) {
    companion object {
        private const val TAG = "InkSeekBar"

        private const val A2_MODE = 4
    }

    private var tracking = false

    private fun dp(value: Float): Int = Style.rawDp(value).coerceAtLeast(1)

    init {
        splitTrack = false
        progressTintList = null
        progressBackgroundTintList = null
        thumbTintList = null
        background = null
        val track = Style.boxPx(Style.paper, Style.ink, dp(2f / 1.875f), dp(8f / 1.875f).toFloat())
        progressDrawable = LayerDrawable(arrayOf(track)).apply {
            setId(0, android.R.id.background)
            setLayerHeight(0, dp(9f))
            setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
        }
        thumb = thumbDrawable(false)
    }

    private fun thumbDrawable(changing: Boolean) =
        if (changing) Style.oval(Style.ink, sizePx = dp(26f))
        else Style.oval(Style.paper, Style.ink, dp(2f / 1.875f), dp(26f))

    fun setTracking(changing: Boolean) {
        if (tracking == changing) return
        tracking = changing
        try {
            if (changing) View::class.java.getMethod("setEinkUpdateMode", Int::class.javaPrimitiveType).invoke(this, A2_MODE)
            else View::class.java.getMethod("resetEinkUpdateMode").invoke(this)
        } catch (e: Exception) {
            Log.w(TAG, "E-ink A2 unavailable: ${e.message}")
        }
        thumb = thumbDrawable(changing)
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) setTracking(false)
    }

    override fun onDetachedFromWindow() {
        setTracking(false)
        super.onDetachedFromWindow()
    }
}

@SuppressLint("ViewConstructor")
class Pager(ctx: Context, private val onPage: (Int) -> Unit) : LinearLayout(ctx) {
    private var current = 0
    private var count = 1
    private val icons = LinkedHashMap<String, Pair<View, ImageView>>()
    private val number = TextView(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(Style.clear)
        val nav = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        addView(nav, LayoutParams(LayoutParams.MATCH_PARENT, Style.hcPx(72)))
        fun pageButton(key: String, label: String, action: () -> Unit): View = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = label
            }
            addView(icon, LayoutParams(Style.hcPx(32), Style.hcPx(32)))
            icons[key] = this to icon
            setOnClickListener { if (isEnabled) action() }
        }
        val itemWidth = Style.hcPx(88)
        nav.addView(pageButton("first", Strings.get("picker_first_page")) { onPage(0) }, LayoutParams(itemWidth, LayoutParams.MATCH_PARENT))
        nav.addView(pageButton("pre", Strings.get("picker_previous_page")) { onPage(current - 1) }, LayoutParams(itemWidth, LayoutParams.MATCH_PARENT))
        nav.addView(View(ctx), LayoutParams(Style.hcPx(68), LayoutParams.MATCH_PARENT))
        number.apply {
            textSize = Style.hcSp(16f)
            setTextColor(Style.pagerText)
            setLineSpacing(0f, 1.12f)
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        nav.addView(number, LayoutParams(Style.hcPx(100), LayoutParams.MATCH_PARENT))
        nav.addView(View(ctx), LayoutParams(Style.hcPx(68), LayoutParams.MATCH_PARENT))
        nav.addView(pageButton("next", Strings.get("picker_next_page")) { onPage(current + 1) }, LayoutParams(itemWidth, LayoutParams.MATCH_PARENT))
        nav.addView(pageButton("last", Strings.get("picker_last_page")) { onPage(count - 1) }, LayoutParams(itemWidth, LayoutParams.MATCH_PARENT))
        update(0, 1)
    }

    fun update(page: Int, pageCount: Int) {
        count = pageCount.coerceAtLeast(1)
        current = page.coerceIn(0, count - 1)
        number.text = "${current + 1} / $count"
        icons.forEach { (key, pair) ->
            val enabled = if (key == "first" || key == "pre") current > 0 else current + 1 < count
            pair.first.isEnabled = enabled
            pair.second.isEnabled = enabled
            pair.second.setImageBitmap(bitmaps.getValue(key + if (enabled) "" else "_disabled"))
        }
    }

    private companion object {

        val encoded = mapOf(
            "first" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAApklEQVRYhe3XMQ6DMBBE0dGksA+WJmdCKOJUHG8pQkEBUnZ3wAFSbOv/JCNsw8weLQd/wK6LAyPJtxtAcgBgAIzkEI0v1ugPBSzj84yHATxxOcAblwIicRkgGpcAMvE0IBtPARTxMEAVDwGUcTeglPJSxt2AWuuzKaD5FvzER6hGnPdHpEKc/zDKIq5zIYkirncpXUOQ7FwA1eDzMNmM7w74Cnl7wAQfkyUX3JNknAAAAABJRU5ErkJggg==",
            "first_disabled" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAQAAADZc7J/AAABWklEQVRIx9VVu07DQBCcu7N5WDxiHmUQCISQEBWioEQ0oeUTkCj4BQpL7hB/QElBh1CEDClCEGnyFUh0dJFoIMgEDYUdxTH2OXYauOlm7/Z2Z/f2BBVGWhL4Xw6kZ7oxiqoH6YKgdPtMHHNlEJROlMsVQfu1tAJwNyUCPcQdfCoqeynGD1tG8YExfHFSI6JyRFd0lfP7sF0GaMHHU6EqyOu3l8VVgBYPEszZ2eMKBEvLKVb94aCouEA11b2uD4QHopZxhS57+wRARZ1qJUoNrRH2XiNDoWR6vAKCuMmWOKUPhB9QhV9jpwmPBg+nN0Q1w4MuvKlNEDSPC2gQGh9A43xoEZPmgXkUOkptJEOfoH8JyBr2gbX553ZuDfr9uL5QSIMetmeDrbjPrUF0I4j3nG9hYNXR4Qxg7eTWIIqJPRD1wlPZ3vp8BGRzYFbm+xvlrWh9n43g4C9+rj9Qpd+0mIMvEAAAAABJRU5ErkJggg==",
            "pre" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAmElEQVRYhe3WPQqAMAyG4Y84tAdz8UwiHqvHi4uCY9okDUGHrH0fKP0BMy+Rgx9gviDQiOgMAQBoABgAE9ExFfCO39OmAUbjJgBNXA3QxlUAi/gwwCo+BLCMdwOs410Aj7gY4BUXAUopm1dcBKi1rqGA8C3wRuQ5hl6IfFexNSLvc2yFyP8l0yI8v+X7dMCDkMZdAN3gzwMu7Vz792SwCAYAAAAASUVORK5CYII=",
            "pre_disabled" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAQAAADZc7J/AAABKUlEQVRIx9WVvUoDQRRGz+y4/oQQXVG7FURIE6zEwtIuzyH4GArpxDfwJTRoVDBGtPEdBGsr066yUT6LTefuOMw2ulNscYfDmZk73xhZan0R/BdANIh7pQVZn7GcIhQd/ax4GoxflzZAu0EG5opcVjZZL63+fowmY5aJFoI2MUlBDXIeKqc45c/RStu9QKeBMnh7TlKnZCW5JyvLKX23QQXADBA3Ph2Cs3EOgwCMpohRkMFcFyHO/Fq85BTMBAD/lCiRupSVbXaKf+AmNjsIxQfBAO7QzEkNA9l4fwrqBwJkZc0tQptrwXeBHNqrL+Ogu1CM7cVCk+tKS49A+QQytcJTeci7WtDYqZHKsvN7iGFwKidbH/cQPZYs0PdtjC7M09dxDcDffVy/AUhW9dbUq4sMAAAAAElFTkSuQmCC",
            "next" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAoklEQVRYhe3Xuw2AMAwE0JOwlExFx2aIlRAsZ5oUocvnwhWkcIfEE4nPBu6+KAsTUPqgmR0ATgkgvdxTURFFAAB3BqAiio9gFKLqEo5AVHcBG9HUhkxEcw6wEF1BxEB0J2EvghLFPQjaLGhFUIcRgCtHhBC2rwGvrxBjXP9xBNJLKG1DaRBJo1g6jKTjWLqQSFcy+VJqZrt0Lc8Qmh+TkTUBDwIw/GiRWw8uAAAAAElFTkSuQmCC",
            "next_disabled" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAQAAADZc7J/AAABM0lEQVRIx9WVvUpDQRCFv71zL2ogmPhXmYAImkYQJLWVDyL4HIqInQ/gU/hHUDAGtPIBbCzsrYKISCRRjkWQgN4bN3sb3Sl35nBmz5lZJyPXieAvAiR7ruGNIPse8T5CU/M/b9IihcF7HUoL7cdgBrJyVSaj6xq/M3BZMroOCV0VwlW46ZeXKwEtDGJmGbmjYRnxMPBS9fke9JqDAWccymTRbmaGj9ZcoCxFPACibYSmKnkYtPrltAIBZDJOEBrb8LByhrEieuB6I6rwpUWxJpNxHNRCsoXQdC3HI8YHiKsgAE5pymTJZpCRFucQcpcjLpTBeWgvzQLdoFngHMlka5OhC+WFCVActFAKdVCRDs2gnUgLja/7WjyFQXwLb9flFT+Lp/TY20n4WH2685yR//+5fgIf5PZPZD/pfgAAAABJRU5ErkJggg==",
            "last" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAqUlEQVRYhe3XQQrDIBAF0GH6Qa/VTe+VUnqlHM9uEugiBJ0/8gvpYlaC/4E6qrXWbsqyP+BsEMDbzFYJYAtvW4UQ7r7sc7j7MgQws/ULEEJQgAwEDWARKQAGkQaIIlIBEUQ6YBQxBTCCmAboRUwF9CCmA44QpZSHFFBrvV9jCaSbUHoMpY1I2oqll5H0OpY+SJhwGsCG0wAATyY8ZQkAvKLhvfXbX7NLAD5GLSVJrFNSwAAAAABJRU5ErkJggg==",
            "last_disabled" to "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAQAAADZc7J/AAABXUlEQVRIx9VVsUrDUBQ9LzdBDWhNq5sVClVcBEE6uyh0EvwEwcVvECpF3PwAZx0drFLsUAtKBz/AD3B0Ep2spNHjELUJbWze66LvDiHv8g73nXNyoigYaVnA3wJwDlRdE4HSK/sQBLNz0b14WVUQtKqRnShYUAKmC0+PxhNQvHkKBb6qJ08RL9Uvo+rAgU/XXIXb8LiX729JRQUqkMqvACzTBWaLLw/WmTYH35UpgCBOh3OAxMYljkPhDAG+2g0wqsgQH/RRtocy4O0acPAzQSubD5+GV6BQUAPBsY0Ecw/XSVnoAqqb2gexw+dTCx9bnECt0zbgwNkBwdzSCDLaRyCuzYx0gSaF4mxr5EFvFXPYxLrVAPwTo2+BsjgT96DGFXAFUiirmTSBMgjgFQwhtDMRcEsAJ9FB0ygT0QLH19Km4YAJ7Dvg7cZb1gllO/rS3XfwvvJ8rwOg/v/P9RMIPt+x6jNf1AAAAABJRU5ErkJggg==",
        )
        val bitmaps: Map<String, Bitmap> by lazy {
            encoded.mapValues { (_, value) ->
                val bytes = Base64.decode(value, Base64.DEFAULT)
                requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
        }
    }
}

class BarAction(val description: String, val glyph: LineIcon.Glyph, val onClick: () -> Unit) {
    var view: FrameLayout? = null
        internal set
}

@SuppressLint("ViewConstructor")
class TitleBar internal constructor(
    ctx: Context,
    closeLabel: String,
    onClose: () -> Unit,
    onAction: () -> Unit,
    actionLabels: List<String>,
    closeGlyph: LineIcon.Glyph,
    actions: List<BarAction>,
) : LinearLayout(ctx) {
    internal companion object {

        const val BAR_HEIGHT = 114
        const val CELL = 96
        const val ICON = 48

        fun icon(ctx: Context, glyph: LineIcon.Glyph) = LineIcon(ctx, glyph, ICON * Style.INKHUB_U)

        fun iconParams() = FrameLayout.LayoutParams(Style.inkPx(ICON), Style.inkPx(ICON), Gravity.CENTER)
    }

    private val title: TextView
    private val action: TextView
    private val closeCell: FrameLayout
    private var shownGlyph: LineIcon.Glyph? = closeGlyph

    init {
        orientation = VERTICAL
        val pad = Style.inkPx(48)
        val bar = FrameLayout(ctx).apply { setBackgroundColor(Style.paper) }
        closeCell = FrameLayout(ctx).apply {
            contentDescription = closeLabel
            addView(icon(ctx, closeGlyph), iconParams())
            setOnClickListener { onClose() }
        }
        bar.addView(closeCell, FrameLayout.LayoutParams(Style.inkPx(CELL), FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = Style.inkPx(24) })
        title = TextView(ctx).apply {
            setTextColor(Style.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.inkPx(40).toFloat())
            typeface = Style.bold
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        bar.addView(title, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        action = TextView(ctx).apply {
            setTextColor(Style.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.inkPx(34).toFloat())
            isSingleLine = true
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
            if (actionLabels.isNotEmpty()) minWidth = ceil(actionLabels.maxOf { paint.measureText(it) }).toInt() + pad * 2
            visibility = View.GONE
            setOnClickListener { onAction() }
        }
        val end = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            actions.forEach { a ->
                val cell = Widgets.iconCell(ctx, a.glyph, a.description, a.onClick)
                a.view = cell
                addView(cell)
            }
            addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        bar.addView(end, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = Style.inkPx(24) })
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, Style.inkPx(BAR_HEIGHT)))
        addView(Widgets.rule(ctx))
    }

    fun setTitle(text: String) {
        title.text = text
    }

    fun setCloseGlyph(glyph: LineIcon.Glyph) {
        if (glyph == shownGlyph) return
        shownGlyph = glyph
        closeCell.removeAllViews()
        closeCell.addView(icon(context, glyph), iconParams())
    }

    fun setAction(label: String?, enabled: Boolean = true) {
        action.visibility = if (label == null) View.GONE else View.VISIBLE
        if (action.text.toString() != label.orEmpty()) action.text = label.orEmpty()
        action.isEnabled = enabled
        action.alpha = if (enabled) 1f else Style.DISABLED_ALPHA
    }
}

sealed class BarCell {

    class TextBtn(val label: String, val onClick: () -> Unit) : BarCell()

    class Action(val iconAsset: String, val label: String, val onClick: () -> Unit) : BarCell() {
        internal var view: LinearLayout? = null
        internal var shownIcon: String = iconAsset
    }

    class Check(val label: String, val onToggle: () -> Unit) : BarCell()

    class GlyphBtn(val glyph: LineIcon.Glyph, val label: String, val onClick: () -> Unit) : BarCell()

    class Title(val label: String) : BarCell()
}

data class BarStyle(
    val bg: Int,
    val fg: Int,
    val height: Number,
    val leftCol: Number = 0,
    val rightCol: Number = 0,
    val leftPadStart: Number = 0,
    val horizontalPad: Number = 0,
    val btnSize: Number = 19,
    val btnPadH: Number = 0,
    val btnPadV: Number = 0,
    val labelSize: Number = 30,
    val iconSize: Number = 56,
    val labelGap: Number = 4,
    val actionPad: Number = 44,
    val divider: Boolean = false,
) {
    companion object {

        val INBOX = BarStyle(
            bg = Style.ink, fg = Style.paper,
            height = 135, leftCol = 220, rightCol = 228, leftPadStart = 51.5,
            btnSize = 40, labelSize = 30, iconSize = 56, labelGap = 4, actionPad = 44,
        )

        val PAGE_HEADER = BarStyle(
            bg = Style.paper, fg = Style.ink,
            height = 135, btnSize = 40, iconSize = 48,
            divider = true, horizontalPad = 20,
        )

        val PAGE_HEADER_ALIGNED = PAGE_HEADER.copy(horizontalPad = 0, btnPadH = 52, btnPadV = 24)
    }
}

class PanelBarHandle(
    val view: View,
    val heightPx: Int,
    private val style: BarStyle,
    private val checkIcon: ImageView?,
    private val titles: List<TextView> = emptyList(),
) {
    var isChecked: Boolean = false
        private set

    fun setChecked(checked: Boolean) {
        isChecked = checked
        val icon = checkIcon ?: return
        val asset = if (checked) "icons/ic_check_on.xml" else "icons/ic_check_off.xml"
        icon.setImageDrawable(Style.iconPx(asset, Style.px(style.iconSize), style.fg))
    }

    fun setTitleLabel(index: Int, label: String) {
        val title = titles.getOrNull(index) ?: return
        if (title.text.toString() != label) title.text = label
    }

    fun updateAction(action: BarCell.Action, enabled: Boolean, iconAsset: String = action.shownIcon, label: String? = null) {
        val view = action.view ?: return
        if (view.isEnabled != enabled) {
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else Style.DISABLED_ALPHA
        }
        if (iconAsset != action.shownIcon) {
            action.shownIcon = iconAsset
            (view.getChildAt(0) as? ImageView)?.setImageDrawable(Style.iconPx(iconAsset, Style.px(style.iconSize), style.fg))
        }
        val text = view.getChildAt(1) as? TextView
        if (label != null && text?.text?.toString() != label) text?.text = label
    }
}

private class PanelBarBuilder(private val ctx: Context, private val style: BarStyle) {
    private val match = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    private var checkIcon: ImageView? = null
    private val titles = ArrayList<TextView>()

    private fun u(v: Number) = Style.px(v)

    private fun TextView.textSizePx(v: Number) = setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.textPx(v))

    fun build(left: List<BarCell>, center: List<BarCell>, right: List<BarCell>): PanelBarHandle {
        val barHeight = u(style.height)
        val bar: View
        if (u(style.leftCol) > 0 || u(style.rightCol) > 0) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(style.bg)
                layoutParams = LinearLayout.LayoutParams(match, barHeight)
            }
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(u(style.leftPadStart), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(u(style.leftCol), match)
                left.forEach { addView(render(it, false)) }
            })

            val useWeight = center.size > 1
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, match, 1f)
                center.forEach { addView(render(it, useWeight)) }
            })
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(u(style.rightCol), match)
                right.forEach { addView(render(it, false)) }
            })
            bar = row
        } else {
            val pad = u(style.horizontalPad)
            val frame = FrameLayout(ctx).apply {
                setBackgroundColor(style.bg)
                layoutParams = LinearLayout.LayoutParams(match, barHeight)
            }
            fun group(cells: List<BarCell>, outer: Int, inner: Int, margin: FrameLayout.LayoutParams.() -> Unit) {
                if (cells.isEmpty()) return
                frame.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = inner
                    layoutParams = FrameLayout.LayoutParams(wrap, match, outer).apply(margin)
                    cells.forEach { addView(render(it, false)) }
                })
            }
            group(left, Gravity.START or Gravity.CENTER_VERTICAL, Gravity.CENTER_VERTICAL) { marginStart = pad }
            group(center, Gravity.CENTER, Gravity.CENTER) {}
            group(right, Gravity.END or Gravity.CENTER_VERTICAL, Gravity.CENTER_VERTICAL) { marginEnd = pad }
            bar = frame
        }
        val root: View = if (style.divider) {
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar)
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(match, 1)
                    setBackgroundColor(style.fg)
                })
            }
        } else bar
        val handle = PanelBarHandle(root, barHeight + if (style.divider) 1 else 0, style, checkIcon, titles)
        if (checkIcon != null) handle.setChecked(false)
        return handle
    }

    private fun render(cell: BarCell, inWeightCenter: Boolean): View = when (cell) {
        is BarCell.TextBtn -> TextView(ctx).apply {
            text = cell.label; setTextColor(style.fg)
            textSizePx(style.btnSize)
            gravity = Gravity.CENTER_VERTICAL
            val ph = u(style.btnPadH)
            val pv = u(style.btnPadV)
            setPadding(ph, pv, ph, pv)
            setOnClickListener { cell.onClick() }
        }
        is BarCell.Action -> action(cell, inWeightCenter)
        is BarCell.Check -> check(cell)

        is BarCell.GlyphBtn -> Widgets.iconCell(ctx, cell.glyph, cell.label, cell.onClick).also { c ->
            (c.getChildAt(0) as? LineIcon)?.color = style.fg
        }
        is BarCell.Title -> TextView(ctx).apply {
            text = cell.label; setTextColor(style.fg)
            textSizePx(40)
            typeface = Style.bold
            gravity = Gravity.CENTER
        }.also { titles.add(it) }
    }

    private fun action(cell: BarCell.Action, useWeight: Boolean): View {
        val iconPx = u(style.iconSize)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = if (useWeight) {
                LinearLayout.LayoutParams(0, match, 1f)
            } else {
                val p = u(style.actionPad)
                setPadding(p, 0, p, 0)
                LinearLayout.LayoutParams(wrap, match)
            }
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
                setImageDrawable(Style.iconPx(cell.iconAsset, iconPx, style.fg))
            })
            addView(TextView(ctx).apply {
                text = cell.label; setTextColor(style.fg)
                textSizePx(style.labelSize)
                gravity = Gravity.CENTER
                setPadding(0, u(style.labelGap), 0, 0)
            })
            setOnClickListener { cell.onClick() }
            cell.view = this
        }
    }

    private fun check(cell: BarCell.Check): View {
        val iconPx = u(style.iconSize)
        val icon = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(iconPx, iconPx) }
        checkIcon = icon
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(icon)

            addView(TextView(ctx).apply {
                text = cell.label; setTextColor(style.fg)
                textSizePx(style.labelSize)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, u(style.labelGap), 0, 0)
            }, LinearLayout.LayoutParams(wrap, wrap))
            setOnClickListener { cell.onToggle() }
        }
    }
}

class ListRow(val view: View, val titleView: TextView, private val metaView: TextView) {
    fun setMeta(value: String) {
        metaView.text = value
        metaView.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }
}

@SuppressLint("ViewConstructor")
class SideNav(context: Context, private val onPick: (String) -> Unit) : LinearLayout(context) {

    sealed class Entry {

        class Item(val id: String, val glyph: LineIcon.Glyph, val label: String, val count: String? = null, val open: Boolean? = null) : Entry()

        class Sub(val id: String, val label: String, val count: String? = null) : Entry()

        object Dots : Entry()
        object Rule : Entry()
    }

    private companion object {

        const val COUNT_TEXT = 30 * Style.INKHUB_U
        const val EXPAND = 40 * Style.INKHUB_U
    }

    private class Cell(val view: View, val label: TextView)

    private val cells = HashMap<String, Cell>()
    private val top = LinearLayout(context).apply { orientation = VERTICAL }
    private val bottom = LinearLayout(context).apply { orientation = VERTICAL }
    private var active: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Style.paper)
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setEntries(topEntries: List<Entry>, bottomEntries: List<Entry> = emptyList()) {
        cells.clear()
        fill(top, topEntries)
        fill(bottom, bottomEntries)
        refresh()
    }

    fun setActive(id: String?) {
        active = id
        refresh()
    }

    private fun fill(block: LinearLayout, entries: List<Entry>) {
        block.removeAllViews()
        for (entry in entries) when (entry) {
            is Entry.Item -> block.addView(item(entry), LayoutParams(LayoutParams.MATCH_PARENT, Style.u(Style.SIDE_ITEM)))
            is Entry.Sub -> block.addView(sub(entry), LayoutParams(LayoutParams.MATCH_PARENT, Style.u(Style.SIDE_SUB)))
            Entry.Dots -> block.addView(Widgets.dashes(context, insetPx = 48))
            Entry.Rule -> block.addView(Widgets.rule(context))
        }
    }

    private fun item(entry: Entry.Item): View {
        val text = Widgets.oneLine(Widgets.text(context, entry.label, Style.SIDE_TEXT))
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Style.u(Style.SIDE_PAD), 0, Style.u(Style.SIDE_PAD), 0)
            addView(LineIcon(context, entry.glyph, Style.SIDE_ICON), LayoutParams(Style.u(Style.SIDE_ICON), Style.u(Style.SIDE_ICON)))
            addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Style.u(Style.SIDE_ICON_GAP) })
            entry.count?.let { addView(Widgets.text(context, it, COUNT_TEXT, Style.soft).apply { setPadding(Style.u(10), 0, 0, 0) }) }

            entry.open?.let { open ->
                addView(LineIcon(context, LineIcon.Glyph.CHEVRON, EXPAND).apply { rotation = if (open) -90f else 90f },
                    LayoutParams(Style.u(EXPAND), Style.u(EXPAND)).apply { leftMargin = Style.u(10) })
            }
        }
        return frame(entry.id, row, text)
    }

    private fun sub(entry: Entry.Sub): View {
        val text = Widgets.oneLine(Widgets.text(context, entry.label, Style.SIDE_TEXT))
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            setPadding(Style.u(Style.SIDE_PAD + Style.SIDE_SUB_INDENT), 0, Style.u(Style.SIDE_PAD), 0)
            addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            entry.count?.let { addView(Widgets.text(context, it, COUNT_TEXT, Style.soft).apply { setPadding(Style.u(10), 0, 0, 0) }) }
        }
        return frame(entry.id, row, text)
    }

    private fun frame(id: String, row: View, label: TextView): View = FrameLayout(context).apply {
        addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setOnClickListener { onPick(id) }
        cells[id] = Cell(this, label)
    }

    private fun refresh() {
        cells.forEach { (id, cell) ->
            val on = id == active
            cell.view.background = if (on) Style.boxPx(Style.paper, Style.ink, Style.u(2.48f).coerceAtLeast(1), Style.u(2.48f).toFloat()) else null
            cell.label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
}

class QueryInput(context: Context) : EditText(context) {
    private val density = resources.displayMetrics.density
    private val caretPaint = Paint()

    var onImeEdit: ((String) -> Unit)? = null

    init {
        isCursorVisible = false
        setTextColor(Style.ink)
        setHintTextColor(Style.hint)
        highlightColor = Style.highlight
        textSize = Style.hcSp(17f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSingleLine = true

        background = Style.boxPx(Style.paper, Style.ink, Style.hcDp(1))
        minHeight = Style.hcDp(44)
        setPadding(Style.hcDp(12), 0, Style.hcDp(12), 0)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, false) {
            private fun edited(what: String, done: Boolean) = done.also { onImeEdit?.invoke(what) }
            override fun commitText(text: CharSequence?, newCursorPosition: Int) =
                edited("commit", super.commitText(text, newCursorPosition))
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
                edited("compose", super.setComposingText(text, newCursorPosition))
            override fun finishComposingText() = edited("finish", super.finishComposingText())
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
                edited("delete", super.deleteSurroundingText(beforeLength, afterLength))
            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                val done = super.sendKeyEvent(event)
                return if (event?.action == KeyEvent.ACTION_UP) edited("key", done) else done
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = layout ?: return
        val offset = selectionStart
        if (!isFocused || offset < 0 || offset != selectionEnd) return
        val line = layout.getLineForOffset(offset)
        val metrics = paint.fontMetrics

        val baseline = totalPaddingTop + layout.getLineBaseline(line).toFloat()
        val x = compoundPaddingLeft + layout.getPrimaryHorizontal(offset)
        caretPaint.color = currentTextColor
        canvas.drawRect(x, baseline + metrics.ascent, x + maxOf(2f, 1.5f * density), baseline + metrics.descent, caretPaint)
    }
}

@SuppressLint("ViewConstructor")
class SearchBox(
    context: Context,
    private val onClear: () -> Unit,
    private val onGo: () -> Unit,
    goLabel: String,
    clearLabel: String,
) : LinearLayout(context) {
    private companion object {
        const val BORDER = 1.24f
        const val FIELD_INSET = 2.48f
        const val TEXT_START = 18.63f
        const val TEXT_SIZE = 21.11f
    }

    val input = QueryInput(context).apply {
        background = null
        minHeight = 0
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.u(TEXT_SIZE).toFloat())
        setPadding(Style.u(TEXT_START), 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    private val clear = Widgets.iconButton(context, LineIcon.Glyph.CLEAR, Style.ICON_U, 60, clearLabel) { onClear() }
        .apply { visibility = GONE }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val inset = Style.u(FIELD_INSET)
        val field = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(inset, inset, 0, inset)
            background = Style.boxPx(Style.paper, Style.ink, Style.u(BORDER).coerceAtLeast(1))
            addView(input, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

            addView(clear, LayoutParams(Style.u(Style.SEARCH_HEIGHT - 2 * FIELD_INSET), LayoutParams.MATCH_PARENT))
        }
        val go = Widgets.iconButton(context, LineIcon.Glyph.SEARCH, Style.ICON_U, 60, goLabel) { onGo() }.apply {
            setBackgroundColor(Style.ink)
            (getChildAt(0) as LineIcon).inverse = true
        }
        addView(field, LayoutParams(0, Style.u(Style.SEARCH_HEIGHT), 1f))
        addView(go, LayoutParams(Style.u(Style.SEARCH_BUTTON), Style.u(Style.SEARCH_HEIGHT)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
            }
        })
    }
}

@SuppressLint("ViewConstructor")
class EmptyState(ctx: Context) : LinearLayout(ctx) {
    private companion object {
        const val ICON = 128
        const val TOP = 424

        const val TITLE_U = 34 * Style.INKHUB_U
        const val DETAIL_U = 30 * Style.INKHUB_U
    }

    private val iconBox = FrameLayout(ctx)
    private val title = Widgets.text(ctx, "", TITLE_U, bold = true).apply { gravity = Gravity.CENTER }
    private val detail = Widgets.text(ctx, "", DETAIL_U).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.3f)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Style.paper)
        val side = Style.inkPx(48)
        setPadding(side, Style.inkPx(TOP), side, 0)
        addView(iconBox, LayoutParams(Style.inkPx(ICON), Style.inkPx(ICON)))
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Style.inkPx(40) })
        addView(detail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Style.inkPx(24) })
    }

    fun set(glyph: LineIcon.Glyph?, title: String, detail: String = "") {
        iconBox.removeAllViews()
        iconBox.visibility = if (glyph == null) View.GONE else View.VISIBLE
        if (glyph != null) {
            val size = Style.inkPx(ICON)
            iconBox.addView(LineIcon(context, glyph, ICON * Style.INKHUB_U).apply { weight = 0.64f }, FrameLayout.LayoutParams(size, size))
        }
        this.title.text = title
        this.detail.text = detail
        this.detail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = minOf(Style.inkPx(TOP), h / 5)
        if (top != paddingTop) post { setPadding(paddingLeft, top, paddingRight, 0) }
    }
}

class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {

    fun get(x: Int, y: Int): Boolean = modules[y][x]

    fun toBitmap(scale: Int, quiet: Int = 4): Bitmap {
        val dim = (size + quiet * 2) * scale
        val pixels = IntArray(dim * dim) { Style.paper }
        for (y in 0 until size) for (x in 0 until size) {
            if (!modules[y][x]) continue
            val px = (x + quiet) * scale
            val py = (y + quiet) * scale
            for (dy in 0 until scale) {
                val row = (py + dy) * dim + px
                for (dx in 0 until scale) pixels[row + dx] = Style.ink
            }
        }
        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.RGB_565)
    }

    companion object {
        private const val TAG = "QrCode"

        fun encode(text: String): QrCode? {
            if (text.isEmpty()) return null
            return try {
                val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
                val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
                val size = matrix.width
                QrCode(size, Array(size) { y -> BooleanArray(size) { x -> matrix.get(x, y).toInt() == 1 } })
            } catch (e: Exception) {
                Log.w(TAG, "encode failed: ${e.message}")
                null
            }
        }
    }
}

class FolderCoverView(context: Context) : ViewGroup(context) {

    private class Geometry(val tabW: Float, val tabH: Float, val slant: Float, val radius: Float, val slots: List<RectF>)

    private val strokePx = strokeWidth()
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Style.ink
        strokeWidth = strokePx
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Style.paper
    }
    private val outline = Path()
    private var geo: Geometry? = null

    val slots: List<ImageView> = List(4) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = INVISIBLE
        }
    }

    init {
        setWillNotDraw(false)
        slots.forEach { addView(it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val g = geometry(w, h, strokePx).also { geo = it }
        for ((i, slot) in slots.withIndex()) {
            val r = g.slots[i]
            slot.measure(
                MeasureSpec.makeMeasureSpec(r.width().toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height().toInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val g = geo ?: return
        for ((i, slot) in slots.withIndex()) {
            val rect = g.slots[i]
            val x = rect.left.toInt(); val y = rect.top.toInt()
            slot.layout(x, y, x + slot.measuredWidth, y + slot.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val g = geo ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val i = strokePx / 2f
        val r = g.radius
        outline.reset()
        outline.moveTo(i, g.tabH + r)
        outline.lineTo(i, i + r)
        outline.quadTo(i, i, i + r, i)
        outline.lineTo(g.tabW, i)
        outline.lineTo(g.tabW + g.slant, g.tabH)
        outline.lineTo(w - i - r, g.tabH)
        outline.quadTo(w - i, g.tabH, w - i, g.tabH + r)
        outline.lineTo(w - i, h - i - r)
        outline.quadTo(w - i, h - i, w - i - r, h - i)
        outline.lineTo(i + r, h - i)
        outline.quadTo(i, h - i, i, h - i - r)
        outline.close()
        canvas.drawPath(outline, fillPaint)
        canvas.drawPath(outline, outlinePaint)
    }

    companion object {
        private fun strokeWidth(): Float = Style.dp(1.5f).coerceAtLeast(2).toFloat()

        private fun geometry(w: Int, h: Int, stroke: Float): Geometry {
            val tabH = (h * 0.075f).coerceAtLeast(stroke * 3)
            val pad = w * 0.085f
            val gap = w * 0.05f
            val top = tabH + pad
            val bottom = h - stroke / 2f - pad
            val slotW = ((w - 2 * pad - gap) / 2f).coerceAtLeast(1f)
            val slotH = ((bottom - top - gap) / 2f).coerceAtLeast(1f)
            val slots = List(4) { k ->
                val x = pad + (k % 2) * (slotW + gap)
                val y = top + (k / 2) * (slotH + gap)
                RectF(x, y, x + slotW, y + slotH)
            }
            return Geometry(tabW = w * 0.42f, tabH = tabH, slant = tabH * 0.9f, radius = w * 0.045f, slots = slots)
        }

        fun slotSize(w: Int, h: Int): Pair<Int, Int> {
            val r = geometry(w, h, strokeWidth()).slots[0]
            return r.width().toInt() to r.height().toInt()
        }
    }
}

class SideSlider(
    private val ctx: Context,
    private val rotation: () -> Int,
    private val listener: Listener,
) {
    interface Listener {

        fun onTurn(step: Int)

        fun onScrubStart(pos: Int)
        fun onScrubMove(pos: Int)

        fun onScrubEnd()
    }

    companion object {
        private const val TAG = "NotipalSlider"
        const val SIDE_LEFT = 1
        const val SIDE_RIGHT = 2

        const val LENGTH = 240

        private const val DIR_28 = "/sys/devices/platform/fe5d0000.i2c/i2c-4/4-0028/"
        private const val DIR_38 = "/sys/devices/platform/fe5d0000.i2c/i2c-4/4-0038/"
        private const val PROBE_28 = "/sys/bus/i2c/devices/4-0028/l_x"
        private const val LEFT_FILE = "l_x"
        private const val RIGHT_FILE = "r_x"

        private const val SLIP_OFFSET = 2

        private const val LONG_PRESS_MS = 200L
        private const val SCRUB_START = 20

        private const val SCRUB_JUMP = 50
        private const val IDLE_POLL_MS = 100L
        private const val ZERO_DEBOUNCE = 2

        private val PROVIDER_TABLES = arrayOf("varchar", "int", "integer", "long", "float", "boolean", "string", "text", "settings")

        fun isSliderKey(keyCode: Int): Boolean = keyCode in 300..301 || keyCode in 309..310
    }

    private class SideState {
        var active = false
        var downPos = -1
        var lastPos = -1
        var downAt = 0L
        var slide = false
        var two = false

        var held = false
        var scrub = false
        var zeros = 0

        fun reset() {
            active = false
            downPos = -1
            lastPos = -1
            downAt = 0L
            slide = false
            two = false
            held = false
            scrub = false
            zeros = 0
        }
    }

    private val inverted = Build.BOARD == "A5X2"
    @Volatile private var token = 0
    @Volatile private var running = false

    @Volatile private var preferredSide = SIDE_RIGHT

    @Volatile var scrubSession = false

    val side: Int get() = preferredSide

    fun start() {
        if (running) return
        running = true
        val mine = ++token
        Thread({ pollLoop(mine) }, "notipal-side-slider").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running) return
        running = false
        token++
    }

    private fun pollLoop(mine: Int) {

        PluginIdentity.claim()
        val dir = when {
            File(PROBE_28).exists() -> DIR_28
            File(DIR_38 + LEFT_FILE).exists() -> DIR_38
            else -> {
                Log.i(TAG, "no side slider on ${Build.BOARD}")
                running = false
                return
            }
        }
        val activePollMs = if (dir == DIR_28) 50L else 30L
        preferredSide = readPreferredSide()
        Log.i(TAG, "started dir=$dir poll=${activePollMs}ms side=$preferredSide inverted=$inverted")
        val sides = arrayOf(SideState(), SideState())
        while (token == mine) {
            val left = poll(dir + LEFT_FILE, SIDE_LEFT, sides[0], mine)
            val right = poll(dir + RIGHT_FILE, SIDE_RIGHT, sides[1], mine)
            try {
                Thread.sleep(if (left || right) activePollMs else IDLE_POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        Log.i(TAG, "stopped")
    }

    private fun poll(path: String, physicalSide: Int, s: SideState, mine: Int): Boolean {
        val positions = parsePositions(readSysfs(path))
        val now = SystemClock.uptimeMillis()
        if (positions == null) {
            s.zeros++
            if (s.zeros >= ZERO_DEBOUNCE && s.active) {
                when {
                    s.scrub -> {
                        val endPos = s.lastPos
                        post(mine) { scrubEnd(physicalSide, endPos) }
                    }
                    !s.two && !s.held && s.slide -> {
                        val distance = s.lastPos - s.downPos
                        post(mine) { turn(physicalSide, distance) }
                    }
                }
                s.reset()
            }
            return false
        }
        s.zeros = 0
        if (positions.size >= 2) {
            if (s.scrub) {
                val endPos = s.lastPos
                post(mine) { scrubEnd(physicalSide, endPos) }
            }
            s.scrub = false
            s.two = true
            s.active = true
            return true
        }
        if (s.two) return true
        val pos = positions[0]
        if (!s.active) {
            s.active = true
            s.downPos = pos
            s.lastPos = pos
            s.downAt = now
            if (scrubSession) {
                s.scrub = true
                post(mine) { scrubStart(physicalSide, pos) }
            }
            return true
        }
        if (s.scrub) {
            if (pos != s.lastPos) {
                val jumped = abs(pos - s.lastPos) > SCRUB_JUMP
                s.lastPos = pos
                if (jumped) post(mine) { scrubStart(physicalSide, pos) } else post(mine) { scrubMove(physicalSide, pos) }
            }
            return true
        }
        if (!s.held && !s.slide && abs(pos - s.downPos) <= SLIP_OFFSET && now - s.downAt >= LONG_PRESS_MS) {
            s.held = true
        }
        if (s.held && abs(pos - s.downPos) > SCRUB_START) {
            s.scrub = true
            s.lastPos = pos
            post(mine) { scrubStart(physicalSide, pos) }
            return true
        }
        if (s.slide || abs(pos - s.lastPos) > SLIP_OFFSET) {
            s.slide = true
            s.lastPos = pos
        }
        return true
    }

    private fun post(mine: Int, block: () -> Unit) {
        Main.post { if (token == mine && running) block() }
    }

    private fun flipped(): Boolean = rotation() == Surface.ROTATION_180

    private fun isPreferred(physicalSide: Int): Boolean {
        val side = if (flipped()) SIDE_LEFT + SIDE_RIGHT - physicalSide else physicalSide
        return side == preferredSide
    }

    private fun userPos(pos: Int): Int = if (flipped()) LENGTH - pos else pos

    private fun turn(physicalSide: Int, distance: Int) {
        if (distance == 0) return
        val down = (distance > 0) != flipped()
        Log.i(TAG, "slide ${if (down) "down" else "up"} physical=$physicalSide dist=$distance preferred=${isPreferred(physicalSide)}")
        if (!isPreferred(physicalSide)) return
        listener.onTurn(if (down) -1 else 1)
    }

    private fun scrubStart(physicalSide: Int, pos: Int) {
        if (!isPreferred(physicalSide)) return
        Log.i(TAG, "scrub start physical=$physicalSide pos=${userPos(pos)} session=$scrubSession")
        listener.onScrubStart(userPos(pos))
    }

    private fun scrubMove(physicalSide: Int, pos: Int) {
        if (isPreferred(physicalSide)) listener.onScrubMove(userPos(pos))
    }

    private fun scrubEnd(physicalSide: Int, pos: Int) {
        if (!isPreferred(physicalSide)) return
        Log.i(TAG, "scrub end physical=$physicalSide pos=${userPos(pos)}")
        listener.onScrubEnd()
    }

    private fun readSysfs(path: String): Long = try {
        File(path).bufferedReader().use { it.readLine()?.trim()?.toLongOrNull(16) ?: 0L }
    } catch (_: Exception) {
        0L
    }

    private fun parsePositions(packed: Long): IntArray? {
        if (packed == 0L) return null
        val positions = ArrayList<Int>(2)
        var remaining = packed
        while (remaining > 0) {
            var pos = (remaining and 0xFF).toInt()
            if (pos > 0) {
                if (inverted) pos = LENGTH - pos
                positions.add(pos)
            }
            remaining = remaining shr 8
        }
        return if (positions.isEmpty()) null else positions.toIntArray()
    }

    private fun readPreferredSide(): Int {
        val posture = readSetting("writing_posture")?.toIntOrNull()
        if (posture != null) return if (posture < 4) SIDE_RIGHT else SIDE_LEFT
        return if (readSetting("hand_dominance") == "1") SIDE_RIGHT else SIDE_LEFT
    }

    private fun readSetting(key: String): String? {
        val resolver = ctx.contentResolver
        for (table in PROVIDER_TABLES) {
            try {
                val uri = Uri.parse("content://com.ratta.supernote.settings.provider/$table")
                resolver.query(uri, arrayOf("value"), "name = ?", arrayOf(key), null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex("value")
                        if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index)
                    }
                }
            } catch (e: Throwable) {
                Log.d(TAG, "setting $key not in table $table: ${e.message}")
            }
        }
        return runCatching { Settings.System.getString(resolver, key) }.getOrNull()
            ?: runCatching { Settings.Secure.getString(resolver, key) }.getOrNull()
            ?: runCatching { Settings.Global.getString(resolver, key) }.getOrNull()
    }
}
