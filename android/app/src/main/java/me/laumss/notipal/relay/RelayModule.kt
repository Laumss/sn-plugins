package me.laumss.notipal.relay

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import me.laumss.notipal.common.AiAccess
import me.laumss.notipal.common.Cancelable
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.FeatureModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.LongFlows
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.RelayPrefs
import me.laumss.notipal.common.StateMachine
import me.laumss.notipal.common.Strings
import me.laumss.notipal.capture.CaptureHooks
import me.laumss.notipal.host.HostLogMonitor
import me.laumss.notipal.host.HostNotice
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.links.LinkHooks
import me.laumss.notipal.palette.PaletteBubble
import me.laumss.notipal.palette.PenBar
import me.laumss.notipal.palette.PaletteHooks
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.ToolbarHooks
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.WindowHooks
import me.laumss.notipal.ui_common.WindowRotation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

sealed class RelayWindow {
    object Closed : RelayWindow() { override fun toString() = "Closed" }
    object Bubble : RelayWindow() { override fun toString() = "Bubble" }
    data class Small(val typing: Boolean) : RelayWindow()
    data class Full(val page: RelayPage, val editing: Boolean = false) : RelayWindow()
}

sealed class RelayEvent {

    object ToSmall : RelayEvent() { override fun toString() = "ToSmall" }

    data class ToFull(val page: RelayPage, val editing: Boolean = false) : RelayEvent()

    object Collapse : RelayEvent() { override fun toString() = "Collapse" }

    object Expand : RelayEvent() { override fun toString() = "Expand" }

    data class Typing(val on: Boolean) : RelayEvent()

    data class Close(val reason: String) : RelayEvent()

    data class Gone(val small: Boolean, val reason: String) : RelayEvent()
}

internal object RelayOpenTrace {
    private const val TAG = "AIRelayOpen"
    private const val IDLE = 0L

    @Volatile private var startedAt = IDLE

    fun begin(what: String) {
        startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "+0ms $what")
    }

    fun mark(what: String) {
        val start = startedAt
        if (start != IDLE) Log.i(TAG, "+${SystemClock.elapsedRealtime() - start}ms $what")
    }

    fun markOrBegin(what: String) {
        if (startedAt == IDLE) begin(what) else mark(what)
    }

    fun end(what: String) {
        mark(what)
        startedAt = IDLE
    }
}

class RelayModule(ctx: ReactApplicationContext) : FeatureModule(ctx, "NotipalRelay"),
    RelayHost, Conversations.Listener, Conversations.Stager {

    companion object {
        private const val TAG = "RelayModule"

        private const val REPLY_WAIT_MS = 90_000L

        private const val STREAM_ALIVE_MS = 60_000L

        private const val SMALL_LEXI_OWNER = "panel:relay-small"
        private const val MONITOR_HOLD = "relay"

        @Volatile internal var current: RelayModule? = null
            private set

        private var webLinkWrapper: (() -> Unit)? = null
        private var sessionClosedWrapper: (() -> Unit)? = null

        fun install() {
            RelayHooks.openTrace = { RelayOpenTrace.mark(it) }
            RelayHooks.flashStatus = { text, ms -> current?.flashIfOpen(text, ms) ?: false }
            RelayHooks.replyFailed = { text -> current?.replyFailedIfOpen(text) ?: false }
            RelayHooks.backendChanged = { current?.backendChangedIfOpen() }
            ToolbarHooks.relaySmallUp = { current?.machine?.state is RelayWindow.Small }
            ToolbarHooks.relaySmallShowing = { current?.isSmall == true }
            ToolbarHooks.closeRelaySmall = { current?.let { if (it.isSmall) it.closePanel() } }
            ToolbarHooks.stowRelaySmall = { current?.stow() == true }
            PaletteHooks.closeRelaySmall = { current?.closeQuietly() }
            LinkHooks.lexiQuestion = { text ->
                val module = current
                if (!AiAccess.enabled()) {

                    Log.i(TAG, "lexi question refused: AI ${if (AiAccess.banned()) "banned" else "off"}")
                    Dialogs.tip(Strings.get(if (AiAccess.banned()) "ai_banned_tip" else "ai_off_tip"))
                } else if (module == null) {
                    Log.w(TAG, "lexi question: no relay module")
                    Dialogs.tip(Strings.get("feature_not_ready"))
                } else module.relayLexiQuestion(text)
            }
            CaptureHooks.aiRegion = { path, box, whole -> stageRegion(path, box, whole) }

            if (RelayHooks.showWebLink !== webLinkWrapper) {
                val open = RelayHooks.showWebLink
                val wrapper: () -> Unit = {
                    val module = current
                    if (module != null && module.machine.state is RelayWindow.Full) {
                        module.webLinkOpening = true
                        open()

                        Main.post { module.webLinkOpening = false }
                    } else open()
                }
                webLinkWrapper = wrapper
                RelayHooks.showWebLink = wrapper
            }

            if (ToolbarHooks.sessionClosed !== sessionClosedWrapper) {
                val previous = ToolbarHooks.sessionClosed
                val wrapper: () -> Unit = {
                    previous()
                    current?.endRelay("toolbar closed")
                }
                sessionClosedWrapper = wrapper
                ToolbarHooks.sessionClosed = wrapper
            }
        }

        private fun stageRegion(srcPath: String, screenBox: Rect, wholeImage: Boolean): IntArray? {
            if (!Conversations.isEnabled()) {
                Log.w(TAG, "stage region ignored: AIRelay disabled")
                return null
            }
            return try {
                PluginIdentity.claim()
                val dir = Dirs.aiCapture
                if (!dir.exists()) dir.mkdirs()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(srcPath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val ts = System.currentTimeMillis()

                val destImg = File(dir, "$ts.${File(srcPath).extension.ifEmpty { "png" }}")
                val destMask = File(dir, "$ts.mask.json")
                File(srcPath).copyTo(destImg, overwrite = true)
                val box = if (wholeImage) Rect(0, 0, bounds.outWidth, bounds.outHeight) else screenBox
                val poly = JSONArray()
                for ((x, y) in listOf(box.left to box.top, box.right to box.top, box.right to box.bottom, box.left to box.bottom)) {
                    poly.put(JSONObject().apply { put("x", x); put("y", y) })
                }
                val mask = JSONObject().apply {
                    put("imageWidth", bounds.outWidth)
                    put("imageHeight", bounds.outHeight)
                    put("boundingBox", JSONObject().apply {
                        put("x", box.left); put("y", box.top); put("w", box.width()); put("h", box.height())
                    })
                    put("polygon", poly)
                    put("ts", ts)
                }
                FileOutputStream(destMask).use { it.write(mask.toString().toByteArray(Charsets.UTF_8)) }
                Conversations.start()

                if (Conversations.submitImageQuery(destImg.path, destMask.path, "") == Conversations.ImageSubmit.SENDING) {
                    intArrayOf(screenBox.left, screenBox.top, screenBox.right, screenBox.bottom)
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "stage region failed: ${e.message}", e)
                null
            }
        }
    }

    enum class Screen { LIST, EDIT, READ, SELECT }

    private class BackStack {

        var returnsToMini = false
        var miniReturnId: String? = null

        var homeIsRoot = false

        var replyFromHome = false

        var replyFromMini = false

        var settingsReturn: SettingsReturn? = null

        fun reset() {
            returnsToMini = false
            miniReturnId = null
            homeIsRoot = false
            replyFromHome = false
            replyFromMini = false
            settingsReturn = null
        }
    }

    private class SettingsReturn(val detailId: String?, val replyFromHome: Boolean, val replyFromMini: Boolean)

    private inner class Machine : StateMachine<RelayWindow, RelayEvent>(RelayWindow.Closed) {
        override val tag: String get() = "RelayWindow"

        override fun reduce(state: RelayWindow, event: RelayEvent): RelayWindow = when (event) {
            RelayEvent.ToSmall -> when (state) {
                is RelayWindow.Small -> state
                else -> RelayWindow.Small(false)
            }
            is RelayEvent.ToFull -> RelayWindow.Full(event.page, event.editing)
            RelayEvent.Collapse -> if (state is RelayWindow.Small) RelayWindow.Bubble else ignored(state, event)
            RelayEvent.Expand -> if (state == RelayWindow.Bubble) RelayWindow.Small(false) else ignored(state, event)
            is RelayEvent.Typing -> if (state is RelayWindow.Small) RelayWindow.Small(event.on) else ignored(state, event)
            is RelayEvent.Close -> RelayWindow.Closed
            is RelayEvent.Gone -> when {
                event.small && (state is RelayWindow.Small || state == RelayWindow.Bubble) -> RelayWindow.Closed
                !event.small && state is RelayWindow.Full -> RelayWindow.Closed
                else -> ignored(state, event)
            }
        }

        private fun ignored(state: RelayWindow, event: RelayEvent): RelayWindow {
            Log.i(tag, "ignored $event in $state")
            return state
        }

        override fun onEnter(state: RelayWindow, from: RelayWindow) = applyWindow(from, state)
    }

    private val machine = Machine()
    private val handler = Main.handler

    private val detailLazy = lazy { DetailPane(this).also(::wireDetail) }

    internal val detail: DetailPane by detailLazy
    private val selectionLazy = lazy { SelectionPane(this).also { it.onActions = { full?.showSelectState() } } }

    internal val selection: SelectionPane by selectionLazy
    private val inboxLazy = lazy {
        InboxPane(this, RelaySettingsPane().view).also { it.search.input.onImeEdit = ::onImeEdit }
    }

    internal val inbox: InboxPane by inboxLazy
    private val voiceLazy = lazy { VoiceStage(this) }
    override val voice: VoiceStage by voiceLazy
    private val smallLazy = lazy { RelaySmallWindow(this) }
    private val smallWindow: RelaySmallWindow by smallLazy

    private var full: RelayFullPanel? = null

    private var fullAway = false

    @Volatile private var webLinkOpening = false

    private val back = BackStack()

    private var flash: String? = null
    private var jsStatus = ""
    private var localStatus = ""
    private val endFlash = Runnable {
        flash = null
        detailChanged()
    }
    private val replyTimeout = Runnable {
        if (localStatus.isEmpty()) return@Runnable
        localStatus = ""
        flashStatus(Strings.get("relay_reply_timeout"), 2500)
    }

    private var hostPage: Pair<String, String?>? = null

    private var messages: List<RelayMessage> = emptyList()
    private var knownIds: Set<String>? = null

    private var openWaiter: ((Boolean) -> Unit)? = null

    private var imeEdit = ""
    private val afterImeEdit = Runnable { refreshKeyboard("$imeEdit+150") }
    private val afterImeEditLate = Runnable { refreshKeyboard("$imeEdit+400") }

    private val subscriptions = ArrayList<Cancelable>()

    internal val isSmall: Boolean get() = machine.state.let { it is RelayWindow.Small || it == RelayWindow.Bubble }

    internal val keepsFullUnder: Boolean get() = RelayPages.busy || webLinkOpening

    internal val screen: Screen
        get() = when (val s = machine.state) {
            is RelayWindow.Full -> when {
                s.editing -> Screen.EDIT
                s.page == RelayPage.INBOX || s.page == RelayPage.SETTINGS || s.page == RelayPage.TRANSCRIPT -> Screen.LIST
                s.page == RelayPage.SELECT -> Screen.SELECT
                else -> Screen.READ
            }
            else -> Screen.READ
        }

    override fun initialize() {
        super.initialize()
        Main.post {
            current = this
            RelayPages.releaseForNewModule()

            Conversations.addListener(this)
            Tools.handle(Tools.AI_RELAY,
                onTap = { Main.post { onToolTap() } },
                onLongPress = null,
                onExit = { endRelay("tool off") },
                onRetap = { onToolRetap() })

            Toolbar.exitMode(Tools.AI_RELAY)
            subscriptions += HostSession.addListener { old, new ->

                if (old.mosaicVisible != new.mosaicVisible) endRelay(if (new.mosaicVisible) "mosaic opened" else "mosaic closed")
            }
            subscriptions += HostSession.addNoticeListener { notice ->
                if (notice is HostNotice.Tick && notice.foreground.isHostPage) {
                    val app = if (notice.foreground.isNoteCanvas) "NOTE" else "DOC"
                    val file = notice.hostFile
                    Main.post { onHostPage(app, file) }
                }
            }
            subscriptions += AiAccess.addListener {

                if (!AiAccess.enabled()) endRelay("AI turned off in settings")
            }
            subscriptions += WindowRotation.addListener { outcome ->

                if (outcome.onHandwritingPage && machine.state !is RelayWindow.Full) endRelay("rotation")
            }
        }
    }

    override fun invalidate() {
        Main.post {
            subscriptions.forEach { it.cancel() }
            subscriptions.clear()
            Conversations.removeListener(this)

            if (machine.state != RelayWindow.Closed) machine.send(RelayEvent.Close("module invalidated"))
            if (current === this) {
                Conversations.setEnabledByUser(false)
                Toolbar.exitMode(Tools.AI_RELAY)

                Tools.handle(Tools.AI_RELAY, onTap = {
                    Log.w(TAG, "AI relay tapped with no relay module")
                    Toolbar.exitMode(Tools.AI_RELAY)
                })
                current = null
            }
        }
        super.invalidate()
    }

    @ReactMethod
    fun openWindow(forPage: Boolean, promise: Promise) = Main.post {
        RelayOpenTrace.markOrBegin("openWindow from JS")
        openRelay(forPage) { shown -> promise.resolve(shown) }
    }

    @ReactMethod
    fun setStatus(text: String) = Main.post {
        if (machine.state == RelayWindow.Closed) return@post
        jsStatus = text
        detailChanged()
    }

    @ReactMethod
    fun flash(text: String, ms: Double) = Main.post { flashIfOpen(text, ms.toLong()) }

    @ReactMethod
    fun setAside(aside: Boolean) = Main.post {
        if (!isSmall) return@post
        smallWindow.setAside(aside)
        syncIdle()
    }

    @ReactMethod
    fun stage(text: String, promise: Promise) = promise.run { Conversations.stageText(text) }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isActive(): Boolean = Conversations.isEnabled()

    private fun onToolTap() {
        RelayOpenTrace.begin("tool tapped")
        openRelay(false) {}
    }

    private fun onToolRetap(): Boolean {
        if (!Conversations.isEnabled()) return false
        Log.i(TAG, "tool tapped while AI relay is on: window shown")
        openRelay(false) {}
        return true
    }

    private fun openRelay(forPage: Boolean, onResult: (Boolean) -> Unit) {
        if (!AiAccess.enabled()) {
            Log.i(TAG, "AI relay not opened: AI ${if (AiAccess.banned()) "banned" else "off"} in settings")
            RelayOpenTrace.end("AI off")
            Toolbar.exitMode(Tools.AI_RELAY)
            onResult(false)
            return
        }
        RelayOpenTrace.mark("turning relay on")
        Conversations.setEnabledByUser(true)
        RelayOpenTrace.mark("relay on")
        Toolbar.setActive(Tools.AI_RELAY, true)
        openRelayWindow(forPage) { shown ->
            if (!shown) {
                RelayOpenTrace.end("no window")
                Log.i(TAG, "window not opened, AI relay off")
                endRelay("window not opened")
            }
            onResult(shown)
        }
    }

    private fun openRelayWindow(forPage: Boolean, onResult: (Boolean) -> Unit) {
        if (ToolbarHooks.lexiFullScreen()) {
            Log.i(TAG, "AIRelay window not opened: Lexi full screen open")
            onResult(false)
            return
        }
        if (machine.state != RelayWindow.Closed) {
            if (WindowHooks.lexiOpen()) WindowHooks.lexiHold(if (isSmall) SMALL_LEXI_OWNER else "panel:relay")
        } else {
            if (refuseDuringExport("open window")) {
                onResult(false)
                return
            }
            Panels.hideAll()
        }
        openAtStart(forPage, onResult)
    }

    private fun refuseDuringExport(what: String): Boolean {
        if (!LongFlows.running(LongFlows.MOSAIC_IMPORT) && !LongFlows.running(LongFlows.DOC_SHARE)) return false
        Log.i(TAG, "AIRelay $what refused: import or document share running")
        Dialogs.tip(Strings.get("busy_processing_title"))
        return true
    }

    private fun openAtStart(forPage: Boolean, onResult: (Boolean) -> Unit) {
        if (machine.state != RelayWindow.Closed) {
            uncollapse("open")
            RelayOpenTrace.end("window was already up")
            onResult(true)
            return
        }
        back.reset()
        back.homeIsRoot = true
        Conversations.start()
        detail.compose()
        val asSmall = forPage || RelayPrefs.openMini()
        if (asSmall) {
            machine.send(RelayEvent.ToSmall)
            onResult(machine.state != RelayWindow.Closed)
        } else {
            openWaiter = onResult
            machine.send(RelayEvent.ToFull(RelayPage.COMPOSE))
        }
    }

    private fun relayLexiQuestion(text: String) {
        if (Conversations.stageText(text)) {
            Log.i(TAG, "lexi question: staged in the AIRelay small window")
            return
        }
        if (refuseDuringExport("lexi question")) return
        Panels.hideAll(except = full)
        if (isSmall) {
            detail.stageText(text)
            return
        }
        Conversations.start()
        if (machine.state is RelayWindow.Full) {

            showRead(null, compose = true)
            detail.stageText(text)
            return
        }
        back.reset()
        detail.compose()
        machine.send(RelayEvent.ToSmall)
        if (machine.state == RelayWindow.Closed) {
            Log.w(TAG, "compose window not shown, question dropped")
            return
        }
        detail.stageText(text)
        Log.i(TAG, "lexi question: AIRelay compose window with ${text.length} chars (relay ${if (Conversations.isEnabled()) "on" else "off"})")
    }

    internal fun endRelay(reason: String) {
        val open = machine.state != RelayWindow.Closed

        val on = Conversations.isEnabled() || Toolbar.isActive(Tools.AI_RELAY)
        if (!open && !on) return
        Log.i(TAG, "AI relay off ($reason) window=$open relay=$on")
        if (open) machine.send(RelayEvent.Close(reason))
        if (!on) return
        Conversations.setEnabledByUser(false)
        Toolbar.exitMode(Tools.AI_RELAY)
        emit("relay:off", Arguments.createMap())
    }

    internal fun closePanel() = endRelay("window closed")

    private fun closeQuietly() {
        if (!isSmall) return
        Log.i(TAG, "small window closed for the palette bubble, AIRelay left on")
        machine.send(RelayEvent.Close("palette bubble"))
    }

    internal fun smallGone(reason: String) {
        if (!isSmall) return
        machine.send(RelayEvent.Gone(small = true, reason = reason))
        endRelay(reason)
    }

    internal fun fullGone(panel: RelayFullPanel) {
        if (panel !== full) return
        full = null
        fullAway = false
        releasePanes()
        openWaiter?.let {
            openWaiter = null
            it(false)
        }
        machine.send(RelayEvent.Gone(small = false, reason = "panel hidden"))
        endRelay("full screen closed")
    }

    private fun releasePanes() {
        if (!inboxLazy.isInitialized()) return
        inbox.onRowsChanged = null
        inbox.onPicks = null
    }

    internal fun setFullAway(panel: RelayFullPanel, away: Boolean) {
        if (panel !== full) return
        fullAway = away
        syncIdle()
    }

    private fun applyWindow(from: RelayWindow, to: RelayWindow) {
        when (to) {
            RelayWindow.Closed -> teardown(from)
            is RelayWindow.Small -> when (from) {
                RelayWindow.Closed -> {
                    windowOpened()
                    enterSmall(fromFull = false)
                }
                RelayWindow.Bubble -> expandBubble()
                is RelayWindow.Full -> enterSmall(fromFull = true)
                is RelayWindow.Small -> Unit
            }
            RelayWindow.Bubble -> collapseToBubble()
            is RelayWindow.Full -> when (from) {
                RelayWindow.Closed -> {
                    windowOpened()
                    enterFull(fromSmall = false)
                }
                is RelayWindow.Small -> enterFull(fromSmall = true)
                RelayWindow.Bubble -> {

                    detail.resume()
                    enterFull(fromSmall = true)
                }
                is RelayWindow.Full -> Unit
            }
        }
        syncIdle()
    }

    private fun windowOpened() {
        RelayOpenTrace.mark("building window")

        HostLogMonitor.hold(MONITOR_HOLD)
        voice.attach()
        RelayTranslucency.onChanged = { detail.applyLook() }
        hostPage = null
        inbox.setMessages(Conversations.inbox.snapshot(), now = false)
    }

    private fun enterSmall(fromFull: Boolean) {
        if (fromFull) {
            RelayOpenTrace.begin("shrink to small window")
            detail.beforeHandover()
            full?.freezeDetail()
            RelayOpenTrace.mark("full screen frozen")
        }
        smallWindow.place(detail.view)
        smallWindow.open()
        detail.redraw()
        RelayOpenTrace.mark("content rendered")
        renderChrome()

        PaletteBubble.hideAndForget()
        PenBar.hideAndForget()

        Conversations.stager = this

        Toolbar.yieldFor(Toolbar.REASON_RELAY_SMALL)
        WindowHooks.lexiHold(SMALL_LEXI_OWNER)
        val old = full
        full = null
        fullAway = false
        releasePanes()
        smallWindow.afterDrawn {
            RelayOpenTrace.end("small window drawn")
            old?.hide()
        }
        Log.i(TAG, "small window shown (fromFull=$fromFull)")
    }

    private fun enterFull(fromSmall: Boolean) {
        if (fromSmall) {
            RelayOpenTrace.begin("expand to full screen")

            smallWindow.endTyping()
            detail.beforeHandover()
            if (Conversations.stager === this) Conversations.stager = null
            smallWindow.resetAside()
            smallWindow.freeze(detail.view)
            RelayOpenTrace.mark("small window frozen")
        }
        val panel = RelayFullPanel(this)
        full = panel
        fullAway = false
        Panels.open(panel) { shown ->
            if (full !== panel) return@open
            val waiter = openWaiter
            openWaiter = null
            if (!shown) {
                Log.w(TAG, "AIRelay full screen not shown")
                full = null
                machine.send(RelayEvent.Gone(small = false, reason = "panel not shown"))
                endRelay("full screen not shown")
                waiter?.invoke(false)
                return@open
            }
            RelayOpenTrace.mark("window added")
            detail.redraw()
            renderChrome()
            RelayOpenTrace.mark("content rendered")
            panel.afterNextDrawn { RelayOpenTrace.mark("first frame drawn") }
            panel.afterDrawn {
                RelayOpenTrace.end("full screen drawn")
                if (fromSmall && full === panel) {
                    smallWindow.close()
                    WindowHooks.lexiRelease(SMALL_LEXI_OWNER)
                }
            }
            waiter?.invoke(true)
        }
    }

    private fun collapseToBubble() {
        smallWindow.collapse()
        detail.pause()
        Log.i(TAG, "small window collapsed to its bubble")
    }

    private fun expandBubble() {
        smallWindow.expand()
        detail.resume()
        smallWindow.setMarked(false)
        PaletteBubble.hideAndForget()
        PenBar.hideAndForget()
        Toolbar.yieldFor(Toolbar.REASON_RELAY_SMALL)
        detail.markShownSeen()
    }

    private fun teardown(from: RelayWindow) {
        val wasSmall = from is RelayWindow.Small || from == RelayWindow.Bubble
        if (smallLazy.isInitialized()) {
            smallWindow.resetAside()
            smallWindow.close()
        }
        val panel = full
        full = null
        fullAway = false
        releasePanes()
        panel?.hide()
        openWaiter?.let {
            openWaiter = null
            it(false)
        }
        if (detailLazy.isInitialized()) detail.clear()
        if (selectionLazy.isInitialized()) selection.clear()
        if (inboxLazy.isInitialized()) {
            inbox.hideKeyboard()
            inbox.setEditing(false)
        }
        if (voiceLazy.isInitialized()) voice.detach()
        RelayTranslucency.onChanged = null
        if (Conversations.stager === this) Conversations.stager = null
        Conversations.setWindowIdle(false)
        flash = null
        jsStatus = ""
        localStatus = ""
        handler.removeCallbacks(endFlash)
        handler.removeCallbacks(replyTimeout)
        handler.removeCallbacks(afterImeEdit)
        handler.removeCallbacks(afterImeEditLate)
        back.reset()
        hostPage = null
        WindowHooks.lexiRelease(SMALL_LEXI_OWNER)

        Toolbar.resume(Toolbar.REASON_RELAY_SMALL)
        Toolbar.restore("relay window closed")
        HostLogMonitor.release(MONITOR_HOLD)
        Log.i(TAG, "window closed (was $from, small=$wasSmall)")
    }

    internal fun syncIdle() {
        val idle = when (machine.state) {
            RelayWindow.Bubble -> true
            is RelayWindow.Small -> smallLazy.isInitialized() && !smallWindow.attached && !smallWindow.aside
            is RelayWindow.Full -> fullAway
            RelayWindow.Closed -> false
        }
        Conversations.setWindowIdle(idle)
    }

    private fun navigate(to: Screen, page: RelayPage? = null, content: () -> Unit) {
        if (to != Screen.READ) expandFromSmall(toHome = false)
        content()
        if (machine.state is RelayWindow.Full) {
            val p = page ?: when (to) {
                Screen.LIST, Screen.EDIT -> RelayPage.INBOX
                Screen.SELECT -> RelayPage.SELECT
                Screen.READ -> if (detail.cardId == null) RelayPage.COMPOSE else RelayPage.DETAIL
            }
            machine.send(RelayEvent.ToFull(p, editing = to == Screen.EDIT))
        }
        renderChrome()
    }

    private fun showRead(id: String?, keepKeyboard: Boolean = false, compose: Boolean = false) = navigate(Screen.READ) {
        when {
            id != null -> when (detail.show(id, keepKeyboard)) {
                DetailPane.Opened.FROM_START -> {
                    back.replyFromHome = true
                    back.replyFromMini = false
                }
                DetailPane.Opened.OTHER -> {
                    back.replyFromHome = false
                    back.replyFromMini = false
                }
                DetailPane.Opened.SAME -> Unit
            }
            compose -> detail.compose()
            else -> detail.start()
        }
        selection.clear()
    }

    private fun showHere(id: String?) {
        back.settingsReturn = null
        showRead(id)
    }

    internal fun showStart() {
        back.settingsReturn = null
        navigate(Screen.READ) {
            detail.newChat()
            selection.clear()
        }
    }

    private fun endSelecting() = navigate(Screen.READ) {
        detail.read()
        selection.clear()
    }

    private fun selectDetail() = navigate(Screen.SELECT) {
        detail.select()
        detail.message?.let { selection.show(it, detail.selectionTurns(), detail.reading) }
    }

    private fun openList(page: RelayPage = RelayPage.INBOX) = navigate(Screen.LIST, page) {
        detail.leave()
        selection.clear()
    }

    internal fun editInbox() = navigate(Screen.EDIT) {
        detail.leave()
        selection.clear()
    }

    internal fun openSettings() {
        back.settingsReturn = if (isSmall) null else SettingsReturn(detail.cardId, back.replyFromHome, back.replyFromMini)
        openList(RelayPage.SETTINGS)
        inbox.showSettings()
    }

    private fun expandFromSmall(toHome: Boolean) {
        if (!isSmall) return
        back.returnsToMini = true
        back.miniReturnId = detail.cardId
        back.homeIsRoot = toHome
        back.replyFromHome = false
        back.replyFromMini = false
        back.settingsReturn = null
        machine.send(RelayEvent.ToFull(if (detail.cardId == null) RelayPage.COMPOSE else RelayPage.DETAIL))
    }

    internal fun growFromSmall() {
        val atReply = !detail.atStart
        expandFromSmall(toHome = !atReply)
        back.replyFromMini = atReply
    }

    internal fun shrinkToSmall() {
        if (machine.state !is RelayWindow.Full || screen != Screen.READ) return
        machine.send(RelayEvent.ToSmall)
    }

    internal fun collapseSmall() {
        if (machine.state !is RelayWindow.Small || !smallWindow.attached) return
        machine.send(RelayEvent.Collapse)
    }

    internal fun uncollapse(why: String) {
        if (machine.state != RelayWindow.Bubble) return
        Log.i(TAG, "small window opened from the bubble ($why)")
        machine.send(RelayEvent.Expand)
    }

    private fun stow(): Boolean {
        val s = machine.state
        if (s !is RelayWindow.Small || s.typing || !smallWindow.attached) return false
        machine.send(RelayEvent.Collapse)
        return true
    }

    private fun collapseFromFull() {
        shrinkToSmall()
        smallWindow.afterDrawn { if (machine.state is RelayWindow.Small) collapseSmall() }
    }

    private fun returnToMini() {
        val keep = back.miniReturnId?.takeIf { wanted -> messages.any { it.id == wanted } }
        shrinkFromAnyPage {
            showRead(keep)
            shrinkToSmall()
        }
    }

    private fun shrinkFromAnyPage(shrink: () -> Unit) {
        val panel = full?.takeIf { machine.state is RelayWindow.Full && screen != Screen.READ }
        panel?.hold()
        shrink()
        if (panel != null && full === panel) {
            Log.w(TAG, "full screen held for a shrink that did not happen: drawn again")
            panel.release()
            renderChrome()
        }
    }

    internal fun backFromDetail() {
        when {
            isSmall -> closePanel()
            detail.cardId != null -> when {
                back.replyFromMini -> returnToMini()
                back.replyFromHome -> showHere(null)
                else -> openList()
            }
            back.homeIsRoot -> leave()
            else -> openList()
        }
    }

    internal fun leaveList() {
        val ret = back.settingsReturn
        when {
            ret != null -> {
                val keep = ret.detailId?.takeIf { wanted -> messages.any { it.id == wanted } }
                showHere(keep)
                if (keep != null) {
                    back.replyFromHome = ret.replyFromHome
                    back.replyFromMini = ret.replyFromMini
                }
            }
            back.homeIsRoot -> showHere(null)
            else -> leave()
        }
    }

    private fun leave() {
        if (back.returnsToMini) returnToMini() else closeUnlessWaiting()
    }

    internal fun closeUnlessWaiting() {
        if (!replyOnItsWay()) {
            closePanel()
            return
        }
        Log.i(TAG, "close while a reply is on its way: collapsing")
        if (isSmall) {
            collapseSmall()
            return
        }
        shrinkFromAnyPage {
            if (screen != Screen.READ) showHere(null)
            collapseFromFull()
        }
    }

    private fun replyOnItsWay(): Boolean {
        if (localStatus.isNotEmpty()) return true
        val since = System.currentTimeMillis() - STREAM_ALIVE_MS
        return messages.any { !it.closed && it.updatedAt > since }
    }

    internal fun listHasBack(): Boolean = back.returnsToMini || back.homeIsRoot || back.settingsReturn != null

    internal fun bandCloses(): Boolean = detail.atStart && back.homeIsRoot && !back.returnsToMini

    private fun showDetail(id: String) {
        Conversations.start()
        back.settingsReturn = null
        expandFromSmall(toHome = false)
        showRead(id)
    }

    private fun showOnPhone(cardId: String) {
        val card = messages.firstOrNull { it.id == cardId } ?: return
        if (card.kind != "formula" && card.conversationId.isNotEmpty()) Conversations.openOnPhone(card.conversationId)
    }

    private fun renderChrome() {
        when (machine.state) {
            is RelayWindow.Full -> full?.renderChrome(screen)
            is RelayWindow.Small, RelayWindow.Bubble -> voice.bindMic(smallWindow.navMic, smallWindow.navMic, Style.px(40))
            RelayWindow.Closed -> return
        }
        detailChanged()
    }

    internal fun windowTitle(): String =
        (flash ?: jsStatus.ifEmpty { null } ?: localStatus.ifEmpty { null }) ?: detail.title()

    private fun flashIfOpen(text: String, ms: Long): Boolean {
        if (machine.state == RelayWindow.Closed) return false
        flashStatus(text, ms)
        return true
    }

    private fun replyFailedIfOpen(text: String): Boolean {
        if (machine.state == RelayWindow.Closed) return false
        replyFailed(text)
        return true
    }

    private fun backendChangedIfOpen() {
        if (machine.state == RelayWindow.Closed) return
        voice.refresh()
        detail.backendChanged()
    }

    private fun replyFailed(text: String) {
        handler.removeCallbacks(replyTimeout)
        detail.replyFailed(waiting = localStatus.isNotEmpty())
        localStatus = ""
        emitAction("reply_failed")
        flashStatus(text, 6_000L)
    }

    private fun onReplyShown() {
        if (machine.state != RelayWindow.Bubble) return
        if (RelayPrefs.autoOpenDetail()) uncollapse("reply") else smallWindow.setMarked(true)
    }

    override fun open(page: RelayPage) {
        when (page) {
            RelayPage.INBOX -> openList()
            RelayPage.TRANSCRIPT -> {
                openList(RelayPage.TRANSCRIPT)
                inbox.showTranscript(null)
            }
            RelayPage.SETTINGS -> openSettings()
            RelayPage.COMPOSE -> showRead(null, compose = true)
            RelayPage.DETAIL -> endSelecting()
            RelayPage.SELECT -> selectDetail()
        }
    }

    override fun back() {
        if (detailLazy.isInitialized() && detail.closeImage(back = true)) return
        if (detailLazy.isInitialized() && detail.input.hideKeyboard()) return
        if (inboxLazy.isInitialized() && inbox.hideKeyboard()) return

        if (isSmall) {
            smallWindow.endTyping()
            return
        }
        if (machine.state == RelayWindow.Closed) return
        when (screen) {
            Screen.LIST -> if (!inbox.back()) leaveList()
            Screen.EDIT -> openList()
            Screen.READ -> backFromDetail()
            Screen.SELECT -> endSelecting()
        }
    }

    override val small: Boolean get() = isSmall

    override fun flashStatus(text: String, ms: Long) {
        jsStatus = ""
        flash = text
        handler.removeCallbacks(endFlash)
        handler.postDelayed(endFlash, ms)
        detailChanged()
    }

    override fun reveal(why: String) = uncollapse(why)

    override fun openCard(id: String, fresh: Boolean) {
        if (fresh) onInboxChanged(Conversations.inbox.snapshot())
        showOnPhone(id)
        showDetail(id)
    }

    override fun openHere(id: String) {
        showOnPhone(id)
        showHere(id)
    }

    override fun question(replyId: String): String? = DetailPane.question(replyId)?.text

    override fun attachmentsChanged() = detail.refreshAttachments()

    override val collapsed: Boolean get() = machine.state == RelayWindow.Bubble

    override fun detailChanged() {
        if (machine.state == RelayWindow.Closed) return
        val title = windowTitle()
        full?.setTitle(title)
        if (isSmall) {
            smallWindow.setTitle(title)
            smallWindow.renderNav(shown = true, homeShown = !(detail.cardId == null && detail.newChatPending))
        }
    }

    override fun awaitReply() {

        if (isSmall && detail.cardId != null) showHere(null)
        else if (!isSmall) renderChrome()
        localStatus = Strings.get("relay_waiting")
        handler.removeCallbacks(replyTimeout)
        handler.postDelayed(replyTimeout, REPLY_WAIT_MS)
        detailChanged()
    }

    override fun close() = closePanel()

    override fun growForImage() = growFromSmall()

    override fun backToSmall() = returnToMini()

    override fun pageAction(actionId: String) {

        if (!isSmall) shrinkToSmall()
        emitAction(actionId)
    }

    override fun attachPages() {
        RelayPages.attach(noteAsImages = !Conversations.isWebMode()) { files, pages ->
            if (files.isEmpty()) return@attach
            if (machine.state == RelayWindow.Closed) {
                PluginIdentity.asNotipal { files.first().parentFile?.deleteRecursively() }
                return@attach
            }
            detail.stagePages(files.map { file ->
                val document = file.name.endsWith(".pdf", ignoreCase = true)
                StagedImage(file.path, "", if (document) Strings.get("relay_pages_chip", pages) else "")
            })
        }
    }

    override fun showInsertOnPage() {
        if (screen != Screen.READ) endSelecting()
        shrinkToSmall()
        flashStatus(Strings.get("relay_inserting"), 2000)
    }

    internal fun pagesLabel(): String =
        Strings.get(if (HostSession.state.document) "relay_tool_pages_doc" else "relay_tool_pages_note")

    internal fun emitAction(action: String) {
        emit("relay:action", Arguments.createMap().apply { putString("action", action) })
    }

    private fun wireDetail(pane: DetailPane) {
        val edit = pane.input.input
        pane.input.onImeEdit = ::onImeEdit

        edit.setOnTouchListener { _, ev ->
            if (machine.state is RelayWindow.Small && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val offset = edit.getOffsetForPosition(ev.x, ev.y)
                smallWindow.beginTyping { detail.focusInput(offset) }
                typingChanged(smallWindow.typing)
            }
            false
        }

        edit.setOnFocusChangeListener { view, hasFocus ->
            if (machine.state is RelayWindow.Small && !hasFocus) smallWindow.endTyping()
            view.postDelayed({ refreshKeyboard("focus") }, 400)
        }
    }

    private fun onImeEdit(what: String) {
        imeEdit = what
        handler.removeCallbacks(afterImeEdit)
        handler.removeCallbacks(afterImeEditLate)
        handler.postDelayed(afterImeEdit, 150)
        handler.postDelayed(afterImeEditLate, 400)
    }

    private fun refreshKeyboard(after: String) {
        when (machine.state) {
            is RelayWindow.Small -> smallWindow.refreshKeyboard(after)
            is RelayWindow.Full -> full?.updateKeyboardInset(after)
            else -> Unit
        }
    }

    internal fun typingChanged(on: Boolean) = machine.send(RelayEvent.Typing(on))

    private fun onHostPage(app: String, file: String?) {
        if (machine.state == RelayWindow.Closed) return
        val base = hostPage
        if (base == null) {
            hostPage = app to file
            Log.i(TAG, "over $app file=$file")
            return
        }
        val (baseApp, baseFile) = base
        val switched = when {
            app != baseApp -> "$baseApp -> $app"
            file != null && baseFile != null && !Dirs.same(file, baseFile) -> "file ${File(baseFile).name} -> ${File(file).name}"
            else -> {
                if (baseFile == null && file != null) hostPage = app to file
                return
            }
        }
        hostPage = app to file
        if (!isSmall) {
            Log.i(TAG, "page changed under the full screen ($switched)")
            return
        }
        Log.i(TAG, "page changed ($switched): small window put away (collapsed=$collapsed)")
        closeUnlessWaiting()
    }

    override fun onInboxChanged(messages: List<RelayMessage>) {
        this.messages = messages
        val known = knownIds
        knownIds = messages.mapTo(HashSet()) { it.id }
        if (known != null && messages.any { it.source == "llm" && it.id !in known }) {
            emit("relay:replyArrived", Arguments.createMap())
        }
        if (machine.state == RelayWindow.Closed) return
        val reply = detail.inboxChanged(messages)
        inbox.setMessages(messages)
        if (reply != null) {
            localStatus = ""
            handler.removeCallbacks(replyTimeout)
            if (detail.atStart || machine.state == RelayWindow.Bubble) {
                Log.i(TAG, "awaited reply $reply opens here (state=${machine.state})")
                showRead(reply, keepKeyboard = true)
                onReplyShown()
                return
            }
            if (isSmall) flashStatus(Strings.get("relay_reply_arrived"), 3000)
        }
        if (detail.cardGone) {

            if (isSmall || back.replyFromHome) showHere(null) else openList()
        } else {
            detail.inboxSettled()
        }
    }

    override fun onInsertionRequested(request: Conversations.InsertionRequest) {
        when (request) {
            is Conversations.InsertionRequest.Text ->
                emit("relay:insert", Arguments.createMap().apply { putString("text", request.text) })
        }
    }

    override fun stageText(text: String) = detail.stageText(text)

    override fun stageImage(imagePath: String, maskPath: String) = detail.stageImage(imagePath, maskPath)
}
