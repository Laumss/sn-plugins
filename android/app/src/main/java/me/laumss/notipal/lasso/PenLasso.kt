package me.laumss.notipal.lasso

import android.graphics.Rect
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.StateMachine
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostLogMonitor
import me.laumss.notipal.host.HostNotice
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.HostSignal
import me.laumss.notipal.host.LassoHolds
import me.laumss.notipal.host.MosaicPermissionResult
import me.laumss.notipal.host.PageRender
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.host.PenGuardTiming
import me.laumss.notipal.host.PenOwner
import me.laumss.notipal.mosaic.MosaicEvent
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.LassoLayer
import me.laumss.notipal.ui_common.Panels
import java.io.File

object LassoHooks {

    @Volatile var regionCapture: ((mode: String, fromBubble: Boolean, box: Rect) -> Unit)? = null

    @Volatile var regionClosed: ((fromBubble: Boolean) -> Unit)? = null

    @Volatile var regionLeft: ((fromBubble: Boolean) -> Unit)? = null

    @Volatile var openImagePanel: (() -> Unit)? = null

    @Volatile var syncClipboard: (() -> Unit)? = null
}

sealed class LassoState {
    object Idle : LassoState() {
        override fun toString() = "Idle"
    }

    data class Framing(val mode: String, val fromBubble: Boolean) : LassoState()

    data class Capturing(val mode: String, val fromBubble: Boolean) : LassoState()

    data class PenDrawing(val requestId: String, val screenW: Int, val screenH: Int) : LassoState()

    data class PenConfirming(val requestId: String, val screenW: Int, val screenH: Int) : LassoState()
}

sealed class LassoEvent {
    data class Frame(val mode: String, val fromBubble: Boolean) : LassoEvent()
    object Picked : LassoEvent() {
        override fun toString() = "Picked"
    }
    object RegionEnded : LassoEvent() {
        override fun toString() = "RegionEnded"
    }
    data class PenBegin(val requestId: String, val screenW: Int, val screenH: Int) : LassoEvent()
    data class PenDrawn(val requestId: String) : LassoEvent()
    data class PenEnded(val requestId: String) : LassoEvent()
}

object PenLasso : StateMachine<LassoState, LassoEvent>(LassoState.Idle) {
    override val tag: String get() = TAG
    private const val TAG = "PenLasso"

    private const val ICON_IDLE = "ic_tool_lasso_ai"
    private const val ICON_TEXT = "ic_tool_lasso_text"
    private const val ICON_SEND = "ic_tool_lasso_send"

    private const val REGION_RESTORE_DELAY_MS = 350L

    @Volatile private var module: LassoModule? = null
    private var installed = false

    private var layer: LassoLayer? = null

    private var selectionSendsText = false

    private var kindRequest = 0
    private var shownIcon: String? = null

    fun install() {
        if (installed) return
        installed = true

        Toolbar.addBlocker("overlay:region-capture") { regionActive() }
        HostSession.addNoticeListener { notice -> onNotice(notice) }
        HostSession.addListener { old, new ->
            if ((old.lassoMenuOpen && !new.lassoMenuOpen) || (old.docLassoSelected && !new.docLassoSelected)) {
                onSelectionChanged(false)
            } else {
                refreshIcon()
            }
        }
        HostLogMonitor.addListener { signal ->

            if (signal is HostSignal.DocLassoStarted) {
                if (!HostLogMonitor.isCurrentGeneration(signal.generation)) return@addListener
                Main.post {
                    if (!HostLogMonitor.isCurrentGeneration(signal.generation) || !HostSession.state.document) return@post
                    onSelectionChanged(true)
                }
            }
        }
        MosaicLink.addListener { event ->
            if (event is MosaicEvent.Selection || event is MosaicEvent.BoardState) Main.post { refreshIcon() }
        }
    }

    internal fun attach(owner: LassoModule) {
        module = owner
    }

    internal fun detach(owner: LassoModule) {
        if (module === owner) module = null
    }

    override fun reduce(state: LassoState, event: LassoEvent): LassoState = when (event) {
        is LassoEvent.Frame -> if (state == LassoState.Idle) LassoState.Framing(event.mode, event.fromBubble) else state
        LassoEvent.Picked -> if (state is LassoState.Framing) LassoState.Capturing(state.mode, state.fromBubble) else state
        LassoEvent.RegionEnded ->
            if (state is LassoState.Framing || state is LassoState.Capturing) LassoState.Idle else state
        is LassoEvent.PenBegin ->
            if (state == LassoState.Idle) LassoState.PenDrawing(event.requestId, event.screenW, event.screenH) else state
        is LassoEvent.PenDrawn ->
            if (state is LassoState.PenDrawing && state.requestId == event.requestId) {
                LassoState.PenConfirming(state.requestId, state.screenW, state.screenH)
            } else {
                state
            }
        is LassoEvent.PenEnded -> if (penRequest(state) == event.requestId) LassoState.Idle else state
    }

    override fun onEnter(state: LassoState, from: LassoState) {
        val wasRegion = from is LassoState.Framing || from is LassoState.Capturing
        when (state) {
            is LassoState.Framing -> {

                Panels.setScreenOwner(this, true)
                Toolbar.putAwayOverlays()
            }
            is LassoState.PenConfirming -> {

                PenGuard.setFullScreenActive(true, PenOwner.penSelection(state.requestId))
                val id = state.requestId
                timers.after(PenGuardTiming.PEN_SELECTION_ACTION_TIMEOUT_MS) {
                    Log.i(TAG, "pen selection cancelled at deadline request=$id")
                    endPenSelection(id, cancelled = true)
                }
            }
            LassoState.Idle -> {
                if (wasRegion) Panels.setScreenOwner(this, false)
                val pen = penRequest(from)
                if (pen != null) {
                    layer?.dismissSilently()
                    layer = null
                    PenGuard.setFullScreenActive(false, PenOwner.penSelection(pen))
                }
            }
            else -> Unit
        }
    }

    private fun penRequest(state: LassoState): String? = when (state) {
        is LassoState.PenDrawing -> state.requestId
        is LassoState.PenConfirming -> state.requestId
        else -> null
    }

    private fun regionActive(): Boolean = state is LassoState.Framing || state is LassoState.Capturing

    fun startRegionCapture(mode: String, fromBubble: Boolean) = Main.post {
        if (regionActive()) {

            if (mode == "ai") LassoHooks.regionLeft?.invoke(fromBubble)
            return@post
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "startRegionCapture mode=$mode fromBubble=$fromBubble")

        penRequest(state)?.let { endPenSelection(it, cancelled = true) }
        send(LassoEvent.Frame(mode, fromBubble))
        try {
            layer?.dismissSilently()
            val overlay = LassoLayer()
            layer = overlay
            val actions = when (mode) {

                "smart" -> listOfNotNull(
                    LassoLayer.Action.SEND_TO_DEVICES,
                    LassoLayer.Action.ADD_TO_DOC_SCREENSHOTS,
                    LassoLayer.Action.OPEN_MOSAIC_CARD.takeUnless { MosaicLink.boardVisible },
                    LassoLayer.Action.CANCEL,
                )
                else -> listOf(
                    LassoLayer.Action.CONFIRM,
                    LassoLayer.Action.OPEN_IMAGE_PANEL,
                    LassoLayer.Action.ADD_TO_DOC_SCREENSHOTS,
                    LassoLayer.Action.CANCEL,
                )
            }
            overlay.show(
                actions = actions,
                onAction = { action, l, t, r, b ->
                    if (layer === overlay) layer = null
                    onRegionAction(mode, fromBubble, action, Rect(l, t, r, b))
                },
                onCancel = {
                    if (layer === overlay) layer = null
                    closeRegion(fromBubble)
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "startRegionCapture failed: ${e.message}", e)
            closeRegion(fromBubble)
        }
    }

    fun finishRegionCapture() {
        if (Main.isMain) endRegion() else Main.post { endRegion() }
    }

    private fun endRegion() {
        if (regionActive()) send(LassoEvent.RegionEnded)
    }

    private fun onRegionAction(mode: String, fromBubble: Boolean, action: LassoLayer.Action, box: Rect) {
        val captureMode = when (action) {
            LassoLayer.Action.CONFIRM -> mode
            LassoLayer.Action.SEND_TO_DEVICES -> "send"
            LassoLayer.Action.ADD_TO_DOC_SCREENSHOTS -> "docScreenshot"
            LassoLayer.Action.OPEN_IMAGE_PANEL -> {
                finishRegionCapture()
                LassoHooks.regionLeft?.invoke(fromBubble)
                Main.post { openImagePanel() }
                return
            }
            LassoLayer.Action.OPEN_MOSAIC_CARD -> "mosaic"
            LassoLayer.Action.CANCEL -> {

                closeRegion(fromBubble)
                return
            }
        }
        send(LassoEvent.Picked)
        if (action != LassoLayer.Action.ADD_TO_DOC_SCREENSHOTS && action != LassoLayer.Action.OPEN_MOSAIC_CARD) {
            capture(captureMode, fromBubble, box)
            return
        }
        val mosaic = action == LassoLayer.Action.OPEN_MOSAIC_CARD
        HostPlugins.requestStoragePermissions(
            what = if (mosaic) "insert region into Mosaic" else "save region to doc screenshots",
            onGranted = {
                if (!mosaic) {
                    capture(captureMode, fromBubble, box)
                } else {
                    HostPlugins.requestMosaicPluginPermissions { result ->
                        Main.post {
                            if (result == MosaicPermissionResult.GRANTED) {
                                capture(captureMode, fromBubble, box)
                            } else {
                                HostPlugins.showMosaicPermissionReminder(result)
                                closeRegion(fromBubble)
                            }
                        }
                    }
                }
            },
            onDenied = { closeRegion(fromBubble) },
        )
    }

    private fun capture(mode: String, fromBubble: Boolean, box: Rect) {
        val hook = LassoHooks.regionCapture
        if (hook == null) {
            notAvailable("region capture mode=$mode")
            closeRegion(fromBubble)
            return
        }
        hook(mode, fromBubble, box)
    }

    private fun closeRegion(fromBubble: Boolean) {
        finishRegionCapture()
        val hook = LassoHooks.regionClosed
        if (hook != null) {
            hook(fromBubble)
            return
        }
        Main.handler.postDelayed({ Toolbar.restore("region capture closed") }, REGION_RESTORE_DELAY_MS)
    }

    private fun openImagePanel() {
        val hook = LassoHooks.openImagePanel
        if (hook == null) {
            notAvailable("image panel")
            Toolbar.restore("region capture closed")
            return
        }
        hook()
    }

    internal fun onLongPress() {
        val hook = LassoHooks.syncClipboard
        if (hook == null) {
            notAvailable("clipboard sync long press")
            return
        }
        hook()
    }

    private fun notAvailable(what: String) {
        Log.w(TAG, "$what: not available in this build")
        Dialogs.tip(Strings.get("feature_not_ready"))
    }

    fun beginPenSelection(requestId: String) = Main.post {
        if (requestId.isBlank()) return@post
        when (val s = state) {
            is LassoState.PenDrawing -> endPenSelection(s.requestId, cancelled = true)
            is LassoState.PenConfirming -> endPenSelection(s.requestId, cancelled = true)

            is LassoState.Framing -> {
                layer?.dismissSilently()
                layer = null
                closeRegion(s.fromBubble)
            }

            is LassoState.Capturing -> {
                Log.w(TAG, "pen selection request=$requestId refused: region capture in progress")
                module?.emit("lasso:penCancel", Arguments.createMap().apply { putString("requestId", requestId) })
                return@post
            }
            LassoState.Idle -> Unit
        }
        send(LassoEvent.PenBegin(requestId, HostSession.screenWidth, HostSession.screenHeight))
        try {
            layer?.dismissSilently()
            val overlay = LassoLayer()
            layer = overlay
            overlay.show(
                actions = listOf(LassoLayer.Action.CONFIRM, LassoLayer.Action.CANCEL),
                beforeAction = { send(LassoEvent.PenDrawn(requestId)) },
                onAction = { _, l, t, r, b ->
                    if (layer === overlay) layer = null
                    if (penRequest(state) == requestId) {
                        module?.emit("lasso:penBbox", Arguments.createMap().apply {
                            putString("requestId", requestId)
                            putInt("left", l)
                            putInt("top", t)
                            putInt("right", r)
                            putInt("bottom", b)
                        })
                    }
                },
                onCancel = { endPenSelection(requestId, cancelled = true) },
                hintKey = "pen_selection_hint",
                bare = true,
            )
        } catch (error: Exception) {
            Log.e(TAG, "beginPenSelection request=$requestId", error)
            endPenSelection(requestId, cancelled = true)
        }
    }

    private fun endPenSelection(requestId: String, cancelled: Boolean): Boolean {
        if (penRequest(state) != requestId) return false
        send(LassoEvent.PenEnded(requestId))
        if (cancelled) module?.emit("lasso:penCancel", Arguments.createMap().apply { putString("requestId", requestId) })
        return true
    }

    fun finishPenSelection(requestId: String, onResult: (Boolean) -> Unit) = Main.post {
        val s = state
        val (w, h) = when (s) {
            is LassoState.PenDrawing -> s.screenW to s.screenH
            is LassoState.PenConfirming -> s.screenW to s.screenH
            else -> -1 to -1
        }
        val valid = penRequest(s) == requestId &&
            w == HostSession.screenWidth && h == HostSession.screenHeight && !MosaicLink.boardVisible
        val ended = endPenSelection(requestId, cancelled = !valid)
        onResult(valid && ended)
    }

    fun cancelPenSelection(requestId: String) = Main.post { endPenSelection(requestId, cancelled = true) }

    fun prepareLivePage(ctx: ReactApplicationContext, filePath: String, page: Int, width: Int, height: Int, onResult: (Boolean) -> Unit) {
        PageRender.readCurrentContext(ctx) { source, _ ->
            if (source == null || !Dirs.same(source.filePath, filePath) || source.pageNum != page ||
                source.pageWidth != width || source.pageHeight != height || MosaicLink.boardVisible) {
                onResult(false)
                return@readCurrentContext
            }
            Thread {
                val temporary = File(ctx.cacheDir, "selection_sync_${System.nanoTime()}.png")
                try {
                    val rendered = PageRender.renderCurrentPageBlocking(ctx, temporary.absolutePath, source, 15_000L)
                    onResult(rendered != null)
                } catch (error: Exception) {
                    Log.w(TAG, "prepareLivePage ended: ${error.message}")
                    onResult(false)
                } finally {
                    if (temporary.exists() && !temporary.delete()) Log.w(TAG, "selection sync file not deleted: $temporary")
                }
            }.apply { name = "lasso-selection-sync"; isDaemon = true }.start()
        }
    }

    private fun hostSelectionOpen(): Boolean = HostLogMonitor.isLassoMenuOpen() || HostSession.state.docLassoSelected

    private fun iconAsset(): String = when {

        MosaicLink.boardVisible -> when {
            MosaicLink.selectionStrokes > 0 -> ICON_TEXT
            MosaicLink.selectionTextCards > 0 -> ICON_SEND
            else -> ICON_IDLE
        }
        HostSession.isDocumentContext() && HostLogMonitor.isDocTextSelected() -> ICON_SEND
        !hostSelectionOpen() -> ICON_IDLE
        selectionSendsText -> ICON_SEND
        else -> ICON_TEXT
    }

    private fun refreshIcon() {
        val asset = iconAsset()
        if (asset == shownIcon) return
        shownIcon = asset
        Tools.setIcon(Tools.SMART_LASSO, asset)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "smart lasso icon -> $asset")
    }

    private fun onSelectionChanged(open: Boolean) {
        selectionSendsText = false
        val request = ++kindRequest
        refreshIcon()
        val owner = module ?: return

        if (!open || Toolbar.anchorOf(Tools.SMART_LASSO) == null || LassoHolds.applyInFlight() ||
            LassoHolds.selectionHeld()) return
        owner.emit("lasso:selectionShown", Arguments.createMap().apply { putInt("request", request) })
    }

    fun setSelectionKind(request: Int, sendsText: Boolean) = Main.post {
        if (request != kindRequest || !hostSelectionOpen()) return@post
        selectionSendsText = sendsText
        refreshIcon()
    }

    private fun onNotice(notice: HostNotice) {
        when (notice) {
            HostNotice.LassoShown -> Main.post { onSelectionChanged(true) }

            is HostNotice.Monitor -> Main.post {
                selectionSendsText = false
                refreshIcon()
            }
            else -> Unit
        }
    }
}

class LassoModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalLasso") {

    init {
        PenLasso.attach(this)
    }

    override fun initialize() {
        super.initialize()

        Tools.handle(Tools.SMART_LASSO, onTap = null, onLongPress = { PenLasso.onLongPress() })
    }

    override fun invalidate() {
        PenLasso.detach(this)
        super.invalidate()
    }

    @ReactMethod
    fun startCapture(mode: String, fromBubble: Boolean) = PenLasso.startRegionCapture(mode, fromBubble)

    @ReactMethod
    fun setSelectionKind(request: Int, sendsText: Boolean) = PenLasso.setSelectionKind(request, sendsText)

    @ReactMethod
    fun beginPenSelection(requestId: String) = PenLasso.beginPenSelection(requestId)

    @ReactMethod
    fun finishPenSelection(requestId: String, promise: Promise) =
        PenLasso.finishPenSelection(requestId) { promise.resolve(it) }

    @ReactMethod
    fun cancelPenSelection(requestId: String) = PenLasso.cancelPenSelection(requestId)

    @ReactMethod
    fun prepareLivePage(filePath: String, page: Int, width: Int, height: Int, promise: Promise) =
        PenLasso.prepareLivePage(reactApplicationContext, filePath, page, width, height) { promise.resolve(it) }
}
