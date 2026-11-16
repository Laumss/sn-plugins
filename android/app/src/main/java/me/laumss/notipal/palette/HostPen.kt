package me.laumss.notipal.palette

import android.os.Build
import android.os.SystemClock
import me.laumss.notipal.common.Cancelable
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostLogMonitor
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.HostSignal
import me.laumss.notipal.host.HostToolKind
import me.laumss.notipal.host.PenDisableClient
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.mosaic.PenSizeSpec
import kotlin.math.abs

object HostPenSpec {
    const val NEEDLE = "needle"
    const val INK = "ink"
    const val CALLIGRAPHY = "calligraphy"
    const val MARKER = "marker"
    const val ERASER = "eraser"
    const val LASSO = "lasso"

    val penIds = listOf(NEEDLE, INK, CALLIGRAPHY, MARKER, ERASER, LASSO)
    val colorIds = listOf("black", "darkGray", "lightGray", "white")

    private const val TYPE_NEEDLE = 10
    private const val TYPE_INK = 16
    private const val TYPE_MARKER = 11
    private const val TYPE_CALLIGRAPHY = 15

    private const val TYPE_LASSO = 4
    private const val LASSO_WIDTH = 200

    private const val ERASER_COLOR = 255
    private const val COLOR_BLACK = 0
    private const val COLOR_DARK_GRAY = -101
    private const val COLOR_LIGHT_GRAY = -102
    private const val COLOR_WHITE = 254

    private class Table(val needle: IntArray, val ink: IntArray, val marker: Int, val calligraphy: IntArray)

    private val A5X = Table(
        needle = intArrayOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000),
        ink = intArrayOf(300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000),
        marker = 3000,
        calligraphy = intArrayOf(500, 600, 700, 800, 900, 1000, 1500, 2000),
    )

    private val A6X = Table(
        needle = intArrayOf(200, 300, 400, 500, 700, 800, 1000, 1100, 1200, 1300, 1800, 2400),
        ink = intArrayOf(400, 500, 700, 900, 1000, 1100, 1200, 1300, 1800, 2400),
        marker = 3800,
        calligraphy = intArrayOf(700, 800, 1000, 1100, 1200, 1300, 1800, 2400),
    )

    private val A5X2 = Table(
        needle = intArrayOf(200, 300, 400, 500, 600, 700, 900, 1000, 1100, 1200, 1800, 2400),
        ink = intArrayOf(400, 500, 600, 800, 900, 1000, 1100, 1200, 1800, 2400),
        marker = 3800,
        calligraphy = intArrayOf(600, 700, 900, 1000, 1100, 1200, 1800, 2400),
    )

    private val ERASER_WIDTHS = intArrayOf(700, 1900, 3300, 5000)

    private val PEN_LABELS = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.5f, 2.0f)

    private fun sizeType(id: String): Int = when (id) {
        INK -> PenSizeSpec.BALL
        CALLIGRAPHY -> PenSizeSpec.CALLIGRAPHY
        MARKER -> PenSizeSpec.MARKER
        else -> PenSizeSpec.NEEDLE
    }

    private fun extraSteps(id: String): IntArray = PenSizeSpec.extraSteps(sizeType(id))

    private fun extraLabels(id: String): FloatArray = PenSizeSpec.extraLabels(sizeType(id))

    private val table: Table = when {
        Build.BOARD == "A5X2" || Build.MODEL.contains("Manta", ignoreCase = true) -> A5X2
        Build.MODEL == "Supernote A5 X" -> A5X
        Build.BOARD == "A6X2" || Build.MODEL == "Supernote A6 X" || Build.MODEL == "Supernote Nomad" -> A6X
        else -> A5X2
    }

    val isA6X2: Boolean = Build.BOARD == "A6X2" || Build.MODEL == "Supernote Nomad"

    fun normalize(id: String): String = if (id in penIds) id else NEEDLE

    fun normalizeColor(id: String): String = if (id in colorIds) id else "black"

    fun hasColor(id: String): Boolean = id == NEEDLE || id == INK || id == CALLIGRAPHY || id == MARKER

    fun hasThickness(id: String): Boolean = id != LASSO

    fun label(id: String): String = Strings.get(
        when (id) {
            INK -> "pen_ink"
            CALLIGRAPHY -> "pen_calligraphy"
            MARKER -> "pen_marker"
            ERASER -> "pen_eraser"
            LASSO -> "pen_lasso"
            else -> "pen_needle"
        }
    )

    fun colorLabel(color: String): String = Strings.get(
        when (color) {
            "darkGray" -> "color_dark_gray"
            "lightGray" -> "color_light_gray"
            "white" -> "color_white"
            else -> "color_black"
        }
    )

    fun typeOf(id: String): Int = when (id) {
        INK, ERASER -> TYPE_INK
        CALLIGRAPHY -> TYPE_CALLIGRAPHY
        MARKER -> TYPE_MARKER
        LASSO -> TYPE_LASSO
        else -> TYPE_NEEDLE
    }

    fun rawColor(id: String, color: String): Int = when (id) {
        ERASER -> ERASER_COLOR
        LASSO -> COLOR_BLACK
        else -> when (color) {
            "darkGray" -> COLOR_DARK_GRAY
            "lightGray" -> COLOR_LIGHT_GRAY
            "white" -> COLOR_WHITE
            else -> COLOR_BLACK
        }
    }

    fun isGray(raw: Int): Boolean = raw == COLOR_DARK_GRAY || raw == COLOR_LIGHT_GRAY

    fun colorIdOf(raw: Int): String? = when (raw) {
        COLOR_BLACK -> "black"
        COLOR_DARK_GRAY -> "darkGray"
        COLOR_LIGHT_GRAY -> "lightGray"
        COLOR_WHITE -> "white"
        else -> null
    }

    fun penIdOfType(type: Int): String? = when (type) {
        TYPE_NEEDLE -> NEEDLE
        TYPE_INK -> INK
        TYPE_CALLIGRAPHY -> CALLIGRAPHY
        TYPE_MARKER -> MARKER
        else -> null
    }

    fun steps(id: String): IntArray = when (id) {
        NEEDLE -> table.needle + extraSteps(id)
        INK -> table.ink + extraSteps(id)
        CALLIGRAPHY -> table.calligraphy + extraSteps(id)
        MARKER -> intArrayOf(table.marker) + extraSteps(id)
        ERASER -> ERASER_WIDTHS
        else -> intArrayOf(LASSO_WIDTH)
    }

    fun labels(id: String): FloatArray = when (id) {
        NEEDLE -> PEN_LABELS + extraLabels(id)
        INK -> PEN_LABELS.copyOfRange(2, PEN_LABELS.size) + extraLabels(id)
        CALLIGRAPHY -> PEN_LABELS.copyOfRange(4, PEN_LABELS.size) + extraLabels(id)
        MARKER -> floatArrayOf(3.0f) + extraLabels(id)
        else -> FloatArray(0)
    }

    fun tierDefaults(id: String): IntArray = when (id) {

        NEEDLE, INK, CALLIGRAPHY -> floatArrayOf(0.4f, 0.7f, 1.5f, 3.0f).map { rawForLabel(id, it) }.toIntArray()
        MARKER -> floatArrayOf(3.0f, 4.0f, 5.0f, 6.0f).map { rawForLabel(id, it) }.toIntArray()
        ERASER -> ERASER_WIDTHS
        else -> IntArray(0)
    }

    fun gradeLabels(id: String): List<String> =
        if (id == ERASER) ERASER_WIDTHS.map { PenSizeSpec.formatLabel(it / 1000f) } else emptyList()

    fun stepIndex(id: String, raw: Int): Int {
        val steps = steps(id)
        var best = 0
        for (i in steps.indices) if (abs(steps[i] - raw) < abs(steps[best] - raw)) best = i
        return best
    }

    fun snap(id: String, raw: Int): Int = steps(id)[stepIndex(id, raw)]

    fun label(id: String, raw: Int): String = when (id) {
        LASSO -> ""
        ERASER -> gradeLabels(id)[stepIndex(id, raw)]
        else -> PenSizeSpec.formatLabel(labels(id)[stepIndex(id, raw)])
    }

    fun rawForLabel(id: String, value: Float): Int {
        val labels = labels(id)
        if (labels.isEmpty()) return defaultWidth(id)
        var best = 0
        for (i in labels.indices) if (abs(labels[i] - value) < abs(labels[best] - value)) best = i
        return steps(id)[best]
    }

    fun defaultWidth(id: String): Int = when (id) {
        NEEDLE, INK, CALLIGRAPHY -> rawForLabel(id, 0.5f)
        ERASER -> ERASER_WIDTHS[1]
        else -> steps(id)[0]
    }

    fun convert(from: String, to: String, raw: Int): Int {
        if (from == to) return snap(to, raw)
        val fromLabels = labels(from)
        if (fromLabels.isEmpty() || labels(to).isEmpty()) return defaultWidth(to)
        return rawForLabel(to, fromLabels[stepIndex(from, raw)])
    }

    fun widthFromLog(id: String, value: Int): Int {
        if (id == MARKER) return table.marker
        val steps = steps(id)
        return if (value in steps.indices) steps[value] else snap(id, value)
    }

    fun iconAsset(id: String, color: String): String? = when (id) {
        ERASER -> "icons/pen/ic_regular_eraser_svg.xml"
        LASSO -> "icons/pen/ic_region_svg.xml"
        else -> PenSizeSpec.penIconAsset(
            when (id) {
                INK -> PenSizeSpec.BALL
                CALLIGRAPHY -> PenSizeSpec.CALLIGRAPHY
                MARKER -> PenSizeSpec.MARKER
                else -> PenSizeSpec.NEEDLE
            },
            normalizeColor(color),
        )
    }
}

data class HostPenState(val penId: String, val width: Int?, val color: String?) {

    fun matches(penId: String, slotWidth: Int, slotColor: String): Boolean {
        if (penId != this.penId) return false
        val w = width
        if (w != null && HostPenSpec.hasThickness(penId) &&
            HostPenSpec.snap(penId, w) != HostPenSpec.snap(penId, slotWidth)
        ) return false
        val c = color
        return c == null || !HostPenSpec.hasColor(penId) || c == HostPenSpec.normalizeColor(slotColor)
    }
}

object HostPen {
    private const val TAG = "HostPen"
    const val NOTE_APP = "superNoteNote"
    const val DOC_APP = "superNoteDocument"

    enum class Result { OK, FAILED, DOC_TEXT_MODE, MOSAIC, NO_HOST_TOOL }

    private const val PAGE_TURN_WINDOW_MS = 3000L

    private const val PAGE_TURN_WAIT_MS = 1500L

    private const val REAPPLY_DELAY_MS = 150L

    private val states = HashMap<String, HostPenState>()

    private val lastColors = HashMap<String, Int>()
    private val listeners = ArrayList<() -> Unit>()
    @Volatile private var installed = false

    private class Held(val id: String, val type: Int, val raw: Int, val rawColor: Int, val state: HostPenState)
    private val held = HashMap<String, Held>()

    private val hostOwn = HashMap<String, HostSignal.HostToolSent>()
    private var lastPageTurnAt = 0L

    private var pendingApp: String? = null
    private var pendingState: HostPenState? = null
    private val pendingTimeout = Runnable { acceptPending("no page turn") }
    private var reapplyApp: String? = null
    private val reapplyRunnable = Runnable { reapplyApp?.let { reapply(it) } }

    fun install() {
        if (installed) return
        installed = true
        HostLogMonitor.addListener { signal -> onSignal(signal) }
    }

    private fun onSignal(signal: HostSignal) {
        if (signal is HostSignal.HostToolSent) {
            Main.post { if (HostLogMonitor.isCurrentGeneration(signal.generation)) onHostTool(signal) }
        } else if (signal is HostSignal.NotePageTurned) {
            Main.post { if (HostLogMonitor.isCurrentGeneration(signal.generation)) onPageTurned() }
        } else if (signal is HostSignal.DocTextSelectionChanged && signal.active) {

            Main.post {
                release(DOC_APP, "document text tool")
                update(DOC_APP, null, "document text tool")
            }
        }
    }

    fun releaseAll(why: String) {
        if (held.isEmpty() && pendingApp == null) return
        Log.i(TAG, "slot pens released: $why")
        held.clear()
        cancelPending()
        Main.handler.removeCallbacks(reapplyRunnable)
    }

    private fun release(app: String, why: String) {
        if (held.remove(app) != null) Log.i(TAG, "slot pen released ($app): $why")
        if (pendingApp == app) cancelPending()
    }

    private fun cancelPending() {
        pendingApp = null
        pendingState = null
        Main.handler.removeCallbacks(pendingTimeout)
    }

    private fun acceptPending(why: String) {
        val app = pendingApp ?: return
        val state = pendingState
        cancelPending()
        release(app, why)
        update(app, state, "host log")
    }

    private fun onPageTurned() {
        lastPageTurnAt = SystemClock.elapsedRealtime()
        val app = pendingApp ?: return
        if (app != NOTE_APP) return
        cancelPending()
        scheduleReapply(app, "page turn after host resend")
    }

    private fun scheduleReapply(app: String, why: String) {
        Log.i(TAG, "host resent its own tool ($app, $why); slot pen goes back")
        reapplyApp = app
        Main.handler.removeCallbacks(reapplyRunnable)
        Main.handler.postDelayed(reapplyRunnable, REAPPLY_DELAY_MS)
    }

    private fun reapply(app: String) {
        val h = held[app] ?: return
        if (MosaicLink.boardVisible || app != appNow()) return
        send(app, h, "reapply") { ok ->
            if (ok) {
                Log.i(TAG, "slot reapplied ($app): ${h.state}")
                update(app, h.state, "slot reapplied")
            } else {
                release(app, "reapply failed")
            }
        }
    }

    private fun appNow(): String = if (HostSession.isDocumentContext()) DOC_APP else NOTE_APP

    fun current(): HostPenState? = states[appNow()]

    fun addListener(l: () -> Unit): Cancelable {
        listeners += l
        return Cancelable { Main.post { listeners -= l } }
    }

    private fun update(app: String, state: HostPenState?, why: String) {
        if (states[app] == state) return
        if (state == null) states.remove(app) else states[app] = state
        Log.i(TAG, "host tool ($app): $state ($why)")
        for (l in listeners.toList()) {
            try {
                l()
            } catch (e: Exception) {
                Log.e(TAG, "listener failed", e)
            }
        }
    }

    private fun onHostTool(signal: HostSignal.HostToolSent) {
        if (MosaicLink.boardVisible) return
        val app = appNow()
        val key = signal.copy(generation = 0)
        val own = hostOwn.put(app, key)
        if (signal.kind == HostToolKind.PEN) signal.color?.let { lastColors[app] = it }
        val state = hostStateOf(signal)
        if (pendingApp != null) cancelPending()
        val ours = held[app]
        if (ours != null && ours.state != state) {

            if (app == NOTE_APP && (own == null || own == key)) {

                if (SystemClock.elapsedRealtime() - lastPageTurnAt <= PAGE_TURN_WINDOW_MS) {
                    scheduleReapply(app, "page turn before host resend")
                    return
                }
                pendingApp = app
                pendingState = state
                Main.handler.postDelayed(pendingTimeout, PAGE_TURN_WAIT_MS)
                return
            }
            release(app, "host changed tool")
        }
        update(app, state, "host log")
    }

    private fun hostStateOf(signal: HostSignal.HostToolSent): HostPenState? =
        when (signal.kind) {
            HostToolKind.PEN -> {
                val penId = signal.type?.let { HostPenSpec.penIdOfType(it) }
                penId?.let {
                    HostPenState(it, signal.sizeIndex?.let { i -> HostPenSpec.widthFromLog(it, i) },
                        signal.color?.let { c -> HostPenSpec.colorIdOf(c) })
                }
            }

            HostToolKind.ERASER -> if (signal.type == 0) HostPenState(HostPenSpec.ERASER, null, null) else null
            HostToolKind.LASSO -> HostPenState(HostPenSpec.LASSO, null, null)
        }

    fun apply(penId: String, width: Int, color: String, onResult: (Result) -> Unit) {
        if (MosaicLink.boardVisible) {
            onResult(Result.MOSAIC)
            return
        }
        val doc = HostSession.isDocumentContext()
        if (doc && HostLogMonitor.isDocTextSelectionActive()) {
            Log.i(TAG, "apply $penId refused: document text tool active")
            onResult(Result.DOC_TEXT_MODE)
            return
        }
        val app = if (doc) DOC_APP else NOTE_APP
        val id = HostPenSpec.normalize(penId)
        val raw = HostPenSpec.snap(id, width)
        val h = Held(id, HostPenSpec.typeOf(id), raw, HostPenSpec.rawColor(id, color),
            HostPenState(id, raw, if (HostPenSpec.hasColor(id)) HostPenSpec.normalizeColor(color) else null))
        if (pendingApp == app) cancelPending()
        send(app, h, "apply") { ok ->
            if (ok) {
                held[app] = h
                update(app, h.state, "slot applied")
            }
            onResult(if (ok) Result.OK else Result.FAILED)
        }
    }

    fun slotPenHeld(): Boolean = held.containsKey(appNow())

    fun restoreHost(onResult: (Result) -> Unit) {
        if (MosaicLink.boardVisible) {
            onResult(Result.MOSAIC)
            return
        }
        val doc = HostSession.isDocumentContext()
        if (doc && HostLogMonitor.isDocTextSelectionActive()) {
            onResult(Result.DOC_TEXT_MODE)
            return
        }
        val app = if (doc) DOC_APP else NOTE_APP
        val own = hostOwn[app]
        val state = own?.let { hostStateOf(it) }
        if (own == null || state == null) {
            Log.i(TAG, "restore host tool ($app): unknown or not sendable ($own)")
            onResult(Result.NO_HOST_TOOL)
            return
        }
        val id = state.penId
        val raw = state.width ?: HostPenSpec.defaultWidth(id)
        val rawColor = if (own.kind == HostToolKind.PEN) own.color ?: HostPenSpec.rawColor(id, "black")
            else HostPenSpec.rawColor(id, "black")
        val type = if (own.kind == HostToolKind.PEN) own.type ?: HostPenSpec.typeOf(id) else HostPenSpec.typeOf(id)
        val h = Held(id, type, raw, rawColor, state)
        if (pendingApp == app) cancelPending()
        send(app, h, "restore") { ok ->
            if (ok) {
                release(app, "host tool restored")
                Log.i(TAG, "host tool restored ($app): $state")
                update(app, state, "host tool restored")
            }
            onResult(if (ok) Result.OK else Result.FAILED)
        }
    }

    private fun send(app: String, h: Held, why: String, onDone: (Boolean) -> Unit) {
        val previous = lastColors[app]
        PenGuard.post("host pen") {
            val sent = PenDisableClient.sendPenInfo(app, h.type, h.raw, h.rawColor)
            val ok = sent == PenDisableClient.Result.OK

            if (ok && previous != null && HostPenSpec.isGray(previous) && !HostPenSpec.isGray(h.rawColor)) {
                PenDisableClient.syncBackground(app, HostPenSpec.isA6X2)
            }
            Main.post {
                if (ok) lastColors[app] = h.rawColor else Log.w(TAG, "$why ${h.id} failed: $sent")
                onDone(ok)
            }
        }
    }
}
