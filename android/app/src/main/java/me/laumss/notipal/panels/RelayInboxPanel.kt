package me.laumss.notipal.panels

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.FloatingDragRefresh
import me.laumss.notipal.FloatingPenGuard
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.HostPenSignals
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.PenGuardOwner
import me.laumss.notipal.SystemKeyboard
import me.laumss.notipal.TouchInput
import me.laumss.notipal.bubbles.BubbleWindowSupport
import me.laumss.notipal.relay.AIRelayCore
import me.laumss.notipal.relay.RelayOpenTrace
import me.laumss.notipal.relay.core.AppPrefs
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.relay.core.FormulaBlocks
import me.laumss.notipal.relay.core.RelayTurns
import me.laumss.notipal.relay.llm.ConversationStream
import me.laumss.notipal.relay.model.RelayMessage
import me.laumss.notipal.relay.transcript.TranscriptClient
import me.laumss.notipal.relay.ui.AttachmentStrip
import me.laumss.notipal.relay.ui.BAND
import me.laumss.notipal.relay.ui.LINE
import me.laumss.notipal.relay.ui.ChatInputBar
import me.laumss.notipal.relay.ui.ChatTurn
import me.laumss.notipal.relay.ui.DetailReadActions
import me.laumss.notipal.relay.ui.DetailSelectActions
import me.laumss.notipal.relay.ui.InboxListView
import me.laumss.notipal.relay.ui.MessageDetailContent
import me.laumss.notipal.relay.ui.RecentConversations
import me.laumss.notipal.relay.ui.RelaySettingsPane
import me.laumss.notipal.relay.ui.RelayTranslucency
import me.laumss.notipal.relay.ui.STATUS_ICON
import me.laumss.notipal.relay.ui.rowTitle
import me.laumss.notipal.relay.ui.summaryLines
import me.laumss.notipal.relay.ui.StagedImage
import me.laumss.notipal.relay.ui.VoiceChip
import me.laumss.notipal.relay.web.WebConversationStore
import me.laumss.notipal.ui_common.PanelBar
import me.laumss.notipal.ui_common.Dialog
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.PanelRegistry
import me.laumss.notipal.ui_common.ScreenScale
import me.laumss.notipal.ui_common.UiUtils
import me.laumss.notipal.ui_common.snux.LineIcon
import me.laumss.notipal.ui_common.snux.Snux
import me.laumss.notipal.ui_common.snux.TopBar
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.reflect.KProperty

class RelayInboxPanel private constructor(
    context: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule,
) : PanelBase(context, toolbar), AIRelayCore.Listener, AIRelayCore.Stager {
    companion object {
        @Volatile private var instance: RelayInboxPanel? = null

        private var candidateStrip = 0
        private const val CANDIDATE_STRIP_DP = 80.5f
        private const val MINI_IME_SLACK_DP = 4

        private const val MINI_WIDTH_RATIO = 0.56f

        private const val MINI_HEIGHT_RATIO = 0.38f
        private const val MINI_MARGIN_DP = 24

        private const val MINI_BORDER_PX = 2

        private const val MINI_KEEP_PX = 120

        private val KEYBOARD_LOOKS_MS = longArrayOf(0L, 150L, 400L, 800L)

        private const val MINI_BAR_PX = 92
        private const val MINI_BAR_ICON_PX = 44
        private const val MINI_TITLE_PX = 30
        private const val MINI_ICON_PX = 40
        private const val MINI_NAV_PX = 80

        private const val REPLY_WAIT_MS = 90_000L

        private const val STREAM_ALIVE_MS = 60_000L

        private const val SLOW_RENDER_MS = 60L
        private const val MINI_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        private var miniX = Int.MIN_VALUE
        private var miniY = Int.MIN_VALUE

        @Volatile private var selectionMode = "text"
        @Volatile private var modeSwitchable = false

        private const val QUESTIONS_KEPT = 50
        private val questions = LinkedHashMap<String, ChatTurn>()

        fun smallUp(): Boolean = instance?.let { it.isShowing && it.isMini && !it.collapsed } == true

        fun closeForPalette() { instance?.takeIf { it.isShowing && it.isMini }?.closeQuietly() }

        fun closeSmall() { instance?.takeIf { it.isShowing && it.isMini }?.closePanel() }

        fun stowSmall(): Boolean = instance?.takeIf { it.isShowing }?.stow() == true

        fun typingSmall(): Boolean = instance?.let { it.isShowing && it.isMini && it.miniTyping } == true

        fun showing(): RelayInboxPanel? = instance?.takeIf { it.isShowing }

        fun setSelectionMode(mode: String, switchable: Boolean) {
            selectionMode = mode
            modeSwitchable = switchable
            instance?.refreshModeIcon()
        }

        fun backendChanged() {
            instance?.onBackendChanged()
        }

        fun getInstance(context: ReactApplicationContext, toolbar: FloatingToolbarModule): RelayInboxPanel =
            instance ?: synchronized(this) {
                instance ?: RelayInboxPanel(context, toolbar).also { instance = it }
            }
    }

    override val tag = "RelayInboxPanel"
    override val panelName = "airRelay"
    override val yieldsLexi = true
    override val fullScreen = true

    override val windowFlags = 0
    override val softInputMode: Int =
        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

    override val keptThroughPanels: Boolean get() = mini

    private enum class Screen { LIST, EDIT, DETAIL_READ, DETAIL_SELECT }

    private val core by lazy { AIRelayCore.get(reactContext) }

    private inner class Shown<T>(initial: T) {
        var value: T = initial
            set(next) {
                if (field != next) {
                    field = next
                    scheduleContent()
                }
            }

        operator fun getValue(owner: Any?, property: KProperty<*>): T = value

        operator fun setValue(owner: Any?, property: KProperty<*>, next: T) {
            value = next
        }
    }

    private var screen by Shown(Screen.LIST)
    private var requestedDetailId by Shown<String?>(null)
    private val messagesState = Shown<List<RelayMessage>>(emptyList())
    private var detailActions: DetailSelectActions? = null

    private var contentHost: FrameLayout? = null
    private var recentView: RecentConversations? = null
    private var detailView: MessageDetailContent? = null
    private var contentQueued = false

    private val renderQueued = Choreographer.FrameCallback {
        if (rendersHeld()) {

            contentQueued = false
            if (!renderHeld) Log.i(tag, "render held: finger on the reply or dragging the window")
            renderHeld = true
        } else renderContent()
    }

    private var renderHeld = false
    private var contentRoot: LinearLayout? = null

    private var selectBar: PanelBar.Handle? = null

    private var selectInsert: PanelBar.Action? = null

    private var listView: InboxListView? = null
    private var listBar: TopBar.Handle? = null

    private var chromeView: View? = null

    private var composing by Shown(false)

    private var newChatTapped by Shown(false)

    private var awaitingReplyAfter: Set<String>? = null

    private var pendingQuestion by Shown<ChatTurn?>(null)

    private var waitingHidden by Shown(false)

    private var awaitingFormula = false

    private var snapshotLead by Shown<ChatTurn?>(null)

    private var chat: ConversationStream? = null
    private var chatConversationId: String? = null
    private var chatAnchor: Int? = null

    private var chatKept: List<ChatTurn> = emptyList()
    private var chatLiveFrom: Int? = null
    private var chatDetailId: String? = null
    private var lastSnapshot: ConversationStream.Snapshot? = null
    private var chatSettings: ConversationStream.RelaySettings? = null

    private val chatMarkers = Shown(RelayTurns.Markers(RelayTurns.DEFAULT_MARKERS))

    private var chatBaseId: String? = null
    private var chatBar: ChatInputBar? = null

    private val replaceAction = Shown<(() -> Unit)?>(null)
    private var actionsView: DetailReadActions? = null
    private var candidateFill: View? = null
    private var keyboardSeen = false
    private var keyboardHow = ""

    private var imeEdit = ""
    private val afterImeEdit = Runnable { updateKeyboardInset("$imeEdit+150") }
    private val afterImeEditLate = Runnable { updateKeyboardInset("$imeEdit+400") }
    private val chatTurns = Shown<List<ChatTurn>>(emptyList())
    private val chatGenerating = Shown(false)
    private val followSignal = Shown(0)

    private var mini by Shown(false)
    private var fullFlags = 0
    private var fullSoftInput = 0
    private var fullOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var miniDragging = false
        set(value) {
            field = value
            if (!value) releaseRender()
        }

    private var miniTyping = false

    private var miniRestY: Int? = null

    private var miniRestH: Int? = null

    private var miniKeys = 0

    private var fullKeys = 0
    private var miniMeasured = ""
    private val onSystemKeyboard: (Boolean) -> Unit = { shown ->
        val root = contentRoot
        if (root != null) {

            if (!shown && mini) endMiniTyping()
            else KEYBOARD_LOOKS_MS.forEach { ms -> root.postDelayed({ updateKeyboardInset("keyboard+$ms") }, ms) }
        }
    }

    private val attachments = mutableListOf<StagedImage>()
    private var attachmentStrip: AttachmentStrip? = null

    private val transcripts by lazy { TranscriptClient.get(reactContext) }

    private val voices = mutableListOf<String>()

    private var voiceBusy = false
    private val voiceListener = TranscriptClient.Listener { onTranscriptsChanged() }

    private var micIcon: ImageView? = null

    private var micView: View? = null
    private var micPx = 0
    private var micTint = Color.BLACK
    private var micRecording: Boolean? = null
    private var miniStartX = 0
    private var miniStartY = 0
    private var miniStartRawX = 0f
    private var miniStartRawY = 0f

    private var collapsed = false
    private var bubble: RelayMiniBubble? = null

    private var suspended = false
    private var aside = false

    private var hostPage: Pair<String, String?>? = null

    private var returnsToMini = false
    private var miniReturnId: String? = null

    private var homeIsRoot = false
    private var replyFromHome = false

    private var replyFromMini = false

    private var flash: String? = null
    private var jsStatus = ""
    private var localStatus = ""
    private val endFlash = Runnable { flash = null; renderMiniNav() }
    private val replyTimeout = Runnable {
        if (localStatus.isEmpty()) return@Runnable
        localStatus = ""
        flashStatus(NativeLocale.t("relay_reply_timeout"), 2500)
    }

    private var modeIcon: ImageView? = null
    private var modePx = 0
    private var modeTint = Color.BLACK

    private var navMic: ImageView? = null
    private var navMode: ImageView? = null

    private var miniTitle: TextView? = null
    private var nameKey: String? = null
    private var nameShown = ""
    private var bandTitle: TextView? = null
    private var modeView: View? = null
    private var miniNav: View? = null
    private var miniHome: View? = null

    val isMini: Boolean get() = mini

    override val ownsScreen: Boolean get() = !mini || miniTyping

    fun showList() = showScreen(Screen.LIST, null)

    fun showDetail(id: String) = showScreen(Screen.DETAIL_READ, id)

    fun openWindow(forPage: Boolean, onResult: (Boolean) -> Unit) {
        if (isShowing) {
            if (mini && collapsed) uncollapse("open")
            RelayOpenTrace.end("window was already up")
            onResult(true)
            return
        }
        composing = true
        awaitingReplyAfter = null
        homeIsRoot = true
        core.start()
        go(Screen.DETAIL_READ, null)
        val small = forPage || AppPrefs.openMini(reactContext)
        showPanel { shown ->
            RelayOpenTrace.mark("window added (small=$small)")
            if (shown) afterNextDraw { RelayOpenTrace.end("first frame drawn") }
            when {
                !shown -> Log.w(tag, "AIRelay window not shown")

                small -> shrinkToMini()

                else -> runAfterFirstDraw { if (!mini && isShowing) toolbar.parkToolbarUnderPanel() }
            }
            onResult(shown)
        }
    }

    private fun showScreen(to: Screen, detailId: String?) {
        core.start()

        if (!isShowing) homeIsRoot = false
        expandFromMini(toHome = false)
        go(to, detailId)
        if (!isShowing) showPanel()
    }

    fun showCompose(text: String) {
        if (isShowing && mini) {
            stageText(text)
            return
        }
        composing = true
        awaitingReplyAfter = null
        core.start()
        go(Screen.DETAIL_READ, null)
        if (isShowing) {

            stageText(text)
            return
        }
        showPanel { shown ->
            if (!shown) {
                Log.w(tag, "compose window not shown, question dropped")
                return@showPanel
            }

            shrinkToMini()
            stageText(text)
            Log.i(tag, "compose window with ${text.length} chars (relay ${if (core.isEnabled()) "on" else "off"})")
        }
    }

    private fun go(to: Screen, detailId: String? = null, keepKeyboard: Boolean = false) {

        if (to != Screen.DETAIL_READ) expandFromMini(toHome = false)

        if (to == Screen.DETAIL_READ && detailId != null && detailId != requestedDetailId) {
            replyFromHome = atStart()
            replyFromMini = false
        }

        if (to != Screen.DETAIL_READ || detailId != null) {
            composing = false
            newChatTapped = false
        }
        screen = to
        requestedDetailId = detailId
        renderChrome()
        syncChat(keepKeyboard)
        markShownSeen()
    }

    private fun atStart() = screen == Screen.DETAIL_READ && requestedDetailId == null

    private fun atHome() = atStart() && composing && (pendingQuestion == null || waitingHidden)

    private fun showStart() {
        newChatTapped = true
        waitingHidden = true
        showHere(null)
    }

    private fun markShownSeen() {
        if (collapsed || screen != Screen.DETAIL_READ) return
        val shown = detailMessage() ?: return
        val ids = messagesState.value.filter { card ->
            !card.seen && (card.id == shown.id || (shown.conversationId.isNotEmpty() && card.conversationId == shown.conversationId))
        }.map { it.id }
        if (ids.isNotEmpty()) core.markSeen(ids)
    }

    override fun buildFullScreenContent(): View {
        RelayOpenTrace.mark("building window")
        core.addListener(this)
        RelayTranslucency.load(reactContext)
        messagesState.value = core.inbox.snapshot()
        val root = object : LinearLayout(reactContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL

            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            viewTreeObserver.addOnGlobalLayoutListener { updateKeyboardInset() }
            setOnApplyWindowInsetsListener { view, insets -> view.post { updateKeyboardInset() }; insets }
        }
        SystemKeyboard.ensureRegistered(reactContext)
        SystemKeyboard.addListener(onSystemKeyboard)

        toolbar.holdMonitorForRelay()
        RelayTranslucency.onChanged = { applyLook() }
        contentRoot = root
        renderChrome()
        val host = FrameLayout(reactContext)
        contentHost = host
        root.addView(host, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val list = InboxListView(reactContext, InboxListView.Callbacks(
            onOpen = { id ->
                showOnPhone(id)
                showDetail(id)
            },
            onStar = { ids, starred -> core.setStarred(ids, starred); go(Screen.LIST) },
            onDelete = { ids -> core.deleteMany(ids); go(Screen.LIST) },
            onSelectDone = { go(Screen.LIST) },
            questionOf = { id -> questions[id]?.text },
            onInsertText = ::insertTranscriptText,
            onAskTranscript = ::askWithTranscript,
            onTranscriptConversation = ::openTranscriptChat,
            tip = { message -> Dialog.tip(reactContext, message) },
        ), RelaySettingsPane(reactContext, dslHost, core))
        list.onRowsChanged = { listBar?.setAction(listAction()) }
        listView = list
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        showLayer()

        list.setMessages(messagesState.value, now = isListScreen())
        val strip = AttachmentStrip(reactContext) { item -> attachments.remove(item); refreshAttachments() }
        strip.onRemoveVoice = ::dropVoice
        attachmentStrip = strip
        transcripts.addListener(voiceListener)
        root.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val bar = ChatInputBar(reactContext, onSend = ::sendChat, onStop = ::stopChat).apply { visibility = View.GONE }
        bar.canSendEmpty = { attachments.isNotEmpty() || voices.isNotEmpty() }

        bar.input.setOnFocusChangeListener { _, hasFocus ->
            if (mini && !hasFocus) endMiniTyping()
            root.postDelayed({ updateKeyboardInset("focus") }, 400)
        }

        bar.input.setOnTouchListener { _, ev ->
            if (mini && ev.actionMasked == MotionEvent.ACTION_DOWN) beginMiniTyping(bar.input.getOffsetForPosition(ev.x, ev.y))
            false
        }
        val onImeEdit: (String) -> Unit = { what ->
            imeEdit = what
            root.removeCallbacks(afterImeEdit)
            root.removeCallbacks(afterImeEditLate)
            root.postDelayed(afterImeEdit, 150)
            root.postDelayed(afterImeEditLate, 400)
        }
        bar.onImeEdit = onImeEdit
        list.search.input.onImeEdit = onImeEdit
        chatBar = bar
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val actions = DetailReadActions(reactContext, ::selectDetail,
            onLasso = { pageAction("lasso_ai") }, onImage = { pageAction("screenshot_ai") }, onVoice = ::toggleVoice,
            onMode = { emitAction("mode") }).apply { visibility = View.GONE }
        actionsView = actions
        bindFullTools()
        root.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val fill = View(reactContext).apply { setBackgroundColor(Color.WHITE) }
        candidateFill = fill
        root.addView(fill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        root.addView(buildMiniNav(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        syncChat()
        applyLook()
        renderContent()
        root.post { root.requestFocus() }
        RelayOpenTrace.mark("window built, ${messagesState.value.size} cards")
        return root
    }

    private fun handleBack() {
        if (chatBar?.hideKeyboard() == true) return
        if (listView?.hideKeyboard() == true) return

        if (mini) { endMiniTyping(); return }
        when (screen) {
            Screen.LIST -> if (listView?.back() != true) leaveList()
            Screen.EDIT -> go(Screen.LIST)
            Screen.DETAIL_READ -> backFromDetail()
            Screen.DETAIL_SELECT -> endSelecting()
        }
    }

    private fun updateKeyboardInset(after: String = "layout") {
        val root = contentRoot ?: return

        if (mini) { updateMiniKeyboard(after); return }
        val keyboard = ChatInputBar.keyboard(root, screenH)
        val density = root.resources.displayMetrics.density
        if (keyboard.strip > candidateStrip) candidateStrip = keyboard.strip

        val strip = (CANDIDATE_STRIP_DP * density).roundToInt()
        fullKeys = when {
            keyboard.keys <= 24 * density -> 0
            keyboard.keys > 120 * density -> if (fullKeys > 0) minOf(fullKeys, keyboard.keys) else keyboard.keys
            else -> fullKeys
        }
        val keys = fullKeys
        val under = actionsView?.takeIf { it.visibility == View.VISIBLE }?.height ?: 0
        val fillView = candidateFill

        val (inset, fill) = when {
            keys <= 0 -> 0 to 0
            fillView != null -> keys to (strip - under).coerceAtLeast(0)
            else -> keys + strip to 0
        }

        val how = "${keyboard.how} focused=${chatBar?.input?.isFocused}"
        if (root.paddingBottom != inset || (fillView != null && fillView.layoutParams.height != fill) || how != keyboardHow) {
            keyboardHow = how
            root.setPadding(0, 0, 0, inset)
            fillView?.let { it.layoutParams = it.layoutParams.apply { height = fill } }
            Log.i(tag, "keyboard inset=$inset fill=$fill (keys=$keys measured=${keyboard.keys} strip=${keyboard.strip} " +
                "candidates=$candidateStrip under=$under $how) after=$after root=${root.height} screen=$screenH")
        }
        if (keyboard.shown) keyboardSeen = true
        else if (keyboardSeen) {

            keyboardSeen = false
            chatBar?.hideKeyboard()
            listView?.hideKeyboard()
        }
    }

    private fun syncChat(keepKeyboard: Boolean = false) {
        val detail = screen == Screen.DETAIL_READ || screen == Screen.DETAIL_SELECT
        val conversationId = if (contentRoot != null && detail) {
            messagesState.value.firstOrNull { it.id == requestedDetailId }?.takeIf(core::ownsCard)?.conversationId?.ifEmpty { null }
        } else null
        if (conversationId != chatConversationId || (conversationId != null && chat == null)) {
            chat?.stop()
            chat = null
            chatAnchor = null
            chatKept = emptyList()
            chatLiveFrom = null
            lastSnapshot = null
            chatSettings = null
            chatMarkers.value = RelayTurns.Markers(RelayTurns.DEFAULT_MARKERS)
            chatBaseId = null
            chatTurns.value = inboxTurnsAfter(requestedDetailId)
            chatGenerating.value = false
            snapshotLead = null

            chatBar?.apply {
                if (!keepKeyboard) hideKeyboard()
                sending = false
                generating = false
            }
            chat = conversationId?.let(core::openConversation)?.also { stream ->
                stream.onSnapshot = { snapshot -> handler.post { if (chat === stream) applySnapshot(snapshot) } }
                stream.onReply = core::offerReply
                thread(name = "Relay-chat-settings", isDaemon = true) {
                    val settings = stream.fetchRelaySettings() ?: return@thread
                    handler.post { if (chat === stream) applyRelaySettings(settings) }
                }
            }
            chatConversationId = conversationId.takeIf { chat != null }
        }
        if (requestedDetailId != chatDetailId) {

            chatDetailId = requestedDetailId
            chatAnchor = null
            chatKept = emptyList()
            chatLiveFrom = null
            val snapshot = lastSnapshot
            if (snapshot != null) applySnapshot(snapshot) else chatTurns.value = inboxTurnsAfter(requestedDetailId)
        }

        val showBar = screen == Screen.DETAIL_READ && (chat != null || mini || composing || attachments.isNotEmpty() || voices.isNotEmpty())

        if (!showBar) chatBar?.hideKeyboard()
        chatBar?.visibility = if (showBar) View.VISIBLE else View.GONE
        attachmentStrip?.visibility = if (showBar && (attachments.isNotEmpty() || voices.isNotEmpty())) View.VISIBLE else View.GONE
        actionsView?.visibility = if (screen == Screen.DETAIL_READ && !mini) View.VISIBLE else View.GONE

        val hint = NativeLocale.t(when {
            !composing -> "relay_chat_hint"
            newChatTapped -> "relay_new_chat_hint"
            else -> "relay_current_chat_hint"
        })
        chatBar?.input?.let { input -> if (input.hint?.toString() != hint) input.hint = hint }
        refreshVoice()
    }

    private fun inboxTurnsAfter(id: String?): List<ChatTurn> {
        val shown = messagesState.value.firstOrNull { it.id == id } ?: return emptyList()
        if (shown.conversationId.isEmpty()) return emptyList()
        return messagesState.value
            .filter { it.conversationId == shown.conversationId && it.createdAt > shown.createdAt }
            .sortedBy { it.createdAt }
            .flatMap { reply -> listOfNotNull(questions[reply.id]) + RelayTurns.split(reply.id, reply.text, chatMarkers.value, lead = reply.lead) }
    }

    private fun applySnapshot(snapshot: ConversationStream.Snapshot) {
        val previous = lastSnapshot?.takeIf { it.conversationId == snapshot.conversationId }
        lastSnapshot = snapshot
        val nodes = snapshot.nodes
        core.noteChatMessages(snapshot.conversationId, nodes.flatMap { node -> node.messages.map { it.id } })
        val found = nodes.indexOfFirst { node -> node.messages.any { it.id == requestedDetailId } }

        val anchor = if (found >= 0) found else chatAnchor ?: nodes.lastIndex
        chatAnchor = anchor
        previous?.let { rewrittenFrom(it.nodes, nodes) }?.takeIf { it >= anchor - 1 }?.let { from ->

            val liveFrom = minOf(from, chatLiveFrom ?: from)
            val current = nodes.drop(liveFrom).mapNotNullTo(HashSet()) { it.current?.id }
            chatKept = chatTurns.value.filter { it.id.substringBefore('#') !in current }
            chatLiveFrom = liveFrom
            Log.i(tag, "chat rewritten on the phone from node $from: ${chatKept.size} turns kept, built again from node $liveFrom")
        }
        val liveFrom = chatLiveFrom

        if (liveFrom == null) {
            snapshotLead = nodes.getOrNull(found - 1)?.takeIf { found > 0 }?.current
                ?.takeIf { it.role == "USER" }
                ?.let(::userTurn)
        }
        val turns = chatKept.toMutableList()
        val keptIds = chatKept.mapTo(HashSet()) { it.id.substringBefore('#') }
        var baseInNode: String? = null

        val first = liveFrom ?: if (found >= 0) found else anchor + 1
        for (i in first until nodes.size) {
            val node = nodes[i]
            val message = node.current ?: continue
            if (message.id in keptIds) continue
            if (i == found) {
                val detail = node.messages.first { it.id == requestedDetailId }

                val head = detail.text.trim()
                val grown = message.takeIf {
                    it.id != detail.id && it.role == "ASSISTANT" && head.isNotEmpty() && it.text.trimStart().startsWith(head)
                }
                when {
                    grown != null -> {

                        val startsUser = RelayTurns.endsInUserTurn(detail.text, chatMarkers.value)
                        turns += RelayTurns.split(grown.id, grown.text.trimStart().substring(head.length), chatMarkers.value, startsUser = startsUser)
                    }

                    liveFrom != null && message.id != detail.id -> turns += chatTurnsOf(message)
                }
                baseInNode = (grown ?: detail).id
                continue
            }
            turns += chatTurnsOf(message)
        }
        chatTurns.value = turns

        chatBaseId = if (liveFrom == null && found >= 0 && found == nodes.lastIndex) baseInNode
            else nodes.lastOrNull()?.current?.takeIf { it.role == "ASSISTANT" }?.id
        chatGenerating.value = snapshot.generating
        chatBar?.generating = snapshot.generating

        showReadLine()
    }

    private fun chatTurnsOf(message: ConversationStream.Message): List<ChatTurn> = when (message.role) {
        "USER" -> listOfNotNull(userTurn(message))
        "ASSISTANT" -> if (message.text.isNotBlank()) RelayTurns.split(message.id, message.text, chatMarkers.value) else emptyList()
        else -> emptyList()
    }

    private fun rewrittenFrom(before: List<ConversationStream.Node>, after: List<ConversationStream.Node>): Int? {
        for ((i, old) in before.withIndex()) {
            val was = old.current ?: continue
            val now = after.getOrNull(i)?.current ?: return i
            if (now.id == was.id) continue
            val text = now.text.trimStart()
            if (now.role == was.role && old.messages.any { it.id != now.id && it.text.isNotBlank() && text.startsWith(it.text.trim()) }) continue
            return i
        }
        return null
    }

    private fun userTurn(message: ConversationStream.Message): ChatTurn? {
        val local = message.images.mapNotNull(core::localImagePath)
        val lines = List(message.images.size - local.size) { NativeLocale.t("relay_image_part") } +
            listOfNotNull(message.text.trim().ifEmpty { null })
        if (lines.isEmpty() && local.isEmpty()) return null
        return ChatTurn(message.id, true, lines.joinToString("\n"), images = local.map { StagedImage(it, "") })
    }

    private fun applyRelaySettings(settings: ConversationStream.RelaySettings) {
        chatSettings = settings
        chatMarkers.value = RelayTurns.Markers(settings.startMarkers.ifBlank { RelayTurns.DEFAULT_MARKERS })
        lastSnapshot?.let(::applySnapshot)
    }

    private fun sendChat(text: String) {
        val bar = chatBar ?: return
        val voiceIds = readyVoices() ?: return
        val images = attachments.toList()
        val stream = chat
        val conversationId = chatConversationId
        if (stream == null || conversationId == null) {
            sendAsNoteQuery(bar, text, images, voiceIds)
            return
        }

        val plainOnly = detailMessage()?.kind == "transcript"
        val existingIds = lastSnapshot?.nodes?.flatMap { node -> node.messages.map { it.id } }?.toSet().orEmpty()
        val cached = chatSettings
        val base = chatBaseId

        val inUserTurn = chatTurns.value.lastOrNull()?.fromUser
            ?: messagesState.value.firstOrNull { it.id == requestedDetailId }
                ?.let { RelayTurns.endsInUserTurn(it.text, chatMarkers.value, it.lead) }
            ?: false
        bar.sending = true
        followSignal.value++
        thread(name = "Relay-chat-send", isDaemon = true) {

            if (!core.webCanAnswer()) {
                handler.post { bar.sending = false }
                return@thread
            }

            val settings = stream.fetchRelaySettings()

            val attached = images.isNotEmpty() || voiceIds.isNotEmpty()
            val prefill = !attached && !plainOnly && (settings ?: cached)?.prefillEnabled == true
            core.prepareChatSend(conversationId, existingIds, prefill)
            val blocks = voiceBlocks(voiceIds)
            val sent = when {
                blocks == null -> false
                attached -> core.sendWithImages(stream, withVoices(text, blocks), images.map { it.imagePath to it.maskPath })
                prefill -> stream.sendQuery(text, base, continueUserTurn = inUserTurn)
                else -> stream.sendText(text)
            }
            handler.post {
                if (chat !== stream) return@post
                settings?.let(::applyRelaySettings)
                bar.sending = false
                if (sent) sentOut(bar, images, voiceIds) else Dialog.tip(reactContext, NativeLocale.t("relay_chat_failed"))
            }
        }
    }

    private fun sendAsNoteQuery(bar: ChatInputBar, text: String, images: List<StagedImage>, voiceIds: List<String>) {
        if (composing && images.isEmpty() && voiceIds.isEmpty() && !core.isEnabled()) {
            sendOnceFromCompose(bar, text)
            return
        }

        val newConversation = composing && newChatTapped

        val awaiting = composing || mini
        if (awaiting) awaitingReplyAfter = messagesState.value.mapTo(HashSet()) { it.id }
        bar.sending = true

        val done: (AIRelayCore.SubmitResult, String) -> Unit = { result, shown ->
            handler.post {
                if (chatBar !== bar) return@post
                bar.sending = false
                if (result == AIRelayCore.SubmitResult.SENT) {
                    sentOut(bar, images, voiceIds)
                    if (newConversation) {
                        newChatTapped = false
                        syncChat(keepKeyboard = true)
                    }
                    if (awaiting && awaitingReplyAfter != null) {
                        awaitingFormula = false
                        pendingQuestion = ChatTurn("question-${System.currentTimeMillis()}", true, shown, images = images)
                        waitingHidden = false

                        if (mini && requestedDetailId != null) showHere(null)

                        else if (!mini) renderChrome()
                        localStatus = NativeLocale.t("relay_waiting")
                        handler.removeCallbacks(replyTimeout)
                        handler.postDelayed(replyTimeout, REPLY_WAIT_MS)
                        renderMiniNav()
                    }
                } else if (result != AIRelayCore.SubmitResult.NOT_CONNECTED && result != AIRelayCore.SubmitResult.NO_ENDPOINT) {

                    Dialog.tip(reactContext, NativeLocale.t("relay_chat_failed"))
                }
            }
        }
        if (images.isEmpty() && voiceIds.isEmpty()) {
            core.submitQuery(text, newConversation) { done(it, text.trim()) }
        } else thread(name = "Relay-note-send", isDaemon = true) {
            val message = voiceBlocks(voiceIds)?.let { withVoices(text, it) }
            val paths = images.map { it.imagePath to it.maskPath }
            val result = when {
                message == null -> AIRelayCore.SubmitResult.FAILED
                (if (newConversation) core.sendInNewConversation(message, paths) else core.sendWithImages(null, message, paths)) ->
                    AIRelayCore.SubmitResult.SENT
                !core.isConnected() || core.webPageMissing() -> AIRelayCore.SubmitResult.NOT_CONNECTED
                else -> AIRelayCore.SubmitResult.FAILED
            }
            done(result, message.orEmpty())
        }
    }

    private fun sendOnceFromCompose(bar: ChatInputBar, text: String) {
        if (text.isBlank()) return
        bar.sending = true
        core.sendOnce(text) { result ->
            handler.post {
                Log.i(tag, "compose sent with AIRelay off: $result")
                if (chatBar === bar) bar.sending = false
                val key = when (result) {
                    AIRelayCore.OnceResult.SENT -> if (core.isWebMode()) "relay_lexi_sent_web" else "relay_lexi_sent"
                    AIRelayCore.OnceResult.NO_PAGE -> "relay_lexi_no_page"
                    AIRelayCore.OnceResult.UNPAIRED -> "relay_lexi_unpaired"
                    AIRelayCore.OnceResult.UNREACHABLE -> "relay_lexi_unreachable"
                    AIRelayCore.OnceResult.REFUSED -> "relay_lexi_refused"
                }
                Dialog.result(reactContext, NativeLocale.t(key))
                if (result == AIRelayCore.OnceResult.SENT && chatBar === bar) {
                    bar.clearText()
                    closePanel()
                }
            }
        }
    }

    private fun sentOut(bar: ChatInputBar, images: List<StagedImage>, voiceIds: List<String> = emptyList()) {
        bar.clearText()
        attachments.removeAll(images.toSet())
        voices.removeAll(voiceIds.toSet())
        refreshAttachments()
    }

    private fun refreshAttachments() {
        chatBar?.refreshSend()
        attachmentStrip?.setItems(attachments.toList(), voices.map { id ->
            val t = transcripts.transcript(id)
            val detail = when (t?.status) {
                null -> NativeLocale.t("relay_audio_starting")
                TranscriptClient.Status.RECORDING -> NativeLocale.t("relay_voice_chip_recording", t.length)
                TranscriptClient.Status.PROCESSING -> NativeLocale.t("relay_voice_chip_processing")
                else -> NativeLocale.t("relay_voice_chip", t.length)
            }
            VoiceChip(id, t?.title ?: "…", detail)
        })
        syncChat()
    }

    private fun toggleVoice() {
        if (voiceBusy) return
        val state = transcripts.state
        val recording = state.activeId?.takeIf { state.phase == TranscriptClient.Phase.RECORDING }
        if (recording == null && (!transcripts.available || state.active)) {
            val reason = when {
                state.phase == TranscriptClient.Phase.STARTING -> NativeLocale.t("relay_audio_starting")
                state.active -> NativeLocale.t("relay_audio_processing")
                transcripts.link == TranscriptClient.Link.WEB_MODE -> NativeLocale.t("relay_voice_web")
                transcripts.link == TranscriptClient.Link.NO_PHONE -> NativeLocale.t("relay_voice_unpaired")
                transcripts.link == TranscriptClient.Link.UNSUPPORTED -> NativeLocale.t("relay_audio_unsupported")
                else -> NativeLocale.t("relay_voice_unreachable")
            }
            if (mini) flashStatus(reason, 3000) else Dialog.tip(reactContext, reason)
            return
        }
        voiceBusy = true
        refreshVoice()
        if (recording != null) {

            stageVoice(recording)
            thread(name = "Relay-voice-stop", isDaemon = true) {
                val error = transcripts.stopRecording()
                handler.post {
                    voiceBusy = false
                    error?.let { Dialog.tip(reactContext, it) }
                    refreshVoice()
                }
            }
            return
        }
        thread(name = "Relay-voice-start", isDaemon = true) {
            val result = transcripts.startRecording()
            handler.post {
                voiceBusy = false
                result.onSuccess { id ->
                    stageVoice(id)
                    if (mini) flashStatus(NativeLocale.t("relay_voice_started"), 3000)
                }.onFailure { Dialog.tip(reactContext, it.message.orEmpty()) }
                refreshVoice()
            }
        }
    }

    private fun stageVoice(id: String) {
        if (collapsed) uncollapse("voice staged")
        if (id !in voices) voices += id
        refreshAttachments()
        Log.i(tag, "staged transcript $id (${voices.size} waiting)")
    }

    private fun dropVoice(id: String) {
        voices.remove(id)
        refreshAttachments()
        val state = transcripts.state
        if (state.activeId == id && state.phase == TranscriptClient.Phase.RECORDING) {
            thread(name = "Relay-voice-stop", isDaemon = true) { transcripts.stopRecording() }
        }
    }

    private fun readyVoices(): List<String>? {
        val state = transcripts.state
        val gone = voices.filter { transcripts.transcript(it) == null && it != state.activeId && transcripts.available }
        if (gone.isNotEmpty()) {
            voices.removeAll(gone.toSet())
            refreshAttachments()
        }
        val waiting = voices.any { id -> id == state.activeId || transcripts.transcript(id)?.live == true }
        if (waiting) {
            if (mini) flashStatus(NativeLocale.t("relay_voice_wait"), 3000)
            else Dialog.tip(reactContext, NativeLocale.t("relay_voice_wait"))
            return null
        }
        return voices.toList()
    }

    private fun voiceBlocks(ids: List<String>): List<String>? =
        ids.map { id -> transcripts.voiceText(id) ?: return null }

    private fun withVoices(text: String, blocks: List<String>): String =
        listOf(blocks.joinToString("\n"), text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

    private fun onTranscriptsChanged() {
        val empty = voices.filter { id -> transcripts.transcript(id)?.let { it.usable || it.live } == false }
        if (empty.isNotEmpty()) {
            voices.removeAll(empty.toSet())
            val notice = NativeLocale.t("relay_audio_no_text")
            if (mini) flashStatus(notice, 2500) else Dialog.tip(reactContext, notice)
        }
        if (voices.isNotEmpty() || empty.isNotEmpty()) refreshAttachments() else refreshVoice()
    }

    private fun refreshVoice() {

        micView?.visibility = if (core.isWebMode()) View.GONE else View.VISIBLE
        val icon = micIcon ?: return
        val state = transcripts.state
        val recording = state.phase == TranscriptClient.Phase.RECORDING
        val offered = transcripts.available || recording
        val settled = state.phase == TranscriptClient.Phase.IDLE || recording
        if (micRecording != recording) {
            micRecording = recording
            val asset = if (recording) "icons/ic_ai_mic_stop.xml" else "icons/ic_ai_mic.xml"
            icon.setImageDrawable(UiUtils.loadAssetIcon(reactContext, asset, micPx, micTint))
        }
        icon.alpha = if (offered && !voiceBusy && settled) 1f else 0.4f
    }

    private fun insertTranscriptText(text: String) {
        Dialog.tip(reactContext, NativeLocale.t(if (core.insertText(text)) "relay_audio_inserted" else "relay_audio_doc_mode"))
    }

    private fun askWithTranscript(transcript: TranscriptClient.Transcript) {
        if (transcript.id !in voices) voices += transcript.id
        composing = true
        awaitingReplyAfter = null
        go(Screen.DETAIL_READ, null)
        refreshAttachments()
    }

    private fun openTranscriptChat(transcript: TranscriptClient.Transcript) {
        thread(name = "Relay-transcript-chat", isDaemon = true) {
            val result = transcripts.toConversation(transcript.id)
            val text = transcripts.transcript(transcript.id)?.takeIf { it.full }?.text ?: transcript.text
            handler.post {
                result.onSuccess { conversationId ->
                    val cardId = core.addTranscriptCard(conversationId, transcript.title, text)

                    onInboxChanged(core.inbox.snapshot())
                    showOnPhone(cardId)
                    showDetail(cardId)
                }.onFailure { Dialog.tip(reactContext, it.message.orEmpty()) }
            }
        }
    }

    override fun stageText(text: String) {
        if (collapsed) uncollapse("text staged")
        val input = chatBar?.input ?: return
        val current = input.text?.toString().orEmpty().trimEnd()
        val joined = if (current.isEmpty()) text else "$current\n$text"
        input.setText(joined)
        input.setSelection(joined.length)
        Log.i(tag, "staged text ${text.length} chars")
    }

    override fun formulaSent(imagePath: String) {
        if (!isShowing) return
        if (awaitingReplyAfter == null) awaitingReplyAfter = messagesState.value.mapTo(HashSet()) { it.id }
        awaitingFormula = true
        pendingQuestion = ChatTurn("question-${System.currentTimeMillis()}", true, "", images = listOf(StagedImage(imagePath, "")))
        waitingHidden = false
        if (mini && requestedDetailId != null) showHere(null) else renderMiniNav()
        Log.i(tag, "formula sent, waiting for its reply here")
    }

    private fun takeAwaitedReply(replyId: String) {
        pendingQuestion?.let { question ->
            questions[replyId] = question
            while (questions.size > QUESTIONS_KEPT) questions.remove(questions.keys.first())
        }
        pendingQuestion = null
        awaitingFormula = false
    }

    override fun stageImage(imagePath: String, maskPath: String) {
        if (collapsed) uncollapse("image staged")
        attachments += StagedImage(imagePath, maskPath)
        refreshAttachments()
        Log.i(tag, "staged image $imagePath (${attachments.size} waiting)")
    }

    private fun stopChat() {
        val stream = chat ?: return
        thread(name = "Relay-chat-stop", isDaemon = true) { stream.stopGeneration() }
    }

    private fun shrinkToMini() {
        val root = contentRoot ?: return
        val lp = layoutParams ?: return
        val wm = windowManager ?: return
        if (mini || screen != Screen.DETAIL_READ) return
        RelayOpenTrace.begin("shrink to small window")
        chatBar?.hideKeyboard()
        keyboardSeen = false
        root.removeCallbacks(afterImeEdit)
        root.removeCallbacks(afterImeEditLate)
        root.setPadding(0, 0, 0, 0)
        fullKeys = 0
        candidateFill?.let { it.layoutParams = it.layoutParams.apply { height = 0 } }
        mini = true
        fullFlags = lp.flags
        fullSoftInput = lp.softInputMode
        fullOrientation = lp.screenOrientation
        placeMini(lp)
        root.foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(ScreenScale.px(reactContext, MINI_BORDER_PX).coerceAtLeast(1), Color.BLACK)
        }
        applyLook()
        RelayOpenTrace.mark("look applied")
        renderChrome()
        syncChat()
        renderContent()
        RelayOpenTrace.mark("content rendered")
        try { wm.updateViewLayout(root, lp) } catch (e: Exception) { Log.w(tag, "shrink: ${e.message}") }
        RelayOpenTrace.mark("window resized")
        Log.i(tag, "mini shown ${lp.width}x${lp.height} at ${lp.x},${lp.y}")

        me.laumss.notipal.bubbles.PaletteBubbleModule.hideAndForget()
        val owner = PenGuardOwner.panel(panelName)
        runAfterFirstDraw {
            RelayOpenTrace.end("small window drawn")
            if (!mini || !isShowing) return@runAfterFirstDraw
            FloatingPenGuard.setFullScreenActive(false, owner)

            handler.postDelayed({ Log.i(tag, "mini pen guard ${FloatingPenGuard.debugState()}") }, 300)
        }

        core.stager = this

        toolbar.stowToolbarForRelay()
        PanelRegistry.onOwnershipChanged()
    }

    private fun stow(): Boolean {
        if (!mini || collapsed || miniTyping || suspended || aside) return false
        collapse()
        return true
    }

    private fun collapse() {
        val root = contentRoot ?: return
        val lp = layoutParams ?: return
        val wm = windowManager ?: return
        if (!mini || collapsed) return
        endMiniTyping()
        FloatingDragRefresh.endNow(root)
        miniDragging = false
        collapsed = true

        lp.screenOrientation = fullOrientation
        updateMiniLayout(root, lp)
        val ball = bubble ?: RelayMiniBubble(reactContext, wm, { screenW to screenH }) { uncollapse("tap") }
            .also { bubble = it }

        if (!suspended && !aside) ball.show(lp.x + lp.width, lp.y)
        syncMiniVisibility()

        chat?.pause()
        syncIdle()
        Log.i(tag, "mini collapsed to the bubble at ${ball.params.x},${ball.params.y}")
    }

    private fun uncollapse(why: String) {
        val root = contentRoot ?: return
        val lp = layoutParams ?: return
        if (!collapsed) return
        collapsed = false
        chat?.resume()
        syncIdle()
        bubble?.setMarked(false)
        me.laumss.notipal.bubbles.PaletteBubbleModule.hideAndForget()
        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        updateMiniLayout(root, lp)
        if (suspended || aside) {
            syncMiniVisibility()
        } else {
            root.visibility = View.VISIBLE
            FloatingPenGuard.refresh(PenGuardOwner.panel(panelName), root)

            runAfterFirstDraw { if (!collapsed) bubble?.detach() }
        }
        toolbar.stowToolbarForRelay()
        markShownSeen()
        Log.i(tag, "mini opened from the bubble ($why)")
    }

    private fun syncMiniVisibility() {
        val root = rootView ?: return
        if (!mini) return
        val away = suspended || aside
        root.visibility = if (away || collapsed) View.GONE else View.VISIBLE
        if (collapsed && !away) bubble?.attach() else bubble?.detach()

        FloatingPenGuard.refresh(PenGuardOwner.panel(panelName), root)
    }

    private fun syncIdle() = core.setWindowIdle(isShowing && (collapsed || suspended))

    fun setAside(value: Boolean) {
        if (aside == value || !mini) return
        aside = value
        if (value) {
            endMiniTyping()
            contentRoot?.let(FloatingDragRefresh::endNow)
            miniDragging = false
        }
        syncMiniVisibility()
        Log.i(tag, "mini ${if (value) "aside for the lasso" else "back from the lasso"}")
    }

    fun onHostPage(app: String, file: String?) {
        val base = hostPage
        if (base == null) {
            hostPage = app to file
            Log.i(tag, "over $app file=$file")
            return
        }
        val (baseApp, baseFile) = base
        val switched = when {
            app != baseApp -> "$baseApp -> $app"
            file != null && baseFile != null && !samePath(file, baseFile) -> "file ${File(baseFile).name} -> ${File(file).name}"
            else -> {
                if (baseFile == null && file != null) hostPage = app to file
                return
            }
        }
        hostPage = app to file
        if (!mini) {
            Log.i(tag, "page changed under the full screen ($switched)")
            return
        }
        Log.i(tag, "page changed ($switched): small window put away (collapsed=$collapsed)")
        closeUnlessWaiting()
    }

    private fun samePath(a: String, b: String): Boolean {
        fun canonical(p: String) = runCatching { File(p).canonicalPath }.getOrElse { File(p).absolutePath }
        return a == b || canonical(a) == canonical(b)
    }

    fun setStatus(text: String) {
        jsStatus = text
        renderMiniNav()
    }

    fun flashStatus(text: String, ms: Long) {
        jsStatus = ""
        flash = text
        handler.removeCallbacks(endFlash)
        handler.postDelayed(endFlash, ms)
        renderMiniNav()
    }

    fun closeFromJs() = closePanel()

    private var quietClose = false

    private fun closeQuietly() {
        Log.i(tag, "small window closed for the palette bubble, AIRelay left on")
        quietClose = true
        closePanel()
    }

    private fun emitAction(actionId: String) {
        toolbarModule.emitEvent("onRelayWindowAction", Arguments.createMap().apply { putString("actionId", actionId) })
    }

    private fun modeOffered() = BuildConfig.ENABLE_DEBUG && modeSwitchable && !core.isWebMode()

    private fun onBackendChanged() {
        if (core.isWebMode() && selectionMode == "formula") {
            selectionMode = "text"
            emitAction("mode_text")
        }
        refreshModeIcon()
        refreshVoice()

        syncChat()
    }

    fun replyFailed(text: String) {
        handler.removeCallbacks(replyTimeout)
        val question = pendingQuestion
        if (localStatus.isNotEmpty() && question != null && awaitingReplyAfter != null && !awaitingFormula) {
            awaitingReplyAfter = null
            pendingQuestion = null
            waitingHidden = false
            chatBar?.input?.let { input ->
                if (input.text.isNullOrBlank()) {
                    input.setText(question.text)
                    input.setSelection(question.text.length)
                }
            }
            question.images.filterTo(attachments) { it !in attachments }
            refreshAttachments()
        }
        localStatus = ""
        emitAction("reply_failed")
        flashStatus(text, 6_000L)
    }

    private fun refreshModeIcon() = handler.post {
        val icon = modeIcon ?: return@post
        modeView?.visibility = if (modeOffered()) View.VISIBLE else View.GONE
        icon.setImageDrawable(UiUtils.loadAssetIcon(reactContext, modeIconAsset(), modePx, modeTint))
    }

    private fun modeIconAsset() = if (selectionMode == "formula") "icons/ic_ai_mode_formula.xml" else "icons/ic_ai_mode_text.xml"

    private fun onReplyShown() {
        if (!collapsed) return
        if (AppPrefs.autoOpenDetail(reactContext)) uncollapse("reply") else bubble?.setMarked(true)
    }

    private fun expandFromMini(toHome: Boolean) {
        if (!mini) return
        RelayOpenTrace.begin("expand to full screen")
        val root = contentRoot
        val lp = layoutParams
        returnsToMini = true
        miniReturnId = requestedDetailId
        homeIsRoot = toHome
        replyFromHome = false
        replyFromMini = false

        val wasTyping = miniTyping
        miniTyping = false
        miniRestY = null
        miniRestH = null
        if (wasTyping) {
            FloatingPenGuard.endKeyboardExpect("window grows")
            chatBar?.hideKeyboard()
        }
        mini = false
        if (collapsed) {
            collapsed = false
            bubble?.detach()
        }
        aside = false
        if (!suspended) root?.visibility = View.VISIBLE
        if (core.stager === this) core.stager = null
        root?.foreground = null
        PanelRegistry.onOwnershipChanged()
        if (root == null || lp == null) return
        FloatingDragRefresh.endNow(root)
        miniDragging = false
        lp.softInputMode = fullSoftInput
        lp.screenOrientation = fullOrientation
        FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.panel(panelName))
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.x = 0
        lp.y = 0
        lp.flags = fullFlags
        RelayOpenTrace.mark("pen guard full")
        applyLook()
        RelayOpenTrace.mark("look applied")
        renderChrome()
        syncChat()
        renderContent()
        RelayOpenTrace.mark("content rendered")
        try { windowManager?.updateViewLayout(root, lp) } catch (e: Exception) { Log.w(tag, "expand: ${e.message}") }
        RelayOpenTrace.mark("window resized")

        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
                RelayOpenTrace.mark("laid out ${root.width}x${root.height}, drawing")
                return true
            }
        })
        Log.i(tag, "mini expanded to full screen")
        runAfterFirstDraw {
            RelayOpenTrace.end("full screen drawn")
            if (!mini && isShowing) toolbar.parkToolbarUnderPanel()
        }
    }

    private fun placeMini(lp: WindowManager.LayoutParams) {

        toolbarModule.refreshScreenDimensions()
        val short = minOf(screenW, screenH)
        val long = maxOf(screenW, screenH)
        val w = (short * MINI_WIDTH_RATIO).roundToInt()
        val h = (long * MINI_HEIGHT_RATIO).roundToInt()
        Log.i(tag, "mini size ${w}x$h for screen ${screenW}x$screenH")
        val margin = dp(MINI_MARGIN_DP)
        lp.width = w
        lp.height = h
        lp.x = clampMiniX(if (miniX != Int.MIN_VALUE) miniX else screenW - w - margin, w)
        lp.y = clampMiniY(if (miniY != Int.MIN_VALUE) miniY else screenH - h - margin, h)
        lp.flags = MINI_FLAGS

        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }

    private fun updateMiniLayout(root: View, lp: WindowManager.LayoutParams) {
        try { windowManager?.updateViewLayout(root, lp) } catch (e: Exception) { Log.w(tag, "mini layout: ${e.message}") }
    }

    private fun beginMiniTyping(tapOffset: Int) {
        val root = contentRoot ?: return
        val lp = layoutParams ?: return
        if (miniTyping) return
        miniTyping = true
        keyboardSeen = false
        miniKeys = 0
        miniMeasured = ""
        PanelRegistry.onOwnershipChanged()
        ScreenshotBubble.hideForPalette()

        if (!SystemKeyboard.shown) FloatingPenGuard.expectKeyboard()
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        updateMiniLayout(root, lp)
        Log.i(tag, "mini typing begins")

        root.postDelayed({
            val input = chatBar?.input ?: return@postDelayed
            if (!mini || !miniTyping) return@postDelayed
            input.requestFocus()

            if (tapOffset in 0..input.length()) input.setSelection(tapOffset)
            (reactContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, 0)
        }, 150)
        KEYBOARD_LOOKS_MS.forEach { ms -> root.postDelayed({ updateKeyboardInset("mini-typing+$ms") }, 350 + ms) }
    }

    private fun endMiniTyping() {
        if (!miniTyping) return
        miniTyping = false
        keyboardSeen = false
        PanelRegistry.onOwnershipChanged()
        FloatingPenGuard.endKeyboardExpect("typing ended")
        chatBar?.hideKeyboard()
        val root = contentRoot ?: return
        val lp = layoutParams ?: return
        miniRestY?.let { lp.y = it }
        miniRestY = null
        miniRestH?.let { lp.height = it }
        miniRestH = null
        if (!mini) return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        updateMiniLayout(root, lp)
        Log.i(tag, "mini typing ends y=${lp.y}")
    }

    private fun updateMiniKeyboard(after: String) {
        val root = contentRoot ?: return
        val lp = layoutParams ?: return
        if (!miniTyping) return
        val density = root.resources.displayMetrics.density
        val visible = Rect().also(root::getWindowVisibleDisplayFrame)
        val framed = (screenH - visible.bottom).coerceAtLeast(0)
        val reported = ChatInputBar.imeVisibleHeight(root.context)
        val seen = 120 * density

        if (reported > seen) miniKeys = if (miniKeys > 0) minOf(miniKeys, reported) else reported
        val keys = if (framed > seen) framed else miniKeys.takeIf { reported > seen } ?: 0
        val actualY = IntArray(2).also(root::getLocationOnScreen)[1]
        val measured = "lp.y=${lp.y} actualY=$actualY reported=$reported framed=$framed keys=$keys candidates=$candidateStrip"
        if (measured != miniMeasured) {
            miniMeasured = measured
            Log.i(tag, "mini measure $measured after=$after")
        }
        if (keys <= 0) {

            if (keyboardSeen && !SystemKeyboard.shown) endMiniTyping()
            return
        }
        keyboardSeen = true
        if (reported - keys > candidateStrip) candidateStrip = reported - keys
        val strip = (CANDIDATE_STRIP_DP * density).roundToInt()
        val under = miniNav?.takeIf { it.visibility == View.VISIBLE }?.height ?: 0
        val room = screenH - keys - strip + under + dp(MINI_IME_SLACK_DP)
        val restY = miniRestY ?: lp.y.also { miniRestY = it }
        val restH = miniRestH ?: lp.height
        val y = minOf(restY, room - restH).coerceAtLeast(0)
        val h = minOf(restH, room - y).coerceAtLeast(ScreenScale.px(reactContext, MINI_KEEP_PX))
        if (y == lp.y && h == lp.height) return
        if (h != restH) miniRestH = restH
        lp.y = y
        lp.height = h
        updateMiniLayout(root, lp)
        Log.i(tag, "mini above keyboard y=$y h=$h keys=$keys strip=$strip (reported=$reported framed=$framed) after=$after")
    }

    private fun clampMiniX(x: Int, w: Int): Int {
        val keep = ScreenScale.px(reactContext, MINI_KEEP_PX).coerceAtMost(w)
        return x.coerceIn(keep - w, screenW - keep)
    }

    private fun clampMiniY(y: Int, h: Int): Int {
        val keep = ScreenScale.px(reactContext, MINI_KEEP_PX).coerceAtMost(h)
        return y.coerceIn(0, (screenH - keep).coerceAtLeast(0))
    }

    private fun onMiniHeaderTouch(ev: MotionEvent): Boolean {
        val root = contentRoot ?: return false
        val lp = layoutParams ?: return false
        if (!mini) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                miniStartX = lp.x
                miniStartY = lp.y
                miniStartRawX = ev.rawX
                miniStartRawY = ev.rawY
                miniDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!TouchInput.isFinger(ev)) return true
                val dx = ev.rawX - miniStartRawX
                val dy = ev.rawY - miniStartRawY
                if (!miniDragging && (abs(dx) > BubbleWindowSupport.DRAG_THRESHOLD_PX ||
                        abs(dy) > BubbleWindowSupport.DRAG_THRESHOLD_PX)) {
                    miniDragging = true
                    FloatingDragRefresh.begin(root, lp.x, lp.y)
                }
                if (miniDragging) {
                    lp.x = clampMiniX(miniStartX + dx.toInt(), lp.width)
                    lp.y = clampMiniY(miniStartY + dy.toInt(), lp.height)
                    if (!FloatingDragRefresh.move(root, lp.x, lp.y)) {
                        try { windowManager?.updateViewLayout(root, lp) } catch (_: Exception) {}
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (miniDragging) {
                    miniDragging = false
                    try { windowManager?.updateViewLayout(root, lp) } catch (_: Exception) {}
                    FloatingDragRefresh.end(root)
                    miniX = lp.x
                    miniY = lp.y

                    miniRestY = null
                    Log.i(tag, "mini dragEnd x=${lp.x} y=${lp.y}")
                }
            }
        }
        return true
    }

    override fun onRotation(): Boolean {
        if (!mini || !isShowing) return super.onRotation()
        val root = contentRoot ?: return false
        val lp = layoutParams ?: return false
        toolbarModule.refreshScreenDimensions()
        endMiniTyping()
        FloatingDragRefresh.endNow(root)
        miniDragging = false
        placeMini(lp)
        if (collapsed) lp.screenOrientation = fullOrientation
        try { windowManager?.updateViewLayout(root, lp) } catch (_: Exception) {}
        bubble?.refit()
        return false
    }

    override fun resumeVisibility() {
        if (!mini) {
            suspended = false
            syncIdle()
            return super.resumeVisibility()
        }
        handler.post {
            if (FloatingToolbarModule.areFloatingOverlaysSuspendedForPalette()) return@post
            val root = rootView ?: return@post
            if (!mini) return@post
            val key = PenGuardOwner.panel(panelName)
            suspended = false
            syncIdle()
            syncMiniVisibility()
            if (root.visibility != View.VISIBLE) return@post
            HostPenSignals.scheduleForegroundReassert("airRelay mini shown again")
            runAfterFirstDraw { if (mini && rootView === root) FloatingPenGuard.refresh(key, root) }
            Log.i(tag, "mini resumed, pen guard ${FloatingPenGuard.debugState()}")
        }
    }

    override fun suspendVisibility() {
        if (!mini) {
            suspended = true
            syncIdle()
            return super.suspendVisibility()
        }
        handler.post {
            if (!mini || !isShowing) return@post
            suspended = true
            syncIdle()
            endMiniTyping()
            contentRoot?.let(FloatingDragRefresh::endNow)
            miniDragging = false
            syncMiniVisibility()
            Log.i(tag, "mini put away (collapsed=$collapsed)")
        }
    }

    override fun onHide() {
        val wasMini = mini
        contentRoot?.let(FloatingDragRefresh::endNow)
        miniDragging = false
        miniTyping = false
        miniRestY = null
        miniRestH = null
        FloatingPenGuard.endKeyboardExpect("window closed")
        mini = false
        composing = false
        newChatTapped = false
        awaitingReplyAfter = null
        pendingQuestion = null
        waitingHidden = false
        awaitingFormula = false
        snapshotLead = null
        bubble?.detach()
        bubble = null
        collapsed = false
        core.setWindowIdle(false)
        suspended = false
        aside = false
        returnsToMini = false
        miniReturnId = null
        homeIsRoot = false
        replyFromHome = false
        replyFromMini = false
        flash = null
        jsStatus = ""
        localStatus = ""
        handler.removeCallbacks(endFlash)
        handler.removeCallbacks(replyTimeout)
        modeIcon = null
        modeView = null
        miniNav = null
        miniHome = null
        navMic = null
        navMode = null
        bandTitle = null
        miniTitle = null
        nameKey = null
        if (core.stager === this) core.stager = null
        attachments.clear()
        attachmentStrip = null
        transcripts.removeListener(voiceListener)
        voices.clear()
        voiceBusy = false
        micIcon = null
        micView = null
        micRecording = null
        chat?.stop()
        chat = null
        chatConversationId = null
        chatDetailId = null
        lastSnapshot = null
        chatSettings = null
        chatBaseId = null
        contentRoot?.removeCallbacks(afterImeEdit)
        contentRoot?.removeCallbacks(afterImeEditLate)
        SystemKeyboard.removeListener(onSystemKeyboard)
        chatBar = null
        keyboardSeen = false
        core.removeListener(this)
        RelayTranslucency.onChanged = null
        Choreographer.getInstance().removeFrameCallback(renderQueued)
        contentQueued = false
        contentHost = null
        recentView = null
        detailView?.clear()
        detailView = null
        actionsView = null
        candidateFill = null
        replaceAction.value = null
        contentRoot = null
        selectBar = null
        selectInsert = null
        listView = null
        listBar = null
        chromeView = null
        detailActions = null
        instance = null

        toolbar.unstowToolbarAfterRelay()
        if (wasMini) toolbar.restoreToolbar()
        toolbar.releaseMonitorForRelay()
    }

    override fun emitCloseEvent() {
        if (quietClose) {
            quietClose = false
            return
        }
        toolbarModule.emitEvent(
            "onNativePanelClose",
            Arguments.createMap().apply {
                putString("panel", panelName)
                requestedDetailId?.let { putString("detailId", it) }
            }
        )
    }

    override fun onInboxChanged(messages: List<RelayMessage>) {
        messagesState.value = messages
        listView?.setMessages(messages)

        val sentWith = awaitingReplyAfter
        val reply = sentWith?.let { ids -> messages.firstOrNull { it.source == "llm" && it.id !in ids } }
        if (reply != null) {
            awaitingReplyAfter = null
            takeAwaitedReply(reply.id)
            localStatus = ""
            handler.removeCallbacks(replyTimeout)
            if (opensHere()) {
                Log.i(tag, "awaited reply ${reply.id} opens here (mini=$mini collapsed=$collapsed)")
                go(Screen.DETAIL_READ, reply.id, keepKeyboard = true)
                onReplyShown()
                return
            }
            if (mini) flashStatus(NativeLocale.t("relay_reply_arrived"), 3000)
        }
        if (requestedDetailId != null && messages.none { it.id == requestedDetailId }) {

            if (mini || replyFromHome) showHere(null) else go(Screen.LIST)
        }

        else {

            markShownSeen()
            syncChat()
            renderMiniNav()
        }
    }

    private fun detailMessage(): RelayMessage? = messagesState.value.firstOrNull { it.id == requestedDetailId }

    override fun onFormulaReplyReady(messageId: String) {
        if (!isShowing) return
        if (awaitingFormula) {
            awaitingReplyAfter = null
            takeAwaitedReply(messageId)
        }

        if (opensHere()) {
            go(Screen.DETAIL_READ, messageId, keepKeyboard = true)
            onReplyShown()
        } else if (mini) flashStatus(NativeLocale.t("relay_reply_arrived"), 3000)
    }

    private fun opensHere() = atStart() || (mini && collapsed)

    private fun closePanel() {
        hide()
        toolbar.restoreToolbar()
    }

    private fun replyOnItsWay(): Boolean {
        if (localStatus.isNotEmpty() || (awaitingFormula && jsStatus.isNotEmpty())) return true
        val since = System.currentTimeMillis() - STREAM_ALIVE_MS
        return messagesState.value.any { !it.closed && it.updatedAt > since }
    }

    private fun closeUnlessWaiting() {
        if (!replyOnItsWay()) {
            closePanel()
            return
        }
        Log.i(tag, "close while a reply is on its way: collapsing")
        if (mini) {
            collapse()
            return
        }
        if (screen != Screen.DETAIL_READ) showHere(null)
        collapseFromFull()
    }

    private fun backFromDetail() {
        when {
            mini -> closePanel()
            requestedDetailId != null -> when {
                replyFromMini -> returnToMini()
                replyFromHome -> showHere(null)
                else -> go(Screen.LIST)
            }
            homeIsRoot -> leave()
            else -> go(Screen.LIST)
        }
    }

    private fun leaveList() {
        if (homeIsRoot) showHere(null) else leave()
    }

    private fun leave() {
        if (returnsToMini) returnToMini() else closeUnlessWaiting()
    }

    private fun openInbox() = go(Screen.LIST)

    private fun openSettings() {
        go(Screen.LIST)
        listView?.showSettings()
    }

    private fun returnToMini() {
        val keep = miniReturnId?.takeIf { wanted -> messagesState.value.any { it.id == wanted } }
        composing = keep == null
        go(Screen.DETAIL_READ, keep)
        shrinkToMini()
    }

    private fun showHere(id: String?) {
        if (id == null) composing = true
        go(Screen.DETAIL_READ, id)
    }

    private fun showOnPhone(cardId: String) {
        val card = messagesState.value.firstOrNull { it.id == cardId } ?: return
        if (card.kind != "formula" && card.conversationId.isNotEmpty()) core.openOnPhone(card.conversationId)
    }

    private fun conversationCards(message: RelayMessage): List<RelayMessage> =
        if (message.conversationId.isEmpty()) listOf(message)
        else messagesState.value.filter { it.conversationId == message.conversationId }

    private fun showInsertOnPage() {
        if (screen != Screen.DETAIL_READ) go(Screen.DETAIL_READ, requestedDetailId)
        shrinkToMini()
        flashStatus(NativeLocale.t("relay_inserting"), 2000)
    }

    private fun selectDetail() = go(Screen.DETAIL_SELECT, requestedDetailId)

    private fun endSelecting() = go(Screen.DETAIL_READ, requestedDetailId)

    private fun copyToClipboard(text: String) {
        val clipboard = reactContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Notipal", text))
        endSelecting()
        Dialog.tip(reactContext, NativeLocale.t("relay_copied"))
    }

    private fun scheduleContent() {
        if (contentHost == null || contentQueued) return
        contentQueued = true
        Choreographer.getInstance().postFrameCallback(renderQueued)
    }

    private fun rendersHeld() = miniDragging || detailView?.touching == true

    private fun releaseRender() {
        if (!renderHeld || rendersHeld()) return
        renderHeld = false
        Log.i(tag, "render released")
        scheduleContent()
    }

    private fun renderContent() {
        val started = SystemClock.elapsedRealtime()
        try { renderContentNow() } finally {
            val took = SystemClock.elapsedRealtime() - started
            if (took >= SLOW_RENDER_MS) Log.i(tag, "slow render: ${took}ms (screen=$screen mini=$mini)")
        }
    }

    private fun renderContentNow() {
        Choreographer.getInstance().removeFrameCallback(renderQueued)
        contentQueued = false
        renderHeld = false
        val host = contentHost ?: return
        val detailScreen = screen == Screen.DETAIL_READ || screen == Screen.DETAIL_SELECT
        val home = detailScreen && atHome()
        if (home) {
            val recent = recentView ?: RecentConversations(
                reactContext,
                questionOf = { id -> questions[id]?.text },
                onOpen = { id ->
                    showOnPhone(id)
                    showHere(id)
                },
                onInbox = ::openInbox,
                emptyHint = NativeLocale.t("relay_compose_hint"),
            ).also {
                recentView = it
                host.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                applyLook()
            }
            recent.visibility = View.VISIBLE

            recent.setNewChat(newChatTapped)
            recent.setMessages(if (newChatTapped) emptyList() else messagesState.value)
        } else {
            recentView?.visibility = View.GONE
        }
        if (detailScreen && !home) {
            val detail = detailView ?: newDetailView(host)
            detail.visibility = View.VISIBLE
            val shown = detailMessage()
            detail.bind(MessageDetailContent.Model(
                message = shown,
                selecting = screen == Screen.DETAIL_SELECT,
                markers = chatMarkers.value,
                chatTurns = chatTurns.value,
                chatGenerating = chatGenerating.value,
                followSignal = followSignal.value,
                emptyTitle = if (composing && newChatTapped) NativeLocale.t("relay_new_chat") else null,
                leadTurn = requestedDetailId.let { id ->
                    if (id == null) pendingQuestion

                    else questions[id] ?: snapshotLead?.takeIf { shown?.kind != "formula" }
                },
            ))
        } else {
            detailView?.let {
                it.clear()
                it.visibility = View.GONE
            }
        }
        val hasReply = detailMessage() != null
        actionsView?.setReplace(replaceAction.value.takeIf { hasReply })
        actionsView?.bind(hasReply)
        renderMiniNav()
        showReadLine()
    }

    private fun newDetailView(host: FrameLayout): MessageDetailContent {
        val detail = MessageDetailContent(reactContext, MessageDetailContent.Callbacks(
            onSelectionPayload = ::insertPicked,
            onReplaceHandwriting = { payload ->
                requestedDetailId?.let { id ->
                    core.insertBlocks(id, payload.plainText, payload.blocksJson, replace = true)
                    showInsertOnPage()
                }
            },
            onSelectionActionsChanged = { actions ->
                detailActions = actions
                if (screen == Screen.DETAIL_SELECT) {
                    showSelectState()
                }
            },
            onReplaceChanged = { replace -> replaceAction.value = replace },

            onReadLine = { _, _ -> },
        ))
        detail.onReleased = ::releaseRender
        detailView = detail
        host.addView(detail, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        applyLook()
        return detail
    }

    private fun insertPicked(payload: FormulaBlocks.Payload) {
        if (FloatingToolbarModule.isDocMode()) {
            copyToClipboard(payload.plainText)
            return
        }
        val id = requestedDetailId ?: return
        if (payload.hasMath) core.insertBlocks(id, payload.plainText, payload.blocksJson, replace = false)
        else core.insertSelection(id, payload.plainText)
        showInsertOnPage()
    }

    private fun applyLook() {

        val paper = if (mini) RelayTranslucency.paperArgbValue else Color.WHITE
        contentRoot?.setBackgroundColor(if (mini) Color.TRANSPARENT else Color.WHITE)
        recentView?.setBackgroundColor(paper)
        detailView?.setPaper(paper)
        recentView?.setSmall(mini)
        detailView?.setSmall(mini)
        chatBar?.setSmall(mini)
    }

    private fun isListScreen() = !mini && (screen == Screen.LIST || screen == Screen.EDIT)

    private fun showLayer() {
        val list = isListScreen()
        listView?.visibility = if (list) View.VISIBLE else View.GONE
        contentHost?.visibility = if (list) View.GONE else View.VISIBLE
        if (!list) listView?.hideKeyboard()
        listView?.setEditing(screen == Screen.EDIT)
        if (list) listView?.refreshIfStale()
    }

    private fun renderChrome() {
        val root = contentRoot ?: return
        chromeView?.let(root::removeView)
        selectBar = null
        selectInsert = null
        listBar = null
        micIcon = null
        micView = null
        micRecording = null
        modeIcon = null
        modeView = null
        bandTitle = null
        miniTitle = null
        showLayer()
        if (screen == Screen.EDIT && isListScreen()) {
            val bar = listSelectBar()
            chromeView = bar.view
            root.addView(bar.view, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bar.heightPx))
            renderMiniNav()
            return
        }
        if (isListScreen()) {
            val back = returnsToMini || homeIsRoot
            val bar = TopBar.build(
                reactContext,
                closeLabel = NativeLocale.t(if (back) "picker_back" else "close"),
                onClose = ::leaveList,
                onAction = { go(Screen.EDIT) },
                actionLabels = listOf(NativeLocale.t("relay_select")),
                closeGlyph = if (back) LineIcon.Glyph.BACK else LineIcon.Glyph.CLOSE,
                trailing = listOf(newChatCell()),
            )
            bar.setTitle("AIRelay")
            bar.setAction(listAction())
            listBar = bar
            chromeView = bar.view
            root.addView(bar.view, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            renderMiniNav()
            return
        }
        if (mini) {
            val bar = miniBar()
            chromeView = bar.view
            root.addView(bar.view, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bar.heightPx))
            bindNavTools()
        } else {
            if (screen == Screen.DETAIL_SELECT) {
                val bar = replySelectBar()
                chromeView = bar.view
                root.addView(bar.view, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bar.heightPx))
            } else {
                val bar = detailBand()
                chromeView = bar
                root.addView(bar, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Snux.u(reactContext, 72)))
            }
        }
        refreshVoice()
        renderMiniNav()
    }

    private fun listAction(): String? =
        if (screen == Screen.LIST && listView?.hasRows == true) NativeLocale.t("relay_select") else null

    private fun newChatCell(): FrameLayout =
        TopBar.iconCell(reactContext, LineIcon.Glyph.PLUS, NativeLocale.t("relay_new_chat"), ::showStart)

    private fun assetCell(asset: String, description: String, onClick: () -> Unit): FrameLayout {
        val icon = ImageView(reactContext).apply {
            setImageDrawable(UiUtils.loadAssetIcon(reactContext, asset, TopBar.iconPx(reactContext), Color.BLACK))
            scaleType = ImageView.ScaleType.CENTER
        }
        return TopBar.viewCell(reactContext, icon, description, onClick)
    }

    private fun detailBand(): View {
        val ctx = reactContext
        fun u(v: Number) = Snux.u(ctx, v)
        val closes = atStart() && homeIsRoot && !returnsToMini
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAND)
            setPadding(u(4), 0, u(12), 0)
        }
        row.addView(TopBar.iconCell(ctx, if (closes) LineIcon.Glyph.CLOSE else LineIcon.Glyph.BACK,
            NativeLocale.t(if (closes) "close" else "picker_back"), ::backFromDetail))
        val title = Snux.text(ctx, "", 16, Color.BLACK).apply {
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        bandTitle = title
        row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = u(6); rightMargin = u(14) })

        fun tab(label: String, on: Boolean, onClick: () -> Unit) = Snux.text(ctx, label, 17, Color.BLACK, bold = on).apply {
            gravity = Gravity.CENTER
            setPadding(u(20), 0, u(20), 0)
            if (on) setBackgroundColor(Color.WHITE) else setOnClickListener { onClick() }
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(tab(NativeLocale.t("relay_tab_new"), false, ::showStart), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, u(54)))
            addView(tab(NativeLocale.t("relay_tab_full"), true) {}, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, u(54)))
            addView(tab(NativeLocale.t("relay_tab_mini"), false, ::shrinkToMini), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, u(54)))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = u(8) })
        bindFullTools()
        showReadLine()
        return row
    }

    private fun showReadLine() {
        val title = windowTitle()
        bandTitle?.let { if (it.text.toString() != title) it.text = title }
        miniTitle?.let { if (it.text.toString() != title) it.text = title }
    }

    private fun windowTitle(): String {
        (flash ?: jsStatus.ifEmpty { null } ?: localStatus.ifEmpty { null })?.let { return it }
        val message = detailMessage()
        if (message == null || atHome()) {
            if (composing && newChatTapped) return NativeLocale.t("relay_new_chat")
            val waiting = pendingQuestion?.text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            return waiting?.takeIf { !atHome() } ?: "AIRelay"
        }
        lastSnapshot?.takeIf { it.conversationId == message.conversationId }?.title?.trim()
            ?.takeIf { it.isNotEmpty() && it != WebConversationStore.DEFAULT_TITLE }
            ?.let { return it }
        val first = conversationCards(message).minByOrNull { it.createdAt } ?: message
        val key = "${first.id}@${first.updatedAt}@${questions[first.id]?.text}"
        if (key != nameKey) {
            nameKey = key
            nameShown = rowTitle(questions[first.id]?.text, summaryLines(first.text).firstOrNull() ?: first.text.trim())
        }
        return nameShown
    }

    private fun listSelectBar(): PanelBar.Handle {
        val list = listView
        val star = PanelBar.Action("icons/ic_star.xml", NativeLocale.t("relay_star")) { list?.starPicked() }
        val delete = PanelBar.Action("icons/ic_bar_delete.xml", NativeLocale.t("relay_delete")) { list?.deletePicked() }
        val bar = PanelBar.build(
            reactContext, PanelBar.Style.INBOX,
            left = listOf(PanelBar.TextBtn(NativeLocale.t("cancel")) { go(Screen.LIST) }),
            center = listOf(star, delete),
            right = listOf(PanelBar.Title("")),
        )
        list?.onPicks = { count, unstar ->
            bar.setTitleLabel(0, NativeLocale.t("relay_picked", count))
            bar.updateAction(star, count > 0,
                if (unstar) "icons/ic_star_on.xml" else "icons/ic_star.xml",
                NativeLocale.t(if (unstar) "relay_unstar" else "relay_star"))
            bar.updateAction(delete, count > 0)
        }
        list?.renderActions()
        return bar
    }

    private fun replySelectBar(): PanelBar.Handle {
        val inDocument = FloatingToolbarModule.isDocMode()
        val insert = PanelBar.Action(
            if (inDocument) "icons/ic_bar_copy.xml" else "icons/ic_bar_insert.xml",
            NativeLocale.t(if (inDocument) "relay_copy" else "relay_insert"),
        ) { detailActions?.takeIf { it.canInsert }?.insert?.invoke() }
        val bar = PanelBar.build(
            reactContext, PanelBar.Style.INBOX,
            left = listOf(PanelBar.TextBtn(NativeLocale.t("cancel"), ::endSelecting)),
            center = listOf(
                PanelBar.Action("icons/ic_bar_clear.xml", NativeLocale.t("relay_clear")) { detailActions?.clear?.invoke() },
                insert,
            ),
            right = listOf(PanelBar.Title("")),
        )
        selectBar = bar
        selectInsert = insert
        showSelectState()
        return bar
    }

    private fun showSelectState() {
        val actions = detailActions
        selectBar?.setTitleLabel(0, NativeLocale.t("relay_selected_rows", actions?.selectedRowCount ?: 0))
        selectInsert?.let { selectBar?.updateAction(it, actions?.canInsert == true) }
    }

    private fun pageAction(actionId: String) {
        if (!mini) shrinkToMini()
        emitAction(actionId)
    }

    private fun collapseFromFull() {
        shrinkToMini()
        runAfterFirstDraw { if (mini && !collapsed) collapse() }
    }

    private fun miniPx(v: Int) = ScreenScale.px(reactContext, v)

    private fun barIcon(asset: String, width: Int, onClick: () -> Unit): ImageView = ImageView(reactContext).apply {
        setImageDrawable(UiUtils.loadAssetIcon(reactContext, asset, miniPx(MINI_BAR_ICON_PX), Color.WHITE))
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT)
        setOnClickListener { onClick() }
    }

    private fun miniBar(): PanelBar.Handle {
        val ctx = reactContext
        val barH = miniPx(MINI_BAR_PX)
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(TextView(ctx).apply {
                miniTitle = this
                text = windowTitle()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenScale.textPx(ctx, MINI_TITLE_PX))
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(miniPx(24), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            })
            addView(barIcon("icons/ic_window_expand.xml", barH) {
                val atReply = !atStart()
                expandFromMini(toHome = !atReply)
                replyFromMini = atReply
            })
            addView(barIcon("icons/ic_window_collapse.xml", barH, ::collapse))
            addView(barIcon("icons/ic_ai_close.xml", barH, ::closeUnlessWaiting))
            setOnTouchListener { _, ev -> onMiniHeaderTouch(ev) }
        }
        return PanelBar.Handle(view, barH, ctx, PanelBar.Style.PAGE_HEADER, null)
    }

    private fun bindFullTools() {
        if (mini || screen != Screen.DETAIL_READ) return
        val actions = actionsView ?: return
        micIcon = actions.micButton
        micView = actions.micCell
        micPx = Snux.u(reactContext, STATUS_ICON)
        micTint = Color.BLACK
        micRecording = null
        modeIcon = actions.modeButton
        modePx = Snux.u(reactContext, STATUS_ICON)
        modeTint = Color.BLACK
        modeView = actions.modeCell
        refreshVoice()
        refreshModeIcon()
    }

    private fun bindNavTools() {
        if (!mini) return
        navMic?.let { icon ->
            micIcon = icon
            micView = icon
            micPx = miniPx(MINI_ICON_PX)
            micTint = Color.BLACK
            micRecording = null
        }
        navMode?.let { icon ->
            modeIcon = icon
            modePx = miniPx(MINI_ICON_PX)
            modeTint = Color.BLACK
            modeView = icon
        }
        refreshVoice()
        refreshModeIcon()
    }

    private fun buildMiniNav(): View {
        val ctx = reactContext
        val h = miniPx(MINI_NAV_PX)
        fun tool(asset: String, description: String, onClick: () -> Unit) = ImageView(ctx).apply {
            setImageDrawable(UiUtils.loadAssetIcon(ctx, asset, miniPx(MINI_ICON_PX), Color.BLACK))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(h, LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { onClick() }
        }
        val home = FrameLayout(ctx).apply {
            contentDescription = NativeLocale.t("relay_new_chat")
            addView(LineIcon(ctx, LineIcon.Glyph.PLUS, 24).apply { color = Color.BLACK },
                FrameLayout.LayoutParams(miniPx(MINI_ICON_PX), miniPx(MINI_ICON_PX), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(h, LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { showStart() }
        }
        val settings = FrameLayout(ctx).apply {
            contentDescription = NativeLocale.t("relay_nav_settings")
            addView(LineIcon(ctx, LineIcon.Glyph.GEAR, 24).apply { color = Color.BLACK },
                FrameLayout.LayoutParams(miniPx(MINI_ICON_PX), miniPx(MINI_ICON_PX), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(h, LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { openSettings() }
        }
        val mic = tool("icons/ic_ai_mic.xml", NativeLocale.t("relay_voice"), ::toggleVoice)
        val mode = tool(modeIconAsset(), NativeLocale.t("relay_tool_mode")) { emitAction("mode") }
        mode.visibility = if (modeOffered()) View.VISIBLE else View.GONE
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(home)
            addView(View(ctx).apply { setBackgroundColor(LINE) },
                LinearLayout.LayoutParams(1, miniPx(44)).apply { leftMargin = miniPx(4); rightMargin = miniPx(4) })
            addView(tool("icons/ic_ai_selection.xml", NativeLocale.t("relay_tool_lasso")) { emitAction("lasso_ai") })
            addView(tool("icons/ic_ai_image.xml", NativeLocale.t("relay_tool_image")) { emitAction("screenshot_ai") })
            addView(mic)
            addView(mode)
            addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            addView(settings)
        }
        miniHome = home
        navMic = mic
        navMode = mode
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(View(ctx).apply { setBackgroundColor(LINE) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h))
        }.also {
            miniNav = it
            bindNavTools()
        }
    }

    private fun renderMiniNav() {

        showReadLine()
        val nav = miniNav ?: return
        val shown = mini && screen == Screen.DETAIL_READ
        nav.visibility = if (shown) View.VISIBLE else View.GONE
        if (!shown) return
        miniHome?.visibility = if (requestedDetailId == null && newChatTapped) View.GONE else View.VISIBLE
    }
}
