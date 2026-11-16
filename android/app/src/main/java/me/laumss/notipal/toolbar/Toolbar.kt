package me.laumss.notipal.toolbar

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.AiAccess
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.ForegroundActivity
import me.laumss.notipal.host.HostHooks
import me.laumss.notipal.host.HostLogMonitor
import me.laumss.notipal.host.HostNotice
import me.laumss.notipal.host.HostPenSignals
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.HostProbe
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.HostSignal
import me.laumss.notipal.host.HostState
import me.laumss.notipal.host.LassoHolds
import me.laumss.notipal.host.MonitorPhase
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.host.PenOwner
import me.laumss.notipal.host.Surface
import me.laumss.notipal.host.SystemKeyboard
import me.laumss.notipal.mosaic.MosaicEvent
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.ui_common.DragFrame
import me.laumss.notipal.ui_common.FloatWindows
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.RotationOutcome
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.WindowHooks
import me.laumss.notipal.ui_common.WindowRotation
import kotlin.math.abs

data class Tool(
    val id: String,
    val icon: String,
    val labelKey: String,
    val surfaces: Set<Surface>,
    val onTap: ((ToolTap) -> Unit)? = null,
    val onLongPress: ((ToolTap) -> Unit)? = null,

    val onExit: (() -> Unit)? = null,

    val onRetap: (() -> Boolean)? = null,
    val sidebarSurfaces: Set<Surface> = emptySet(),
    val latches: Boolean = false,
    val highlights: Boolean = latches,
    val longPressSurfaces: Set<Surface> = emptySet(),
    val iconFor: ((Surface) -> String)? = null,
    val fallbackText: String = "?",
) {
    fun hasLongPress(surface: Surface): Boolean = surface in longPressSurfaces
}

data class ToolTap(val toolId: String, val host: HostState, val anchor: Rect)

object Tools {
    const val CLIP_MANAGER = "manage_clips"
    const val IMAGE = "insert_doc_screenshot"
    const val SMART_LASSO = "smart_lasso"
    const val INK_PALETTE = "ink_palette"
    const val PEN_SLOTS = "pen_slots"
    const val AI_RELAY = "ai_relay"
    const val LINK = "insert_link"
    const val TEXT_RECEIVE = "insert_text"
    const val NOTE_TO_MOSAIC = "note_to_mosaic"
    const val DOC_SHARE = "doc_share"
    const val DOC_TO_MOSAIC = "doc_to_mosaic"
    const val COLLAPSE_ID = "toolbar_collapse"

    const val CLIP_SLOT = "clip"

    private val NOTE = setOf(Surface.NOTE)
    private val DOC = setOf(Surface.DOCUMENT)
    private val MOSAIC = setOf(Surface.MOSAIC)
    private val ALL = setOf(Surface.NOTE, Surface.DOCUMENT, Surface.MOSAIC)

    val COLLAPSE = Tool(COLLAPSE_ID, "ic_tool_collapse", "config_tool_collapse", emptySet(), fallbackText = "⋯")

    private val catalog = LinkedHashMap<String, Tool>().apply {
        fun add(t: Tool) = put(t.id, t)
        add(Tool(CLIP_MANAGER, "ic_tool_doc", "config_tool_clip_manager", ALL, fallbackText = "Cm"))
        add(Tool(IMAGE, "ic_tool_image", "config_tool_image_capture", ALL, fallbackText = "Sc",

            iconFor = { if (it == Surface.DOCUMENT) "ic_tool_doc_capture" else "ic_tool_image" },
            longPressSurfaces = NOTE + MOSAIC))
        add(Tool(SMART_LASSO, "ic_tool_lasso_ai", "config_tool_smart_lasso", ALL, fallbackText = "AI", longPressSurfaces = ALL))
        add(Tool(PEN_SLOTS, "ic_tool_pen_slots", "config_tool_pen_slots", ALL, fallbackText = "Ps",
            highlights = true, longPressSurfaces = ALL))
        add(Tool(INK_PALETTE, "ic_tool_palette", "config_tool_ink_palette", emptySet(), fallbackText = "Ip",
            sidebarSurfaces = MOSAIC, highlights = true, longPressSurfaces = MOSAIC))
        add(Tool(AI_RELAY, "ic_tool_ai_relay", "config_tool_ai_relay", ALL, fallbackText = "Rl", latches = true))
        add(Tool(LINK, "ic_tool_link", "config_tool_link", NOTE, fallbackText = "Lk"))
        add(Tool(TEXT_RECEIVE, "ic_tool_text", "config_tool_text", emptySet(), fallbackText = "Tx",
            sidebarSurfaces = NOTE + MOSAIC, latches = true))
        add(Tool(NOTE_TO_MOSAIC, "ic_tool_note_to_mosaic", "mosaic_note_import", emptySet(), fallbackText = "Nm", sidebarSurfaces = NOTE))
        add(Tool(DOC_SHARE, "ic_edit_to_note", "doc_share_localsend", emptySet(), fallbackText = "N", sidebarSurfaces = DOC))
        add(Tool(DOC_TO_MOSAIC, "ic_tool_doc_to_mosaic", "doc_mosaic_import", emptySet(), fallbackText = "Dm", sidebarSurfaces = DOC))
    }

    private val DEFAULT_HIDDEN = setOf(LINK)

    private val MOSAIC_MAIN = listOf(CLIP_MANAGER, IMAGE, SMART_LASSO, PEN_SLOTS, AI_RELAY)

    private val icons = HashMap<String, String>()

    private fun prefs() = Prefs.of("toolbar")

    fun register(tool: Tool) {
        synchronized(catalog) { catalog[tool.id] = tool }
        Main.post { Toolbar.refresh("tool registered ${tool.id}") }
    }

    fun handle(
        id: String,
        onTap: ((ToolTap) -> Unit)?,
        onLongPress: ((ToolTap) -> Unit)? = null,
        onExit: (() -> Unit)? = null,
        onRetap: (() -> Boolean)? = null,
    ) {
        val base = synchronized(catalog) { catalog[id] } ?: run {
            Log.w("Tools", "handle: unknown tool $id")
            return
        }
        register(base.copy(onTap = onTap, onLongPress = onLongPress, onExit = onExit, onRetap = onRetap))
    }

    fun get(id: String): Tool? = if (id == COLLAPSE_ID) COLLAPSE else synchronized(catalog) { catalog[id] }

    fun setIcon(id: String, icon: String) {
        Main.post {
            if (icons.put(id, icon) != icon) Toolbar.refresh("icon $id")
        }
    }

    internal fun iconOf(tool: Tool, surface: Surface): String =
        icons[tool.id] ?: tool.iconFor?.invoke(surface) ?: tool.icon

    internal fun labelOf(tool: Tool): String = Strings.get(tool.labelKey)

    fun order(): List<String> {
        migrateOrder()
        val saved = prefs().getString("order", null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        val known = synchronized(catalog) { catalog.keys.toList() }
        return saved.filter { it in known } + known.filter { it !in saved }
    }

    private fun migrateOrder() {
        val p = prefs()
        if (p.getBoolean(ORDER_MIGRATED_KEY, false)) return
        val e = p.edit().putBoolean(ORDER_MIGRATED_KEY, true)
        p.getString("order", null)?.let { saved ->
            val ids = saved.split(',').filter { it.isNotEmpty() && it != PEN_SLOTS }.toMutableList()
            val at = ids.indexOf(INK_PALETTE).takeIf { it >= 0 && p.getBoolean(OLD_MIGRATED_KEY, false) }
                ?: ids.indexOf(AI_RELAY).takeIf { it >= 0 } ?: ids.size
            ids.add(at, PEN_SLOTS)
            e.putString("order", ids.joinToString(","))
        }
        e.apply()
    }

    private const val ORDER_MIGRATED_KEY = "order_pen_slots"
    private const val OLD_MIGRATED_KEY = "order_palette_main"

    fun setOrder(ids: List<String>) {
        prefs().edit().putString("order", ids.joinToString(",")).apply()
        Main.post { Toolbar.refresh("order changed") }
    }

    fun hidden(): Set<String> = prefs().getStringSet("hidden", null) ?: DEFAULT_HIDDEN

    fun setHidden(ids: Set<String>) {
        prefs().edit().putStringSet("hidden", HashSet(ids)).apply()
        Main.post { Toolbar.refresh("hidden changed") }
    }

    private fun ordered(): List<Tool> {
        val ai = AiAccess.enabled()
        return order().filter { ai || it != AI_RELAY }.mapNotNull { id -> synchronized(catalog) { catalog[id] } }
    }

    internal fun mainTools(surface: Surface): List<Tool> {
        if (surface == Surface.MOSAIC) {
            val shown = ordered().filter { it.id in MOSAIC_MAIN }

            return shown.sortedBy {
                when (it.id) {
                    CLIP_MANAGER -> 0
                    IMAGE -> 1
                    else -> 2
                }
            }
        }
        val hide = hidden()
        return ordered().filter { surface in it.surfaces && it.id !in hide }
    }

    internal fun sidebarTools(surface: Surface): List<Tool> {
        val all = synchronized(catalog) { catalog.values.toList() }
        val rank = listOf(TEXT_RECEIVE, NOTE_TO_MOSAIC, INK_PALETTE, DOC_SHARE, DOC_TO_MOSAIC)
        return all.filter { surface in it.sidebarSurfaces }
            .sortedBy { rank.indexOf(it.id).let { i -> if (i < 0) rank.size else i } }
    }
}

sealed class ToolbarState {
    object Hidden : ToolbarState()
    object Stowed : ToolbarState()
    object Expanded : ToolbarState()
    data class Yielding(val reasons: Set<String>, val back: ToolbarState) : ToolbarState()
}

object ToolbarHooks {

    @Volatile var relaySmallUp: () -> Boolean = { false }

    @Volatile var relaySmallShowing: () -> Boolean = { false }

    @Volatile var closeRelaySmall: () -> Unit = {}

    @Volatile var stowRelaySmall: () -> Boolean = { false }

    @Volatile var stowPaletteBubble: () -> Unit = {}
    @Volatile var unstowPaletteBubble: () -> Unit = {}

    @Volatile var foldPenBar: () -> Unit = {}

    @Volatile var lexiFullScreen: () -> Boolean = { false }

    @Volatile var sessionShown: () -> Unit = {}

    @Volatile var sessionClosed: () -> Unit = {}
}

object Toolbar {
    private const val TAG = "Toolbar"

    private const val HOST_CLOSE_DELAY_MS = 150L
    private const val RETRY_DELAY_MS = 350L
    private const val RETRY_AFTER_ADD_FAILED_MS = 500L

    private const val MIN_VISIBLE_DP = 32
    private const val LEXI_HOLD = "toolbar"

    const val REASON_LEXI = "lexi"
    const val REASON_RELAY_SMALL = "relay-small"
    const val REASON_TWO_FINGER = "two-finger"
    const val REASON_PANEL = "panel"
    const val REASON_PASTE = "paste"
    const val REASON_ROTATION = "rotation"

    private const val HOST_STOW = "host"

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: ToolbarPanel? = null
    private var handle: ToolbarHandle? = null

    private var session = false
    private var collapsed = false

    private var restorePending = false

    private var pendingShow = false

    private var buildGeneration = 0L
    private var retryRunnable: Runnable? = null

    private var relayStowed = false
    private var lexiStowed = false

    private var dockLeft = true
    private var stickyX = -1
    private var stickyY = -1
    private var preDockX = -1
    private var preDockY = -1
    private var positionLoaded = false
    private var handleDragging = false

    private val parkReasons = LinkedHashSet<String>()

    private val blockers = LinkedHashMap<String, () -> Boolean>()

    private var restoreOverHostLasso = false

    private var terminateAfterPluginMenuClose = false

    private var hostClosePending = false
    private var hostCloseGeneration = -1L
    private var hostCloseRunnable: Runnable? = null

    private var mosaicRebuildRunnable: Runnable? = null

    private var surfaceBubbleRaisePending = false

    private val activeIds: MutableSet<String> = LinkedHashSet()
    private var clipFilled = BooleanArray(ToolbarMetrics.CLIP_SLOT_COUNT)

    @Volatile private var module: ToolbarModule? = null
    private var installed = false
    private var showingNow = false

    private var lastForeground: ForegroundActivity? = null

    private var pendingRestoreReason = ""
    private val restoreRunnable = Runnable { evaluateRestore(pendingRestoreReason) }

    val state: ToolbarState
        get() {
            val back = if (collapsed && !relayStowed && !lexiStowed) ToolbarState.Stowed else ToolbarState.Expanded
            return when {
                !hasSession() -> ToolbarState.Hidden
                root == null -> ToolbarState.Yielding(currentBlockers(), back)
                relayStowed || lexiStowed -> ToolbarState.Yielding(
                    setOfNotNull(REASON_RELAY_SMALL.takeIf { relayStowed }, REASON_LEXI.takeIf { lexiStowed }),
                    ToolbarState.Expanded,
                )
                collapsed -> ToolbarState.Stowed
                else -> ToolbarState.Expanded
            }
        }

    fun hasSession(): Boolean = session || root != null || pendingShow || restorePending

    fun yieldFor(reason: String) {
        Main.assertMain()
        when (reason) {
            REASON_RELAY_SMALL -> stowForRelay()
            REASON_LEXI -> stowForLexi()
            REASON_TWO_FINGER, REASON_ROTATION -> collapseKept(reason)
            REASON_PANEL -> parkForPanel()
            else -> {
                parkReasons += reason
                park()
                suspendPanels()
                FloatWindows.stowAll(reason)
            }
        }
    }

    fun resume(reason: String) {
        Main.assertMain()
        when (reason) {
            REASON_RELAY_SMALL -> unstowAfterRelay()
            REASON_LEXI -> unstowAfterLexi()
            REASON_TWO_FINGER, REASON_ROTATION -> Unit
            else -> {

                if (reason != REASON_PANEL) FloatWindows.unstowAll(reason)
                if (!parkReasons.remove(reason)) return
                scheduleRestore(HostSession.RESTORE_DELAY_NORMAL, "$reason done")
            }
        }
    }

    fun addBlocker(name: String, test: () -> Boolean) {
        Main.post { blockers[name] = test }
    }

    fun restore(reason: String = "restore request") {
        Main.post {
            if (module == null) return@post
            restorePending = restorePending || root != null || session
            evaluateRestore(reason)
        }
    }

    fun allowRestoreOverHostLasso() {
        Main.post { if (HostLogMonitor.isLassoMenuOpen()) restoreOverHostLasso = true }
    }

    fun setActive(toolId: String, active: Boolean) {
        Main.post {
            val changed = if (active) activeIds.add(toolId) else activeIds.remove(toolId)
            if (changed) refresh("active $toolId=$active")
        }
    }

    fun isActive(toolId: String): Boolean = toolId in activeIds

    fun setClips(filled: BooleanArray) {
        Main.post {
            clipFilled = BooleanArray(ToolbarMetrics.CLIP_SLOT_COUNT) { filled.getOrElse(it) { false } }
            refresh("clips")
        }
    }

    fun anchorOf(toolId: String): Rect? = panel?.toolView(toolId)?.let(::screenRect)

    fun install() {
        HostHooks.hasToolbarSession = { hasSession() }
        HostSession.toolbarRestorePending = { restorePending }
        HostSession.toolbarWindowUp = { root != null }
        HostSession.toolbarOwnsHostEntry = { ownsHostEntry() }
        HostSession.onTwoFingerDoubleTap = { onTwoFingerDoubleTap() }
        MosaicLink.openRequestHandler = { openFromMosaic() }
        WindowHooks.overlaysSuspended = { overlaysSuspended() }
        if (installed) return
        installed = true
        HostSession.addListener { old, new -> onHostState(old, new) }
        HostSession.addNoticeListener { notice -> onNotice(notice) }
        HostLogMonitor.addListener { signal -> onSignal(signal) }
        MosaicLink.addListener { event -> onMosaicEvent(event) }
        Panels.addActiveListener { any -> onPanelsActive(any) }
        Panels.addCloseListener { name -> module?.emit("panel:closed", Arguments.createMap().apply { putString("panel", name) }) }
        WindowRotation.addListener { outcome -> onRotation(outcome) }
    }

    internal fun attach(owner: ToolbarModule) {
        module = owner

        Main.post {
            if (module !== owner) return@post
            if (restorePending && !HostSession.state.pluginViewOpen) {
                scheduleRestore(HostSession.RESTORE_DELAY_SHORT, "module init pending toolbar restore")
            }
        }
    }

    internal fun detach(owner: ToolbarModule) {
        if (module === owner) module = null
    }

    private fun overlaysSuspended(): Boolean =
        SelectionHold.applyInFlight || SelectionHold.selectionHeld || parkReasons.any { it != REASON_PANEL } ||
            blockers.any { (name, test) -> name.startsWith("overlay:") && test() } ||
            WindowHooks.lexiOpen() || MosaicLink.motion

    private fun surface(): Surface {
        val s = HostSession.state
        return when {
            s.mosaicVisible -> Surface.MOSAIC
            s.document -> Surface.DOCUMENT
            else -> Surface.NOTE
        }
    }

    private fun layout(): ToolbarLayout {
        val surface = surface()
        return ToolbarLayout(surface, Tools.mainTools(surface), Tools.sidebarTools(surface), clipFilled, activeIds.toSet())
    }

    internal fun refresh(reason: String) {
        val current = panel ?: return
        if (collapsed || root == null) return
        if (current.update(layout())) {
            relayout()
            return
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar rebuilt: $reason")
        removeWindow()
        pendingShow = true
        createExpanded()
    }

    internal fun stateMap(): WritableMap = Arguments.createMap().apply {

        putString("state", when {
            !hasSession() -> "hidden"
            root == null || relayStowed || lexiStowed -> "yielding"
            collapsed -> "stowed"
            else -> "expanded"
        })
        putString("side", if (dockLeft) "left" else "right")
        putBoolean("session", hasSession())

        putBoolean("windowAttached", root?.isAttachedToWindow == true)
    }

    private fun requestedDocMode(mode: String?): Boolean = when (mode?.trim()?.lowercase()) {
        "doc_notipal", "doc", "document" -> true
        "note_notipal", "note" -> false
        else -> when (HostProbe.foregroundActivity()?.packageName) {
            HostProbe.DOC_PACKAGE -> true
            HostProbe.NOTE_PACKAGE -> false
            else -> HostSession.state.document
        }
    }

    fun toggleFromPluginButton(mode: String?) {
        Main.post {
            val requestedDoc = requestedDocMode(mode)
            val attached = root?.takeIf { it.isAttachedToWindow }
            if (root != null && attached == null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toggleFromPluginButton: discard detached root")
                removeWindow()
            }
            val active = attached != null || restorePending
            Log.i(TAG, "toggleFromPluginButton active=$active requestedDocMode=$requestedDoc " +
                "rootAttached=${attached != null} pendingShow=$pendingShow toolbarRestorePending=$restorePending")
            if (active) {
                if (HostSession.state.document != requestedDoc) {
                    showNow(requestedDoc)
                    return@post
                }
                restorePending = false
                cancelScheduledRestore()
                PenGuard.setFullScreenActive(false, PenOwner.HOST_TOOLBAR_MENU)
                close()
            } else {
                Log.i(TAG, "toggleFromPluginButton: requesting entry permissions before toolbar")
                HostSession.ensureEntryPermissions { granted ->
                    Main.post {
                        if (!granted) {
                            Log.w(TAG, "toggleFromPluginButton: entry permissions denied")
                            return@post
                        }
                        showNow(requestedDoc)
                    }
                }
            }
        }
    }

    private fun openFromMosaic(): Boolean {
        if (module == null) return false
        Main.post {
            val has = hasSession()
            Log.i(TAG, "open from Mosaic session=$has rootAttached=${root?.isAttachedToWindow == true}")
            if (has) return@post
            HostSession.ensureEntryPermissions { granted ->
                Main.post {
                    if (!granted) {
                        Log.w(TAG, "open from Mosaic: entry permissions denied")
                        return@post
                    }
                    showNow(null)
                    cancelHostCloseIntent()
                }
            }
        }
        return true
    }

    private fun showNow(requestedDoc: Boolean?) {
        if (HostSession.settingsBlocksToolbar()) {
            cancelHostCloseIntent()
            HostPlugins.closePluginView()
            return
        }
        Log.i(TAG, "showToolbarNow rootAttached=${root?.isAttachedToWindow == true} " +
            "foregroundMonitor=${HostSession.foregroundMonitorRunning}")
        session = true
        restorePending = false
        cancelScheduledRestore()
        try {
            showingNow = true
            HostSession.setAppMode(requestedDoc ?: requestedDocMode(null), "toolbar show")
            showingNow = false
            collapsed = false
            removeWindow()
            pendingShow = true
            armHostCloseForShow()
            if (!HostSession.foregroundMonitorRunning) HostSession.startForegroundMonitor()
            createExpanded()
            ToolbarHooks.sessionShown()
        } catch (t: Throwable) {
            showingNow = false
            Log.e(TAG, "showToolbarNow: ${t.message}", t)
            scheduleRetry("showToolbarNow failed")
        }
    }

    internal fun closeForSettings() {
        removeWindow()
        for (id in activeIds.toList()) notifyModeExit(id)
        activeIds.clear()
    }

    fun close() {
        val destroy = {
            module?.emit("toolbar:closed", Arguments.createMap())
            ToolbarHooks.sessionClosed()
            session = false
            lexiStowed = false
            relayStowed = false
            WindowHooks.lexiRelease(LEXI_HOLD)
            HostSession.stopForegroundMonitor()
            activeIds.clear()

            parkReasons.filter { it != REASON_PANEL }.forEach(FloatWindows::unstowAll)
            parkReasons.clear()

            FloatWindows.unstowAll(HOST_STOW)
            restorePending = false
            terminateAfterPluginMenuClose = false
            cancelScheduledRestore()
            removeWindow()
            busy(false)
            Panels.hideAll()
            FloatWindows.closeForSurfaceChange("toolbar closed", all = true)
            HostPlugins.closePluginView()
        }
        if (Main.isMain) destroy() else Main.post(destroy)
    }

    private fun windowManager(): WindowManager = App.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun loadPosition() {
        if (positionLoaded) return
        val prefs = Prefs.of("toolbar")
        stickyX = prefs.getInt("x", -1)
        stickyY = prefs.getInt("y", -1)
        positionLoaded = true
    }

    private fun savePosition(x: Int, y: Int) {
        stickyX = x
        stickyY = y
        positionLoaded = true
        panel?.refreshCollapseIconSide()
        Prefs.of("toolbar").edit().putInt("x", x).putInt("y", y).apply()
    }

    @Suppress("DEPRECATION")
    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun relayout() {
        val view = root ?: return
        val lp = params ?: return
        try {
            windowManager().updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "relayout: ${e.message}")
        }
    }

    private fun moveWindow(x: Int, y: Int) {
        val lp = params ?: return
        lp.x = x
        lp.y = y
        panel?.windowX = x
        panel?.windowY = y
        relayout()
    }

    private fun createHandle() {
        val (screenW, screenH) = Style.screenSize()
        val h = ToolbarHandle(dockLeft, gestures)
        val lp = overlayParams(h.width, h.height).apply {
            x = if (dockLeft) 0 else screenW - h.width
            y = (stickyY.takeIf { it >= 0 } ?: (screenH / 2 - h.height / 2)).coerceIn(0, (screenH - h.height).coerceAtLeast(0))
        }
        try {
            windowManager().addView(h.view, lp)
        } catch (t: Throwable) {
            Log.w(TAG, "collapsed handle addView failed: ${t.message}", t)
            throw t
        }
        handle = h
        panel = null
        root = h.view
        params = lp
        h.view.post { avoidMosaicToolbar("collapsed shown") }
        PenGuard.track("toolbar", h.view)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "collapsed handle shown, side=${if (dockLeft) "left" else "right"}")
    }

    private fun createExpanded() {
        if (module == null) {
            pendingShow = false
            return
        }
        if (HostSession.settingsBlocksToolbar()) {
            pendingShow = false
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "createExpandedToolbar: config panel owns screen")
            return
        }
        if (root?.isAttachedToWindow == true) {
            pendingShow = false
            return
        }
        if (root != null) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "createExpandedToolbar: clearing detached root")
            root = null
        }
        pendingShow = true
        try {
            createExpandedInternal()
        } catch (t: Throwable) {
            Log.e(TAG, "createExpandedToolbar build failed: ${t.message}", t)
            clearBuildForRetry()
            scheduleRetry("view construction failed")
        }
    }

    private fun createExpandedInternal() {
        val (_, screenH) = Style.screenSize()
        loadPosition()
        val p = ToolbarPanel(layout(), gestures)
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = if (stickyX >= 0) stickyX else ToolbarMetrics.dp(24)
            y = if (stickyY >= 0) stickyY else screenH / 2 - ToolbarMetrics.dp(80)
        }
        p.windowX = lp.x
        p.windowY = lp.y
        try {
            windowManager().addView(p.view, lp)
        } catch (t: Throwable) {
            Log.w(TAG, "createExpandedToolbar addView failed: ${t.message}", t)
            clearBuildForRetry()
            scheduleRetry("WindowManager.addView failed", RETRY_AFTER_ADD_FAILED_MS)
            return
        }
        panel = p
        handle = null
        root = p.view
        params = lp
        p.view.post {
            keepReachable("expanded shown")
            avoidMosaicToolbar("expanded shown")
        }
        pendingShow = false
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "createExpandedToolbar addView complete x=${lp.x} y=${lp.y}")
        try {
            HostSession.startForegroundMonitor()
        } catch (t: Throwable) {
            Log.w(TAG, "toolbar monitor start deferred: ${t.message}", t)
        }
        try {
            PenGuard.track("toolbar", p.view)
        } catch (t: Throwable) {
            Log.w(TAG, "toolbar pen guard tracking deferred: ${t.message}", t)
        }
        scheduleHostCloseAfterShown()
    }

    private fun clearBuildForRetry() {
        val view = root
        if (view != null) {
            DragFrame.endNow(view)
            try {
                windowManager().removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "clear build: ${e.message}")
            }
        }
        handle?.dispose()
        root = null
        panel = null
        handle = null
        params = null
    }

    private fun scheduleRetry(reason: String, delayMs: Long = RETRY_DELAY_MS) {
        retryRunnable?.let(Main.handler::removeCallbacks)
        val generation = buildGeneration
        val retry = Runnable {
            retryRunnable = null
            val attached = root?.isAttachedToWindow == true
            if (generation != buildGeneration || module == null || attached || !pendingShow || !HostSession.state.inHostApp) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "toolbar retry cancelled reason=$reason generation=$generation current=$buildGeneration " +
                        "root=${root != null} pending=$pendingShow inNote=${HostSession.state.inHostApp}")
                }
                return@Runnable
            }
            createExpanded()
        }
        retryRunnable = retry
        Main.handler.postDelayed(retry, delayMs)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar retry scheduled reason=$reason delayMs=$delayMs generation=$generation")
    }

    private fun removeWindow() {
        buildGeneration += 1L
        retryRunnable?.let(Main.handler::removeCallbacks)
        retryRunnable = null
        cancelHostCloseIntent()
        pendingShow = false
        val view = root
        if (view != null) {
            DragFrame.endNow(view)
            try {
                windowManager().removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "removeView: ${e.message}")
            }
            root = null
        }
        handle?.dispose()
        panel = null
        handle = null
        params = null
        handleDragging = false
    }

    private fun restoreWindow() {
        if (collapsed) createHandle() else createExpanded()
    }

    private fun ownsHostEntry(): Boolean = hostClosePending && hostCloseGeneration == buildGeneration

    private fun cancelScheduledHostClose() {
        hostCloseRunnable?.let(Main.handler::removeCallbacks)
        hostCloseRunnable = null
    }

    private fun cancelHostCloseIntent() {
        cancelScheduledHostClose()
        hostClosePending = false
        hostCloseGeneration = -1L
    }

    private fun armHostCloseForShow() {
        cancelScheduledHostClose()
        hostClosePending = true
        hostCloseGeneration = buildGeneration
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar host hand-off armed generation=$buildGeneration")
    }

    private fun scheduleHostCloseAfterShown() {
        if (!ownsHostEntry()) return
        val generation = buildGeneration
        cancelScheduledHostClose()
        val close = Runnable {
            hostCloseRunnable = null
            if (module == null || !ownsHostEntry() || buildGeneration != generation) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar host hand-off stale generation=$generation")
                return@Runnable
            }
            val view = root
            if (view == null || !view.isAttachedToWindow) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar host hand-off deferred: root not attached")
                return@Runnable
            }
            hostClosePending = false
            hostCloseGeneration = -1L
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar host hand-off closing host generation=$generation")
            HostPlugins.closePluginView()
        }
        hostCloseRunnable = close
        Main.handler.postDelayed(close, HOST_CLOSE_DELAY_MS)
    }

    private fun handoffPending(visible: Boolean): Boolean =
        pendingShow || ownsHostEntry() || hostCloseRunnable != null ||
            (visible && restorePending && !HostSession.state.mosaicVisible)

    private fun inferDockSide() {
        val lp = params ?: return
        val vw = panel?.view?.let { v -> v.measuredWidth.takeIf { it > 0 } ?: v.width.takeIf { it > 0 } } ?: return
        val screenW = Style.screenSize().first
        dockLeft = lp.x <= screenW - (lp.x + vw)
    }

    private fun switchToCollapsed() {
        if (collapsed && handle != null) return
        inferDockSide()
        collapsed = true
        relayStowed = false
        lexiStowed = false
        removeWindow()
        createHandle()
        WindowHooks.lexiRelease(LEXI_HOLD)
    }

    private fun switchToExpanded() {
        if (!collapsed && panel != null) return
        relayStowed = false
        lexiStowed = false
        if (HostHooks.lexiSmallOpen()) WindowHooks.lexiHold(LEXI_HOLD)
        if (ToolbarHooks.relaySmallUp()) ToolbarHooks.closeRelaySmall()
        if (preDockX >= 0) {
            savePosition(preDockX, preDockY)
            preDockX = -1
            preDockY = -1
        }
        collapsed = false
        removeWindow()
        createExpanded()
        ToolbarHooks.unstowPaletteBubble()
    }

    private fun stowHeld() {
        loadPosition()
        if (stickyX >= 0) {
            dockLeft = stickyX <= Style.screenSize().first / 2
            preDockX = stickyX
            preDockY = stickyY
        }
        collapsed = true
        WindowHooks.lexiRelease(LEXI_HOLD)
    }

    private fun dockToNearestEdge() {
        val lp = params ?: return
        val screenW = Style.screenSize().first
        val vw = panel?.view?.let { v -> v.measuredWidth.takeIf { it > 0 } ?: v.width.takeIf { it > 0 } } ?: 0
        dockLeft = lp.x + vw / 2 <= screenW / 2
        preDockX = lp.x
        preDockY = lp.y
        savePosition(if (dockLeft) 0 else screenW - vw, lp.y)
        switchToCollapsed()
    }

    private fun collapseKept(reason: String) {
        if (collapsed) return
        if (root != null && panel != null) dockToNearestEdge()
        else if (session) stowHeld()
        else return
        Log.i(TAG, "toolbar collapsed ($reason)")
    }

    private fun stowForRelay() {
        if (!collapsed) {
            if (root != null && panel != null) {
                dockToNearestEdge()
                relayStowed = collapsed
                return
            }
            stowHeld()
            relayStowed = true
        }
        if (root == null) restore("relay small window")
    }

    private fun unstowAfterRelay() {
        if (!relayStowed) return
        relayStowed = false
        if (!collapsed) return
        if (HostHooks.lexiSmallOpen()) {
            lexiStowed = true
            return
        }
        expandBack()
    }

    private fun stowForLexi() {
        if (!session && root == null) return
        if (!collapsed) {
            if (root != null && panel != null) {
                dockToNearestEdge()
                lexiStowed = collapsed
                return
            }
            stowHeld()
            lexiStowed = true
        }
        if (root == null) restore("lexi small window")
    }

    private fun unstowAfterLexi() {
        if (!lexiStowed) return
        lexiStowed = false
        if (!collapsed) return
        if (ToolbarHooks.relaySmallShowing()) {
            relayStowed = true
            return
        }
        expandBack()
    }

    private fun expandBack() {
        if (root != null && handle != null) {
            switchToExpanded()
            return
        }
        if (preDockX >= 0) {
            savePosition(preDockX, preDockY)
            preDockX = -1
            preDockY = -1
        }
        collapsed = false
        restore("unstow")
    }

    private fun avoidMosaicToolbar(reason: String) {
        if (!HostSession.state.mosaicVisible) return
        val rects = MosaicLink.toolbarRects()
        if (rects.isEmpty()) return
        val lp = params ?: return
        val view = root ?: return
        if (!view.isAttachedToWindow) return
        val vw = view.measuredWidth.takeIf { it > 0 } ?: view.width
        val vh = view.measuredHeight.takeIf { it > 0 } ?: view.height
        if (vw <= 0 || vh <= 0) {
            view.post { avoidMosaicToolbar("$reason:measured") }
            return
        }
        val (screenW, screenH) = Style.screenSize()
        val margin = ToolbarMetrics.dp(8)
        fun overlaps(x: Int, y: Int): Boolean {
            val me = Rect(x - margin, y - margin, x + vw + margin, y + vh + margin)
            return rects.any { Rect.intersects(me, it) }
        }
        if (!overlaps(lp.x, lp.y)) return
        val maxX = (screenW - vw).coerceAtLeast(0)
        val maxY = (screenH - vh).coerceAtLeast(0)
        var best: IntArray? = null
        var bestCost = Int.MAX_VALUE
        for (r in rects) {
            val candidates = mutableListOf(
                intArrayOf(lp.x, (r.bottom + margin).coerceIn(0, maxY)),
                intArrayOf(lp.x, (r.top - margin - vh).coerceIn(0, maxY)),
            )
            if (!collapsed) {
                candidates += intArrayOf((r.right + margin).coerceIn(0, maxX), lp.y)
                candidates += intArrayOf((r.left - margin - vw).coerceIn(0, maxX), lp.y)
            }
            for (c in candidates) {
                if (overlaps(c[0], c[1])) continue
                val cost = abs(c[0] - lp.x) + abs(c[1] - lp.y)
                if (cost < bestCost) {
                    bestCost = cost
                    best = c
                }
            }
        }
        val target = best ?: return
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "avoid Mosaic toolbar[$reason]: ${lp.x},${lp.y} -> ${target[0]},${target[1]}")
        moveWindow(target[0], target[1])
    }

    private fun keepReachable(reason: String) {
        if (collapsed) return
        val lp = params ?: return
        val view = root ?: return
        if (!view.isAttachedToWindow) return
        val vw = view.measuredWidth.takeIf { it > 0 } ?: view.width
        val vh = view.measuredHeight.takeIf { it > 0 } ?: view.height
        if (vw <= 0 || vh <= 0) {
            view.post { keepReachable("$reason:measured") }
            return
        }
        val (screenW, screenH) = Style.screenSize()
        val minVisible = ToolbarMetrics.dp(MIN_VISIBLE_DP)
        val x = when {
            lp.x > screenW - minVisible -> (screenW - vw).coerceAtLeast(0)
            lp.x + vw < minVisible -> 0
            else -> lp.x
        }
        val y = when {
            lp.y > screenH - minVisible -> (screenH - vh).coerceAtLeast(0)
            lp.y + vh < minVisible -> 0
            else -> lp.y
        }
        if (x == lp.x && y == lp.y) return
        Log.i(TAG, "toolbar off screen[$reason]: ${lp.x},${lp.y} -> $x,$y size=${vw}x$vh screen=${screenW}x$screenH")
        moveWindow(x, y)
    }

    private fun screenRect(view: View): Rect {
        val at = IntArray(2).also(view::getLocationOnScreen)
        return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    private fun markForRestore(parkGuards: Boolean = true) {
        if (parkGuards) PenGuard.beginPark("hide for restore")
        restorePending = restorePending || root != null || session
    }

    private fun park() {
        cancelScheduledRestore()
        markForRestore()
        removeWindow()
    }

    private fun parkForPanel() {
        parkReasons += REASON_PANEL
        if (Panels.toolbarRectParked()) {
            park()
        } else {
            markForRestore(parkGuards = false)
            removeWindow()
        }
    }

    private fun scheduleRestore(delay: Long, reason: String) {
        pendingRestoreReason = reason
        Main.handler.removeCallbacks(restoreRunnable)
        Main.handler.postDelayed(restoreRunnable, delay)
    }

    private fun cancelScheduledRestore() {
        Main.handler.removeCallbacks(restoreRunnable)
    }

    private fun suspendPanels() {
        if (HostSession.settingsBlocksToolbar()) return
        Panels.suspendAll()
    }

    private fun resumePanels() {
        if (!overlaysSuspended()) Panels.resumeAll()
    }

    private fun stowFloats() = FloatWindows.stowAll(HOST_STOW)

    private fun unstowFloats() {
        FloatWindows.unstowAll(HOST_STOW)
        FloatWindows.reattach()
    }

    private fun currentBlockers(): Set<String> {
        val s = HostSession.state
        val out = LinkedHashSet<String>()
        if (HostSession.settingsBlocksToolbar()) out += "settings"
        if (!s.inHostApp) out += "foreign-app"
        if (HostLogMonitor.isSubviewOpen()) out += "host-subview"
        if (HostLogMonitor.isToolbarMenuOpen()) out += "host-menu"

        if (SelectionHold.applyInFlight || SelectionHold.selectionHeld) out += REASON_PASTE
        if (MosaicLink.chromeMenuOpen) out += "mosaic-menu"
        if (MosaicLink.padOpen) out += "mosaic-pad"
        if (MosaicLink.quickSwitchOpen) out += "mosaic-quick-switch"
        if (ToolbarHooks.lexiFullScreen()) out += REASON_LEXI
        out += parkReasons
        for ((name, test) in blockers) if (test()) out += name
        return out
    }

    private fun evaluateRestore(reason: String) {
        if (module == null) return
        val s = HostSession.state
        val early = currentBlockers().filterNot { it == REASON_PANEL }
        if (early.isNotEmpty()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "restore[$reason] blocked by $early")
            return
        }
        if (s.mosaicVisible && MosaicLink.boardVisible) {
            resumePanels()
            if (!HostSession.overlayOwnsScreen()) {
                if (root == null) rebuildForMosaic(true) else recreateSurfaceFloats("restore evaluated[$reason] (mosaic)", raise = false)
            }
            restorePending = false
            PenGuard.endPark("restore evaluated[$reason] (mosaic)")
            return
        }
        if (s.pluginViewOpen) return
        if (HostPenSignals.insertImageFlowActive) return
        if (HostLogMonitor.isLassoMenuOpen() && !restoreOverHostLasso) return
        resumePanels()
        if (!HostSession.overlayOwnsScreen()) {
            WindowHooks.lexiRelease("docCapture")
            restoreOverHostLasso = false
            if (restorePending && root == null && session) restoreWindow()
            restorePending = false
            unstowFloats()
            PenGuard.endPark("restore evaluated[$reason]")
        }
    }

    private fun recreateSurfaceFloats(reason: String, raise: Boolean) {
        if (module == null) return
        if (HostSession.overlayOwnsScreen() || MosaicLink.chromeMenuOpen || MosaicLink.padOpen ||
            MosaicLink.quickSwitchOpen || WindowHooks.lexiOpen()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "recreateSurfaceBubbles[$reason]: skipped, overlay or Mosaic menu owns screen")
            return
        }
        if (!HostSession.state.inHostApp) {
            stowFloats()
            return
        }

        if (raise) stowFloats()
        unstowFloats()
    }

    private fun onHostState(old: HostState, new: HostState) {
        if (old.document != new.document && !showingNow) switchAppMode(new.document)
        if (old.inHostApp != new.inHostApp) {
            if (!new.inHostApp) {
                markForRestore(parkGuards = false)
                terminateAfterPluginMenuClose = false
                Main.post {
                    removeWindow()
                    suspendPanels()
                    stowFloats()
                }
            } else {
                scheduleRestore(HostSession.RESTORE_DELAY_SHORT, "returned to note canvas")
            }
        }
        if (old.mosaicVisible != new.mosaicVisible || old.mosaicSurface != new.mosaicSurface || old.mosaicNoteRef != new.mosaicNoteRef) {
            onMosaicBoard(old, new)
        }
    }

    private fun switchAppMode(document: Boolean) {
        if (!hasSession()) return
        val wasCollapsed = collapsed
        val s = HostSession.state
        val blocked = !s.inHostApp || HostSession.settingsBlocksToolbar() ||
            HostLogMonitor.isSubviewOpen() || HostLogMonitor.isToolbarMenuOpen() ||
            s.pluginViewOpen || HostSession.overlayOwnsScreen()
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar app mode=${if (document) "doc_notipal" else "note_notipal"} blocked=$blocked")
        if (blocked) {
            restorePending = true
            park()
            return
        }
        restorePending = false
        cancelScheduledRestore()
        removeWindow()
        if (wasCollapsed) {
            createHandle()
        } else {
            pendingShow = true
            createExpanded()
        }
    }

    private fun onNotice(notice: HostNotice) {
        when (notice) {
            is HostNotice.Tick -> lastForeground = notice.foreground
            is HostNotice.PluginView -> onPluginView(notice)
            is HostNotice.LassoCancelled -> {
                restoreOverHostLasso = false
                if (!LassoHolds.awaitingNextSelection()) scheduleRestore(HostSession.RESTORE_DELAY_NORMAL, notice.reason)
            }
            HostNotice.InsertPlacementSettled -> scheduleRestore(HostSession.RESTORE_DELAY_NORMAL, "insert placement settled")
            is HostNotice.Module -> if (notice.attached) {

                if (root?.isAttachedToWindow != true && !restorePending) cancelHostCloseIntent()
                if (pendingMosaicRebuild) {
                    pendingMosaicRebuild = false
                    val s = HostSession.state
                    scheduleMosaicRebuild(s.mosaicVisible, s.mosaicSurface, "module attached")
                }
            } else {

                buildGeneration += 1L
                pendingShow = false
                cancelHostCloseIntent()
                retryRunnable?.let(Main.handler::removeCallbacks)
                retryRunnable = null
                mosaicRebuildRunnable?.let(Main.handler::removeCallbacks)
                mosaicRebuildRunnable = null
            }
            is HostNotice.Monitor -> if (notice.phase == MonitorPhase.STOPPED) {
                restorePending = false
                terminateAfterPluginMenuClose = false
                cancelScheduledRestore()
            }
            else -> Unit
        }
    }

    private fun onPluginView(n: HostNotice.PluginView) {
        if (!n.showing) {
            scheduleRestore(HostSession.RESTORE_DELAY_SHORT, "PluginHost hidden")
            return
        }
        if (!n.ownsHost && !n.mosaicHost) Panels.suspendAll()
        if (n.mosaicHost && BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "onPluginHostStateChanged: Mosaic board owns PluginContainer, keep toolbar")
        }
        val rootAtShowEdge = root?.takeIf { it.isAttachedToWindow }
        if (rootAtShowEdge != null && !n.toolbarOwnsHost && !n.mosaicHost) {
            markForRestore()
            val owner = module
            Main.post {
                if (module !== owner || !HostSession.state.pluginViewOpen || root !== rootAtShowEdge) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host-visible removal stale, keep current toolbar")
                    return@post
                }
                removeWindow()
                if (!n.selfInitiated) suspendPanels()
                stowFloats()
            }
        }
    }

    private fun onSignal(signal: HostSignal) {
        when (signal) {
            is HostSignal.SubviewOpened -> onSubview(true, signal.generation)
            is HostSignal.SubviewClosed -> onSubview(false, signal.generation)
            is HostSignal.ToolbarMenuChanged -> onToolbarMenu(signal.open, signal.pluginListMenu, signal.generation)
            else -> Unit
        }
    }

    private fun onSubview(open: Boolean, generation: Int) {
        if (!HostLogMonitor.isCurrentGeneration(generation)) return
        val apply = Runnable {
            if (!HostLogMonitor.isCurrentGeneration(generation) || HostLogMonitor.isSubviewOpen() != open) return@Runnable
            if (module == null) return@Runnable
            if (open) {
                markForRestore()
                if (root != null) removeWindow()
                suspendPanels()
                stowFloats()
            } else {
                scheduleRestore(HostSession.RESTORE_DELAY_NORMAL, "note subview closed")
            }
        }
        if (open) Main.handler.post(apply) else Main.handler.postDelayed(apply, HostSession.RESTORE_DELAY_NORMAL)
    }

    private fun onToolbarMenu(open: Boolean, pluginListMenu: Boolean, generation: Int) {
        if (!HostLogMonitor.isCurrentGeneration(generation)) return
        if (open) {
            Main.post {
                if (!HostLogMonitor.isCurrentGeneration(generation) || !HostLogMonitor.isToolbarMenuOpen()) return@post
                if (module == null) return@post
                val wasRunning = root != null || restorePending
                if (pluginListMenu && wasRunning && HostSession.state.buttonChannelBroken) {
                    terminateAfterPluginMenuClose = true
                    Log.w(TAG, "plugin menu opened while host button channel is broken; will terminate Notipal on close")
                }
                markForRestore()
                if (root != null) removeWindow()
                suspendPanels()
                stowFloats()
            }
        } else {
            Main.post {
                if (!HostLogMonitor.isCurrentGeneration(generation) || HostLogMonitor.isToolbarMenuOpen()) return@post
                if (!terminateAfterPluginMenuClose) {
                    scheduleRestore(HostSession.RESTORE_DELAY_LONG + HostSession.RESTORE_DELAY_SHORT, "toolbar menu closed")
                    return@post
                }
                Main.handler.postDelayed({
                    if (!HostLogMonitor.isCurrentGeneration(generation) ||
                        HostLogMonitor.isToolbarMenuOpen() || !terminateAfterPluginMenuClose) return@postDelayed
                    if (module == null) return@postDelayed
                    terminateAfterPluginMenuClose = false
                    restorePending = false
                    cancelScheduledRestore()
                    Log.w(TAG, "plugin menu closed with broken host button channel; terminating Notipal")
                    close()
                }, HostSession.RESTORE_DELAY_LONG)
            }
        }
    }

    private fun onPanelsActive(any: Boolean) {
        if (module == null) return
        if (any) {
            yieldFor(REASON_PANEL)
        } else {
            parkReasons.remove(REASON_PANEL)
            scheduleRestore(HostSession.RESTORE_DELAY_NORMAL, "native panel closed")
        }
    }

    private fun onRotation(outcome: RotationOutcome) {

        root?.let(DragFrame::endNow)
        if (outcome.onHandwritingPage) {
            val hadWindow = root != null
            collapseKept(REASON_ROTATION)

            if (!hadWindow && outcome.floatsClosed.isNotEmpty() && root == null) restore("rotation closed floats")
        }
        if (outcome.panelClosed && root == null && session) restore("panel closed by rotation")
        val lp = params
        val view = root
        if (lp != null && view != null) {
            val (screenW, screenH) = Style.screenSize()
            if (collapsed && handle != null) {
                val w = view.width.takeIf { it > 0 } ?: ToolbarMetrics.dp(ToolbarMetrics.COLLAPSED_HIT_WIDTH_DP)
                val h = view.height.takeIf { it > 0 } ?: ToolbarMetrics.dp(ToolbarMetrics.COLLAPSED_HEIGHT_DP)
                moveWindow(if (dockLeft) 0 else screenW - w, lp.y.coerceIn(0, (screenH - h).coerceAtLeast(0)))
            } else if (!collapsed && panel != null) {
                relayout()
                keepReachable("display changed")
            }
        }
    }

    private var pendingMosaicRebuild = false

    private fun onMosaicBoard(old: HostState, new: HostState) {
        val visible = new.mosaicVisible
        if (!visible && !old.mosaicVisible) return
        if (old.mosaicVisible != visible) {
            FloatWindows.closeForSurfaceChange(if (visible) "mosaic opened" else "mosaic closed")
        }
        surfaceBubbleRaisePending = visible && (surfaceBubbleRaisePending || !old.mosaicVisible)
        if (visible) {
            restorePending = false
            cancelScheduledRestore()
        }
        if (module == null) {
            pendingMosaicRebuild = true
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onMosaicVisibilityChanged: module unavailable visible=$visible, rebuild pending")
            return
        }
        pendingMosaicRebuild = false
        scheduleMosaicRebuild(visible, new.mosaicSurface, "board state")
    }

    private fun scheduleMosaicRebuild(visible: Boolean, surface: String, reason: String) {
        mosaicRebuildRunnable?.let(Main.handler::removeCallbacks)
        var deferrals = 0
        val task = object : Runnable {
            override fun run() {
                val s = HostSession.state
                if (module == null || s.mosaicVisible != visible || s.mosaicSurface != surface) return

                val forceBoardRebuild = visible && deferrals >= 8
                if (handoffPending(visible) && !forceBoardRebuild) {
                    deferrals++
                    Main.handler.postDelayed(this, HostSession.RESTORE_DELAY_SHORT)
                    return
                }
                mosaicRebuildRunnable = null
                rebuildForMosaic(visible)
                PenGuard.republishMosaicSnapshot("toolbar rebuild: $reason")
            }
        }
        mosaicRebuildRunnable = task
        Main.handler.postDelayed(task, HostSession.RESTORE_DELAY_SHORT)
    }

    private fun mosaicExitSwapBlocked(): Boolean {
        val s = HostSession.state
        return currentBlockers().any { it != REASON_PANEL } || s.pluginViewOpen || HostLogMonitor.isLassoMenuOpen() ||
            HostSession.overlayOwnsScreen() || HostPenSignals.insertImageFlowActive
    }

    private fun rebuildForMosaic(visible: Boolean) {
        if (HostSession.settingsBlocksToolbar()) return
        if (visible && (MosaicLink.chromeMenuOpen || MosaicLink.quickSwitchOpen)) {
            park()
            return
        }
        val later = { Main.handler.postDelayed({ recreateSurfaceFloats("mosaic closed", raise = false) }, HostSession.RESTORE_DELAY_SHORT) }
        if (hasSession()) {
            if (!visible) {
                if (root != null && !mosaicExitSwapBlocked()) {
                    restorePending = false
                    cancelScheduledRestore()
                    removeWindow()
                    if (collapsed) createHandle() else {
                        pendingShow = true
                        createExpanded()
                    }
                    Log.i(TAG, "toolbar swapped in place after mosaic closed")
                } else {
                    park()
                    scheduleRestore(HostSession.RESTORE_DELAY_SHORT, "mosaic closed")
                }
                later()
                return
            }
            restorePending = false
            cancelScheduledRestore()
            removeWindow()
            if (collapsed) createHandle() else {
                pendingShow = true
                createExpanded()
            }
            Log.i(TAG, "toolbar rebuilt for mosaic canvas")
        } else if (!visible) {
            later()
            return
        }
        if (visible) {
            val raise = surfaceBubbleRaisePending
            surfaceBubbleRaisePending = false
            recreateSurfaceFloats("mosaic opened", raise)
        }
    }

    private fun onMosaicEvent(event: MosaicEvent) {
        when (event) {
            is MosaicEvent.ChromeMenu -> Main.post {
                if (MosaicLink.chromeMenuOpen != event.open || module == null) return@post
                if (event.open) {
                    park()
                    stowFloats()
                } else {
                    scheduleRestore(HostSession.RESTORE_DELAY_SHORT, "mosaic menu closed")
                }
            }

            is MosaicEvent.Motion -> Main.post {
                if (MosaicLink.motion != event.on || module == null) return@post
                if (event.on) {

                    val wasRelayStowed = relayStowed
                    relayStowed = false
                    val relay = ToolbarHooks.stowRelaySmall()
                    if (!relay) relayStowed = wasRelayStowed
                    stowFloats()
                    ToolbarHooks.foldPenBar()
                    suspendPanels()
                    collapseKept("mosaic motion")
                    Log.i(TAG, "Mosaic motion: floats hidden, toolbar collapsed, pen bar folded" +
                        if (relay) ", AIRelay small window to its bubble" else "")
                } else {
                    scheduleRestore(HostSession.RESTORE_DELAY_SHORT, "mosaic motion ended")
                }
            }
            MosaicEvent.TextEditingOpened -> Main.post { if (module != null) suspendPanels() }
            is MosaicEvent.Pad -> Main.post {
                if (MosaicLink.padOpen != event.open) return@post
                if (event.open) putAwayOverlays() else bringBackOverlays("mosaic pad closed")
            }
            is MosaicEvent.QuickSwitch -> Main.post {
                if (MosaicLink.quickSwitchOpen != event.open) return@post
                if (event.open) putAwayOverlays() else bringBackOverlays("mosaic quick switch closed")
            }
            MosaicEvent.ToolbarRects -> Main.post { avoidMosaicToolbar("mosaic toolbar rects") }
            else -> Unit
        }
    }

    fun putAwayOverlays() {
        if (module != null) {
            park()
            suspendPanels()
        } else {
            cancelScheduledRestore()
            markForRestore()
        }
        stowFloats()
    }

    fun putAwayFloats() {
        suspendPanels()
        stowFloats()
    }

    fun bringBackOverlays(reason: String) {
        if (module != null) scheduleRestore(HostSession.RESTORE_DELAY_SHORT, reason)
    }

    private fun onTwoFingerDoubleTap() {
        val s = HostSession.state
        val onPage = lastForeground?.isHostPage == true
        val onMosaic = MosaicLink.boardVisible && !MosaicLink.padOpen && !MosaicLink.chromeMenuOpen && !MosaicLink.quickSwitchOpen
        val pluginCovers = s.pluginViewOpen && !onMosaic
        if (onPage && !SystemKeyboard.shown && !pluginCovers && ToolbarHooks.relaySmallUp()) {
            relayStowed = false
            ToolbarHooks.stowPaletteBubble()
            if (ToolbarHooks.stowRelaySmall()) {
                Log.i(TAG, "two-finger double tap: AIRelay small window to its bubble, toolbar collapsed")
                HostPenSignals.scheduleForegroundReassert("two-finger double tap")
                return
            }
        }
        val view = root
        val skip = when {
            !onPage -> "not on a note or document"
            collapsed -> "already collapsed"
            view == null || !view.isAttachedToWindow || view.visibility != View.VISIBLE || panel == null -> "toolbar not shown"
            pluginCovers -> "plugin view showing"
            Panels.anyShowing() -> "panel showing"
            SystemKeyboard.shown -> "keyboard up"
            handleDragging || panel?.dragging == true -> "toolbar dragging"
            else -> null
        }
        if (skip != null) {

            if (onPage && collapsed) ToolbarHooks.stowPaletteBubble()
            Log.i(TAG, "two-finger double tap ignored: $skip")
            return
        }
        Log.i(TAG, "two-finger double tap: toolbar collapses, palette bubble put away, pen bar folded")
        ToolbarHooks.stowPaletteBubble()
        dockToNearestEdge()
        HostPenSignals.scheduleForegroundReassert("two-finger double tap")
    }

    private fun notifyModeExit(id: String) {
        val onExit = Tools.get(id)?.onExit
        if (onExit != null) runHandler(id) { onExit() } else emitAction(id, "exit")
    }

    private fun emitAction(tool: String, gesture: String, slot: Int? = null) {
        val owner = module ?: return
        owner.emit("toolbar:action", Arguments.createMap().apply {
            putString("tool", tool)
            putString("gesture", gesture)
            if (slot != null) putInt("slot", slot)
            putMap("host", HostSession.toMap())
        })
    }

    private fun clearStaleHostStow(why: String) {
        if (!FloatWindows.hasReason(HOST_STOW)) return
        if (HostSession.overlayOwnsScreen() || MosaicLink.chromeMenuOpen || MosaicLink.padOpen ||
            MosaicLink.quickSwitchOpen || MosaicLink.motion) return
        Log.i(TAG, "stale host stow cleared on $why")
        unstowFloats()
    }

    private val gestures = object : ToolbarGestures {
        override fun onToolTap(tool: Tool, view: View) {
            clearStaleHostStow("tap ${tool.id}")

            val anchor = screenRect(view)
            if (tool.latches) {
                val retap = Tools.get(tool.id)?.onRetap
                if (tool.id in activeIds && retap != null && runCatching { retap() }.getOrElse {
                        Log.e(TAG, "retap ${tool.id} failed", it)
                        false
                    }) return
                if (activeIds.remove(tool.id)) {
                    refresh("mode exit ${tool.id}")
                    notifyModeExit(tool.id)
                    return
                }
                activeIds.add(tool.id)
                refresh("mode enter ${tool.id}")
            } else {
                panel?.flash(view)
            }
            val handler = Tools.get(tool.id)?.onTap
            if (handler != null) {
                runHandler(tool.id) { handler(ToolTap(tool.id, HostSession.state, anchor)) }
            } else {
                emitAction(tool.id, "tap")
            }
        }

        override fun onToolLongPress(tool: Tool, view: View) {
            clearStaleHostStow("long press ${tool.id}")
            val handler = Tools.get(tool.id)?.onLongPress
            if (handler != null) {
                runHandler(tool.id) { handler(ToolTap(tool.id, HostSession.state, screenRect(view))) }
            } else {
                emitAction(tool.id, "longPress")
            }
        }

        override fun onClipTap(slot: Int) = emitAction(Tools.CLIP_SLOT, "tap", slot)
        override fun onClipLongPress(slot: Int) = emitAction(Tools.CLIP_SLOT, "longPress", slot)
        override fun onCollapseTap() = dockToNearestEdge()

        override fun onDragBegin(view: View, x: Int, y: Int) = DragFrame.begin(view, x, y)

        override fun onDragMove(view: View, x: Int, y: Int) {
            val lp = params ?: return
            lp.x = x
            lp.y = y
            panel?.windowX = x
            panel?.windowY = y
            if (!DragFrame.move(view, x, y)) relayout()
        }

        override fun onDragEnd(view: View, x: Int, y: Int) {
            relayout()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "dragEnd: x=$x y=$y expanded=true")

            DragFrame.end(view)
            savePosition(x, y)
            avoidMosaicToolbar("drag end")
        }

        override fun onHandleOpen() = switchToExpanded()
        override fun onHandleLongPress() = close()
        override fun onHandleDragging(dragging: Boolean) {
            handleDragging = dragging
        }
    }

    private fun runHandler(toolId: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "tool handler failed: $toolId", e)
        }
    }

    fun exitMode(toolId: String) = setActive(toolId, false)

    private var busyView: View? = null

    fun busy(on: Boolean, text: String? = null) {
        Main.post {
            val shown = busyView
            if (!on) {
                if (shown != null) {
                    busyView = null
                    try {
                        windowManager().removeView(shown)
                    } catch (e: Exception) {
                        Log.w(TAG, "busy remove: ${e.message}")
                    }
                }
                return@post
            }
            if (shown != null) {
                (shown as? FrameLayout)?.let { (it.getChildAt(0) as? TextView)?.text = text ?: Strings.get("busy_processing_title") }
                return@post
            }
            val label = TextView(App.context).apply {
                this.text = text ?: Strings.get("busy_processing_title")
                textSize = 24f
                setTextColor(Style.paper)
                setBackgroundColor(Style.ink)
                val pad = Style.dp(12)
                setPadding(pad * 2, pad, pad * 2, pad)
            }
            val frame = FrameLayout(App.context).apply {
                addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
            val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            try {
                windowManager().addView(frame, lp)
                busyView = frame
            } catch (e: Exception) {
                Log.w(TAG, "busy show: ${e.message}")
            }
        }
    }
}
