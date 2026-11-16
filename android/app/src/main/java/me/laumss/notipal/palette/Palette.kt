package me.laumss.notipal.palette

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.common.App
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.common.StateMachine
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.Surface
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.mosaic.MosaicPaletteSpec
import me.laumss.notipal.mosaic.PenSizeSpec
import me.laumss.notipal.toolbar.ToolbarHooks
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.Panel
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.ScrollHost
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln

object PaletteHooks {

    @Volatile var closeRelaySmall: () -> Unit = {}
}

internal sealed class PenBook {

    abstract val presetsKey: String
    abstract val tiersKey: String

    abstract val penIds: List<String>
    abstract fun normalize(id: String): String
    abstract fun penLabel(id: String): String

    abstract fun icon(id: String, color: String): String?

    abstract fun hasColor(id: String): Boolean
    abstract fun hasThickness(id: String): Boolean
    abstract fun steps(id: String): IntArray

    abstract fun labels(id: String): FloatArray

    abstract fun grades(id: String): List<String>

    abstract fun slotLabel(id: String, raw: Int): String
    abstract fun snap(id: String, raw: Int): Int
    abstract fun rawForLabel(id: String, value: Float): Int
    abstract fun convert(from: String, to: String, raw: Int): Int

    abstract fun defaultWidth(id: String): Int

    abstract fun tierDefaults(id: String): IntArray

    abstract fun tierGroup(id: String): String

    open fun defaultSlots(): Array<PaletteSlot?> = arrayOfNulls(PaletteSlots.MAX_SLOTS)

    fun stepIndex(id: String, raw: Int): Int {
        val steps = steps(id)
        var best = 0
        for (i in steps.indices) if (abs(steps[i] - raw) < abs(steps[best] - raw)) best = i
        return best
    }

    object Mosaic : PenBook() {
        override val presetsKey: String get() = MosaicPaletteSpec.PRESETS_KEY
        override val tiersKey: String get() = MosaicPaletteSpec.TIERS_KEY
        override val penIds: List<String> get() = MosaicPaletteSpec.penIds
        private fun type(id: String): Int = MosaicPaletteSpec.penType(normalize(id))
        override fun normalize(id: String): String = MosaicPaletteSpec.normalize(id)
        override fun penLabel(id: String): String = Strings.get(
            when (normalize(id)) {
                "ball" -> "pen_calligraphy"
                "needle" -> "pen_needle"
                "pencil" -> "pen_pencil"
                "mk-lightGray" -> "palette_marker_light_gray"
                "mk-darkGray" -> "palette_marker_dark_gray"
                else -> "pen_marker"
            }
        )
        override fun icon(id: String, color: String): String = MosaicPaletteSpec.iconAsset(normalize(id))
        override fun hasColor(id: String): Boolean = false
        override fun hasThickness(id: String): Boolean = true
        override fun steps(id: String): IntArray = PenSizeSpec.stepsFor(type(id))
        override fun labels(id: String): FloatArray =
            if (type(id) == PenSizeSpec.PENCIL) FloatArray(0) else PenSizeSpec.labelsFor(type(id))
        override fun grades(id: String): List<String> =
            if (type(id) == PenSizeSpec.PENCIL) PenSizeSpec.PENCIL_LABELS.toList() else emptyList()
        override fun slotLabel(id: String, raw: Int): String = PenSizeSpec.label(type(id), raw)
        override fun snap(id: String, raw: Int): Int = PenSizeSpec.snap(type(id), raw)
        override fun rawForLabel(id: String, value: Float): Int = PenSizeSpec.rawForLabel(type(id), value)
        override fun convert(from: String, to: String, raw: Int): Int = PenSizeSpec.convert(type(from), type(to), raw)
        override fun defaultWidth(id: String): Int = snap(id, 100)
        override fun tierDefaults(id: String): IntArray {
            val t = type(id)
            val steps = PenSizeSpec.stepsFor(t)
            return when (t) {
                PenSizeSpec.PENCIL -> intArrayOf(0, 1, 3, 7).map { steps[it.coerceAtMost(steps.lastIndex)] }.toIntArray()
                PenSizeSpec.MARKER -> floatArrayOf(3.0f, 4.0f, 5.0f, 6.0f).map { rawForLabel(id, it) }.toIntArray()

                else -> floatArrayOf(0.4f, 0.7f, 1.5f, 3.0f).map { rawForLabel(id, it) }.toIntArray()
            }
        }
        override fun tierGroup(id: String): String = "t${type(id)}"
    }

    object Host : PenBook() {

        override fun defaultSlots(): Array<PaletteSlot?> = arrayOf(
            PaletteSlot(HostPenSpec.NEEDLE, HostPenSpec.rawForLabel(HostPenSpec.NEEDLE, 0.5f)),
            PaletteSlot(HostPenSpec.INK, HostPenSpec.rawForLabel(HostPenSpec.INK, 0.7f)),
            PaletteSlot(HostPenSpec.CALLIGRAPHY, HostPenSpec.rawForLabel(HostPenSpec.CALLIGRAPHY, 1.0f)),
            PaletteSlot(HostPenSpec.MARKER, HostPenSpec.defaultWidth(HostPenSpec.MARKER), color = "lightGray"),
            PaletteSlot(HostPenSpec.ERASER, HostPenSpec.defaultWidth(HostPenSpec.ERASER)),
            PaletteSlot(HostPenSpec.LASSO, HostPenSpec.defaultWidth(HostPenSpec.LASSO)),
            null, null, null,
        )
        override val presetsKey: String get() = "host_palette_presets"
        override val tiersKey: String get() = "host_palette_tier_prefs"
        override val penIds: List<String> get() = HostPenSpec.penIds
        override fun normalize(id: String): String = HostPenSpec.normalize(id)
        override fun penLabel(id: String): String = HostPenSpec.label(normalize(id))
        override fun icon(id: String, color: String): String? = HostPenSpec.iconAsset(normalize(id), color)
        override fun hasColor(id: String): Boolean = HostPenSpec.hasColor(normalize(id))
        override fun hasThickness(id: String): Boolean = HostPenSpec.hasThickness(normalize(id))
        override fun steps(id: String): IntArray = HostPenSpec.steps(normalize(id))
        override fun labels(id: String): FloatArray = HostPenSpec.labels(normalize(id))
        override fun grades(id: String): List<String> = HostPenSpec.gradeLabels(normalize(id))
        override fun slotLabel(id: String, raw: Int): String = HostPenSpec.label(normalize(id), raw)
        override fun snap(id: String, raw: Int): Int = HostPenSpec.snap(normalize(id), raw)
        override fun rawForLabel(id: String, value: Float): Int = HostPenSpec.rawForLabel(normalize(id), value)
        override fun convert(from: String, to: String, raw: Int): Int =
            HostPenSpec.convert(normalize(from), normalize(to), raw)
        override fun defaultWidth(id: String): Int = HostPenSpec.defaultWidth(normalize(id))
        override fun tierDefaults(id: String): IntArray = HostPenSpec.tierDefaults(normalize(id))
        override fun tierGroup(id: String): String = normalize(id)
    }

    companion object {

        fun current(): PenBook? = when {
            MosaicLink.boardVisible -> Mosaic
            HostSession.state.surface == Surface.NOTE || HostSession.state.surface == Surface.DOCUMENT -> Host
            else -> null
        }
    }
}

internal data class PaletteSlot(val penId: String, val thickness: Int, val locked: Boolean = false, val color: String = "black")

internal object PaletteSlots {
    private const val TAG = "PaletteSlots"
    const val MAX_SLOTS = PalettePanel.MAX_SLOTS

    fun load(book: PenBook): Array<PaletteSlot?> {
        val out = arrayOfNulls<PaletteSlot>(MAX_SLOTS)
        try {
            val json = Prefs.of(PalettePanel.PREFS).getString(book.presetsKey, null) ?: return book.defaultSlots()
            val arr = JSONArray(json)
            for (i in 0 until minOf(MAX_SLOTS, arr.length())) {
                if (arr.isNull(i)) continue
                val o = arr.getJSONObject(i)
                val penId = book.normalize(o.getString("penId"))
                out[i] = PaletteSlot(
                    penId,
                    book.snap(penId, o.getInt("thickness")),
                    o.optBoolean("locked", false),
                    o.optString("color", "black").ifEmpty { "black" },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load ${book.presetsKey}: ${e.message}")
        }
        return out
    }

    fun save(book: PenBook, slots: Array<PaletteSlot?>) {
        try {
            val arr = JSONArray()
            for (i in 0 until MAX_SLOTS) {
                val p = slots.getOrNull(i)
                if (p == null) {
                    arr.put(JSONObject.NULL)
                    continue
                }
                val o = JSONObject().put("penId", p.penId).put("thickness", p.thickness).put("locked", p.locked)
                if (book.hasColor(p.penId)) o.put("color", p.color)
                arr.put(o)
            }
            Prefs.of(PalettePanel.PREFS).edit().putString(book.presetsKey, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "save ${book.presetsKey}: ${e.message}")
        }
    }
}

internal object PaletteDots {
    fun size(steps: IntArray, raw: Int, min: Float, max: Float): Float {
        if (steps.isEmpty()) return min
        val lo = steps.first().coerceAtLeast(1).toDouble()
        val hi = steps.last().coerceAtLeast(1).toDouble()
        val t = if (hi > lo) (ln(raw.coerceAtLeast(1) / lo) / ln(hi / lo)).coerceIn(0.0, 1.0) else 0.0
        return (min + (max - min) * t).toFloat()
    }
}

internal object PaletteTiers {
    private const val TAG = "PaletteTiers"

    fun load(book: PenBook, penId: String): IntArray {
        val defaults = book.tierDefaults(penId)
        try {
            val json = Prefs.of(PalettePanel.PREFS).getString(book.tiersKey, null) ?: return defaults
            val arr = JSONObject(json).optJSONArray(book.tierGroup(penId)) ?: return defaults
            if (arr.length() != defaults.size) return defaults
            return IntArray(defaults.size) { book.snap(penId, arr.getInt(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "load ${book.tiersKey}: ${e.message}")
            return defaults
        }
    }

    fun save(book: PenBook, penId: String, tiers: IntArray) {
        try {
            val prefs = Prefs.of(PalettePanel.PREFS)
            val o = prefs.getString(book.tiersKey, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            o.put(book.tierGroup(penId), JSONArray(tiers.toList()))
            prefs.edit().putString(book.tiersKey, o.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "save ${book.tiersKey}: ${e.message}")
        }
    }
}

sealed class PaletteState {
    object Idle : PaletteState() {
        override fun toString() = "Idle"
    }

    data class Reading(val request: Long, val forcePanel: Boolean) : PaletteState()

    object PanelOpen : PaletteState() {
        override fun toString() = "PanelOpen"
    }

    object Applying : PaletteState() {
        override fun toString() = "Applying"
    }
}

sealed class PaletteEvent {
    data class Read(val request: Long, val forcePanel: Boolean) : PaletteEvent()
    data class ReadDone(val request: Long) : PaletteEvent()
    object PanelShown : PaletteEvent() {
        override fun toString() = "PanelShown"
    }
    object PanelClosed : PaletteEvent() {
        override fun toString() = "PanelClosed"
    }
    object ApplySent : PaletteEvent() {
        override fun toString() = "ApplySent"
    }
    object ApplyFailed : PaletteEvent() {
        override fun toString() = "ApplyFailed"
    }
}

object Palette : StateMachine<PaletteState, PaletteEvent>(PaletteState.Idle) {
    override val tag: String get() = TAG
    private const val TAG = "Palette"

    private var entryRequest = 0L
    private var panel: PalettePanel? = null

    private var sessionClosedWrapper: (() -> Unit)? = null

    fun install() {
        ToolbarHooks.stowPaletteBubble = {
            PaletteBubble.stowForTwoFinger()
            PenBar.foldForTwoFinger()
        }
        ToolbarHooks.unstowPaletteBubble = { PaletteBubble.unstowAfterTwoFinger() }
        ToolbarHooks.foldPenBar = { PenBar.foldForCanvasMotion() }
        HostPen.install()
        if (ToolbarHooks.sessionClosed !== sessionClosedWrapper) {
            val previous = ToolbarHooks.sessionClosed
            val wrapper: () -> Unit = {
                previous()

                Main.post { HostPen.releaseAll("toolbar closed") }
            }
            sessionClosedWrapper = wrapper
            ToolbarHooks.sessionClosed = wrapper
        }
    }

    override fun reduce(state: PaletteState, event: PaletteEvent): PaletteState = when (event) {

        is PaletteEvent.Read -> when (state) {
            PaletteState.Idle, is PaletteState.Reading -> PaletteState.Reading(event.request, event.forcePanel)
            else -> state
        }
        is PaletteEvent.ReadDone ->
            if (state is PaletteState.Reading && state.request == event.request) PaletteState.Idle else state
        PaletteEvent.PanelShown -> PaletteState.PanelOpen
        PaletteEvent.PanelClosed -> PaletteState.Idle
        PaletteEvent.ApplySent -> if (state == PaletteState.PanelOpen) PaletteState.Applying else state
        PaletteEvent.ApplyFailed -> if (state == PaletteState.Applying) PaletteState.PanelOpen else state
    }

    fun onPaletteTap() = paletteEntry(forcePanel = false)

    fun onPaletteLongPress() = paletteEntry(forcePanel = true)

    fun openPanel() = paletteEntry(forcePanel = true)

    private fun paletteEntry(forcePanel: Boolean) {
        Main.post {

            if (!forcePanel && !MosaicLink.boardVisible) {
                Log.i(TAG, "palette tap without the Mosaic board; ignored")
                return@post
            }
            val request = ++entryRequest
            send(PaletteEvent.Read(request, forcePanel))
            MosaicLink.requestPalette { result -> onInfo(request, forcePanel, result) }
        }
    }

    fun onSlotsTap() = slotsEntry(forcePanel = false)

    fun onSlotsLongPress() = slotsEntry(forcePanel = true)

    private fun slotsEntry(forcePanel: Boolean) {
        Main.post {
            when (PenBook.current()) {
                PenBook.Mosaic -> {
                    if (!forcePanel) {
                        PenBar.toggle()
                        return@post
                    }
                    val p = panel ?: PalettePanel().also { panel = it }
                    p.prepareMosaicSlots()
                    openPanel(p)
                }
                PenBook.Host -> hostEntry(forcePanel)
                null -> Log.i(TAG, "pen slots outside note, document and Mosaic; ignored")
            }
        }
    }

    private fun hostEntry(forcePanel: Boolean) {
        if (!forcePanel) {
            PenBar.toggle()
            return
        }
        val p = panel ?: PalettePanel().also { panel = it }
        p.prepareHost()
        openPanel(p)
    }

    private fun onInfo(request: Long, forcePanel: Boolean, result: JSONObject) {
        val current = state
        if (current !is PaletteState.Reading || current.request != request) {
            Log.i(TAG, "palette info for request $request dropped (state=$current)")
            return
        }
        send(PaletteEvent.ReadDone(request))
        if (!forcePanel && !MosaicLink.boardVisible) return
        when {
            !result.optBoolean("success") -> {
                Log.i(TAG, "palette info failed: ${result.optString("error")}")
                Dialogs.tip(Strings.get("palette_mosaic_retry"))
            }
            forcePanel || (result.optJSONArray("strokeIds")?.length() ?: 0) > 0 -> {
                val p = panel ?: PalettePanel().also { panel = it }
                p.prepareMosaic(result)
                openPanel(p)
            }
            else -> PaletteBubble.toggle()
        }
    }

    private fun openPanel(p: PalettePanel) {
        send(PaletteEvent.PanelShown)
        Panels.open(p) { shown ->
            if (!shown) {
                Log.w(TAG, "palette panel could not be shown")
                send(PaletteEvent.PanelClosed)
            }
        }
    }

    internal fun onPanelHidden() = send(PaletteEvent.PanelClosed)

    internal fun apply(request: JSONObject) {
        if (state != PaletteState.PanelOpen) {
            Log.w(TAG, "apply ignored in $state")
            return
        }
        send(PaletteEvent.ApplySent)
        MosaicLink.requestPalette(request) { result ->
            val p = panel
            if (result.optBoolean("success")) {
                Log.i(TAG, "palette applied changed=${result.optInt("changed")}")
                if (p?.showing == true) p.hide() else send(PaletteEvent.PanelClosed)
            } else {
                Log.i(TAG, "palette apply failed: ${result.optString("error")}")
                send(PaletteEvent.ApplyFailed)
                if (p?.showing == true) p.updateApplyState()
                Dialogs.tip(Strings.get("palette_mosaic_retry"))
            }
        }
    }

    internal fun applyHost(penId: String, thickness: Int, color: String) {
        if (state != PaletteState.PanelOpen) {
            Log.w(TAG, "host apply ignored in $state")
            return
        }
        send(PaletteEvent.ApplySent)
        HostPen.apply(penId, thickness, color) { result ->
            val p = panel
            if (result == HostPen.Result.OK) {
                if (p?.showing == true) p.hide() else send(PaletteEvent.PanelClosed)
            } else {
                send(PaletteEvent.ApplyFailed)
                if (p?.showing == true) p.updateApplyState()
                hostResultTip(result)
            }
        }
    }

    internal fun hostResultTip(result: HostPen.Result) {
        when (result) {
            HostPen.Result.DOC_TEXT_MODE -> Dialogs.tip(Strings.get("palette_doc_pick_pen"))
            HostPen.Result.FAILED -> Dialogs.tip(Strings.get("palette_host_failed"))
            HostPen.Result.NO_HOST_TOOL -> Dialogs.tip(Strings.get("palette_host_unknown"))
            HostPen.Result.OK, HostPen.Result.MOSAIC -> Unit
        }
    }
}

class PalettePanel internal constructor() : Panel("palette") {
    override val tag: String get() = "PalettePanel"
    override val heightRatio: Double get() = 0.90
    override val yieldsLexi: Boolean get() = true

    companion object {

        const val PREFS = "palette"

        const val COLS = 5
        const val ROWS = 2
        const val MAX_SLOTS = COLS * ROWS - 1
        const val TIER_COUNT = 4

        private const val SLOT_CIRCLE_PX = 96
        private const val SLOT_ICON_PX = 54
        private const val SLOT_LABEL_HEIGHT_PX = 46
        private const val SLOT_LABEL_MARGIN_PX = 9
        private const val SLOT_COLUMN_GAP_PX = 106
        private const val SLOT_ROW_GAP_PX = 28
        private const val SLOT_BORDER_PX = 2
        private const val SLOT_SELECTED_BORDER_PX = 7
        private const val SLOT_DASH_PX = 2

        private const val EDITOR_CIRCLE_PX = 88
        private const val EDITOR_ICON_PX = 48
        private const val EDITOR_GAP_PX = 24
        private const val COLOR_DOT_PX = 52

        fun normalizePenType(raw: Int): Int? = when (raw) {
            PenSizeSpec.NEEDLE, PenSizeSpec.BALL, PenSizeSpec.CALLIGRAPHY, PenSizeSpec.MARKER, PenSizeSpec.PENCIL -> raw
            else -> null
        }
    }

    private val ctx get() = App.context

    private var book: PenBook = PenBook.Mosaic
    private val isHost: Boolean get() = book == PenBook.Host

    private var selection = JSONObject()
    private var avgThickness = 100
    private var dominantPenType: Int? = null
    private var initialPenId: String? = null
    private var initialPenType: Int? = null

    private val presets = arrayOfNulls<PaletteSlot>(MAX_SLOTS)
    private var selectedSlot: Int? = null
    private var selectedPenId: String? = null
    private var selectedColor = "black"
    private var thickness = 100
    private var thicknessChanged = false

    private var tiers = IntArray(0)
    private var activeTier: Int? = null

    private var thicknessLabel: TextView? = null
    private var gridContainer: LinearLayout? = null
    private var penTypeLabel: TextView? = null
    private var thicknessHeaderLabel: TextView? = null
    private var thicknessSection: LinearLayout? = null
    private var colorSection: View? = null
    private var applyBtn: TextView? = null
    private val penBtns = LinkedHashMap<String, FrameLayout>()
    private val colorBtns = LinkedHashMap<String, FrameLayout>()
    private val tierBtns = ArrayList<LinearLayout>()
    private var tierRowView: View? = null
    private var stepperView: View? = null
    private var gradeGrid: LinearLayout? = null
    private var gradePen: String? = null
    private val gradeCircles = ArrayList<View>()

    private fun px(v: Number) = Style.px(v)
    private fun dp(v: Int) = Style.dp(v)
    private fun dp(v: Float) = Style.dp(v)
    private fun sp(v: Float) = Style.sp(v)

    private fun strokeCount(): Int = selection.optJSONArray("strokeIds")?.length() ?: 0

    internal fun prepareMosaic(info: JSONObject) {
        book = PenBook.Mosaic
        parseInfo(info)
        loadPresets()
        selectedSlot = null
        selectedPenId = MosaicPaletteSpec.normalize(selection.optString("dominantPenId", "ball"))
        selectedColor = "black"
        initialPenId = selectedPenId
        initialPenType = dominantPenType?.let { normalizePenType(it) }
        Log.i(tag, "show: dominantPenType=$dominantPenType -> $selectedPenId, avgThickness=$avgThickness")
        thicknessChanged = false
        activeTier = null
        thickness = PenSizeSpec.snap(initialPenType, avgThickness)
        reloadTiers()
        if (strokeCount() > 0) pushCurrentPreset()
    }

    internal fun prepareMosaicSlots() {
        book = PenBook.Mosaic
        selection = JSONObject()
        dominantPenType = null
        loadPresets()
        selectedSlot = null
        val penId = "ball"
        selectedPenId = penId
        selectedColor = "black"
        initialPenId = penId
        initialPenType = null
        thicknessChanged = false
        activeTier = null
        thickness = book.defaultWidth(penId)
        reloadTiers()
        Log.i(tag, "show (Mosaic slots)")
    }

    internal fun prepareHost() {
        book = PenBook.Host
        selection = JSONObject()
        loadPresets()
        val now = HostPen.current()
        val penId = book.normalize(now?.penId ?: HostPenSpec.NEEDLE)
        selectedPenId = penId
        selectedColor = HostPenSpec.normalizeColor(now?.color ?: "black")
        thickness = now?.width?.let { book.snap(penId, it) } ?: book.defaultWidth(penId)
        initialPenId = penId
        initialPenType = null
        thicknessChanged = false
        activeTier = null
        selectedSlot = presets.indices.firstOrNull { i ->
            val p = presets[i]
            p != null && now != null && now.matches(p.penId, p.thickness, p.color)
        }
        reloadTiers()
        Log.i(tag, "show (host): current=$now -> $penId $selectedColor $thickness slot=$selectedSlot")
    }

    private fun parseInfo(o: JSONObject) {
        selection = o
        try {
            val runtimeDeviceType = if (o.has("deviceType") && !o.isNull("deviceType")) o.getInt("deviceType") else null
            val runtimePage = o.optJSONObject("pageSize")
            PenSizeSpec.configureRuntime(runtimeDeviceType, runtimePage?.optInt("width", 0), runtimePage?.optInt("height", 0))
            avgThickness = o.optInt("avgThickness", 100)
            dominantPenType = if (o.has("dominantPenType") && !o.isNull("dominantPenType")) o.getInt("dominantPenType") else null
        } catch (e: Exception) {
            Log.e(tag, "parseInfo: ${e.message}")
        }
    }

    override fun onHide() {
        thicknessLabel = null
        gridContainer = null
        penTypeLabel = null
        thicknessHeaderLabel = null
        thicknessSection = null
        colorSection = null
        applyBtn = null
        penBtns.clear()
        colorBtns.clear()
        tierBtns.clear()
        tierRowView = null
        stepperView = null
        gradeGrid = null
        gradePen = null
        gradeCircles.clear()
        Palette.onPanelHidden()
    }

    private fun curPreset(): PaletteSlot? = selectedSlot?.let { presets[it] }

    private fun pushCurrentPreset() {
        val penId = selectedPenId ?: return
        val incoming = PaletteSlot(penId, book.snap(penId, thickness))
        val writableSlots = presets.indices.filter { presets[it]?.locked != true }
        if (writableSlots.isEmpty()) return
        val ordered = mutableListOf(incoming)
        for (index in writableSlots) presets[index]?.let { ordered.add(it) }
        writableSlots.forEachIndexed { position, index -> presets[index] = ordered.getOrNull(position) }
        selectedSlot = writableSlots.first()
        savePresets()
    }

    override fun build(root: LinearLayout) {
        root.addView(Widgets.panelHeader(ctx, Strings.get("palette_header_title")))

        val scroll = ScrollHost(ctx, ScrollHost.Mode.FREE, overlayBar = true)
        val content = scroll.content
        content.setPadding(0, 0, 0, 0)
        content.addView(buildSlotsSection())
        content.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f))
            setBackgroundColor(Style.paletteDivider)
        })
        content.addView(buildLowerSection())
        updatePenSelection()
        updateColorSelection()
        updateTierSelection()
        syncThicknessDisplay()
        root.addView(scroll)

        val apply = Widgets.filledButton(ctx, Strings.get(if (isHost) "palette_use" else "palette_apply")) { doApply() }
        applyBtn = apply
        updateApplyState()
        root.addView(Widgets.bottomBar(
            ctx,
            leftButtons = listOf(Widgets.outlinedButton(ctx, Strings.get("palette_reset")) { doResetAll() }),
            rightButtons = listOf(Widgets.outlinedButton(ctx, Strings.get("cancel")) { hide() }, apply),
        ))
    }

    private fun buildSlotsSection(): LinearLayout {
        val section = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, dp(22))
        }
        gridContainer = LinearLayout(ctx).apply {
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
        val columnGap = px(SLOT_COLUMN_GAP_PX)
        val rowGap = px(SLOT_ROW_GAP_PX)
        for (r in 0 until ROWS) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (r > 0) topMargin = rowGap }
            }
            for (c in 0 until COLS) {
                val idx = r * COLS + c
                if (c > 0) row.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(columnGap, 0) })
                row.addView(if (idx < MAX_SLOTS) makeSlotCell(idx) else makeSlotLockCell())
            }
            container.addView(row)
        }
    }

    private fun makeSlotCell(index: Int): View {
        val preset = presets[index]
        val isActive = index == selectedSlot
        val circleSize = px(SLOT_CIRCLE_PX)
        val iconSize = px(SLOT_ICON_PX)

        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(circleSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                if (preset != null) {
                    if (selectedSlot == index) {
                        selectedSlot = null
                        restoreFromSelection()
                    } else {
                        selectedSlot = index
                        loadSlotIntoEditor()
                    }
                    refreshAll()
                } else {
                    val penId = selectedPenId ?: book.penIds.first()
                    presets[index] = PaletteSlot(penId, thickness, color = selectedColor)
                    selectedSlot = index
                    savePresets()
                    refreshAll()
                }
            }
        }

        val circle = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            background = slotCircleBackground(isActive)
        }
        if (preset == null) {
            circle.addView(TextView(ctx).apply {
                text = "+"
                textSize = sp(28f)
                typeface = Style.bold
                setTextColor(Style.ink)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        } else {
            addPenGlyph(circle, preset.penId, preset.color, iconSize)
            if (preset.locked) {
                val lockSize = px(28)
                circle.addView(ImageView(ctx).apply {
                    setImageBitmap(Style.iconBitmap("icons/ic_palette_lock.xml", lockSize))
                    setBackgroundColor(Style.paper)
                    contentDescription = Strings.get("palette_slot_locked")
                    layoutParams = FrameLayout.LayoutParams(lockSize, lockSize, Gravity.TOP or Gravity.END)
                })
            }
        }
        cell.addView(circle)

        cell.addView(TextView(ctx).apply {
            text = preset?.let { book.slotLabel(it.penId, it.thickness) }.orEmpty()
            textSize = sp(17f)
            typeface = Style.bold
            setTextColor(Style.ink)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(circleSize, px(SLOT_LABEL_HEIGHT_PX)).apply {
                topMargin = px(SLOT_LABEL_MARGIN_PX)
                bottomMargin = px(SLOT_LABEL_MARGIN_PX)
            }
        })
        return cell
    }

    private fun makeSlotLockCell(): View {
        val preset = curPreset()
        val circleSize = px(SLOT_CIRCLE_PX)
        val iconSize = px(SLOT_ICON_PX)
        val locked = preset?.locked == true
        val label = Strings.get(if (locked) "palette_unlock_slot" else "palette_lock_slot")
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(circleSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            isEnabled = preset != null
            alpha = if (preset != null) 1f else 0.35f
            contentDescription = if (preset != null) label else Strings.get("palette_select_slot_to_lock")
            addView(FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
                background = slotCircleBackground(locked)
                addIcon(this, if (locked) "icons/ic_palette_lock.xml" else "icons/ic_palette_unlock.xml", iconSize)
            })
            addView(TextView(ctx).apply {
                text = label
                textSize = sp(14f)
                setTextColor(Style.ink)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(circleSize, px(SLOT_LABEL_HEIGHT_PX)).apply {
                    topMargin = px(SLOT_LABEL_MARGIN_PX)
                    bottomMargin = px(SLOT_LABEL_MARGIN_PX)
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

    private fun slotCircleBackground(selected: Boolean) =
        if (selected) {
            Style.oval(Style.paper, Style.ink, px(SLOT_SELECTED_BORDER_PX).coerceAtLeast(1))
        } else {
            val dash = px(SLOT_DASH_PX).coerceAtLeast(1).toFloat()
            Style.dashedOval(Style.paper, Style.ink, px(SLOT_BORDER_PX).coerceAtLeast(1), dash, dash)
        }

    private fun addIcon(host: FrameLayout, asset: String, iconSize: Int) {
        host.addView(ImageView(ctx).apply {
            setImageBitmap(Style.iconBitmap(asset, iconSize))
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        })
    }

    private fun addPenGlyph(host: FrameLayout, penId: String, color: String, iconSize: Int) {
        val icon = book.icon(penId, color)
        if (icon != null) {
            addIcon(host, icon, iconSize)
            return
        }
        host.addView(TextView(ctx).apply {
            text = book.penLabel(penId)
            textSize = sp(14f)
            typeface = Style.bold
            setTextColor(Style.ink)
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
    }

    private fun boldLabel(): TextView = TextView(ctx).apply {
        textSize = sp(18f)
        typeface = Style.bold
        setTextColor(Style.ink)
        setPadding(dp(24), 0, dp(24), dp(16))
    }

    private fun buildLowerSection(): LinearLayout {
        val lower = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(34), 0, dp(26))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val editorCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val penLabel = boldLabel()
        penTypeLabel = penLabel
        updatePenTypeLabel()
        editorCol.addView(penLabel)
        buildPenTypeGrid(editorCol)
        if (isHost) buildColorRow(editorCol)

        val thicknessControls = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(30) }
        }
        thicknessSection = thicknessControls
        val header = boldLabel()
        thicknessHeaderLabel = header
        thicknessControls.addView(header)
        buildThicknessInline(thicknessControls)
        editorCol.addView(thicknessControls)
        lower.addView(editorCol)
        return lower
    }

    private fun buildPenTypeGrid(parent: LinearLayout) {
        penBtns.clear()
        book.penIds.chunked(3).forEachIndexed { index, row ->
            parent.addView(makePenRow(row), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (index == 0) 0 else dp(12) })
        }
    }

    private fun makePenRow(row: List<String>): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        for ((i, pen) in row.withIndex()) {
            if (i > 0) addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(px(EDITOR_GAP_PX), 0) })
            val tile = makePenTile(pen)
            penBtns[pen] = tile
            addView(tile)
        }
    }

    private fun makePenTile(pen: String): FrameLayout = FrameLayout(ctx).apply {
        val circleSize = px(EDITOR_CIRCLE_PX)
        layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
        background = slotCircleBackground(selectedPenId == pen)
        contentDescription = book.penLabel(pen)
        setOnClickListener {
            if (selectedPenId == pen) return@setOnClickListener
            val prevPen = selectedPenId ?: pen
            selectedPenId = pen
            activeTier = null
            reloadTiers()
            val converted = book.convert(prevPen, pen, thickness)
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

    private fun renderPenTileIcon(host: FrameLayout, pen: String) {
        host.removeAllViews()
        addPenGlyph(host, pen, selectedColor, px(EDITOR_ICON_PX))
    }

    private fun updatePenTypeLabel() {
        penTypeLabel?.text = selectedPenId?.let { book.penLabel(it) } ?: Strings.get("palette_pen_type")
    }

    private fun updatePenSelection() {
        for ((id, frame) in penBtns) {
            frame.background = slotCircleBackground(selectedPenId == id)
            renderPenTileIcon(frame, id)
        }
        updatePenTypeLabel()
    }

    private fun buildColorRow(parent: LinearLayout) {
        colorBtns.clear()
        val section = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(30) }
        }
        section.addView(boldLabel().apply { text = Strings.get("palette_color") })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((i, color) in HostPenSpec.colorIds.withIndex()) {
            if (i > 0) row.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(px(EDITOR_GAP_PX), 0) })
            val size = px(EDITOR_CIRCLE_PX)
            val tile = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                contentDescription = HostPenSpec.colorLabel(color)
                val dot = px(COLOR_DOT_PX)
                addView(View(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(dot, dot, Gravity.CENTER)
                    background = Style.oval(colorFill(color), Style.ink, px(2).coerceAtLeast(1))
                })
                setOnClickListener {
                    if (selectedColor == color) return@setOnClickListener
                    selectedColor = color
                    updateColorSelection()
                    updatePenSelection()
                    updateApplyState()
                    autoSaveToActivePreset()
                }
            }
            colorBtns[color] = tile
            row.addView(tile)
        }
        section.addView(row)
        colorSection = section
        parent.addView(section)
    }

    private fun colorFill(color: String): Int = when (color) {
        "darkGray" -> Style.penDarkGray
        "lightGray" -> Style.penLightGray
        "white" -> Style.paper
        else -> Style.ink
    }

    private fun updateColorSelection() {
        val pen = selectedPenId
        colorSection?.visibility = if (pen != null && book.hasColor(pen)) View.VISIBLE else View.GONE
        for ((color, tile) in colorBtns) tile.background = slotCircleBackground(color == selectedColor)
    }

    private fun buildThicknessInline(parent: LinearLayout) {
        val tierRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tierBtns.clear()
        for (i in 0 until TIER_COUNT) {
            if (i > 0) tierRow.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(px(EDITOR_GAP_PX), 0) })
            val btn = makeTierBtn(i)
            tierBtns.add(btn)
            tierRow.addView(btn)
        }
        parent.addView(tierRow)
        tierRowView = tierRow

        val stepper = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(30) }
        }
        stepper.addView(makeStepperBtn("−") { stepThickness(-1) })
        val label = TextView(ctx).apply {
            textSize = sp(18f)
            typeface = Style.bold
            setTextColor(Style.ink)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
        }
        thicknessLabel = label
        stepper.addView(label)
        stepper.addView(makeStepperBtn("+") { stepThickness(1) })
        parent.addView(stepper)
        stepperView = stepper

        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        parent.addView(grid)
        gradeGrid = grid
        gradePen = null

        syncThicknessDisplay()
    }

    private fun populateGradeGrid(grid: LinearLayout, pen: String, grades: List<String>) {
        grid.removeAllViews()
        gradeCircles.clear()
        grades.indices.chunked(4).forEachIndexed { rowIndex, row ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                if (rowIndex > 0) layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            }
            row.forEachIndexed { i, grade ->
                if (i > 0) line.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(px(EDITOR_GAP_PX), 0) })
                line.addView(makeGradeCell(pen, grade, grades))
            }
            grid.addView(line)
        }
    }

    private fun makeGradeCell(pen: String, grade: Int, grades: List<String>): LinearLayout {
        val circleSize = px(EDITOR_CIRCLE_PX)
        val circle = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            background = slotCircleBackground(false)
            val dotSize = px(18f + 30f * grade / grades.lastIndex.coerceAtLeast(1))
            addView(View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
                background = Style.oval(Style.ink)
            })
        }
        gradeCircles.add(circle)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(circleSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                thickness = book.steps(pen)[grade]
                thicknessChanged = true
                activeTier = null
                syncThicknessDisplay()
                updateTierSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }
            addView(circle)
            addView(TextView(ctx).apply {
                text = grades[grade]
                textSize = sp(15f)
                typeface = Style.bold
                setTextColor(Style.ink)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(circleSize, px(SLOT_LABEL_HEIGHT_PX))
                    .apply { topMargin = px(SLOT_LABEL_MARGIN_PX) }
            })
        }
    }

    private fun makeTierBtn(index: Int): LinearLayout {
        val circleSize = px(EDITOR_CIRCLE_PX)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(circleSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val pen = selectedPenId ?: return@setOnClickListener
                val raw = tiers.getOrNull(index) ?: return@setOnClickListener
                if (!book.hasThickness(pen)) return@setOnClickListener
                if (activeTier == index) {
                    activeTier = null
                } else {
                    activeTier = index
                    thickness = raw
                    thicknessChanged = true
                }
                syncThicknessDisplay()
                updateTierSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }
            addView(FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
                background = slotCircleBackground(false)
                addView(View(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER)
                    background = Style.oval(Style.ink)
                })
            })
            addView(TextView(ctx).apply {
                textSize = sp(15f)
                typeface = Style.bold
                setTextColor(Style.ink)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(circleSize, px(SLOT_LABEL_HEIGHT_PX))
                    .apply { topMargin = px(SLOT_LABEL_MARGIN_PX) }
            })
        }
    }

    private fun tierDotSize(pen: String, raw: Int): Int = px(PaletteDots.size(book.steps(pen), raw, 18f, 48f))

    private fun reloadTiers() {
        tiers = selectedPenId?.let { PaletteTiers.load(book, it) } ?: IntArray(0)
        activeTier = null
    }

    private fun makeStepperBtn(label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        textSize = sp(18f)
        typeface = Style.bold
        setTextColor(Style.paletteStepperText)
        gravity = Gravity.CENTER
        val sz = dp(38)
        layoutParams = LinearLayout.LayoutParams(sz, sz)
        background = Style.oval(Style.paper, Style.paletteDash, dp(1.5f))
        setOnClickListener { onClick() }
    }

    private fun updateTierSelection() {
        val pen = selectedPenId
        val hasThickness = pen != null && book.hasThickness(pen)
        thicknessSection?.visibility = if (hasThickness) View.VISIBLE else View.GONE
        val grades = if (hasThickness && pen != null) book.grades(pen) else emptyList()
        val graded = grades.isNotEmpty()
        tierRowView?.visibility = if (graded) View.GONE else View.VISIBLE
        stepperView?.visibility = if (graded) View.GONE else View.VISIBLE
        val grid = gradeGrid
        if (grid != null) {
            grid.visibility = if (graded) View.VISIBLE else View.GONE
            if (graded && pen != null) {
                if (gradePen != pen) {
                    gradePen = pen
                    populateGradeGrid(grid, pen, grades)
                }
                val current = book.stepIndex(pen, thickness)
                gradeCircles.forEachIndexed { i, circle -> circle.background = slotCircleBackground(i == current) }
            }
        }
        for ((i, cell) in tierBtns.withIndex()) {
            val raw = tiers.getOrNull(i)
            cell.visibility = if (raw == null || pen == null) View.INVISIBLE else View.VISIBLE
            if (raw == null || pen == null) continue
            val circle = cell.getChildAt(0) as? FrameLayout ?: continue
            val dot = circle.getChildAt(0) ?: continue
            val label = cell.getChildAt(1) as? TextView ?: continue
            val dotSize = tierDotSize(pen, raw)
            circle.background = slotCircleBackground(activeTier == i)
            dot.layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
            label.text = book.slotLabel(pen, raw)
        }
    }

    private fun syncThicknessDisplay() {
        val pen = selectedPenId
        thicknessLabel?.text = if (pen == null || !book.hasThickness(pen)) "—" else book.slotLabel(pen, thickness)
        thicknessHeaderLabel?.text = Strings.get("palette_thickness")
    }

    private fun stepThickness(direction: Int) {
        val pen = selectedPenId ?: return
        if (!book.hasThickness(pen)) return
        val tierIndex = activeTier
        if (tierIndex != null && tierIndex in tiers.indices) {
            val steps = book.steps(pen)
            val curIdx = book.stepIndex(pen, tiers[tierIndex])
            val newIdx = (curIdx + direction).coerceIn(0, steps.lastIndex)
            tiers[tierIndex] = steps[newIdx]
            thickness = steps[newIdx]
            thicknessChanged = true
            PaletteTiers.save(book, pen, tiers)
            updateTierSelection()
        } else {
            val steps = book.steps(pen)
            val curIdx = steps.indexOfFirst { it == thickness }.takeIf { it >= 0 }
                ?: steps.indices.minByOrNull { abs(steps[it] - thickness) } ?: 0
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
        selectedPenId = book.normalize(preset.penId)
        selectedColor = if (isHost) HostPenSpec.normalizeColor(preset.color) else "black"
        thickness = preset.thickness
        thicknessChanged = true
        reloadTiers()
    }

    private fun restoreFromSelection() {
        if (isHost) {
            thicknessChanged = false
            activeTier = null
            return
        }
        selectedPenId = MosaicPaletteSpec.normalize(selection.optString("dominantPenId", "ball"))
        thickness = PenSizeSpec.snap(initialPenType?.let { normalizePenType(it) }, avgThickness)
        thicknessChanged = false
        reloadTiers()
    }

    private fun doResetAll() {
        val count = presets.count { it != null && !it.locked }
        if (count == 0) return
        Dialogs.confirm(Strings.get("palette_delete_confirm", count)) { confirmed ->
            if (!confirmed) return@confirm
            Main.post {
                for (i in presets.indices) if (presets[i]?.locked != true) presets[i] = null
                selectedSlot = selectedSlot?.takeIf { presets[it] != null }
                savePresets()
                refreshAll()
            }
        }
    }

    private fun autoSaveToActivePreset() {
        val idx = selectedSlot ?: return
        val preset = curPreset() ?: return
        if (preset.locked) return
        val penId = selectedPenId ?: return
        presets[idx] = PaletteSlot(penId, thickness, color = selectedColor)
        savePresets()
        rebuildGrid()
    }

    private fun penTypeChanged(): Boolean = selectedPenId != initialPenId

    internal fun updateApplyState() {
        val btn = applyBtn ?: return
        val canApply = if (isHost) selectedPenId != null
            else strokeCount() > 0 && (thicknessChanged || penTypeChanged())
        Style.setEnabledLook(btn, canApply)
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
        val penId = selectedPenId ?: return

        applyBtn?.isEnabled = false
        if (isHost) {
            Palette.applyHost(penId, thickness, selectedColor)
            return
        }
        val request = JSONObject(selection.toString())
            .put("penId", penId)
            .put("thickness", thickness)
            .put("changePen", penTypeChanged())
        Palette.apply(request)
    }

    private fun prefs() = Prefs.of(PREFS)

    private fun loadPresets() {
        val loaded = PaletteSlots.load(book)
        for (i in presets.indices) presets[i] = loaded.getOrNull(i)
        selectedSlot = null
    }

    private fun savePresets() {
        PaletteSlots.save(book, presets)
        PenBar.reloadPresets()
        if (!isHost) PaletteBubble.reloadPresets()
    }

}

class PaletteModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalPalette") {
    override fun initialize() {
        super.initialize()
        Tools.handle(
            Tools.INK_PALETTE,
            onTap = { Palette.onPaletteTap() },
            onLongPress = { Palette.onPaletteLongPress() },

            onExit = { PaletteBubble.hideAndForget() },
        )
        Tools.handle(
            Tools.PEN_SLOTS,
            onTap = { Palette.onSlotsTap() },
            onLongPress = { Palette.onSlotsLongPress() },
            onExit = { PenBar.hideAndForget() },
        )
    }
}
