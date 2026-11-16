package me.laumss.notipal.ui_common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.util.Xml
import android.view.View
import android.view.WindowManager
import com.ratta.supernote.pluginlib.utils.DeviceUtils
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import org.xmlpull.v1.XmlPullParser
import java.lang.reflect.Method
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object Style {
    private const val TAG = "Style"

    val ink: Int = Color.BLACK
    val paper: Int = Color.WHITE
    val clear: Int = Color.TRANSPARENT

    val soft: Int = 0xFF222222.toInt()

    val pagerText: Int = Color.rgb(45, 45, 45)

    val muted: Int = 0xFF555555.toInt()

    val subtle: Int = 0xFF666666.toInt()

    val hint: Int = 0xFF888888.toInt()

    val emptyText: Int = 0xFF999999.toInt()

    val placeholder: Int = 0xFF9A9A9A.toInt()

    val sendIdle: Int = 0xFFA0A0A0.toInt()

    val line: Int = 0xFFB5B5B5.toInt()

    val faintText: Int = 0xFFBBBBBB.toInt()

    val highlight: Int = 0xFFD0D0D0.toInt()

    val divider: Int = 0xFFD8D8D8.toInt()

    val band: Int = 0xFFDCDCDC.toInt()

    val faint: Int = 0xFFE0E0E0.toInt()

    val gray: IntArray = intArrayOf(soft, pagerText, muted, subtle, hint, emptyText, placeholder, sendIdle, line, faintText, highlight, divider, band, faint)

    val pageHighlightGray: Int = 0xFFC8C8C8.toInt()

    fun rgb(red: Int, green: Int, blue: Int): Int = Color.rgb(red, green, blue)

    val toolbarFg: Int = Color.parseColor("#1A1A1A")
    val toolbarBorder: Int = Color.parseColor("#111111")
    val toolbarActive: Int = Color.parseColor("#111111")
    val toolbarFlash: Int = Color.parseColor("#0F0F10")

    val textBubbleInk: Int = Color.parseColor("#111111")

    val settingsValue: Int = Color.parseColor("#777777")
    val settingsToolHidden: Int = Color.parseColor("#B3B3B3")

    val clipSlotHint: Int = settingsValue

    val pickerInk: Int = Color.rgb(20, 20, 20)

    val pickerTitle: Int = Color.rgb(24, 24, 24)

    val pickerFolderLine: Int = Color.rgb(28, 28, 28)

    val pickerSlotLine: Int = Color.rgb(35, 35, 35)

    val pickerExtension: Int = Color.rgb(65, 65, 65)

    val pickerPageBorder: Int = Color.rgb(150, 150, 150)

    val pickerTrayDisabled: Int = Color.rgb(158, 158, 158)

    val pickerActionDisabled: Int = Color.rgb(170, 170, 170)

    val pickerCardStroke: Int = Color.rgb(184, 184, 184)

    val pickerFolderTab: Int = Color.rgb(222, 222, 222)

    val pickerImageBg: Int = Color.rgb(240, 240, 240)

    val pickerZoomScrim: Int = 0xCC000000.toInt()

    val captureBackdrop: Int = 0xFFE8E8E8.toInt()

    val paletteStepperText: Int = 0xFF6B6B6B.toInt()

    val paletteDivider: Int = 0xFFC6C6C6.toInt()

    val paletteDash: Int = 0xFF9D9D9D.toInt()

    val penDarkGray: Int = 0xFF9D9D9D.toInt()
    val penLightGray: Int = 0xFFC9C9C9.toInt()

    const val DISABLED_ALPHA = 0.4f

    private const val DESIGN_WIDTH_PX = 1920
    private const val DESIGN_DENSITY = 1.875f
    private const val MIN_FACTOR = 0.88f
    private const val LOW_DENSITY_BOOST = 1.37f
    private const val A5X_TOOLBAR_BOOST = 5.2f / 3.8f

    private val isA5X2: Boolean = Build.BOARD == "A5X2"

    private val isA5X: Boolean =
        !isA5X2 &&
            (Build.MODEL.equals("Supernote A5 X", ignoreCase = true) ||
                (Build.MODEL.contains("A5 X", ignoreCase = true) &&
                    !Build.MODEL.contains("X2", ignoreCase = true)) ||
                Build.MODEL.equals("A5X", ignoreCase = true) ||
                Build.MODEL.endsWith(" A5X", ignoreCase = true))

    private val metrics get() = App.context.resources.displayMetrics

    private fun shortSide(): Int = minOf(metrics.widthPixels, metrics.heightPixels)

    private fun densityBoost(): Float =
        if (shortSide() < DESIGN_WIDTH_PX && metrics.density <= 1.0f) LOW_DENSITY_BOOST else 1.0f

    fun factor(): Float {
        val short = shortSide()
        val raw = if (short >= DESIGN_WIDTH_PX) 1.0f else short.toFloat() / DESIGN_WIDTH_PX
        return maxOf(raw, MIN_FACTOR)
    }

    private fun isA5XToolbar(): Boolean {
        if (isA5X) return true
        if (isA5X2) return false
        val dm = metrics
        val short = minOf(dm.widthPixels, dm.heightPixels)
        val long = maxOf(dm.widthPixels, dm.heightPixels)
        return short == 1404 && long == 1872 && dm.density <= 1.5f
    }

    fun toolbarDp(v: Int): Int = (v * metrics.density * toolbarScale()).roundToInt()

    fun toolbarScale(): Float {
        val long = maxOf(metrics.widthPixels, metrics.heightPixels)
        val extra = if (long == 2560 && shortSide() == 1920) 1.1f else 1.0f
        return maxOf(0.86f, toolbarFactor()) * extra
    }

    fun toolbarFactor(): Float = if (isA5XToolbar()) factor() * A5X_TOOLBAR_BOOST else factor() * densityBoost()

    private fun boostedFactor(): Float = factor() * densityBoost()

    fun dp(v: Float): Int = (v * metrics.density * boostedFactor()).roundToInt()

    fun dp(v: Int): Int = dp(v.toFloat())

    fun sp(v: Float): Float = v * boostedFactor()

    fun px(designPx: Number): Int = (designPx.toFloat() * metrics.density * densityBoost() / DESIGN_DENSITY).roundToInt()

    fun textPx(designPx: Number): Float = px(designPx).toFloat()

    private const val PANEL_WIDTH_DP = 558.93f
    private const val LARGE_SCREEN_LONG_PX = 2560
    private const val PANEL_WIDTH_PCT_PORTRAIT_LARGE = 0.64f
    private const val PANEL_WIDTH_PCT_LANDSCAPE_LARGE = 0.70f
    private const val PANEL_WIDTH_PCT_LANDSCAPE_SMALL = 0.68f
    private const val PANEL_HEIGHT_PCT_PORTRAIT_LARGE = 0.76
    private const val PANEL_HEIGHT_PCT_LANDSCAPE_LARGE = 0.82
    private const val PANEL_HEIGHT_PCT_LANDSCAPE_SMALL = 0.88

    private fun useLargePanelPct(screenLong: Int) = isA5X || screenLong >= LARGE_SCREEN_LONG_PX

    fun screenSize(): Pair<Int, Int> {
        val wm = App.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val bounds = wm.currentWindowMetrics.bounds
                    return bounds.width() to bounds.height()
                }
                @Suppress("DEPRECATION")
                val size = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
                return size.x to size.y
            } catch (e: Exception) {
                Log.w(TAG, "screen size from WindowManager failed, using display metrics", e)
            }
        }
        return metrics.widthPixels to metrics.heightPixels
    }

    fun panelWidth(): Int {
        val (w, h) = screenSize()
        return panelWidth(w, h)
    }

    fun panelWidth(screenW: Int, screenH: Int): Int {
        val density = metrics.density
        val landscape = screenW > screenH
        return when {
            useLargePanelPct(maxOf(screenW, screenH)) -> {
                val ratio = if (landscape) PANEL_WIDTH_PCT_LANDSCAPE_LARGE else PANEL_WIDTH_PCT_PORTRAIT_LARGE
                (screenW * ratio).toInt()
            }
            landscape -> (screenW * PANEL_WIDTH_PCT_LANDSCAPE_SMALL).toInt()
            else -> (PANEL_WIDTH_DP * density * (if (density <= 1.0f) LOW_DENSITY_BOOST else 1.0f)).toInt()
        }
    }

    fun panelHeight(ratio: Double): Int {
        val (w, h) = screenSize()
        return panelHeight(w, h, ratio)
    }

    fun panelHeight(screenW: Int, screenH: Int, ratio: Double): Int {
        val landscape = screenW > screenH
        val r = when {
            useLargePanelPct(maxOf(screenW, screenH)) ->
                if (landscape) PANEL_HEIGHT_PCT_LANDSCAPE_LARGE else PANEL_HEIGHT_PCT_PORTRAIT_LARGE
            landscape -> maxOf(ratio, PANEL_HEIGHT_PCT_LANDSCAPE_SMALL)
            else -> ratio
        }
        return (screenH * r).toInt()
    }

    enum class DeviceType(val sdkValue: Int) {
        A5(0), A6(1), A6X(2), A5X(3), A6X2(4), A5X2(5), UNKNOWN(-1);

        companion object {
            fun fromSdkValue(value: Int): DeviceType = entries.firstOrNull { it.sdkValue == value } ?: UNKNOWN
        }
    }

    data class DeviceProfile(
        val type: DeviceType,
        val screenWidth: Int,
        val screenHeight: Int,
        val uiScale: Float,
        val titleScale: Float,
        val pickerGridColumns: Int,
        val supportsA5BookFeatures: Boolean,
    )

    private const val FALLBACK_MIN_SCALE = 0.75f
    private const val A5X_TITLE_SCALE = 0.88f
    private const val A6_UI_SCALE = 0.86f
    private const val CONTENT_SCALE = 1.25f

    private val sdkDeviceType: Int by lazy {
        try {
            DeviceUtils.getDeviceType()
        } catch (e: Throwable) {
            Log.w(TAG, "device type unavailable", e)
            -1
        }
    }

    fun device(): DeviceProfile = deviceProfile(sdkDeviceType, metrics.widthPixels, metrics.heightPixels)

    fun deviceProfile(sdkType: Int, screenWidth: Int, screenHeight: Int): DeviceProfile {
        val type = DeviceType.fromSdkValue(sdkType)
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val short = min(width, height)
        val known = type != DeviceType.UNKNOWN
        val a6 = type == DeviceType.A6X || type == DeviceType.A6X2
        return DeviceProfile(
            type = type,
            screenWidth = width,
            screenHeight = height,
            uiScale = when {
                a6 -> A6_UI_SCALE
                known -> 1f
                else -> (short / DESIGN_WIDTH_PX.toFloat()).coerceIn(FALLBACK_MIN_SCALE, 1f)
            },
            titleScale = when {
                type == DeviceType.A5X -> A5X_TITLE_SCALE
                a6 -> A6_UI_SCALE
                known -> 1f
                else -> (short / DESIGN_WIDTH_PX.toFloat()).coerceIn(A5X_TITLE_SCALE, 1f)
            },
            pickerGridColumns = if (short >= DESIGN_WIDTH_PX) 5 else 4,
            supportsA5BookFeatures = type == DeviceType.A5X || type == DeviceType.A5X2,
        )
    }

    fun hcPx(designPx: Number): Int = (designPx.toFloat() * metrics.density / DESIGN_DENSITY * device().uiScale).roundToInt()

    fun hcNoteSp(designPx: Number): Float = designPx.toFloat() / DESIGN_DENSITY * device().uiScale

    fun hcTitleSp(v: Float): Float = v * device().titleScale

    fun hcDp(v: Number): Int = (v.toFloat() * metrics.density * CONTENT_SCALE * device().uiScale).roundToInt()

    fun hcSp(v: Float): Float = v * CONTENT_SCALE * device().uiScale

    fun hcScale(): Float = metrics.density / DESIGN_DENSITY * device().uiScale

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
    const val ICON_U = 30
    const val CHEVRON_U = 26
    const val PANE_INSET = 32
    const val SEARCH_HEIGHT = 64.59f
    const val SEARCH_BUTTON = 100

    const val F_BODY = 22
    const val F_SECTION = 20
    const val F_META = 18

    const val OUTLINE_U = 1.24f

    const val MINI_TEXT_SCALE = 0.85f

    const val MINI_TITLE_PX = 30

    fun u(units: Number): Int = hcPx(units.toFloat() * DESIGN_DENSITY)

    fun inkPx(inkHubPx: Number): Int = hcPx(inkHubPx.toFloat() * INKHUB_PX)

    fun rawDp(v: Number): Int = (v.toFloat() * metrics.density).roundToInt()

    fun rawSp(v: Number): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), metrics)

    private fun spScale(): Float = metrics.density * App.context.resources.configuration.fontScale

    fun snuxFont(units: Number): Int = (u(units) / spScale()).roundToInt().coerceAtLeast(1)

    fun detailFont(): Int = (textPx(MINI_TITLE_PX) / spScale()).roundToInt().coerceAtLeast(1)

    fun miniTitleSp(): Int = snuxFont(F_BODY * 0.8f) + 3

    val bold: Typeface get() = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    val normal: Typeface get() = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

    fun weighted(weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false) else Typeface.DEFAULT_BOLD

    fun box(fill: Int, stroke: Int = 0, strokeDp: Float = 0f, radiusDp: Float = 0f): GradientDrawable =
        boxPx(fill, stroke, if (strokeDp > 0f) dp(strokeDp) else 0, if (radiusDp > 0f) dp(radiusDp).toFloat() else 0f)

    fun dashedBox(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke, dp(3).toFloat(), dp(3).toFloat())
    }

    fun dashedOval(fill: Int, stroke: Int, strokePx: Int, dashPx: Float, gapPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(strokePx, stroke, dashPx, gapPx)
        }

    fun boxPx(fill: Int, stroke: Int = 0, strokePx: Int = 0, radiusPx: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, stroke)
            cornerRadius = radiusPx
        }

    fun oval(fill: Int, stroke: Int = 0, strokePx: Int = 0, sizePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, stroke)
            if (sizePx > 0) setSize(sizePx, sizePx)
        }

    fun clearDrawable(): Drawable = ColorDrawable(clear)

    fun setEnabledLook(view: View, enabled: Boolean) {
        if (view.isEnabled != enabled) view.isEnabled = enabled
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        if (view.alpha != alpha) view.alpha = alpha
    }

    fun icon(name: String, sizeDp: Float, color: Int = ink): Drawable = iconPx(name, dp(sizeDp), color)

    fun iconPx(name: String, sizePx: Int, color: Int = ink, strokeDeltaPx: Float = 0f): Drawable {
        val bmp = iconBitmap(name, sizePx, color, strokeDeltaPx) ?: return clearDrawable()
        return BitmapDrawable(App.context.resources, bmp)
    }

    fun iconBitmap(name: String, widthPx: Int, tint: Int? = null, strokeDeltaPx: Float = 0f): Bitmap? =
        VectorIcons.load(assetPath(name), widthPx, tint, strokeDeltaPx)

    private fun assetPath(name: String): String =
        if (name.contains('/') || name.endsWith(".xml")) name else "icons/$name.xml"

    private object VectorIcons {
        private class VPath(
            val path: Path,
            val fillColor: Int?,
            val strokeColor: Int?,
            val strokeWidth: Float,
            val cap: Paint.Cap,
            val join: Paint.Join,
        )

        private val cache = HashMap<String, Bitmap>()
        private val failed = HashSet<String>()

        private val pathParser: Method? by lazy {
            try {
                Class.forName("android.util.PathParser").getMethod("createPathFromPathData", String::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "PathParser unavailable", e)
                null
            }
        }

        fun load(asset: String, widthPx: Int, tint: Int?, strokeDeltaPx: Float): Bitmap? {
            if (widthPx <= 0) return null
            val key = "$asset@$widthPx@${tint ?: "null"}@$strokeDeltaPx"
            synchronized(cache) { cache[key]?.let { return it } }
            return try {
                val bmp = render(asset, widthPx, tint, strokeDeltaPx) ?: return null
                synchronized(cache) { cache[key] = bmp }
                bmp
            } catch (e: Exception) {
                if (synchronized(failed) { failed.add(asset) }) Log.w(TAG, "icon $asset failed", e)
                null
            }
        }

        private fun render(asset: String, widthPx: Int, tint: Int?, strokeDeltaPx: Float): Bitmap? {
            var viewportW = 24f
            var viewportH = 24f
            val paths = ArrayList<VPath>()
            App.context.assets.open(asset).use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(Xml.FEATURE_RELAXED, true)
                parser.setInput(stream, "UTF-8")
                var evt = parser.eventType
                while (evt != XmlPullParser.END_DOCUMENT) {
                    if (evt == XmlPullParser.START_TAG) when (parser.name) {
                        "vector" -> {
                            attr(parser, "viewportWidth")?.toFloatOrNull()?.let { viewportW = it }
                            attr(parser, "viewportHeight")?.toFloatOrNull()?.let { viewportH = it }
                        }
                        "path" -> {
                            val data = attr(parser, "pathData").orEmpty()
                            if (data.isNotEmpty()) paths.add(VPath(
                                path = parsePath(data),
                                fillColor = parseColor(attr(parser, "fillColor")),
                                strokeColor = parseColor(attr(parser, "strokeColor")),
                                strokeWidth = attr(parser, "strokeWidth")?.toFloatOrNull() ?: 0f,
                                cap = when (attr(parser, "strokeLineCap")) {
                                    "round" -> Paint.Cap.ROUND
                                    "square" -> Paint.Cap.SQUARE
                                    else -> Paint.Cap.BUTT
                                },
                                join = when (attr(parser, "strokeLineJoin")) {
                                    "round" -> Paint.Join.ROUND
                                    "bevel" -> Paint.Join.BEVEL
                                    else -> Paint.Join.MITER
                                },
                            ))
                        }
                    }
                    evt = parser.next()
                }
            }
            if (paths.isEmpty() || viewportW <= 0 || viewportH <= 0) return null
            val scale = widthPx / viewportW
            val heightPx = (viewportH * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            for (vp in paths) {
                if (vp.fillColor != null) {
                    canvas.drawPath(vp.path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = tint ?: vp.fillColor
                    })
                }
                if (vp.strokeColor != null && vp.strokeWidth > 0f) {
                    canvas.drawPath(vp.path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = tint ?: vp.strokeColor
                        strokeWidth = (vp.strokeWidth + strokeDeltaPx / scale).coerceAtLeast(vp.strokeWidth * 0.3f)
                        strokeCap = vp.cap
                        strokeJoin = vp.join
                    })
                }
            }
            return bmp
        }

        private fun parsePath(data: String): Path {
            val p = Path()
            val parser = pathParser ?: return p
            try {
                (parser.invoke(null, data) as? Path)?.let { p.set(it) }
            } catch (e: Exception) {
                Log.w(TAG, "bad pathData: ${data.take(40)}", e)
            }
            return p
        }

        private fun attr(parser: XmlPullParser, name: String): String? =
            parser.getAttributeValue("http://schemas.android.com/apk/res/android", name)

        private fun parseColor(s: String?): Int? {
            if (s == null || !s.startsWith("#")) return null
            val hex = s.substring(1)
            val full = if (hex.length == 3 || hex.length == 4) hex.map { "$it$it" }.joinToString("") else hex
            return try {
                val c = Color.parseColor("#$full")
                if (Color.alpha(c) == 0) null else c
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "bad colour $s", e)
                null
            }
        }
    }
}

class LineIcon(ctx: Context, private val glyph: Glyph, private val sizeU: Number) : View(ctx) {
    enum class Glyph { SEARCH, CLOSE, CLEAR, CHEVRON, BACK, CLOCK, SHOT, RECEIVE, FOLDER, INBOX, CHAT, MIC, STAR, PEN, SEND, TRASH, CROP, INSERT, RESTORE, PLUS, COPY, GEAR }

    var color: Int = Style.ink
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
        val size = Style.u(sizeU).toFloat().coerceAtMost(minOf(width, height).toFloat())
        val k = size / 30f
        val ox = (width - size) / 2f
        val oy = (height - size) / 2f
        val ink = when {
            !isEnabled -> Style.placeholder
            inverse -> Style.paper
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
                    val px = x(15f + r * cos(a).toFloat())
                    val py = y(15f + r * sin(a).toFloat())
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
