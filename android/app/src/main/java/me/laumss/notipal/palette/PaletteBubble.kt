package me.laumss.notipal.palette

import android.content.Context
import android.graphics.Point
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.common.Strings
import me.laumss.notipal.common.TouchInput
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.mosaic.MosaicPaletteSpec
import me.laumss.notipal.mosaic.PenSizeSpec
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.FloatWindow
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.WindowHooks
import me.laumss.notipal.ui_common.WindowSupport
import org.json.JSONArray
import kotlin.math.abs

object PaletteBubble : FloatWindow("palette") {
    override val tag: String get() = TAG
    private const val TAG = "PaletteBubble"

    private const val COLS = 5
    private const val ROWS = 2
    private const val MAX_SLOTS = COLS * ROWS - 1
    private const val FIRST_X_PX = 24
    private const val FIRST_BOTTOM_MARGIN_PX = 200
    private const val LEXI_OWNER = "paletteBubble"

    private const val BORDER_DP = 2

    private data class Slot(val penId: String, val thickness: Int, val penType: Int)

    private var presets: Array<Slot?> = arrayOfNulls(MAX_SLOTS)
    private var body: LinearLayout? = null

    private var wantShown = false
        set(value) {
            field = value
            if (value) WindowHooks.lexiHold(LEXI_OWNER) else WindowHooks.lexiRelease(LEXI_OWNER)
        }

    private var stowed = false

    private var placedW = 0
    private var placedH = 0

    fun toggle() {
        Main.post {
            stowed = false
            if (attached) {
                wantShown = false
                hide()
            } else {
                wantShown = true
                showBubble()
            }
        }
    }

    fun hideAndForget() {
        Main.post {
            stowed = false
            wantShown = false
            hide()
        }
    }

    fun stowForTwoFinger() {
        Main.post {
            if (!wantShown) return@post
            stowed = true
            wantShown = false
            hide()
        }
    }

    fun unstowAfterTwoFinger() {
        Main.post {
            if (!stowed) return@post
            stowed = false
            if (!MosaicLink.boardVisible) return@post
            wantShown = true
            showBubble()
        }
    }

    fun reloadPresets() {
        Main.post {
            val before = presets
            loadPresets()

            val view = body?.takeIf { showing } ?: return@post
            if (before.contentEquals(presets)) return@post
            populate(view)
            if (attached) {
                val (screenW, screenH) = Style.screenSize()
                val (w, h) = windowSize(view, screenW, screenH)
                updateParams { it.width = w; it.height = h }
            }
        }
    }

    override val rememberPosition: Boolean get() = false
    override val dragByBody: Boolean get() = false

    override fun closeForRotation() {
        stowed = false
        wantShown = false
        hide()
    }

    override fun windowSize(content: View, screenW: Int, screenH: Int): Pair<Int, Int> {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        )
        return content.measuredWidth to content.measuredHeight
    }

    override fun defaultPosition(w: Int, h: Int, screenW: Int, screenH: Int): Point =
        Point(FIRST_X_PX, (screenH - h - FIRST_BOTTOM_MARGIN_PX).coerceAtLeast(0))

    override fun clamp(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Point {
        if (screenW == placedW && screenH == placedH) return Point(x, y)
        placedW = screenW
        placedH = screenH
        return Point(
            x.coerceIn(0, (screenW - w).coerceAtLeast(0)),
            y.coerceIn(0, (screenH - h).coerceAtLeast(0))
        )
    }

    override fun clampDrag(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Point = Point(x, y)

    override fun buildParams(w: Int, h: Int): WindowManager.LayoutParams =
        super.buildParams(w, h).apply { flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS }

    override fun onMoved(x: Int, y: Int) {
        val (screenW, screenH) = Style.screenSize()
        placedW = screenW
        placedH = screenH
    }

    override fun onAttached(view: View) {
        Toolbar.setActive(Tools.INK_PALETTE, true)
    }

    override fun onDetached() {
        Main.post { if (!attached) Toolbar.setActive(Tools.INK_PALETTE, false) }
    }

    private fun showBubble() {

        PaletteHooks.closeRelaySmall()
        loadPresets()
        val view = BubbleLayout(App.context) { ev -> handleDragTouch(ev) }
        populate(view)
        body = view
        show(view)
    }

    private fun unit(): Float = App.context.resources.displayMetrics.density * WindowSupport.bubbleScale()

    private fun populate(container: LinearLayout) {
        container.removeAllViews()
        val ctx = container.context
        val sf = WindowSupport.bubbleScale()
        val u = unit()
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER_HORIZONTAL
        container.setPadding((12 * u).toInt(), (2 * u).toInt(), (6 * u).toInt(), (10 * u).toInt())

        container.background = Style.boxPx(Style.paper, Style.toolbarBorder, Style.toolbarDp(BORDER_DP).coerceAtLeast(1), 0f)

        val hasPresets = presets.any { it != null }
        container.addView(TextView(ctx).apply {
            text = Strings.get(if (hasPresets) "palette_bubble_hint" else "palette_bubble_empty")
            textSize = 11.5f * sf
            setTextColor(Style.ink)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (9 * u).toInt()
                bottomMargin = (9 * u).toInt()
            }
        })

        for (row in 0 until ROWS) {
            val rowLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (row > 0) topMargin = (4 * u).toInt() }
            }
            for (col in 0 until COLS) rowLayout.addView(slotView(ctx, row * COLS + col, u, sf))
            container.addView(rowLayout)
        }
    }

    private fun slotView(ctx: Context, index: Int, u: Float, sf: Float): View {
        val settingsCell = index == MAX_SLOTS
        val preset = presets.getOrNull(index)
        val circleSz = (44 * u).toInt()

        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(circleSz, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { if (index % COLS > 0) marginStart = (10 * u).toInt() }
        }
        val circle = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(circleSz, circleSz)
            background = if (preset == null) {
                val dash = (2 * u).coerceAtLeast(1f)
                Style.dashedOval(Style.paper, Style.paletteDash, (1 * u).toInt().coerceAtLeast(1), dash, dash)
            } else {
                Style.oval(Style.paper, Style.ink, (1.5f * u).toInt().coerceAtLeast(1))
            }
        }
        cell.addView(circle)
        cell.addView(TextView(ctx).apply {
            text = if (settingsCell) Strings.get("palette_open_settings")
                else preset?.let { PenSizeSpec.label(it.penType, it.thickness) }.orEmpty()
            textSize = 11.5f * sf
            typeface = Style.bold
            setTextColor(Style.ink)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(circleSz, (18 * u).toInt())
        })

        if (settingsCell) {
            circle.addView(TextView(ctx).apply {
                text = "+"
                textSize = 28f * sf
                setTextColor(Style.ink)
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            cell.contentDescription = Strings.get("palette_header_title")
            cell.setOnClickListener { Palette.openPanel() }
            return cell
        }
        if (preset == null) return cell

        val iconSz = (26 * u).toInt()
        circle.addView(ImageView(ctx).apply {
            setImageBitmap(Style.iconBitmap(MosaicPaletteSpec.iconAsset(preset.penId), iconSz))
            layoutParams = FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER)
        })
        cell.setOnClickListener {
            if (!MosaicLink.boardVisible) {
                Log.i(TAG, "slot $index tapped without the Mosaic board; ignored")
                return@setOnClickListener
            }
            MosaicLink.applyPalettePreset(preset.penId, preset.thickness) { result ->
                if (!result.optBoolean("success")) Dialogs.tip(Strings.get("palette_mosaic_retry"))
            }
        }
        return cell
    }

    private fun loadPresets() {
        val next = arrayOfNulls<Slot>(MAX_SLOTS)
        try {
            val json = Prefs.of(PalettePanel.PREFS).getString(MosaicPaletteSpec.PRESETS_KEY, null)
            if (json != null) {
                val arr = JSONArray(json)
                for (i in 0 until minOf(MAX_SLOTS, arr.length())) {
                    if (arr.isNull(i)) continue
                    val o = arr.getJSONObject(i)
                    val penId = MosaicPaletteSpec.normalize(o.getString("penId"))
                    val penType = MosaicPaletteSpec.penType(penId)
                    next[i] = Slot(penId, PenSizeSpec.snap(penType, o.getInt("thickness")), penType)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "reloadPresets: ${e.message}")
        }
        presets = next
    }

    private class BubbleLayout(ctx: Context, private val drag: (MotionEvent) -> Boolean) : LinearLayout(ctx) {
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragging = false
                    drag(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!TouchInput.isFinger(ev)) return false
                    if (dragging) return true
                    if (abs(ev.rawX - downRawX) > WindowSupport.DRAG_THRESHOLD_PX ||
                        abs(ev.rawY - downRawY) > WindowSupport.DRAG_THRESHOLD_PX
                    ) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {

            if (ev.actionMasked != MotionEvent.ACTION_DOWN) drag(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) dragging = false
            return true
        }
    }
}
