package me.laumss.notipal.host

import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.widget.Toast
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.PromiseImpl
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Cancelable
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.StateMachine
import me.laumss.notipal.common.Strings
import me.laumss.notipal.mosaic.MosaicEvent
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.Style
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class Surface { NOTE, DOCUMENT, MOSAIC, OTHER }

data class HostState(
    val surface: Surface,
    val pluginViewOpen: Boolean,
    val permissionsOk: Boolean,
    val restoring: Boolean,
    val lassoMenuOpen: Boolean,
    val docTextSelected: Boolean,
    val inHostApp: Boolean = true,
    val document: Boolean = false,
    val mosaicVisible: Boolean = false,
    val mosaicSurface: String = MosaicLink.SURFACE_BOARD,
    val mosaicNoteRef: String? = null,
    val docLassoSelected: Boolean = false,
    val hostDocTextSelected: Boolean = false,
    val buttonChannelBroken: Boolean = false,
) {

    fun derive(): HostState = copy(
        surface = when {
            mosaicVisible -> Surface.MOSAIC
            !inHostApp -> Surface.OTHER
            document -> Surface.DOCUMENT
            else -> Surface.NOTE
        },
        docTextSelected = hostDocTextSelected && document && !mosaicVisible,
    )
}

sealed class HostEvent {

    data class Foreground(val inHostApp: Boolean) : HostEvent()

    data class AppMode(val document: Boolean) : HostEvent()

    data class PluginView(val showing: Boolean) : HostEvent()

    data class LassoMenu(val open: Boolean) : HostEvent()

    data class DocTextSelected(val selected: Boolean) : HostEvent()

    data class DocLasso(val selected: Boolean) : HostEvent()

    data class MosaicBoard(val visible: Boolean, val surface: String, val noteRef: String?) : HostEvent()

    data class ButtonChannel(val working: Boolean) : HostEvent()

    data class Permissions(val ok: Boolean) : HostEvent()

    data class Restoring(val on: Boolean) : HostEvent()

    object MonitorStarted : HostEvent()

    object MonitorStopped : HostEvent()
}

enum class MonitorPhase {

    STARTED,

    STOPPING,

    STOPPED,

    ORPHANED,
}

data class DisplayChange(
    val oldWidth: Int,
    val oldHeight: Int,
    val oldRotation: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val reason: String,
    val changed: Boolean,
    val foreground: ForegroundActivity?,

    val lastForegroundKey: String?,
)

sealed class HostNotice {
    data class Monitor(val phase: MonitorPhase) : HostNotice()

    data class Module(val attached: Boolean) : HostNotice()

    data class Tick(val foreground: ForegroundActivity, val hostFile: String?) : HostNotice()

    data class Display(val change: DisplayChange, val rotationSync: Boolean) : HostNotice()

    data class PluginView(
        val showing: Boolean,
        val ownsHost: Boolean = false,
        val selfInitiated: Boolean = false,
        val toolbarOwnsHost: Boolean = false,
        val mosaicHost: Boolean = false,
    ) : HostNotice()

    object LassoShown : HostNotice()

    data class LassoCancelling(val reason: String) : HostNotice()

    data class LassoCancelled(val reason: String) : HostNotice()

    data class DocLassoReleased(val reason: String) : HostNotice()

    object InsertPlacementSettled : HostNotice()
}

object LassoHolds {

    @Volatile var applyInFlight: () -> Boolean = { false }

    @Volatile var selectionHeld: () -> Boolean = { false }

    @Volatile var awaitingNextSelection: () -> Boolean = { false }
}

object HostSession : StateMachine<HostState, HostEvent>(
    HostState(
        surface = Surface.NOTE,
        pluginViewOpen = false,
        permissionsOk = true,
        restoring = false,
        lassoMenuOpen = false,
        docTextSelected = false,
    )
) {
    private const val TAG = "HostSession"

    override val tag: String get() = TAG

    private const val MONITOR_INTERVAL_MS = 800L

    const val RESTORE_DELAY_SHORT = 150L
    const val RESTORE_DELAY_NORMAL = 350L
    const val RESTORE_DELAY_LONG = 700L

    @Volatile var toolbarRestorePending: () -> Boolean = { false }

    @Volatile var toolbarWindowUp: () -> Boolean = { false }

    @Volatile var toolbarOwnsHostEntry: () -> Boolean = { false }

    @Volatile var overlayOwnsScreen: () -> Boolean = { HostHooks.anyPanelShowing() }

    @Volatile var settingsBlocksToolbar: () -> Boolean = { false }

    @Volatile var rotationActive: () -> Boolean = { false }

    @Volatile var rotationSyncWanted: (DisplayChange) -> Boolean = { false }

    @Volatile var onTwoFingerDoubleTap: () -> Unit = {}

    @Volatile private var published: HostState = state

    @Volatile private var monitorRunning = false

    @Volatile private var module: HostModule? = null

    private var appliedDocMode: Boolean? = null

    @Volatile private var lastForegroundKey: String? = null
    @Volatile private var noteForegroundSinceMs = 0L

    @Volatile var screenWidth = 1404
        private set
    @Volatile var screenHeight = 1872
        private set
    private var lastDisplayRotation = -1
    private var displayCallbacksRegistered = false
    private var displayListenerRegistered = false

    private var windowStateMonitor: WindowStateMonitor? = null
    private var restoreBootstrapRunnable: Runnable? = null
    private var installed = false

    private val monitorTickSerial = AtomicLong()

    private val foregroundProbeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notipal-fg-probe").apply { isDaemon = true }
    }

    private val notices = CopyOnWriteArrayList<(HostNotice) -> Unit>()

    fun addNoticeListener(l: (HostNotice) -> Unit): Cancelable {
        notices.add(l)
        return Cancelable { notices.remove(l) }
    }

    private fun announce(notice: HostNotice) {
        for (l in notices) {
            try {
                l(notice)
            } catch (e: Exception) {
                Log.e(TAG, "notice listener failed: $notice", e)
            }
        }
    }

    override fun reduce(state: HostState, event: HostEvent): HostState = when (event) {
        is HostEvent.Foreground -> state.copy(inHostApp = event.inHostApp)
        is HostEvent.AppMode -> state.copy(document = event.document)
        is HostEvent.PluginView -> state.copy(pluginViewOpen = event.showing)
        is HostEvent.LassoMenu -> state.copy(lassoMenuOpen = event.open)
        is HostEvent.DocTextSelected -> state.copy(hostDocTextSelected = event.selected)
        is HostEvent.DocLasso -> state.copy(docLassoSelected = event.selected)
        is HostEvent.MosaicBoard -> state.copy(
            mosaicVisible = event.visible, mosaicSurface = event.surface, mosaicNoteRef = event.noteRef,
        )
        is HostEvent.ButtonChannel -> state.copy(buttonChannelBroken = !event.working)
        is HostEvent.Permissions -> state.copy(permissionsOk = event.ok)
        is HostEvent.Restoring -> state.copy(restoring = event.on)

        HostEvent.MonitorStarted -> state.copy(
            inHostApp = true, lassoMenuOpen = false, docLassoSelected = false, hostDocTextSelected = false,
        )
        HostEvent.MonitorStopped -> state.copy(
            lassoMenuOpen = false, docLassoSelected = false, hostDocTextSelected = false,
        )
    }.derive()

    override fun onEnter(state: HostState, from: HostState) {
        published = state
        module?.publish(toMap(state))
    }

    fun toMap(): WritableMap = toMap(published)

    private fun toMap(s: HostState): WritableMap = Arguments.createMap().apply {
        putString("surface", s.surface.name.lowercase())
        putBoolean("document", s.document)
        putBoolean("inHostApp", s.inHostApp)
        putBoolean("pluginViewOpen", s.pluginViewOpen)
        putBoolean("permissionsOk", s.permissionsOk)
        putBoolean("restoring", s.restoring)
        putBoolean("lassoMenuOpen", s.lassoMenuOpen)
        putBoolean("docTextSelected", s.docTextSelected)
        putBoolean("docLassoSelected", s.docLassoSelected)
        putString("mosaicSurface", s.mosaicSurface)
        val noteRef = s.mosaicNoteRef
        if (noteRef == null) putNull("mosaicNoteRef") else putString("mosaicNoteRef", noteRef)
    }

    fun isDocumentContext(): Boolean = published.document && !MosaicLink.boardVisible

    fun noteForegroundElapsedMs(): Double {
        if (!published.inHostApp) return -1.0
        val since = noteForegroundSinceMs
        if (since == 0L) return 1e9
        return (SystemClock.elapsedRealtime() - since).toDouble()
    }

    val foregroundMonitorRunning: Boolean get() = monitorRunning

    val moduleAttached: Boolean get() = module != null

    fun install() {
        HostHooks.requestChromeProbe = { reason -> HostProbe.request(reason) }
        HostHooks.isDocumentContext = { isDocumentContext() }
        HostHooks.foregroundMonitorRunning = { monitorRunning }
        HostHooks.startForegroundMonitor = { startForegroundMonitor() }
        HostHooks.stopForegroundMonitor = { stopForegroundMonitor() }
        MosaicLink.ensureRegistered()
        if (installed) return
        installed = true

        HostLogMonitor.addListener { signal -> onSignal(signal) }
        MosaicLink.addListener { event -> onMosaicEvent(event) }
    }

    internal fun attach(owner: HostModule, context: ReactApplicationContext) {
        module = owner
        Main.post {
            if (module !== owner) return@post
            restoreBootstrapRunnable?.let(Main.handler::removeCallbacks)
            restoreBootstrapRunnable = null
            send(HostEvent.Restoring(false))

            val previousMonitor = windowStateMonitor
            if (previousMonitor == null || !previousMonitor.isFor(context)) {
                previousMonitor?.stop()
                windowStateMonitor = WindowStateMonitor(context) { showing -> onPluginHostStateChanged(showing) }
            }
            windowStateMonitor?.start()
            registerDisplayCallbacks(context)
            Main.post {
                if (module !== owner) return@post

                onDisplayMaybeChanged("module-init")

                owner.publish(toMap())
                announce(HostNotice.Module(true))
            }
        }
    }

    internal fun detach(owner: HostModule) {
        if (module !== owner) return
        module = null
        monitorRunning = false
        HostLogMonitor.ownerTakesOver()
        val teardown: () -> Unit = {
            announce(HostNotice.Module(false))
            announce(HostNotice.Monitor(MonitorPhase.ORPHANED))
            Main.handler.removeCallbacks(monitorTick)
            HostPenSignals.releaseDocStickerHold("module invalidated")

            PenGuard.releaseScopes("module invalidated", PenGuardScope.HOST_UI)
        }
        if (Main.isMain) teardown() else Main.post(teardown)
    }

    private fun registerDisplayCallbacks(context: ReactApplicationContext) {
        val app = context.applicationContext
        if (!displayCallbacksRegistered) {
            displayCallbacksRegistered = true
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    Main.post { if (module != null) onDisplayMaybeChanged("configuration") }
                }

                override fun onLowMemory() = Unit

                override fun onTrimMemory(level: Int) = Unit
            })
        }
        if (!displayListenerRegistered) {
            try {
                val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit

                    override fun onDisplayRemoved(displayId: Int) = Unit

                    override fun onDisplayChanged(displayId: Int) {
                        if (displayId == Display.DEFAULT_DISPLAY) {
                            Main.post { if (module != null) onDisplayMaybeChanged("display-listener") }
                        }
                    }
                }, Main.handler)
                displayListenerRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "display listener registration failed: ${e.message}")
            }
        }
    }

    private fun onDisplayMaybeChanged(reason: String) {
        val oldW = screenWidth
        val oldH = screenHeight
        val oldRotation = lastDisplayRotation
        val (w, h) = Style.screenSize()
        screenWidth = w
        screenHeight = h
        val rotation = HostProbe.readDisplayRotation()
        lastDisplayRotation = rotation
        val displayChanged = oldW != w || oldH != h || oldRotation != rotation
        val foreground = if (displayChanged) HostProbe.foregroundActivity() else null
        val change = DisplayChange(oldW, oldH, oldRotation, w, h, rotation, reason, displayChanged, foreground, lastForegroundKey)
        val startRotationSync = try {
            rotationSyncWanted(change)
        } catch (e: Exception) {
            Log.e(TAG, "rotationSyncWanted failed", e)
            false
        }
        PenGuard.onDisplayChanged(w, h, rotation, reason, rotationPending = startRotationSync)
        if (!displayChanged) return
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "orientation changed reason=$reason ${oldW}x${oldH}@${oldRotation} → ${w}x${h}@$rotation")
        }
        announce(HostNotice.Display(change, startRotationSync))
        HostProbe.request("display: $reason")
    }

    private val monitorTick = object : Runnable {
        override fun run() {
            if (!monitorRunning) return
            val owner = module ?: return
            val self = this
            val serial = monitorTickSerial.get()
            foregroundProbeExecutor.execute {
                val foreground = HostProbe.foregroundActivity()
                val hostFile = foreground?.takeIf { it.isHostPage }?.let { HostProbe.hostFilePath() }
                Main.post {
                    if (serial != monitorTickSerial.get() || !monitorRunning || module !== owner) return@post
                    if (foreground != null) runMonitorTick(foreground, hostFile)
                    Main.handler.postDelayed(self, MONITOR_INTERVAL_MS)
                }
            }
        }
    }

    fun startForegroundMonitor() {
        if (!Main.isMain) {
            Main.post { startForegroundMonitor() }
            return
        }
        HostLogMonitor.ownerTakesOver()
        if (monitorRunning) return
        val context = App.react
        if (context == null) {
            Log.w(TAG, "foreground monitor not started: no React context")
            return
        }
        monitorRunning = true
        send(HostEvent.MonitorStarted)

        PenGuard.setFullScreenActive(false, PenOwner.CLIP_PASTE)
        HostPenSignals.endInsertImageGuard()
        if (windowStateMonitor == null) {
            windowStateMonitor = WindowStateMonitor(context) { showing -> onPluginHostStateChanged(showing) }
        }
        windowStateMonitor?.start()
        PenGuard.claimOwnership(App.context.filesDir)
        SystemKeyboard.ensureRegistered(App.context)
        PenGuard.beginHostBootstrap("foreground monitor start")
        HostLogMonitor.start()
        TwoFingerDoubleTap.start { if (module != null) onTwoFingerDoubleTap() }
        monitorTickSerial.incrementAndGet()
        Main.handler.removeCallbacks(monitorTick)
        Main.handler.postDelayed(monitorTick, MONITOR_INTERVAL_MS)
        announce(HostNotice.Monitor(MonitorPhase.STARTED))
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor started")
    }

    fun stopForegroundMonitor() {
        if (!Main.isMain) {
            Main.post { stopForegroundMonitor() }
            return
        }
        announce(HostNotice.Monitor(MonitorPhase.STOPPING))
        monitorRunning = false
        monitorTickSerial.incrementAndGet()
        Main.handler.removeCallbacks(monitorTick)

        HostLogMonitor.stop()
        TwoFingerDoubleTap.stop()
        send(HostEvent.MonitorStopped)
        HostPenSignals.resetForMonitorStop("foreground monitor stopped")

        PenGuard.releaseScopes(
            "foreground monitor stopped",
            PenGuardScope.HOST_UI, PenGuardScope.FLOW, PenGuardScope.PRESS,
        )
        PenGuard.setFullScreenActive(false, PenOwner.FOREIGN_ACTIVITY)
        PenGuard.resetHostBaseline("foreground monitor stopped")
        PenGuard.endPark("foreground monitor stopped", flush = true)
        announce(HostNotice.Monitor(MonitorPhase.STOPPED))
    }

    private fun runMonitorTick(foreground: ForegroundActivity, hostFile: String?) {
        val fgPkg = foreground.packageName
        val supportsHandwriting = foreground.isHostPage
        announce(HostNotice.Tick(foreground, hostFile))
        PenGuard.setFullScreenActive(!supportsHandwriting, PenOwner.FOREIGN_ACTIVITY)
        val fgKey = foreground.key
        if (fgKey != lastForegroundKey) {
            val previous = lastForegroundKey
            lastForegroundKey = fgKey
            if (previous != null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground activity changed: $previous → $fgKey")
                HostPenSignals.scheduleForegroundReassert(fgKey)
            }
            HostProbe.request("foreground: $fgKey")
        }
        if (supportsHandwriting) {
            val targetDocMode = foreground.isDocument
            if (state.document != targetDocMode || appliedDocMode != targetDocMode) {
                setAppMode(targetDocMode, "foreground ${if (targetDocMode) "DOC" else "NOTE"}")
            }
        }
        val inNote = fgPkg == HostProbe.PLUGIN_PACKAGE || supportsHandwriting
        if (inNote != state.inHostApp) {
            if (inNote) {
                noteForegroundSinceMs = SystemClock.elapsedRealtime()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: returned to note canvas")
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: left note canvas, hiding overlays")

                Main.post { PenGuard.endPark("left note canvas", flush = true) }
            }
            send(HostEvent.Foreground(inNote))
            if (!inNote) PenGuard.releaseScopes("left note canvas", PenGuardScope.HOST_UI)
        }
        syncDocumentTextSelectionGuard()
        syncPluginHostGuard()
    }

    fun setAppMode(document: Boolean, reason: String) {
        val appChanged = state.document != document
        send(HostEvent.AppMode(document))
        syncDocumentTextSelectionGuard()
        PenGuard.setAppContext(document)

        HostProbe.clear("app mode: $reason")
        HostProbe.request("app mode: $reason")
        appliedDocMode = document
        if (appChanged && BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "host app mode=${if (document) "document" else "note"} reason=$reason")
        }
    }

    private fun syncDocumentTextSelectionGuard() {
        PenGuard.setFullScreenActive(
            state.inHostApp && isDocumentContext() && HostLogMonitor.isDocTextSelectionActive(),
            PenOwner.DOC_TEXT_SELECTION,
        )
    }

    private fun syncPluginHostGuard() {
        PenGuard.setFullScreenActive(
            state.inHostApp && state.pluginViewOpen && !state.mosaicVisible,
            PenOwner.PLUGIN_HOST,
        )
    }

    private fun onSignal(signal: HostSignal) {
        val generation = signal.generation
        when (signal) {
            is HostSignal.SubviewOpened -> onNoteSubviewChanged(true, generation)
            is HostSignal.SubviewClosed -> onNoteSubviewChanged(false, generation)
            is HostSignal.ToolbarMenuChanged -> onToolbarMenuChanged(signal.open, generation)
            is HostSignal.LassoMenuChanged -> {
                if (!HostLogMonitor.isCurrentGeneration(generation)) return
                HostPenSignals.clearLassoWritableReset()
                Main.post { onLassoMenuChanged(signal.open, generation) }
            }
            is HostSignal.MovedLassoGone -> Main.post {
                if (!HostLogMonitor.isCurrentGeneration(generation)) return@post
                if (LassoHolds.applyInFlight() || !LassoHolds.selectionHeld()) return@post

                if (HostLogMonitor.isLassoMenuOpen()) return@post
                onLassoMenuCancelled("held lasso selection cancelled")
            }
            is HostSignal.NativePenAreasChanged -> {
                if (!HostLogMonitor.isCurrentGeneration(generation)) return
                HostPenSignals.onNativePenAreas(signal.rects, rotationActive = rotationActive()) {
                    announce(HostNotice.InsertPlacementSettled)
                }
            }
            is HostSignal.HostFullScreenDisableArea -> {
                if (!HostLogMonitor.isCurrentGeneration(generation)) return
                HostPenSignals.onHostTableRebuilt(rotationActive = rotationActive())
            }
            is HostSignal.DocWriteAreaRebuilt -> {
                if (!HostLogMonitor.isCurrentGeneration(generation)) return
                HostPenSignals.onDocWriteAreaRebuilt()
            }
            is HostSignal.DocWritableEnabled -> {
                if (!HostLogMonitor.isCurrentGeneration(generation)) return
                HostPenSignals.onDocWritableEnabled()
            }
            is HostSignal.DocTextSelectedChanged -> Main.post {
                if (HostLogMonitor.isCurrentGeneration(generation)) {
                    send(HostEvent.DocTextSelected(HostLogMonitor.isDocTextSelected()))
                }
            }
            is HostSignal.DocTextSelectionChanged -> Main.post {
                if (!HostLogMonitor.isCurrentGeneration(generation)) return@post
                syncDocumentTextSelectionGuard()
                if (HostLogMonitor.isDocTextSelectionActive()) {
                    PenGuard.reassertDocumentAreas("document text selection")
                }
            }
            is HostSignal.DocLassoReleased -> {
                if (!HostLogMonitor.isCurrentGeneration(generation)) return
                Main.post { onDocLassoReleased(signal.reason) }
            }
            is HostSignal.DocLassoStarted -> {
                if (!HostLogMonitor.isCurrentGeneration(generation)) return
                if (!published.document) return
                HostPenSignals.armDocLassoHold("host selection", signal.confirmed)
                Main.post {
                    if (!HostLogMonitor.isCurrentGeneration(generation) || !state.document) return@post
                    send(HostEvent.DocLasso(true))
                }
            }

            is HostSignal.PluginImageCommitted -> Unit

            is HostSignal.HostToolSent, is HostSignal.NotePageTurned -> Unit
        }
    }

    private fun onNoteSubviewChanged(open: Boolean, generation: Int) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onNoteSubviewChanged: open=$open gen=$generation")
        if (!HostLogMonitor.isCurrentGeneration(generation)) return
        if (!open) {

            Main.handler.postDelayed({
                if (HostLogMonitor.isCurrentGeneration(generation) && !HostLogMonitor.isSubviewOpen()) {
                    PenGuard.setFullScreenActive(false, PenOwner.NOTE_SUBVIEW)
                }
            }, RESTORE_DELAY_NORMAL)
        }
        if (module == null) return
        val applyState = Runnable {
            if (!HostLogMonitor.isCurrentGeneration(generation) || HostLogMonitor.isSubviewOpen() != open) return@Runnable
            if (open) {
                HostLogMonitor.closeLassoMenuFromFallback("subview opened")
                PenGuard.setFullScreenActive(true, PenOwner.NOTE_SUBVIEW)
            } else {
                HostProbe.request("subview closed")

                HostPenSignals.scheduleReassert(reason = "note subview restored", allowEmptyAtSchedule = true)
            }
        }
        if (open) Main.post { applyState.run() } else Main.handler.postDelayed(applyState, RESTORE_DELAY_NORMAL)
    }

    private fun onToolbarMenuChanged(open: Boolean, generation: Int) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onToolbarMenuChanged: open=$open gen=$generation " +
            "isCurrent=${HostLogMonitor.isCurrentGeneration(generation)}")
        if (!HostLogMonitor.isCurrentGeneration(generation)) return
        if (open) {
            Main.post {
                if (!HostLogMonitor.isCurrentGeneration(generation) || !HostLogMonitor.isToolbarMenuOpen()) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu open: state stale, skip")
                    return@post
                }
                if (module == null) return@post
                PenGuard.setFullScreenActive(true, PenOwner.HOST_TOOLBAR_MENU)
                if (isDocumentContext()) HostProbe.request("document toolbar menu opened")
            }
        } else {
            Main.post {
                if (!HostLogMonitor.isCurrentGeneration(generation) || HostLogMonitor.isToolbarMenuOpen()) return@post

                PenGuard.setFullScreenActive(false, PenOwner.HOST_TOOLBAR_MENU)
                HostProbe.request("toolbar menu closed")
            }
        }
    }

    private fun onLassoMenuChanged(open: Boolean, generation: Int) {
        if (!HostLogMonitor.isCurrentGeneration(generation)) return
        HostPenSignals.cancelLassoMenuRelease()

        if (open || !HostLogMonitor.isLassoMenuOpen()) send(HostEvent.LassoMenu(open))
        if (open) {

            if (MosaicLink.boardVisible) {
                Log.i(TAG, "host lasso menu opened under the Mosaic board: no full-screen hold")
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host lasso menu full-screen=true")
                PenGuard.setFullScreenActive(true, PenOwner.HOST_LASSO_MENU)
            }

            HostLogMonitor.clearLassoRelocating()
            HostPenSignals.endInsertImageGuard()
            announce(HostNotice.LassoShown)

            if (!LassoHolds.applyInFlight()) PenGuard.setFullScreenActive(false, PenOwner.CLIP_PASTE)
            return
        }
        if (LassoHolds.applyInFlight()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host lasso menu close: palette apply in flight; defer restore")
            return
        }

        if (HostLogMonitor.isLassoMenuOpen()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host lasso menu close: stale callback, newer lasso is open")
            return
        }

        if (LassoHolds.selectionHeld() && HostLogMonitor.isLassoRelocating()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host lasso menu close: held selection is being moved; keep hold")
            return
        }
        onLassoMenuCancelled("lasso menu closed")
    }

    private fun onLassoMenuCancelled(reason: String) {
        announce(HostNotice.LassoCancelling(reason))
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "host lasso menu cancelled: release full-screen owner reason=$reason")
        }
        HostPenSignals.cancelLassoMenuRelease()

        HostPenSignals.cancelScheduledReasserts()
        PenGuard.setFullScreenActive(false, PenOwner.HOST_LASSO_MENU)
        PenGuard.setFullScreenActive(false, PenOwner.CLIP_PASTE)

        if (LassoHolds.awaitingNextSelection()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "lasso cancel: stale close while awaiting the next selection")
            return
        }
        HostPenSignals.endInsertImageGuard()
        announce(HostNotice.LassoCancelled(reason))
    }

    private fun onDocLassoReleased(reason: String) {
        if (state.docLassoSelected) send(HostEvent.DocLasso(false))

        HostPenSignals.releaseDocStickerHold(reason)

        if (LassoHolds.applyInFlight()) return
        HostPenSignals.releaseDocLassoHold(reason)
        announce(HostNotice.DocLassoReleased(reason))
    }

    private fun onMosaicEvent(event: MosaicEvent) {
        when (event) {
            is MosaicEvent.BoardState -> onMosaicBoardState(event)
            is MosaicEvent.Pad -> Main.post {
                if (MosaicLink.padOpen != event.open) return@post

                PenGuard.setFullScreenActive(event.open, PenOwner.MOSAIC_PAD)
                if (!event.open) reassertAfterCover("mosaic pad closed")
            }
            is MosaicEvent.QuickSwitch -> Main.post {
                if (MosaicLink.quickSwitchOpen != event.open) return@post
                if (!event.open) reassertAfterCover("mosaic quick switch closed")
            }

            is MosaicEvent.ChromeMenu, is MosaicEvent.Motion, MosaicEvent.TextEditingOpened,
            MosaicEvent.ToolbarRects, is MosaicEvent.Selection, MosaicEvent.PenState -> Unit
        }
    }

    fun reassertAfterCover(reason: String) {
        HostProbe.request(reason)
        HostPenSignals.scheduleReassert(reason = reason, allowEmptyAtSchedule = true)
    }

    private fun onMosaicBoardState(event: MosaicEvent.BoardState) {
        val visible = event.visible
        val nextSurface = event.surface.ifBlank { MosaicLink.SURFACE_BOARD }
        val wasVisible = state.mosaicVisible
        if (!visible && !wasVisible) {

            send(HostEvent.MosaicBoard(false, nextSurface, event.noteRef))
            return
        }
        send(HostEvent.MosaicBoard(visible, nextSurface, event.noteRef))
        syncDocumentTextSelectionGuard()
        syncPluginHostGuard()

        if (!visible && state.inHostApp && HostLogMonitor.isLassoMenuOpen()) {
            PenGuard.setFullScreenActive(true, PenOwner.HOST_LASSO_MENU)
        }
        if (visible) {

            HostPenSignals.cancelLassoMenuRelease()
            HostPenSignals.clearLassoWritableReset()
            PenGuard.setFullScreenActive(false, PenOwner.HOST_TOOLBAR_MENU)
            PenGuard.setFullScreenActive(false, PenOwner.HOST_LASSO_MENU)
            PenGuard.endPark("Mosaic board visible")
        }
        HostProbe.request("mosaic visibility")
    }

    private fun onPluginHostStateChanged(showing: Boolean) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onPluginHostStateChanged: showing=$showing")
        send(HostEvent.PluginView(showing))
        syncPluginHostGuard()
        if (!showing) {
            MosaicLink.onPluginHostHidden()

            HostPenSignals.scheduleReassert(reason = "PluginHost hidden", allowEmptyAtSchedule = true)
        }

        if (module == null) {

            if (!toolbarWindowUp() && !overlayOwnsScreen() && showing) {
                PenGuard.reassert("host visible, module unavailable")
            }

            if (!showing && toolbarRestorePending()) {
                scheduleRestoreBootstrap("PluginHost hidden without active module")
            } else if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "onPluginHostStateChanged: module unavailable showing=$showing " +
                    "pendingRestore=${toolbarRestorePending()}")
            }
            return
        }
        if (!showing) {
            announce(HostNotice.PluginView(showing = false))
            return
        }
        val hostEntry = HostProbe.readHostEntrySnapshot()
        val ownsHost = hostEntry?.isCurrentOwner == true
        Log.i(TAG, "[HOST-ENTRY] visible ownerMatch=$ownsHost snapshot=$hostEntry")
        if (ownsHost && settingsBlocksToolbar()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onPluginHostStateChanged: settings panel active, close host")
            HostPlugins.closePluginView()
            return
        }
        val toolbarOwnsHost = ownsHost && toolbarOwnsHostEntry()
        val selfInitiated = toolbarOwnsHost

        val mosaicHost = !ownsHost && state.mosaicVisible && MosaicLink.boardVisible
        announce(HostNotice.PluginView(true, ownsHost, selfInitiated, toolbarOwnsHost, mosaicHost))
    }

    private fun scheduleRestoreBootstrap(reason: String) {
        if (state.restoring || module != null || !toolbarRestorePending() || state.pluginViewOpen) return

        val context = App.react ?: run {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "restore bootstrap deferred: no bridge context reason=$reason")
            return
        }
        send(HostEvent.Restoring(true))
        restoreBootstrapRunnable?.let(Main.handler::removeCallbacks)
        val task = Runnable {
            restoreBootstrapRunnable = null
            if (module != null || !toolbarRestorePending() || state.pluginViewOpen || !state.inHostApp) {
                send(HostEvent.Restoring(false))
                return@Runnable
            }
            val opened = HostPlugins.showOwnForRestore(context)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "restore bootstrap reason=$reason opened=$opened pendingRestore=${toolbarRestorePending()}")
            }

            if (!opened) send(HostEvent.Restoring(false))
        }
        restoreBootstrapRunnable = task
        Main.handler.postDelayed(task, RESTORE_DELAY_SHORT)
    }

    private var entryPermissionInFlight = false

    fun ensureEntryPermissions(onResult: (Boolean) -> Unit) {
        if (entryPermissionInFlight) return
        entryPermissionInFlight = true
        HostPlugins.settingsOpenedForDenial = false
        Log.i(TAG, "ensureEntryPermissions: FILE:READ")
        fun finish(granted: Boolean) {
            entryPermissionInFlight = false
            val dirOk = Dirs.pluginDirAvailable
            if (granted && !dirOk) {
                Log.w(TAG, "plugin dir unavailable: permissions granted but the host gave no plugin directory")
            }
            send(HostEvent.Permissions(granted && dirOk))

            if (!(granted && dirOk) && !HostPlugins.settingsOpenedForDenial) {
                Dialogs.tip(Strings.get("initial_permissions_required"))
            }
            onResult(granted)
        }
        HostPlugins.requestPermission(HostPlugins.FILE_READ) { read ->
            if (!read) { finish(false); return@requestPermission }
            Log.i(TAG, "ensureEntryPermissions: FILE:WRITE")
            HostPlugins.requestPermission(HostPlugins.FILE_WRITE) { write ->
                if (!write) { finish(false); return@requestPermission }
                Log.i(TAG, "ensureEntryPermissions: FILE:DELETE")
                HostPlugins.requestPermission(HostPlugins.FILE_DELETE) { delete ->
                    if (!delete) { finish(false); return@requestPermission }
                    Log.i(TAG, "ensureEntryPermissions: INTERNET")
                    HostPlugins.requestPermission(HostPlugins.INTERNET) { internet -> finish(internet) }
                }
            }
        }
    }
}

enum class MosaicPermissionResult { GRANTED, DENIED, PLUGIN_UNAVAILABLE }

object HostPlugins {
    private const val TAG = "HostPlugins"

    const val FILE_READ = "plugin.permission.FILE:READ"
    const val FILE_WRITE = "plugin.permission.FILE:WRITE"
    const val FILE_DELETE = "plugin.permission.FILE:DELETE"
    const val INTERNET = "plugin.permission.INTERNET"

    private const val PERMISSION_STATUS_ALWAYS = 2
    private const val PERMISSION_STATUS_SESSION = 1

    private const val HOST_PERMISSION_CALLBACK_CLASS =
        "com.ratta.supernote.pluginhost.security.PluginPermissionManager\$PermissionCallback"
    private const val SETTINGS_ACTIVITY = "com.ratta.settings.SettingsActivity"
    private const val SETTINGS_PLUGIN_DETAIL_ACTION = "com.ratta.settings.application.PluginDetailFragment"

    private const val SETTINGS_PLUGIN_NAME = "Inkling"

    private val deniedPermissions: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    @Volatile internal var settingsOpenedForDenial = false

    private fun loader(): ClassLoader = App.context.classLoader

    private fun manager(): Pair<Class<*>, Any?> {
        val managerClass = Class.forName(HostProbe.HOST_PLUGIN_MANAGER_CLASS, false, loader())
        val manager = managerClass.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
        return managerClass to manager
    }

    private fun pluginAppOf(managerClass: Class<*>, manager: Any, pluginId: String): Any? =
        managerClass.methods.firstOrNull {
            it.name == "getPluginApp" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }?.invoke(manager, pluginId)

    private fun withNativePluginManager(label: String, block: (NativeModule) -> Unit) {
        try {
            val pm = HostProbe.nativePluginManager() ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "$label: NativePluginManager not found")
                return
            }
            block(pm)
        } catch (t: Throwable) {
            Log.e(TAG, "$label: ${t.message}", t)
        }
    }

    fun closePluginView() = withNativePluginManager("closePluginView") { pm ->

        if (MosaicLink.boardVisible) {
            Log.i(TAG, "closePluginView skipped: Mosaic board owns PluginContainer")
            return@withNativePluginManager
        }
        notifySettingsClientClosed(pm)
        for (name in arrayOf("closePluginView", "hidePluginView")) {
            val methods = pm.javaClass.methods.filter { it.name == name }
            if (methods.isEmpty()) continue
            val noArg = methods.firstOrNull { it.parameterCount == 0 }
            if (noArg != null) {
                noArg.invoke(pm)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "$name() called")
                return@withNativePluginManager
            }

            val singleArg = methods.firstOrNull {
                it.parameterCount == 1 && (
                    it.parameterTypes[0].isAssignableFrom(PromiseImpl::class.java) ||
                        Promise::class.java.isAssignableFrom(it.parameterTypes[0])
                    )
            }
            if (singleArg != null) {
                singleArg.invoke(pm, PromiseImpl(null, null))
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "$name(promise) called")
                return@withNativePluginManager
            }
        }
        if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "closePluginView: no suitable method found")
    }

    private fun notifySettingsClientClosed(pm: NativeModule) {
        try {
            val pa = pm.javaClass.methods.firstOrNull { it.name == "getPluginApp" && it.parameterCount == 0 }?.invoke(pm)
                ?: HostProbe.findDeclaredField(pm.javaClass, "pluginApp")?.apply { isAccessible = true }?.get(pm)
                ?: return
            val clientUid = (pa.javaClass.methods.firstOrNull {
                it.name == "getClientUid" && it.parameterCount == 0
            }?.invoke(pa) as? Number)?.toInt() ?: -1
            if (clientUid < 0) return

            val hostLoader = pa.javaClass.classLoader ?: return
            val managerClass = hostLoader.loadClass(HostProbe.HOST_PLUGIN_MANAGER_CLASS)
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val binder = managerClass.getMethod("getCurrentClientBinder", Integer.TYPE).invoke(manager, clientUid) ?: return
            val packageName = binder.javaClass.methods.firstOrNull {
                it.name == "getServicePackageName" && it.parameterCount == 0
            }?.invoke(binder) as? String
            if (packageName != HostProbe.SETTINGS_PACKAGE) return

            val serviceClass = hostLoader.loadClass("com.ratta.supernote.pluginhost.services.PluginHostService")
            val hostContext = HostProbe.findDeclaredField(serviceClass, "mContext")?.apply { isAccessible = true }?.get(null)
            val service = when {
                serviceClass.isInstance(hostContext) -> hostContext
                hostContext is android.content.ContextWrapper -> hostContext.baseContext
                else -> null
            }
            if (service == null || !serviceClass.isInstance(service)) {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "settings close state notify skipped: PluginHostService unavailable")
                return
            }
            serviceClass.getMethod("notifyClientPluginState", Integer.TYPE, Integer.TYPE).invoke(service, clientUid, 0)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "settings close state notified: uid=$clientUid state=0")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "settings close state notify failed: ${e.message}")
        }
    }

    internal fun showOwnForRestore(context: ReactApplicationContext): Boolean {
        val hostLoader = context.classLoader
        try {
            val managerClass = Class.forName(HostProbe.HOST_PLUGIN_MANAGER_CLASS, false, hostLoader)
            val manager = managerClass.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
            val pluginApp = if (manager != null) pluginAppOf(managerClass, manager, PluginIdentity.PLUGIN_ID) else null
            if (pluginApp != null) {
                val directShow = pluginApp.javaClass.methods.firstOrNull {
                    it.name == "showPluginView" && it.parameterCount == 1 &&
                        (it.parameterTypes[0] == Integer.TYPE || it.parameterTypes[0] == Integer::class.java)
                }
                if (directShow != null) {
                    directShow.invoke(pluginApp, 0)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "restore via PluginApp.showPluginView(0)")
                    return true
                }
            }
            val managerShow = managerClass.methods.firstOrNull {
                it.name == "showPluginView" && it.parameterCount == 2 &&
                    it.parameterTypes[0].isAssignableFrom(String::class.java) &&
                    (it.parameterTypes[1] == Integer.TYPE || it.parameterTypes[1] == Integer::class.java)
            }
            if (manager != null && managerShow != null) {
                managerShow.invoke(manager, PluginIdentity.PLUGIN_ID, 0)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "restore via PluginManager.showPluginView(0)")
                return true
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "restore manager route unavailable: ${e.message}")
        }
        return postPluginShowEvent(hostLoader, PluginIdentity.PLUGIN_ID, 0, "restore")
    }

    private fun postPluginShowEvent(hostLoader: ClassLoader, pluginId: String, showType: Int, label: String): Boolean {
        return try {
            val eventClass = Class.forName("com.ratta.supernote.pluginhost.eventbus.PluginShowEvent", false, hostLoader)
            val event = eventClass.getConstructor(String::class.java, Integer.TYPE).newInstance(pluginId, showType)
            val eventBusClass = Class.forName("org.greenrobot.eventbus.EventBus", false, hostLoader)
            val eventBus = eventBusClass.getMethod("getDefault").invoke(null)
            eventBusClass.getMethod("post", Any::class.java).invoke(eventBus, event)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "$label via PluginShowEvent($pluginId, $showType)")
            true
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "$label: PluginShowEvent($pluginId) unavailable: ${e.message}")
            false
        }
    }

    fun showHostPluginView(pluginId: String): Boolean {
        if (postPluginShowEvent(loader(), pluginId, 1, "plugin opened")) return true
        try {
            val (managerClass, manager) = manager()
            val show = managerClass.methods.firstOrNull {
                it.name == "showPluginView" && it.parameterCount == 2 &&
                    it.parameterTypes[0].isAssignableFrom(String::class.java) &&
                    (it.parameterTypes[1] == Integer.TYPE || it.parameterTypes[1] == Integer::class.java)
            }
            if (manager != null && show != null) {
                show.invoke(manager, pluginId, 1)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "plugin opened via PluginManager.showPluginView($pluginId, 1)")
                return true
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "PluginManager.showPluginView($pluginId) unavailable: ${e.message}")
        }
        return false
    }

    fun isHostPluginInstalled(pluginId: String): Boolean = try {
        val (managerClass, manager) = manager()
        manager != null && pluginAppOf(managerClass, manager, pluginId) != null
    } catch (e: Exception) {
        Log.w(TAG, "plugin $pluginId lookup failed: ${e.message}")
        false
    }

    fun openMosaicPluginForPending() {
        Main.post {
            if (showHostPluginView(MosaicLink.PLUGIN_ID)) return@post
            try {
                App.context.startActivity(Intent().apply {
                    component = ComponentName(MosaicLink.PACKAGE, MosaicLink.ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Mosaic opened via fallback activity")
            } catch (e: Exception) {
                Log.e(TAG, "Unable to open Mosaic: ${e.message}", e)
            }
        }
    }

    fun inspectAndFlushHostEntry(): String {
        var result = "none"
        try {
            val pa = HostProbe.hostPluginApp() ?: run {
                Log.i(TAG, "hostdiag: pluginApp=null")
                return result
            }
            fun readField(name: String): Any? = try {
                HostProbe.findDeclaredField(pa.javaClass, name)?.apply { isAccessible = true }?.get(pa)
            } catch (e: Exception) {
                Log.w(TAG, "hostdiag: field $name unreadable: ${e.message}")
                null
            }
            val entry = HostEntrySnapshot.read(pa)
            if (!entry.isCurrentOwner) {
                Log.i(TAG, "[HOST-ENTRY] ignore background mount snapshot=$entry")
                return result
            }
            val state = readField("state")
            val stateName = (state as? Enum<*>)?.name ?: state?.toString()
            val cache = readField("pluginEventCacheList") as? List<*>
            val eventNames = cache?.map { it?.javaClass?.simpleName ?: "null" } ?: emptyList()
            val lastShowType = (readField("lastShowType") as? Number)?.toInt()
            Log.i(TAG, "hostdiag: state=$stateName cache=${cache?.size} [${eventNames.joinToString(",")}] " +
                "pluginModule=${readField("mPluginModule") != null} lastShowType=$lastShowType")

            val hasButtonEvent = eventNames.any { it.contains("PluginButtonEvent") }
            val hasConfigEvent = eventNames.any { it.contains("ConfigButtonPressEvent") }
            if (stateName == "initialized" && (hasButtonEvent || hasConfigEvent)) {

                Log.i(TAG, "hostdiag: state stuck at initialized, flushing entry events via PluginApp.onMounted()")
                try {
                    pa.javaClass.getMethod("onMounted").invoke(pa)
                    result = if (hasConfigEvent) "config" else "flushed"
                } catch (e: Exception) {
                    Log.e(TAG, "hostdiag: onMounted invoke failed: ${e.message}")
                }
            } else if (entry.opensConfig()) {
                result = "config"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "hostdiag: error: ${t.message}")
        }
        return result
    }

    private fun permissionRequestDesc(permission: String): String = when (permission) {
        INTERNET -> Strings.get("perm_desc_internet")
        FILE_WRITE -> Strings.get("perm_desc_file_write")
        FILE_DELETE -> Strings.get("perm_desc_file_delete")
        else -> Strings.get("perm_desc_file_read")
    }

    fun requestStoragePermissions(
        what: String,
        showDeniedFeedback: Boolean = true,
        onGranted: () -> Unit,
        onDenied: () -> Unit,
    ) {
        fun denied(kind: String, key: String) {
            Log.w(TAG, "$what denied: $kind")
            if (showDeniedFeedback) Toast.makeText(App.context, Strings.get(key), Toast.LENGTH_LONG).show()
            onDenied()
        }
        requestPermission(FILE_READ) { read ->
            Main.post {
                if (!read) {
                    denied("FILE:READ", "file_read_permission_needed")
                    return@post
                }
                requestPermission(FILE_WRITE) { write ->
                    Main.post { if (write) onGranted() else denied("FILE:WRITE", "file_write_permission_needed") }
                }
            }
        }
    }

    fun requestPermission(permission: String, onResult: ((Boolean) -> Unit)? = null) {
        requestDirectHostPermission(permission) { granted ->
            if (granted) {
                deniedPermissions.remove(permission)
            } else if (!deniedPermissions.add(permission)) {
                settingsOpenedForDenial = true
                openPluginSettings()
            }
            onResult?.invoke(granted)
        }
    }

    private fun requestDirectHostPermission(permission: String, onResult: (Boolean) -> Unit) {
        Main.post {
            try {
                val (managerClass, manager) = manager()
                val pluginApp = manager?.let { pluginAppOf(managerClass, it, PluginIdentity.PLUGIN_ID) }
                if (manager == null || pluginApp == null) {
                    Log.w(TAG, "direct host permission $permission: host manager or PluginApp unavailable")
                    onResult(false)
                    return@post
                }
                requestHostPluginPermission(managerClass, manager, pluginApp, permission, permissionRequestDesc(permission)) { granted ->
                    Log.i(TAG, "direct host permission $permission result=$granted")
                    onResult(granted)
                }
            } catch (e: Exception) {
                Log.e(TAG, "direct host permission $permission failed: ${e.message}", e)
                onResult(false)
            }
        }
    }

    private fun requestHostPluginPermission(
        managerClass: Class<*>,
        manager: Any,
        pluginApp: Any,
        permission: String,
        description: String,
        onResult: (Boolean) -> Unit,
    ) {
        try {
            val hasMethod = managerClass.methods.firstOrNull {
                it.name == "hasPermission" && it.parameterCount == 2 && it.parameterTypes[1] == String::class.java
            } ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "host permission: hasPermission unavailable")
                onResult(false)
                return
            }
            val status = (hasMethod.invoke(manager, pluginApp, permission) as? Number)?.toInt() ?: 0
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host permission has $permission status=$status")

            if (status == PERMISSION_STATUS_ALWAYS || status == PERMISSION_STATUS_SESSION) {
                onResult(true)
                return
            }

            val requestMethod = managerClass.methods.firstOrNull {
                it.name == "requestPermission" && it.parameterCount == 4 &&
                    it.parameterTypes[1] == String::class.java && it.parameterTypes[2] == String::class.java
            } ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "host permission: requestPermission unavailable")
                onResult(false)
                return
            }
            val hostLoader = loader()
            val callbackClass = Class.forName(HOST_PERMISSION_CALLBACK_CLASS, false, hostLoader)
            val callbackLock = Any()
            var callbackFinished = false

            fun finish(granted: Boolean) {
                var deliver = false
                synchronized(callbackLock) {
                    if (!callbackFinished) {
                        callbackFinished = true
                        deliver = true
                    }
                }
                if (deliver) Main.post { onResult(granted) }
            }
            val callback = Proxy.newProxyInstance(
                hostLoader,
                arrayOf(callbackClass),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onResult" -> {
                            val result = (args?.firstOrNull() as? Number)?.toInt() ?: 0
                            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host permission request $permission status=$result")
                            finish(result == PERMISSION_STATUS_ALWAYS || result == PERMISSION_STATUS_SESSION)
                        }
                        "onError" -> {
                            if (BuildConfig.ENABLE_DEBUG) {
                                Log.w(TAG, "host permission request $permission failed: ${args?.firstOrNull()}")
                            }
                            finish(false)
                        }
                    }
                    null
                },
            )
            requestMethod.invoke(manager, pluginApp, permission, description, callback)
        } catch (e: Exception) {
            Log.e(TAG, "host permission request $permission failed: ${e.message}", e)
            onResult(false)
        }
    }

    fun requestMosaicPluginPermissions(onResult: ((MosaicPermissionResult) -> Unit)? = null) {
        Main.post {
            try {
                val (managerClass, manager) = manager()
                if (manager == null) {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Mosaic permission: host manager unavailable")
                    onResult?.invoke(MosaicPermissionResult.DENIED)
                    return@post
                }
                val mosaicApp = pluginAppOf(managerClass, manager, MosaicLink.PLUGIN_ID) ?: run {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Mosaic permission: plugin not installed")
                    onResult?.invoke(MosaicPermissionResult.PLUGIN_UNAVAILABLE)
                    return@post
                }
                val description = Strings.get("mosaic_permission_request")
                requestHostPluginPermission(managerClass, manager, mosaicApp, FILE_READ, description) { readGranted ->
                    if (!readGranted) {
                        onResult?.invoke(MosaicPermissionResult.DENIED)
                        return@requestHostPluginPermission
                    }
                    requestHostPluginPermission(managerClass, manager, mosaicApp, FILE_WRITE, description) { writeGranted ->
                        onResult?.invoke(
                            if (writeGranted) MosaicPermissionResult.GRANTED else MosaicPermissionResult.DENIED
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mosaic permission check failed: ${e.message}", e)
                onResult?.invoke(MosaicPermissionResult.DENIED)
            }
        }
    }

    fun showMosaicPermissionReminder(result: MosaicPermissionResult) {
        val messageKey =
            if (result == MosaicPermissionResult.PLUGIN_UNAVAILABLE) "mosaic_plugin_needed" else "mosaic_permission_needed"
        Dialogs.confirm(Strings.get(messageKey), Strings.get("cancel"), Strings.get("open_settings")) { openSettings ->
            if (openSettings) openPluginSettings(MosaicLink.PLUGIN_ID)
        }
    }

    fun openPluginSettings(pluginId: String = SETTINGS_PLUGIN_NAME) {
        try {
            App.context.startActivity(Intent(SETTINGS_PLUGIN_DETAIL_ACTION).apply {
                component = ComponentName(HostProbe.SETTINGS_PACKAGE, SETTINGS_ACTIVITY)
                putExtra("pluginId", pluginId)
                putExtra("fromAPP", pluginId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "openPluginSettings: launched plugin detail for $pluginId")
        } catch (e: Exception) {
            Log.w(TAG, "openPluginSettings plugin detail failed: ${e.message}")
            try {
                App.context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    setPackage(HostProbe.SETTINGS_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (fallback: Exception) {
                Log.e(TAG, "openPluginSettings fallback failed: ${fallback.message}", fallback)
            }
        }
    }
}

class HostModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalHost") {

    init {
        HostSession.attach(this, ctx)
    }

    override fun invalidate() {
        HostSession.detach(this)
        super.invalidate()
    }

    internal fun publish(state: WritableMap) {
        emit("host:state", state)
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getState(): WritableMap = HostSession.toMap()

    @ReactMethod
    fun requestPermission(kind: String, promise: Promise) {
        val permission = when (kind) {
            "file_read" -> HostPlugins.FILE_READ
            "file_write" -> HostPlugins.FILE_WRITE
            "file_delete" -> HostPlugins.FILE_DELETE
            "internet" -> HostPlugins.INTERNET
            else -> {
                promise.reject("E_NOTIPALHOST", "unknown permission kind: $kind")
                return
            }
        }
        HostPlugins.requestPermission(permission) { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun inspectAndFlushHostEntry(promise: Promise) {
        Main.post { promise.run { HostPlugins.inspectAndFlushHostEntry() } }
    }

    @ReactMethod
    fun reportHostButtonChannel(working: Boolean) {
        if (BuildConfig.ENABLE_DEBUG) Log.i("NotipalHost", "host button channel report working=$working")
        Main.post { HostSession.send(HostEvent.ButtonChannel(working)) }
    }

    @ReactMethod
    fun releaseStaleLassoMenu(reason: String) {
        HostLogMonitor.closeLassoMenuFromFallback(reason)
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun noteForegroundElapsedMs(): Double = HostSession.noteForegroundElapsedMs()

    @ReactMethod
    fun tip(text: String) = Dialogs.tip(text)

    @ReactMethod
    fun confirm(text: String, cancelText: String, confirmText: String, promise: Promise) =
        Dialogs.confirm(text, cancelText, confirmText) { promise.resolve(it) }
}
