package me.laumss.notipal.panels
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.*
import me.laumss.notipal.bubbles.PaletteBubbleModule

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.ui_common.*
import org.json.JSONArray
import org.json.JSONObject

class PalettePanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "PalettePanel"
    override val panelName = "palette"
    override val yieldsLexi = true
    override val heightRatio = 0.90

    companion object {
        @Volatile var currentInstance: PalettePanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): PalettePanel {
            val inst = currentInstance ?: PalettePanel(ctx, module)
            currentInstance = inst
            return inst
        }

        const val COLS = 5
        const val VISIBLE_COLS = 5
        const val ROWS = 2
        const val MAX_SLOTS = COLS * ROWS - 1

        private const val SLOT_CIRCLE_PX = 96
        private const val SLOT_ICON_PX = 54
        private const val SLOT_LABEL_HEIGHT_PX = 46
        private const val SLOT_LABEL_MARGIN_PX = 9
        private const val SLOT_COLUMN_GAP_PX = 106
        private const val SLOT_ROW_GAP_PX = 28
        private const val SLOT_BORDER_PX = 2
        private const val SLOT_SELECTED_BORDER_PX = 7
        private const val SLOT_DASH_PX = 2
        private const val COLOR_BORDER_PX = 1
        private const val COLOR_SELECTED_BORDER_PX = 6
        private const val COLOR_DASH_PX = 2
        private const val COLOR_DASH_GAP_PX = 4

        private const val EDITOR_CIRCLE_PX = 88
        private const val EDITOR_ICON_PX = 48
        private const val EDITOR_GAP_PX = 24
        private const val COLOR_SWATCH_PX = 60

        private val INK = Color.BLACK
        private val INK2 = Color.parseColor("#6B6B6B")
        private val LINE = Color.parseColor("#C6C6C6")
        private val LINE2 = Color.parseColor("#9D9D9D")

        fun normalizePenType(raw: Int): Int? = when (raw) {
            PenSizeSpec.NEEDLE, PenSizeSpec.BALL, PenSizeSpec.CALLIGRAPHY, PenSizeSpec.MARKER, PenSizeSpec.PENCIL -> raw
            else -> null
        }
    }

    data class PenDef(
        val id: String,
        val penType: Int,
        val label: String,
    )

    private val PENS = listOf(
        PenDef("needle", 10, NativeLocale.t("pen_needle")),
        PenDef("ball",   16, NativeLocale.t("pen_ball")),
        PenDef("brush",  PenSizeSpec.CALLIGRAPHY, NativeLocale.t("pen_calligraphy")),
        PenDef("marker", 11, NativeLocale.t("pen_marker")),
    )

    private val MOSAIC_PENS = listOf(
        PenDef("ball", PenSizeSpec.CALLIGRAPHY, NativeLocale.t("pen_calligraphy")),
        PenDef("needle", 10, NativeLocale.t("pen_needle")),
        PenDef("pencil", PenSizeSpec.PENCIL, NativeLocale.t("pen_pencil")),
        PenDef("marker", 11, NativeLocale.t("pen_marker")),
        PenDef("mk-lightGray", 11, NativeLocale.t("palette_marker_light_gray")),
        PenDef("mk-darkGray", 11, NativeLocale.t("palette_marker_dark_gray")),
    )

    private fun visiblePens(): List<PenDef> = if (mosaicSession) MOSAIC_PENS else PENS

    private fun canonicalPenId(id: String): String = if (mosaicSession) MosaicPaletteSpec.normalize(id) else id

    private fun penDefById(id: String) = visiblePens().find { it.id == canonicalPenId(id) } ?: visiblePens()[0]

    data class PenColorDef(val key: String, val value: Int, val label: String, val swatch: Int)

    private val PRODUCT_COLORS = listOf(
        PenColorDef("black",     0x00, NativeLocale.t("color_black"),      Color.BLACK),
        PenColorDef("darkGray",  0x9D, NativeLocale.t("color_dark_gray"),  Color.parseColor("#9D9D9D")),
        PenColorDef("lightGray", 0xC9, NativeLocale.t("color_light_gray"), Color.parseColor("#C9C9C9")),
        PenColorDef("ghost",     0xFE, NativeLocale.t("color_ghost"),      Color.WHITE),
    )

    private fun colorDefByKey(key: String) = PRODUCT_COLORS.find { it.key == key }

    data class TierPreset(val key: String, var labelValue: Float)

    private val tierPresets = mutableListOf(
        TierPreset("thin",   0.3f),
        TierPreset("medium", 0.5f),
        TierPreset("wide",   0.7f),
        TierPreset("thick",  1.0f),
    )

    data class Preset(val penId: String, val color: String, val thickness: Int, val locked: Boolean = false)

    private var strokeCount = 0
    private var geometryCount = 0
    private var avgThickness = 100
    private var elementNums = listOf<Int>()
    private var dominantPenType: Int? = null
    private var dominantPenColor: Int? = null
    private var mosaicSession = false
    private var mosaicSelection = JSONObject()
    private var initialPenId: String? = null

    private var presets = arrayOfNulls<Preset>(MAX_SLOTS)
    private var selectedSlot: Int? = null

    private var selectedColor: String? = null
    private var selectedPenId: String? = null
    private var thickness = 100
    private var thicknessChanged = false
    private var initialColor: String? = null
    private var initialPenType: Int? = null
    private var activeTierKey: String? = null

    private var thicknessLabel: TextView? = null
    private var gridContainer: LinearLayout? = null
    private var colorContainer: LinearLayout? = null
    private var penTypeLabel: TextView? = null
    private var thicknessHeaderLabel: TextView? = null
    private var thicknessSection: LinearLayout? = null
    private var applyBtn: TextView? = null
    private val penBtns = mutableMapOf<String, View>()
    private val colorBtns = mutableMapOf<String, View>()
    private val tierBtns = mutableMapOf<String, View>()
    private var tierRowView: View? = null
    private var stepperView: View? = null
    private var pencilGrid: View? = null
    private val pencilCircles = mutableListOf<View>()

    fun show(infoJson: String) {
        currentInstance = this
        PaletteBubbleModule.hideStatic()
        parseInfo(infoJson)
        presets.fill(null)
        selectedSlot = null
        loadPresets()
        loadTierPrefs()
        selectedPenType(dominantPenType)
        selectedColor = if (mosaicSession) MosaicPaletteSpec.color(selectedPenId ?: "ball")
            else dominantPenColor?.let { c -> PRODUCT_COLORS.find { it.value == c }?.key } ?: "black"
        initialPenId = selectedPenId
        initialPenType = dominantPenType?.let { normalizePenType(it) }
        initialColor = selectedColor
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "show: dominantPenType=$dominantPenType -> $selectedPenId, " +
            "dominantPenColor=$dominantPenColor -> $selectedColor, avgThickness=$avgThickness")
        thicknessChanged = false
        activeTierKey = null
        thickness = PenSizeSpec.snap(initialPenType, avgThickness)

        if (elementNums.isNotEmpty() || (mosaicSession && (mosaicSelection.optJSONArray("strokeIds")?.length() ?: 0) > 0)) pushCurrentPreset()
        showPanel()
    }

    private fun selectedPenType(type: Int?) {
        if (mosaicSession) {
            selectedPenId = MosaicPaletteSpec.normalize(mosaicSelection.optString("dominantPenId", "ball"))
            return
        }
        val normalized = type?.let { normalizePenType(it) }
        selectedPenId = when (normalized) {
            PenSizeSpec.BALL -> "ball"
            PenSizeSpec.CALLIGRAPHY -> "brush"
            PenSizeSpec.MARKER -> "marker"
            else -> "needle"
        }
    }

    override fun onHide() {
        thicknessLabel = null
        gridContainer = null
        colorContainer = null
        colorHeaderLabel = null
        penTypeLabel = null
        thicknessHeaderLabel = null
        thicknessSection = null
        applyBtn = null
        penBtns.clear()
        colorBtns.clear()
        tierBtns.clear()
        tierRowView = null
        stepperView = null
        pencilGrid = null
        pencilCircles.clear()
        currentInstance = null
    }

    private fun parseInfo(json: String) {
        try {
            val o = JSONObject(json)
            mosaicSession = o.optBoolean("mosaic", false)
            mosaicSelection = o
            val runtimeDeviceType = if (o.has("deviceType") && !o.isNull("deviceType")) o.getInt("deviceType") else null
            val runtimePage = o.optJSONObject("pageSize")
            PenSizeSpec.configureRuntime(
                runtimeDeviceType,
                runtimePage?.optInt("width", 0),
                runtimePage?.optInt("height", 0),
            )
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "runtime pen table deviceType=$runtimeDeviceType " +
                "pageSize=${runtimePage?.optInt("width", 0)}x${runtimePage?.optInt("height", 0)}")
            strokeCount = o.optInt("strokeCount", 0)
            geometryCount = o.optInt("geometryCount", 0)
            avgThickness = o.optInt("avgThickness", 100)
            dominantPenType = if (o.has("dominantPenType") && !o.isNull("dominantPenType"))
                o.getInt("dominantPenType") else null
            dominantPenColor = if (o.has("dominantPenColor") && !o.isNull("dominantPenColor"))
                o.getInt("dominantPenColor") else null
            val arr = o.optJSONArray("elementNums")
            elementNums = if (arr != null) (0 until arr.length()).map { arr.getInt(it) } else emptyList()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "parseInfo: ${e.message}")
        }
    }

    private fun curPreset(): Preset? = selectedSlot?.let { presets[it] }

    private fun pushCurrentPreset() {
        val penId = selectedPenId ?: return
        val color = selectedColor ?: return
        val penType = penDefById(penId).penType
        val incoming = Preset(penId, color, PenSizeSpec.snap(penType, thickness))
        val writableSlots = presets.indices.filter { presets[it]?.locked != true }
        if (writableSlots.isEmpty()) return
        val ordered = mutableListOf(incoming)
        for (index in writableSlots) {
            presets[index]?.let { ordered.add(it) }
        }
        writableSlots.forEachIndexed { position, index -> presets[index] = ordered.getOrNull(position) }
        selectedSlot = writableSlots.first()
        savePresets()
    }

    private fun currentPenDef(): PenDef? = selectedPenId?.let { penDefById(it) }
    private fun effectivePenType(): Int? = currentPenDef()?.penType

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("palette_header_title"))

            custom { host ->
                val scrollHost = PanelScrollHost(host.ctx, overlayScrollbar = true)
                val content = scrollHost.content
                content.setPadding(0, 0, 0, 0)

                content.addView(buildSlotsSection())
                content.addView(makeFullDivider())

                content.addView(buildLowerSplitSection())

                updatePenSelection()
                updateColorSelection()
                updateTierSelection()
                syncThicknessDisplay()

                scrollHost.view
            }

            custom { _ ->
                applyBtn = makeFilledBtn(NativeLocale.t("palette_apply")) { doApply() }
                updateApplyState()
                makeBottomBar(
                    leftButtons = listOf(
                        makeOutlinedBtn(NativeLocale.t("palette_reset")) { doResetAll() }
                    ),
                    rightButtons = listOf(
                        makeOutlinedBtn(NativeLocale.t("cancel")) { closeAndRestore() },
                        applyBtn!!
                    )
                )
            }
        }
    }

    private fun makeFullDivider(): View {
        return View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f)
            )
            setBackgroundColor(LINE)
        }
    }

    private fun buildSlotsSection(): LinearLayout {
        val section = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, dp(22))
        }

        gridContainer = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        rebuildGrid()
        section.addView(gridContainer)

        return section
    }

    private fun rebuildGrid() {
        val container = gridContainer ?: return
        container.removeAllViews()
        val columnGap = ScreenScale.px(reactContext, SLOT_COLUMN_GAP_PX)
        val rowGap = ScreenScale.px(reactContext, SLOT_ROW_GAP_PX)
        for (r in 0 until ROWS) {
            val row = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (r > 0) topMargin = rowGap }
            }
            for (c in 0 until VISIBLE_COLS) {
                val idx = r * COLS + c
                if (c > 0) row.addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(columnGap, 0)
                })
                row.addView(if (idx < MAX_SLOTS) makeSlotCell(idx) else makeSlotLockCell())
            }
            container.addView(row)
        }
    }

    private fun makeSlotCell(index: Int): View {
        val preset = presets[index]
        val isActive = index == selectedSlot
        val circleSize = ScreenScale.px(reactContext, SLOT_CIRCLE_PX)
        val iconSize = ScreenScale.px(reactContext, SLOT_ICON_PX)

        val cell = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                circleSize, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (preset != null) {
                    if (selectedSlot == index) {
                        selectedSlot = null
                        restoreFromLasso()
                    } else {
                        selectedSlot = index
                        loadSlotIntoEditor()
                    }
                    refreshAll()
                } else {
                    val color = selectedColor ?: "black"
                    val penId = selectedPenId ?: "needle"
                    presets[index] = Preset(penId, color, thickness)
                    selectedSlot = index
                    savePresets()
                    refreshAll()
                }
            }
        }

        val circle = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            background = makeSlotCircleBackground(isActive)
        }

        if (preset == null) {
            circle.addView(TextView(reactContext).apply {
                text = "+"
                textSize = sp(28f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(INK)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        } else {
            val pen = penDefById(preset.penId)
            addNativePenIcon(
                circle,
                if (mosaicSession) MosaicPaletteSpec.iconAsset(preset.penId)
                else PenSizeSpec.penIconAsset(pen.penType, preset.color),
                iconSize,
            )
            if (preset.locked) {
                val lockSize = ScreenScale.px(reactContext, 28)
                circle.addView(ImageView(reactContext).apply {
                    setImageBitmap(VectorAssets.loadBitmap(reactContext, "icons/ic_palette_lock.xml", lockSize))
                    setBackgroundColor(Color.WHITE)
                    contentDescription = NativeLocale.t("palette_slot_locked")
                    layoutParams = FrameLayout.LayoutParams(lockSize, lockSize, Gravity.TOP or Gravity.END)
                })
            }
        }

        cell.addView(circle)

        val label = preset?.let {
            PenSizeSpec.label(penDefById(it.penId).penType, it.thickness)
        }.orEmpty()
        cell.addView(TextView(reactContext).apply {
            text = label
            textSize = sp(17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                circleSize, ScreenScale.px(reactContext, SLOT_LABEL_HEIGHT_PX)
            ).apply {
                topMargin = ScreenScale.px(reactContext, SLOT_LABEL_MARGIN_PX)
                bottomMargin = ScreenScale.px(reactContext, SLOT_LABEL_MARGIN_PX)
            }
        })

        return cell
    }

    private fun makeSlotLockCell(): View {
        val preset = curPreset()
        val circleSize = ScreenScale.px(reactContext, SLOT_CIRCLE_PX)
        val iconSize = ScreenScale.px(reactContext, SLOT_ICON_PX)
        val locked = preset?.locked == true
        val label = NativeLocale.t(if (locked) "palette_unlock_slot" else "palette_lock_slot")
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(circleSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            isEnabled = preset != null
            alpha = if (preset != null) 1f else 0.35f
            contentDescription = if (preset != null) label else NativeLocale.t("palette_select_slot_to_lock")
            addView(FrameLayout(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
                background = makeSlotCircleBackground(locked)
                addNativePenIcon(this,
                    if (locked) "icons/ic_palette_lock.xml" else "icons/ic_palette_unlock.xml", iconSize)
            })
            addView(TextView(reactContext).apply {
                text = label
                textSize = sp(14f)
                setTextColor(INK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(circleSize, ScreenScale.px(reactContext, SLOT_LABEL_HEIGHT_PX)).apply {
                    topMargin = ScreenScale.px(reactContext, SLOT_LABEL_MARGIN_PX)
                    bottomMargin = ScreenScale.px(reactContext, SLOT_LABEL_MARGIN_PX)
                }
            })
            setOnClickListener {
                val index = selectedSlot ?: return@setOnClickListener
                val current = presets[index] ?: return@setOnClickListener
                presets[index] = current.copy(locked = !current.locked)
                savePresets()
                rebuildGrid()
            }
        }
    }

    private fun makeSlotCircleBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            if (selected) {
                setStroke(ScreenScale.px(reactContext, SLOT_SELECTED_BORDER_PX).coerceAtLeast(1), INK)
            } else {
                val dash = ScreenScale.px(reactContext, SLOT_DASH_PX).coerceAtLeast(1).toFloat()
                setStroke(
                    ScreenScale.px(reactContext, SLOT_BORDER_PX).coerceAtLeast(1),
                    INK,
                    dash,
                    dash
                )
            }
        }
    }

    private fun addNativePenIcon(host: FrameLayout, asset: String, iconSize: Int) {
        host.addView(ImageView(reactContext).apply {
            setImageBitmap(VectorAssets.loadBitmap(reactContext, asset, iconSize))
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        })
    }

    private fun buildLowerSplitSection(): LinearLayout {
        val lower = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(34), 0, dp(26))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val editorCol = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        penTypeLabel = TextView(reactContext).apply {
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        updatePenTypeLabel()
        editorCol.addView(penTypeLabel)
        buildPenTypeGrid(editorCol)

        val colorSection = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(30) }
        }
        colorContainer = colorSection
        if (!mosaicSession) {
            buildColorSection(colorSection)
            editorCol.addView(colorSection)
        }

        val thicknessControls = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(30) }
        }
        thicknessSection = thicknessControls
        thicknessHeaderLabel = TextView(reactContext).apply {
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        thicknessControls.addView(thicknessHeaderLabel)
        buildThicknessInline(thicknessControls)
        editorCol.addView(thicknessControls)
        lower.addView(editorCol)

        return lower
    }

    private fun buildPenTypeGrid(parent: LinearLayout) {
        penBtns.clear()
        visiblePens().chunked(if (mosaicSession) 3 else PENS.size).forEachIndexed { index, pens ->
            parent.addView(makePenRow(pens), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (index == 0) 0 else dp(12) })
        }
    }

    private fun makePenRow(pens: List<PenDef>): LinearLayout {
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for ((i, pen) in pens.withIndex()) {
                if (i > 0) addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ScreenScale.px(reactContext, EDITOR_GAP_PX), 0
                    )
                })
                val tile = makePenTile(pen)
                penBtns[pen.id] = tile
                addView(tile)
            }
        }
    }

    private fun makePenTile(pen: PenDef): FrameLayout {
        val isActive = selectedPenId == pen.id
        return FrameLayout(reactContext).apply {
            val circleSize = ScreenScale.px(reactContext, EDITOR_CIRCLE_PX)
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            background = makeSlotCircleBackground(isActive)
            setOnClickListener {
                if (selectedPenId == pen.id) return@setOnClickListener
                val prevPen = effectivePenType()
                selectedPenId = pen.id
                if (mosaicSession) selectedColor = MosaicPaletteSpec.color(pen.id)
                activeTierKey = null
                val newPen = effectivePenType()
                val converted = PenSizeSpec.convert(prevPen, newPen, thickness)
                if (converted != thickness) {
                    thickness = converted
                    thicknessChanged = true
                }
                syncThicknessDisplay()
                updatePenSelection()
                updateColorSelection()
                updateTierSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }
            renderPenTileIcon(this, pen)
        }
    }

    private fun renderPenTileIcon(host: FrameLayout, pen: PenDef) {
        host.removeAllViews()
        addNativePenIcon(
            host,
            if (mosaicSession) MosaicPaletteSpec.iconAsset(pen.id) else PenSizeSpec.penIconAsset(pen.penType),
            ScreenScale.px(reactContext, EDITOR_ICON_PX),
        )
    }

    private fun updatePenTypeLabel() {
        penTypeLabel?.text = currentPenDef()?.label ?: NativeLocale.t("palette_pen_type")
    }

    private fun updatePenSelection() {
        for ((id, btn) in penBtns) {
            val selected = selectedPenId == id
            val frame = btn as? FrameLayout ?: continue
            frame.background = makeSlotCircleBackground(selected)
            renderPenTileIcon(frame, penDefById(id))
        }
        updatePenTypeLabel()
    }

    private var colorHeaderLabel: TextView? = null

    private fun buildColorSection(parent: LinearLayout) {
        colorBtns.clear()

        colorHeaderLabel = TextView(reactContext).apply {
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        updateColorHeaderLabel()
        parent.addView(colorHeaderLabel)

        buildColorGrid(parent, PRODUCT_COLORS, 4)
    }

    private fun buildColorGrid(parent: LinearLayout, colors: List<PenColorDef>, cols: Int) {
        val gap = ScreenScale.px(reactContext, EDITOR_GAP_PX)
        for (i in colors.indices step cols) {
            val row = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                if (i > 0) layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gap }
            }
            for (c in 0 until cols) {
                if (c > 0) row.addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(gap, 0)
                })
                if (i + c < colors.size) {
                    row.addView(makeColorDot(colors[i + c]))
                } else {
                    row.addView(View(reactContext).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                    })
                }
            }
            parent.addView(row)
        }
    }

    private fun updateColorHeaderLabel() {
        val colorDef = selectedColor?.let { colorDefByKey(it) }
        colorHeaderLabel?.text = colorDef?.label ?: NativeLocale.t("palette_color")
    }

    private fun makeColorDot(def: PenColorDef): FrameLayout {
        val cellSize = ScreenScale.px(reactContext, EDITOR_CIRCLE_PX)
        val dotSz = ScreenScale.px(reactContext, COLOR_SWATCH_PX)
        val cell = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            setOnClickListener {
                selectedColor = def.key
                updateColorSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }
            addView(View(reactContext).apply {
                layoutParams = FrameLayout.LayoutParams(dotSz, dotSz, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(def.swatch)
                    setStroke(
                        dp(1.5f),
                        when (def.key) {
                            "black" -> Color.BLACK
                            "darkGray" -> Color.parseColor("#9D9D9D")
                            else -> Color.parseColor("#C9C9C9")
                        }
                    )
                }
            })
        }
        colorBtns[def.key] = cell
        return cell
    }

    private fun updateColorSelection() {
        for ((key, btn) in colorBtns) {
            btn.alpha = 1f
            btn.background = makeColorCircleBackground(selectedColor == key)
        }
        updateColorHeaderLabel()
    }

    private fun makeColorCircleBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            if (selected) {
                setStroke(
                    ScreenScale.px(reactContext, COLOR_SELECTED_BORDER_PX).coerceAtLeast(1),
                    INK,
                )
            } else {
                setStroke(
                    ScreenScale.px(reactContext, COLOR_BORDER_PX).coerceAtLeast(1),
                    INK,
                    ScreenScale.px(reactContext, COLOR_DASH_PX).coerceAtLeast(1).toFloat(),
                    ScreenScale.px(reactContext, COLOR_DASH_GAP_PX).coerceAtLeast(1).toFloat(),
                )
            }
        }
    }

    private fun buildThicknessInline(parent: LinearLayout) {

        val tierRow = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((i, tier) in tierPresets.withIndex()) {
            if (i > 0) tierRow.addView(View(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ScreenScale.px(reactContext, EDITOR_GAP_PX), 0
                )
            })
            val btn = makeTierBtn(tier)
            tierBtns[tier.key] = btn
            tierRow.addView(btn)
        }
        parent.addView(tierRow)
        tierRowView = tierRow

        val stepper = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(30) }
        }
        stepper.addView(makeStepperBtn("−") { stepThickness(-1) })
        thicknessLabel = TextView(reactContext).apply {
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
        }
        stepper.addView(thicknessLabel)
        stepper.addView(makeStepperBtn("+") { stepThickness(1) })
        parent.addView(stepper)
        stepperView = stepper

        val grid = buildPencilGrid()
        parent.addView(grid)
        pencilGrid = grid

        syncThicknessDisplay()
    }

    private fun buildPencilGrid(): LinearLayout {
        val grid = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        PenSizeSpec.PENCIL_LABELS.indices.chunked(4).forEachIndexed { rowIndex, row ->
            val line = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                if (rowIndex > 0) layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            }
            row.forEachIndexed { i, grade ->
                if (i > 0) line.addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(ScreenScale.px(reactContext, EDITOR_GAP_PX), 0)
                })
                line.addView(makePencilCell(grade))
            }
            grid.addView(line)
        }
        return grid
    }

    private fun makePencilCell(grade: Int): LinearLayout {
        val circleSize = ScreenScale.px(reactContext, EDITOR_CIRCLE_PX)
        val circle = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            background = makeSlotCircleBackground(false)
            val dotSize = ScreenScale.px(reactContext, 18f + 30f * grade / PenSizeSpec.PENCIL_LABELS.lastIndex)
            addView(View(reactContext).apply {
                layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(INK)
                }
            })
        }
        pencilCircles.add(circle)
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(circleSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                thickness = PenSizeSpec.stepsFor(PenSizeSpec.PENCIL)[grade]
                thicknessChanged = true
                activeTierKey = null
                syncThicknessDisplay()
                updateTierSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }
            addView(circle)
            addView(TextView(reactContext).apply {
                text = PenSizeSpec.PENCIL_LABELS[grade]
                textSize = sp(15f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(INK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    circleSize, ScreenScale.px(reactContext, SLOT_LABEL_HEIGHT_PX)
                ).apply { topMargin = ScreenScale.px(reactContext, SLOT_LABEL_MARGIN_PX) }
            })
        }
    }

    private fun makeTierBtn(tier: TierPreset): LinearLayout {
        val isActive = activeTierKey == tier.key
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val circleSize = ScreenScale.px(reactContext, EDITOR_CIRCLE_PX)
            layoutParams = LinearLayout.LayoutParams(
                circleSize, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (effectivePenType() == PenSizeSpec.MARKER) return@setOnClickListener
                if (activeTierKey == tier.key) {
                    activeTierKey = null
                } else {
                    activeTierKey = tier.key
                    thickness = PenSizeSpec.rawForLabel(effectivePenType(), tier.labelValue)
                    thicknessChanged = true
                }
                syncThicknessDisplay()
                updateTierSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }

            addView(FrameLayout(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
                background = makeSlotCircleBackground(isActive)
                val dotSize = tierDotSize(effectiveTierValue(tier))
                addView(View(reactContext).apply {
                    layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(INK)
                    }
                })
            })

            addView(TextView(reactContext).apply {
                text = PenSizeSpec.formatLabel(effectiveTierValue(tier))
                textSize = sp(15f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(INK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    circleSize, ScreenScale.px(reactContext, SLOT_LABEL_HEIGHT_PX)
                ).apply { topMargin = ScreenScale.px(reactContext, SLOT_LABEL_MARGIN_PX) }
            })
        }
    }

    private fun effectiveTierValue(tier: TierPreset): Float {
        val labels = PenSizeSpec.labelsFor(effectivePenType())
        if (labels.isEmpty()) return tier.labelValue
        val idx = labels.indices.minByOrNull { Math.abs(labels[it] - tier.labelValue) } ?: 0
        return labels[idx]
    }

    private fun tierDotSize(value: Float): Int {
        val progress = ((value - 0.1f) / 0.9f).coerceIn(0f, 1f)
        return ScreenScale.px(reactContext, 18f + 30f * progress)
    }

    private fun makeStepperBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK2)
            gravity = Gravity.CENTER
            val sz = dp(38)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1.5f), LINE2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateTierSelection() {
        val isMarker = effectivePenType() == PenSizeSpec.MARKER
        thicknessSection?.visibility = if (isMarker) View.GONE else View.VISIBLE
        val isPencil = effectivePenType() == PenSizeSpec.PENCIL
        tierRowView?.visibility = if (isPencil) View.GONE else View.VISIBLE
        stepperView?.visibility = if (isPencil) View.GONE else View.VISIBLE
        pencilGrid?.visibility = if (isPencil) View.VISIBLE else View.GONE
        if (isPencil) {
            val current = PenSizeSpec.stepIndex(PenSizeSpec.PENCIL, thickness)
            pencilCircles.forEachIndexed { i, circle -> circle.background = makeSlotCircleBackground(i == current) }
        }
        for ((key, btn) in tierBtns) {
            val isActive = activeTierKey == key
            val tier = tierPresets.find { it.key == key } ?: continue
            val cell = btn as? LinearLayout ?: continue
            val circle = cell.getChildAt(0) as? FrameLayout ?: continue
            val dot = circle.getChildAt(0) ?: continue
            val label = cell.getChildAt(1) as? TextView ?: continue
            val value = effectiveTierValue(tier)
            val dotSize = tierDotSize(value)

            circle.background = makeSlotCircleBackground(isActive)
            dot.layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
            label.text = PenSizeSpec.formatLabel(value)
        }
    }

    private fun syncThicknessDisplay() {
        val isMarker = effectivePenType() == PenSizeSpec.MARKER
        val labelText = if (isMarker) "—" else PenSizeSpec.label(effectivePenType(), thickness)
        thicknessLabel?.text = labelText
        thicknessHeaderLabel?.text = NativeLocale.t("palette_thickness")
    }

    private fun stepThickness(direction: Int) {
        val penType = effectivePenType()
        if (penType == PenSizeSpec.MARKER) return

        if (activeTierKey != null) {
            val tier = tierPresets.find { it.key == activeTierKey } ?: return
            val labels = PenSizeSpec.labelsFor(penType)
            if (labels.isEmpty()) return
            val curIdx = labels.indices.minByOrNull { Math.abs(labels[it] - tier.labelValue) } ?: return
            val newIdx = (curIdx + direction).coerceIn(0, labels.lastIndex)
            tier.labelValue = labels[newIdx]
            thickness = PenSizeSpec.rawForLabel(penType, tier.labelValue)
            thicknessChanged = true
            saveTierPrefs()
            updateTierSelection()
        } else {
            val steps = PenSizeSpec.stepsFor(penType)
            val curIdx = steps.indexOfFirst { it == thickness }.takeIf { it >= 0 }
                ?: steps.indices.minByOrNull { Math.abs(steps[it] - thickness) } ?: 0
            val newIdx = (curIdx + direction).coerceIn(0, steps.lastIndex)
            thickness = steps[newIdx]
            thicknessChanged = true
        }
        syncThicknessDisplay()
        updateApplyState()
        autoSaveToActivePreset()
    }

    private fun loadSlotIntoEditor() {
        val preset = curPreset() ?: return
        selectedPenId = canonicalPenId(preset.penId)
        selectedColor = if (mosaicSession) MosaicPaletteSpec.color(selectedPenId!!) else preset.color
        thickness = preset.thickness
        thicknessChanged = true
        activeTierKey = null
    }

    private fun restoreFromLasso() {
        selectedPenType(dominantPenType)
        selectedColor = initialColor
        thickness = PenSizeSpec.snap(initialPenType?.let { normalizePenType(it) }, avgThickness)
        thicknessChanged = false
        activeTierKey = null
    }

    private fun doResetAll() {
        val count = presets.count { it != null && !it.locked }
        if (count == 0) return
        showDeleteConfirm(count) {
            for (i in presets.indices) {
                if (presets[i]?.locked != true) presets[i] = null
            }
            selectedSlot = selectedSlot?.takeIf { presets[it] != null }
            savePresets()
            refreshAll()
        }
    }

    private fun showDeleteConfirm(count: Int, onConfirm: () -> Unit) {
        showConfirmDialog(NativeLocale.t("palette_delete_confirm", count), onConfirm)
    }

    private fun showConfirmDialog(msg: String, onConfirm: () -> Unit) {
        me.laumss.notipal.ui_common.Dialog.confirmResult(reactContext, msg) { confirmed ->
            if (confirmed) onConfirm()
        }
    }

    private fun autoSaveToActivePreset() {
        val idx = selectedSlot ?: return
        val preset = curPreset() ?: return
        if (preset.locked) return
        val color = selectedColor ?: return
        val penId = selectedPenId ?: return
        presets[idx] = Preset(penId, color, thickness)
        savePresets()
        rebuildGrid()
    }

    private fun colorChanged(): Boolean {
        if (mosaicSession) return false
        val cur = selectedColor ?: return false
        return cur != initialColor
    }

    private fun penTypeChanged(): Boolean {
        if (mosaicSession) return selectedPenId != initialPenId
        val curType = effectivePenType() ?: return false
        return curType != initialPenType
    }

    private fun updateApplyState() {
        val hasSelection = if (mosaicSession) (mosaicSelection.optJSONArray("strokeIds")?.length() ?: 0) > 0
            else elementNums.isNotEmpty()
        val canApply = hasSelection && (colorChanged() || thicknessChanged || penTypeChanged())
        applyBtn?.alpha = if (canApply) 1f else 0.4f
        applyBtn?.isEnabled = canApply
    }

    private fun refreshAll() {
        rebuildGrid()
        updatePenSelection()
        updateColorSelection()
        updateTierSelection()
        syncThicknessDisplay()
        updateApplyState()
    }

    private fun doApply() {
        if (mosaicSession) {
            val penId = selectedPenId ?: return
            applyBtn?.isEnabled = false
            val request = JSONObject(mosaicSelection.toString()).put("penId", penId).put("thickness", thickness)
                .put("changePen", penTypeChanged())
            MosaicLink.requestPalette(reactContext, request) { result ->
                if (result.optBoolean("success")) closeAndRestore()
                else {
                    updateApplyState()
                    Dialog.tip(reactContext, NativeLocale.t("palette_mosaic_retry"))
                }
            }
            return
        }
        val colorDef = selectedColor?.let { colorDefByKey(it) }
        val colorValue = if (colorChanged() && colorDef != null) colorDef.value else null

        val map = Arguments.createMap().apply {
            if (colorValue != null) putInt("penColor", colorValue)
            if (thicknessChanged) putInt("thickness", thickness)
            if (penTypeChanged()) putInt("penType", effectivePenType()!!)
            putString("elementNums", JSONArray(elementNums).toString())
        }
        toolbarModule.beginPaletteApply()

        applyBtn?.isEnabled = false

        suspendVisibility()
        toolbarModule.emitEvent("paletteApply", map)
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun loadPresets() {
        try {
            val prefs = reactContext.getSharedPreferences(NotipalPrefs.PRESETS_FILE, 0)
            val key = MosaicPaletteSpec.presetsKey(mosaicSession)
            val json = prefs.getString(key, null) ?: return
            val arr = JSONArray(json)
            for (i in 0 until MAX_SLOTS) {
                if (i >= arr.length() || arr.isNull(i)) {
                    presets[i] = null
                    continue
                }
                val o = arr.getJSONObject(i)
                val penId = canonicalPenId(o.getString("penId"))
                val pen = penDefById(penId)
                presets[i] = Preset(penId, o.getString("color"),
                    PenSizeSpec.snap(pen.penType, o.getInt("thickness")), o.optBoolean("locked", false))
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "loadPresets: ${e.message}")
        }
    }

    private fun savePresets() {
        try {
            val arr = JSONArray()
            for (i in 0 until MAX_SLOTS) {
                val p = presets[i]
                if (p == null) { arr.put(JSONObject.NULL); continue }
                arr.put(JSONObject().apply {
                    put("penId", p.penId)
                    put("color", p.color)
                    put("thickness", p.thickness)
                    put("locked", p.locked)
                    put("penType", penDefById(p.penId).penType)
                })
            }
            val prefs = reactContext.getSharedPreferences(NotipalPrefs.PRESETS_FILE, 0)
            prefs.edit().putString(MosaicPaletteSpec.presetsKey(mosaicSession), arr.toString()).apply()
            PaletteBubbleModule.reloadPresets()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "savePresets: ${e.message}")
        }
    }

    private fun loadTierPrefs() {
        try {
            val prefs = reactContext.getSharedPreferences(NotipalPrefs.PRESETS_FILE, 0)
            val defaults = floatArrayOf(0.3f, 0.5f, 0.7f, 1.0f)
            tierPresets.forEachIndexed { index, tier -> tier.labelValue = defaults[index] }
            val json = prefs.getString(MosaicPaletteSpec.tiersKey(mosaicSession), null) ?: return
            val o = JSONObject(json)
            for (tier in tierPresets) {
                if (o.has(tier.key)) {
                    tier.labelValue = o.getDouble(tier.key).toFloat()
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveTierPrefs() {
        try {
            val o = JSONObject()
            for (tier in tierPresets) {
                o.put(tier.key, tier.labelValue.toDouble())
            }
            val prefs = reactContext.getSharedPreferences(NotipalPrefs.PRESETS_FILE, 0)
            prefs.edit().putString(MosaicPaletteSpec.tiersKey(mosaicSession), o.toString()).apply()
        } catch (_: Exception) {}
    }
}
