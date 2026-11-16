package me.laumss.notipal.relay

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.EinkAuto
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.SystemKeyboard
import me.laumss.notipal.ui_common.BarAction
import me.laumss.notipal.ui_common.BarCell
import me.laumss.notipal.ui_common.BarStyle
import me.laumss.notipal.ui_common.FrozenView
import me.laumss.notipal.ui_common.LineIcon
import me.laumss.notipal.ui_common.Panel
import me.laumss.notipal.ui_common.PanelBarHandle
import me.laumss.notipal.ui_common.ScrollHost
import me.laumss.notipal.ui_common.SideSlider
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.TitleBar
import me.laumss.notipal.ui_common.Widgets
import kotlin.math.roundToInt

private const val CANDIDATE_STRIP_DP = 80.5f

private val KEYBOARD_LOOKS_MS = longArrayOf(0L, 150L, 400L, 800L)

private const val SCRUB_MIN_UNITS = 4f
private const val SCRUB_MAX_UNITS = 24f
private const val SCRUB_CONTINUE_MS = 1500L
private const val SCRUB_AUTO_HOLD_MS = 500L
private const val SCRUB_AUTO_OWNER = "relay-scrub"

class RelayFullPanel internal constructor(private val host: RelayModule) : Panel("relay") {
    override val tag: String get() = "RelayFullPanel"
    override val fullScreen: Boolean get() = true
    override val keptThroughPanels: Boolean get() = host.keepsFullUnder
    override val parksToolbarRect: Boolean get() = false
    override val yieldsLexi: Boolean get() = true

    override val windowFlags: Int get() = 0
    override val softInputMode: Int
        get() = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

    private val ctx: Context = App.context

    private var content: LinearLayout? = null
    private var chromeView: View? = null
    private var body: FrameLayout? = null
    private var detailFrame: FrameLayout? = null
    private var fill: View? = null

    private var listBar: TitleBar? = null
    private var selectBar: PanelBarHandle? = null
    private var selectInsert: BarCell.Action? = null
    private var bandTitle: TextView? = null

    private var keyboardSeen = false
    private var keyboardHow = ""

    private var fullKeys = 0
    private val onSystemKeyboard: (Boolean) -> Unit = {
        val root = content
        if (root != null) KEYBOARD_LOOKS_MS.forEach { ms -> root.postDelayed({ updateKeyboardInset("keyboard+$ms") }, ms) }
    }

    override fun build(root: LinearLayout) {
        val inner = object : LinearLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {

                if (SideSlider.isSliderKey(event.keyCode)) return true
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) host.back()
                return true
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                post { startSlider() }
            }

            override fun onDetachedFromWindow() {
                stopSlider()
                super.onDetachedFromWindow()
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.paper)

            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            viewTreeObserver.addOnGlobalLayoutListener { updateKeyboardInset() }
            setOnApplyWindowInsetsListener { view, insets -> view.post { updateKeyboardInset() }; insets }
        }
        root.addView(inner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        content = inner
        val frame = FrameLayout(ctx)
        body = frame
        inner.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        move(host.inbox.view, frame)
        val details = FrameLayout(ctx)
        detailFrame = details
        frame.addView(details, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        move(host.detail.view, details)
        move(host.selection.view, details)

        host.selection.view.visibility = View.GONE
        val blank = View(ctx).apply { setBackgroundColor(Style.paper) }
        fill = blank
        inner.addView(blank, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        SystemKeyboard.ensureRegistered(ctx)
        SystemKeyboard.addListener(onSystemKeyboard)
        inner.post { inner.requestFocus() }
    }

    private fun move(view: View, into: ViewGroup) {
        (view.parent as? ViewGroup)?.removeView(view)
        into.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private var frozen: FrozenView? = null

    fun freezeDetail() {
        val frame = detailFrame ?: return
        if (frozen != null || host.detail.view.parent !== frame) return
        frozen = FrozenView.over(host.detail.view, Style.paper)
    }

    private var held = false

    fun hold() {
        held = true
    }

    fun release() {
        held = false
    }

    override fun onHide() {
        held = false
        frozen = null
        stopSlider()
        SystemKeyboard.removeListener(onSystemKeyboard)
        content = null
        chromeView = null
        body = null
        detailFrame = null
        fill = null
        listBar = null
        selectBar = null
        selectInsert = null
        bandTitle = null
        keyboardSeen = false
        fullKeys = 0
        host.fullGone(this)
    }

    override fun suspendVisibility() {
        super.suspendVisibility()
        Main.post { host.setFullAway(this, true) }
    }

    override fun resumeVisibility() {
        super.resumeVisibility()
        Main.post { host.setFullAway(this, false) }
    }

    fun afterDrawn(block: () -> Unit) = afterFirstDraw(block)

    fun afterNextDrawn(block: () -> Unit) = Main.post { afterNextDraw(block) }

    fun showLayer(screen: RelayModule.Screen) {
        val list = screen == RelayModule.Screen.LIST || screen == RelayModule.Screen.EDIT
        val inbox = host.inbox
        inbox.view.visibility = if (list) View.VISIBLE else View.GONE
        detailFrame?.visibility = if (list) View.GONE else View.VISIBLE
        host.selection.view.visibility = if (screen == RelayModule.Screen.SELECT) View.VISIBLE else View.GONE
        if (!list) inbox.hideKeyboard()
        inbox.setEditing(screen == RelayModule.Screen.EDIT)
        if (list) inbox.refresh()
    }

    fun renderChrome(screen: RelayModule.Screen) {
        if (held) return
        val root = content ?: return
        chromeView?.let(root::removeView)
        chromeView = null
        listBar = null
        selectBar = null
        selectInsert = null
        bandTitle = null
        showLayer(screen)
        val voice = host.voice
        when (screen) {
            RelayModule.Screen.EDIT -> {
                val bar = listSelectBar()
                chromeView = bar.view
                root.addView(bar.view, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bar.heightPx))
                voice.bindMic(null, null, 0)
            }
            RelayModule.Screen.LIST -> {
                val back = host.listHasBack()
                val bar = Widgets.titleBar(
                    ctx, "AIRelay", onClose = { host.leaveList() },
                    actions = listOf(BarAction(Strings.get("relay_new_chat"), LineIcon.Glyph.PLUS) { host.showStart() }),
                    closeLabel = Strings.get(if (back) "picker_back" else "close"),
                    closeGlyph = if (back) LineIcon.Glyph.BACK else LineIcon.Glyph.CLOSE,
                    actionLabels = listOf(Strings.get("relay_select")),
                    onAction = { host.editInbox() },
                )
                bar.setAction(listAction(screen))
                listBar = bar
                chromeView = bar
                root.addView(bar, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                host.inbox.onRowsChanged = { listBar?.setAction(listAction(host.screen)) }
                voice.bindMic(null, null, 0)
            }
            RelayModule.Screen.SELECT -> {
                val bar = replySelectBar()
                chromeView = bar.view
                root.addView(bar.view, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bar.heightPx))
                voice.bindMic(null, null, 0)
            }
            RelayModule.Screen.READ -> {
                val band = detailBand()
                chromeView = band
                root.addView(band, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Style.u(72)))

                voice.bindMic(host.detail.actions.micButton, host.detail.actions.micCell, Style.u(STATUS_ICON))
            }
        }
        voice.refresh()
    }

    private fun listAction(screen: RelayModule.Screen): String? =
        if (screen == RelayModule.Screen.LIST && host.inbox.hasRows) Strings.get("relay_select") else null

    fun setTitle(text: String) {
        if (held) return
        bandTitle?.let { if (it.text.toString() != text) it.text = text }
    }

    private fun detailBand(): View {
        fun u(v: Number) = Style.u(v)
        val closes = host.bandCloses()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Style.band)
            setPadding(u(4), 0, u(12), 0)
        }
        row.addView(Widgets.iconCell(ctx, if (closes) LineIcon.Glyph.CLOSE else LineIcon.Glyph.BACK,
            Strings.get(if (closes) "close" else "picker_back")) { host.backFromDetail() })
        val title = Widgets.text(ctx, "", 16, Style.ink).apply {
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        bandTitle = title
        row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = u(6); rightMargin = u(14) })
        fun tab(label: String, on: Boolean, onClick: () -> Unit) = Widgets.text(ctx, label, 17, Style.ink, bold = on).apply {
            gravity = Gravity.CENTER
            setPadding(u(20), 0, u(20), 0)
            if (on) setBackgroundColor(Style.paper) else setOnClickListener { onClick() }
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(tab(Strings.get("relay_tab_new"), false) { host.showStart() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, u(54)))
            addView(tab(Strings.get("relay_tab_full"), true) {}, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, u(54)))
            addView(tab(Strings.get("relay_tab_mini"), false) { host.shrinkToSmall() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, u(54)))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = u(8) })
        title.text = host.windowTitle()
        return row
    }

    private fun listSelectBar(): PanelBarHandle {
        val inbox = host.inbox
        val star = BarCell.Action("icons/ic_star.xml", Strings.get("relay_star")) { inbox.starPicked() }
        val delete = BarCell.Action("icons/ic_bar_delete.xml", Strings.get("relay_delete")) { inbox.deletePicked() }
        val bar = Widgets.panelBar(
            ctx, BarStyle.INBOX,
            left = listOf(BarCell.TextBtn(Strings.get("cancel")) { host.open(RelayPage.INBOX) }),
            center = listOf(star, delete),
            right = listOf(BarCell.Title("")),
        )
        inbox.onPicks = { count, unstar ->
            bar.setTitleLabel(0, Strings.get("relay_picked", count))
            bar.updateAction(star, count > 0,
                if (unstar) "icons/ic_star_on.xml" else "icons/ic_star.xml",
                Strings.get(if (unstar) "relay_unstar" else "relay_star"))
            bar.updateAction(delete, count > 0)
        }
        inbox.renderActions()
        return bar
    }

    private fun replySelectBar(): PanelBarHandle {
        val selection = host.selection
        val inDocument = HostSession.state.document
        val insert = BarCell.Action(
            if (inDocument) "icons/ic_bar_copy.xml" else "icons/ic_bar_insert.xml",
            Strings.get(if (inDocument) "relay_copy" else "relay_insert"),
        ) { selection.actions?.takeIf { it.canInsert }?.insert?.invoke() }
        val bar = Widgets.panelBar(
            ctx, BarStyle.INBOX,
            left = listOf(BarCell.TextBtn(Strings.get("cancel")) { host.open(RelayPage.DETAIL) }),
            center = listOf(
                BarCell.Action("icons/ic_bar_clear.xml", Strings.get("relay_clear")) { selection.actions?.clear?.invoke() },
                insert,
            ),
            right = listOf(BarCell.Title("")),
        )
        selectBar = bar
        selectInsert = insert
        showSelectState()
        return bar
    }

    fun showSelectState() {
        if (held) return
        val actions = host.selection.actions
        selectBar?.setTitleLabel(0, Strings.get("relay_selected_rows", actions?.selectedRowCount ?: 0))
        selectInsert?.let { selectBar?.updateAction(it, actions?.canInsert == true) }
    }

    fun updateKeyboardInset(after: String = "layout") {
        val root = content ?: return
        val screenH = Style.screenSize().second
        val keyboard = ChatInput.keyboard(root, screenH)
        val density = root.resources.displayMetrics.density
        if (keyboard.strip > candidateStrip) candidateStrip = keyboard.strip
        val strip = (CANDIDATE_STRIP_DP * density).roundToInt()
        fullKeys = when {
            keyboard.keys <= 24 * density -> 0
            keyboard.keys > 120 * density -> if (fullKeys > 0) minOf(fullKeys, keyboard.keys) else keyboard.keys
            else -> fullKeys
        }
        val keys = fullKeys
        val actions = host.detail.actions
        val under = actions.takeIf { it.visibility == View.VISIBLE && it.isShown }?.height ?: 0
        val fillView = fill
        val (inset, fillHeight) = when {
            keys <= 0 -> 0 to 0
            fillView != null -> keys to (strip - under).coerceAtLeast(0)
            else -> keys + strip to 0
        }

        val how = "${keyboard.how} focused=${host.detail.input.input.isFocused}"
        if (root.paddingBottom != inset || (fillView != null && fillView.layoutParams.height != fillHeight) || how != keyboardHow) {
            keyboardHow = how
            root.setPadding(0, 0, 0, inset)
            fillView?.let { it.layoutParams = it.layoutParams.apply { height = fillHeight } }
            Log.i(tag, "keyboard inset=$inset fill=$fillHeight (keys=$keys measured=${keyboard.keys} strip=${keyboard.strip} " +
                "candidates=$candidateStrip under=$under $how) after=$after root=${root.height} screen=$screenH")
        }
        if (keyboard.shown) keyboardSeen = true
        else if (keyboardSeen) {

            keyboardSeen = false
            host.detail.input.hideKeyboard()
            host.inbox.hideKeyboard()
        }
    }

    private var slider: SideSlider? = null

    private var scrubScroll: ScrollHost? = null
    private var scrubRefPos = 0
    private var scrubRefOffset = 0
    private var scrubAuto = false
    private val landScrub = Runnable { finishScrub() }
    private val releaseScrubAuto = Runnable { releaseScrubAutoNow() }

    private val sliderListener = object : SideSlider.Listener {
        override fun onTurn(step: Int) {
            val scroll = shownScroll() ?: return
            Log.i(tag, "slider turn $step turned=${scroll.turn(step)}")
        }
        override fun onScrubStart(pos: Int) = scrubStart(pos)
        override fun onScrubMove(pos: Int) = scrubMove(pos)
        override fun onScrubEnd() = scrubEnd()
    }

    private fun startSlider() {
        if (!showing) return
        val s = slider ?: SideSlider(ctx, { content?.display?.rotation ?: Surface.ROTATION_0 }, sliderListener)
            .also { slider = it }
        s.start()
    }

    private fun stopSlider() {
        finishScrub()
        releaseScrubAutoNow()
        slider?.stop()
    }

    private fun shownScroll(): ScrollHost? {
        var best: ScrollHost? = null
        fun walk(view: View) {
            if (!view.isShown) return
            if (view is ScrollHost) {
                if (view.height > (best?.height ?: -1)) best = view
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        content?.let(::walk)
        return best
    }

    private fun scrubStart(pos: Int) {
        Main.handler.removeCallbacks(landScrub)
        val scroll = scrubScroll ?: shownScroll() ?: return
        if (scrubScroll == null) {
            scrubScroll = scroll
            slider?.scrubSession = true

            Main.handler.removeCallbacks(releaseScrubAuto)
            if (!scrubAuto) scrubAuto = EinkAuto.acquire(SCRUB_AUTO_OWNER, false)
            Log.i(tag, "slider scrub start offset=${scroll.scrollOffset}/${scroll.maxOffset} auto=$scrubAuto")
        }
        scrubRefPos = pos
        scrubRefOffset = scroll.scrollOffset
    }

    private fun scrubMove(pos: Int) {
        val scroll = scrubScroll ?: return
        val page = scroll.viewportHeight
        if (page <= 0) return

        val screens = (scroll.contentHeight.toFloat() / page).coerceAtLeast(1f)
        val units = (SideSlider.LENGTH / screens).coerceIn(SCRUB_MIN_UNITS, SCRUB_MAX_UNITS)
        val target = scrubRefOffset + ((scrubRefPos - pos) / units).roundToInt() * page
        if (target < 0 || target > scroll.maxOffset) {

            val end = target.coerceIn(0, scroll.maxOffset)
            scroll.scrubTo(end)
            scrubRefOffset = end
            scrubRefPos = pos
            return
        }
        scroll.scrubTo(target)
    }

    private fun scrubEnd() {
        if (scrubScroll == null) return
        Main.handler.removeCallbacks(landScrub)
        Main.handler.postDelayed(landScrub, SCRUB_CONTINUE_MS)
    }

    private fun finishScrub() {
        Main.handler.removeCallbacks(landScrub)
        val scroll = scrubScroll ?: return
        Log.i(tag, "slider scrub land offset=${scroll.scrollOffset}")
        scrubScroll = null
        slider?.scrubSession = false
        Main.handler.removeCallbacks(releaseScrubAuto)
        Main.handler.postDelayed(releaseScrubAuto, SCRUB_AUTO_HOLD_MS)
    }

    private fun releaseScrubAutoNow() {
        Main.handler.removeCallbacks(releaseScrubAuto)
        if (!scrubAuto) return
        scrubAuto = false
        EinkAuto.release(SCRUB_AUTO_OWNER)
    }

    private companion object {

        var candidateStrip = 0
    }
}
