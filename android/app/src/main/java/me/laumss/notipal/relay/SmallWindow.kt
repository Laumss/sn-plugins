package me.laumss.notipal.relay

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import me.laumss.notipal.toolbar.ToolbarMetrics
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.FrozenView
import me.laumss.notipal.ui_common.LineIcon
import me.laumss.notipal.ui_common.SmallWindow
import me.laumss.notipal.ui_common.Style

private const val BAR_PX = 92
private const val BAR_ICON_PX = 44
private const val NAV_PX = 80
private const val NAV_ICON_PX = 40

private const val FIRST_DRAW_TIMEOUT_MS = 1500L
private const val FIRST_DRAW_SETTLE_MS = 80L

private const val ASIDE = "aside"

internal class RelaySmallWindow(private val host: RelayModule) : SmallWindow(
    "relay-small",
    bubbleIcon = Tools.get(Tools.AI_RELAY)?.icon ?: "ic_tool_ai_relay",
    bubbleStrokeDeltaPx = ToolbarMetrics.ICON_STROKE_DELTA_PX,
) {
    override val tag: String get() = "RelaySmallWindow"

    private val ctx: Context = App.context

    private fun px(v: Int) = Style.px(v)

    private val root = object : LinearLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) host.back()
            return true
        }
    }.apply {
        orientation = LinearLayout.VERTICAL

        setBackgroundColor(Style.clear)
        foreground = Style.boxPx(Style.clear, Style.ink, px(SmallWindow.BORDER_PX).coerceAtLeast(1))
    }

    private val title = TextView(ctx).apply {
        setTextColor(Style.paper)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.rawSp(Style.miniTitleSp()))
        typeface = Typeface.MONOSPACE
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(px(24), 0, 0, 0)
    }

    private val slot = FrameLayout(ctx)

    private val nav: LinearLayout
    private val navHome: View

    val navMic: ImageView

    var aside = false
        private set

    init {
        val barH = px(BAR_PX)
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Style.ink)
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(barIcon("icons/ic_window_expand.xml", barH) { host.growFromSmall() })
            addView(barIcon("icons/ic_window_collapse.xml", barH) { host.collapseSmall() })
            addView(barIcon("icons/ic_ai_close.xml", barH) { host.closeUnlessWaiting() })
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barH))
        root.addView(slot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val h = px(NAV_PX)
        fun tool(asset: String, description: String, onClick: () -> Unit) = ImageView(ctx).apply {
            setImageDrawable(Style.iconPx(asset, px(NAV_ICON_PX), Style.ink))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(h, LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { onClick() }
        }
        fun glyphCell(glyph: LineIcon.Glyph, description: String, onClick: () -> Unit) = FrameLayout(ctx).apply {
            contentDescription = description
            addView(LineIcon(ctx, glyph, 24).apply { color = Style.ink },
                FrameLayout.LayoutParams(px(NAV_ICON_PX), px(NAV_ICON_PX), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(h, LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { onClick() }
        }
        navHome = glyphCell(LineIcon.Glyph.PLUS, Strings.get("relay_new_chat")) { host.showStart() }
        navMic = tool("icons/ic_ai_mic.xml", Strings.get("relay_voice")) { host.voice.toggle() }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Style.paper)
            addView(navHome)
            addView(View(ctx).apply { setBackgroundColor(Style.line) },
                LinearLayout.LayoutParams(1, px(44)).apply { leftMargin = px(4); rightMargin = px(4) })
            addView(tool("icons/ic_ai_selection.xml", Strings.get("relay_tool_lasso")) { host.emitAction("lasso_ai") })
            addView(tool("icons/ic_ai_image.xml", Strings.get("relay_tool_image")) { host.emitAction("screenshot_ai") })
            addView(tool("icons/ic_ai_document.xml", host.pagesLabel()) { host.attachPages() })
            addView(navMic)
            addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            addView(glyphCell(LineIcon.Glyph.GEAR, Strings.get("relay_nav_settings")) { host.openSettings() })
        }
        nav = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(ctx).apply { setBackgroundColor(Style.line) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h))
        }
        root.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setBody(root)
        setDragHandle(bar)

        underInputHeight = { nav.takeIf { it.visibility == View.VISIBLE }?.height ?: 0 }
    }

    private fun barIcon(asset: String, width: Int, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageDrawable(Style.iconPx(asset, px(BAR_ICON_PX), Style.paper))
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT)
        setOnClickListener { onClick() }
    }

    fun place(content: View) {
        thaw()
        if (content.parent === slot) return
        (content.parent as? ViewGroup)?.removeView(content)
        slot.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private var frozen: FrozenView? = null

    fun freeze(content: View) {
        if (frozen != null || content.parent !== slot) return
        frozen = FrozenView.over(content, paper = null)
    }

    private fun thaw() {
        frozen?.remove()
        frozen = null
    }

    fun setTitle(text: String) {
        if (title.text.toString() != text) title.text = text
    }

    fun renderNav(shown: Boolean, homeShown: Boolean) {
        nav.visibility = if (shown) View.VISIBLE else View.GONE
        if (shown) navHome.visibility = if (homeShown) View.VISIBLE else View.GONE
    }

    fun setAside(value: Boolean) {
        if (aside == value) return
        aside = value
        if (value) putAway(ASIDE) else bringBack(ASIDE)
        Log.i(tag, "mini ${if (value) "aside for the lasso" else "back from the lasso"}")
    }

    fun afterDrawn(block: () -> Unit) {
        Main.post {
            val view = windowView
            if (view == null) {
                block()
                return@post
            }
            var done = false
            lateinit var listener: ViewTreeObserver.OnDrawListener
            val fire = Runnable {
                if (done) return@Runnable
                done = true
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
                block()
            }

            listener = ViewTreeObserver.OnDrawListener { Main.handler.postDelayed(fire, FIRST_DRAW_SETTLE_MS) }
            view.viewTreeObserver.addOnDrawListener(listener)
            view.invalidate()
            Main.handler.postDelayed(fire, FIRST_DRAW_TIMEOUT_MS)
        }
    }

    fun resetAside() {
        if (!aside) return
        aside = false
        bringBack(ASIDE)
    }

    override fun onBubbleTap() = host.uncollapse("tap")

    override fun onTypingEnded() {
        host.detail.input.hideKeyboard()
        host.typingChanged(false)
    }

    override fun onPressBegin(finger: Boolean) {
        host.detail.dragging = true
    }

    override fun onPressEnd(longPressed: Boolean) {
        host.detail.dragging = false
    }

    override fun onAttached(view: View) {
        host.syncIdle()
    }

    override fun onDetached() {
        super.onDetached()

        thaw()
        host.detail.dragging = false
        host.syncIdle()
    }

    override fun closeForRotation() {
        val was = showing || collapsed
        super.closeForRotation()
        if (was) host.smallGone("window change")
    }
}
