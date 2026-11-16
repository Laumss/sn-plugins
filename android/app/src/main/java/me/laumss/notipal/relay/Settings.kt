package me.laumss.notipal.relay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.common.RelayPrefs
import me.laumss.notipal.common.Strings
import me.laumss.notipal.ui_common.InkSeekBar
import me.laumss.notipal.ui_common.Panel
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.ScrollHost
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.SwitchMark
import me.laumss.notipal.ui_common.Widgets
import kotlin.math.roundToInt

class RelaySettingsPane {
    val view: View = RelaySettingsView(App.context)
}

@SuppressLint("ViewConstructor")
private class RelaySettingsView(context: Context) : LinearLayout(context) {

    private companion object {
        const val POLL_MS = 2_000L
    }

    private class SettingRow(val view: View, val meta: TextView)

    private fun u(v: Number) = Style.u(v)
    private fun t(key: String, vararg args: Any) = Strings.get(key, *args)

    private val handler = Handler(Looper.getMainLooper())

    private val scroll = ScrollHost(context).apply { follow = true }
    private val backendMeta: TextView
    private val webBox = LinearLayout(context).apply { orientation = VERTICAL }
    private var webStatus: TextView? = null
    private val phoneMeta: TextView
    private val phoneAction: TextView

    private val phoneRow: View
    private val linkRow: View
    private val followRow: View
    private val linkMeta: TextView
    private val openMeta: TextView
    private val autoOpen: SwitchMark
    private val phoneFollows: SwitchMark
    private val slider: InkSeekBar
    private val poll = object : Runnable {
        override fun run() {
            if (!isShown) return
            refreshLink()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Style.paper)
        val content = scroll.content
        content.setPadding(u(Style.PANE_INSET), 0, maxOf(u(Style.PANE_INSET), scroll.followLaneWidthPx), u(24))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        content.addView(Widgets.sectionLabel(context, t("relay_settings_connection"), topU = 22))
        content.addView(Widgets.rule(context))
        val backend = row(t("config_airrelay_backend"), right = action(t("config_airrelay_switch")) {
            Conversations.setBackend(if (Conversations.isWebMode()) Conversations.Backend.RIKKAHUB else Conversations.Backend.WEB)
            refreshBackend()
            refreshLink()
        })
        backendMeta = backend.meta
        content.addView(backend.view)
        content.addView(webBox)
        phoneAction = action(t("config_airrelay_unbind")) {
            Conversations.unpairPhone()
            refreshPhone()
            refreshLink()
        }
        val phone = row(t("config_airrelay_phone"), right = phoneAction)
        phoneMeta = phone.meta
        phoneRow = phone.view
        content.addView(phone.view)
        val link = row(t("config_airrelay_link"), right = action(t("config_airrelay_search")) {
            Conversations.searchAgain()
            refreshLink()
        }, last = true)
        linkMeta = link.meta
        linkRow = link.view
        content.addView(link.view)

        content.addView(Widgets.sectionLabel(context, t("relay_settings_window")))
        content.addView(Widgets.rule(context))

        val open = row(t("config_airrelay_open_way"), right = action(t("config_airrelay_switch")) {
            RelayPrefs.setOpenMini(!RelayPrefs.openMini())
            refreshOpen()
        })
        openMeta = open.meta
        content.addView(open.view)
        autoOpen = switch { RelayPrefs.autoOpenDetail() }
        content.addView(row(t("config_airrelay_auto_open"), right = autoOpen, onClick = {
            RelayPrefs.setAutoOpenDetail(!RelayPrefs.autoOpenDetail())
            autoOpen.on = RelayPrefs.autoOpenDetail()
        }).view)
        phoneFollows = switch { RelayPrefs.phoneFollows() }
        followRow = row(t("config_airrelay_phone_follow"), right = phoneFollows, onClick = {
            RelayPrefs.setPhoneFollows(!RelayPrefs.phoneFollows())
            phoneFollows.on = RelayPrefs.phoneFollows()
        }).view
        content.addView(followRow)
        slider = InkSeekBar(context).apply {
            max = (RelayTranslucency.MAX_LEVEL - RelayTranslucency.MIN_LEVEL) / RelayTranslucency.STEP
        }
        content.addView(row(t("config_airrelay_translucent"), right = sliderGroup(), last = true).view)

        refreshAll()
    }

    private fun row(title: String, right: View? = null, last: Boolean = false, onClick: (() -> Unit)? = null): SettingRow {
        val metaView = Widgets.text(context, "", Style.F_META, Style.subtle).apply {
            setPadding(0, u(4), 0, 0)
            visibility = GONE
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(Widgets.text(context, title, Style.F_BODY))
            addView(metaView)
        }
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = u(Style.ROW_MIN)
            setPadding(0, u(14), 0, u(14))
            addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            if (right != null) {
                val lp = (right.layoutParams as? LayoutParams) ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                addView(right, lp.apply { leftMargin = u(16) })
            }
            if (onClick != null) setOnClickListener { onClick() }
        }
        val view = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(line)
            addView(if (last) Widgets.rule(context) else Widgets.dashes(context))
        }
        return SettingRow(view, metaView)
    }

    private fun setMeta(view: TextView, value: String) {
        view.text = value
        view.visibility = if (value.isEmpty()) GONE else VISIBLE
    }

    private fun action(label: String, onClick: () -> Unit): TextView =
        Widgets.link(context, label, onClick = onClick).apply { setPadding(u(16), u(10), 0, u(10)) }

    private fun switch(get: () -> Boolean): SwitchMark = SwitchMark(context, get()).apply {
        layoutParams = LayoutParams(u(52), u(30))
    }

    private fun sliderGroup(): View {
        fun levelOf(progress: Int) = RelayTranslucency.MIN_LEVEL + progress * RelayTranslucency.STEP
        val percent = Widgets.text(context, "", Style.F_META).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            minWidth = u(44)
        }
        slider.progress = (RelayTranslucency.level() - RelayTranslucency.MIN_LEVEL) / RelayTranslucency.STEP
        percent.text = "${levelOf(slider.progress)}%"
        slider.setOnTouchListener { view, event ->

            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                percent.text = "${levelOf(progress)}%"
            }
            override fun onStartTrackingTouch(bar: SeekBar) = slider.setTracking(true)
            override fun onStopTrackingTouch(bar: SeekBar) {
                slider.setTracking(false)
                RelayTranslucency.setLevel(levelOf(bar.progress))
            }
        })
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(slider, LayoutParams(u(170), u(44)))
            addView(percent, LayoutParams(LayoutParams.WRAP_CONTENT, u(44)))
        }
    }

    private fun refreshAll() {
        refreshBackend()
        refreshPhone()
        refreshLink()
        refreshOpen()
        autoOpen.on = RelayPrefs.autoOpenDetail()
        phoneFollows.on = RelayPrefs.phoneFollows()
        slider.progress = (RelayTranslucency.level() - RelayTranslucency.MIN_LEVEL) / RelayTranslucency.STEP
    }

    private fun refreshBackend() {
        val web = Conversations.isWebMode()
        setMeta(backendMeta, t(if (web) "config_airrelay_backend_web" else "config_airrelay_backend_rikkahub"))
        webBox.visibility = if (web) VISIBLE else GONE
        phoneRow.visibility = if (web) GONE else VISIBLE
        linkRow.visibility = if (web) GONE else VISIBLE
        followRow.visibility = if (web) GONE else VISIBLE
        if (web) rebuildWeb() else {
            webBox.removeAllViews()
            webStatus = null
        }
    }

    private fun rebuildWeb() {
        webBox.removeAllViews()
        val server = Conversations.ensureWebServer()
        val url = server?.pageUrl()
        if (url != null) {
            webBox.addView(Widgets.qrCard(
                context,
                payload = url,
                title = t("config_airrelay_web_title"),
                desc = t("config_airrelay_web_desc"),
                link = url,
                wrapLink = true,
            ).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = u(12); bottomMargin = u(4) }
            })
        } else {
            webBox.addView(Widgets.text(
                context,
                t(if (server == null) "config_airrelay_web_not_running" else "config_airrelay_web_no_lan"),
                Style.F_META, Style.subtle,
            ).apply { setPadding(0, u(12), 0, u(4)) })
        }

        val status = row(t("config_airrelay_web_status"), right = action(t("config_airrelay_web_new_link")) {
            Conversations.rotateWebToken()
            rebuildWeb()
        }, last = true)
        webStatus = status.meta
        webBox.addView(status.view)
        refreshWebStatus()
    }

    private fun refreshWebStatus() {
        val view = webStatus ?: return
        val server = Conversations.webServer()
        setMeta(view, when {
            server == null -> t("config_airrelay_web_not_running")
            server.workerCount > 0 && !server.chatReady -> t("config_airrelay_web_connected_no_model", server.workerCount)
            server.workerCount > 0 -> t("config_airrelay_web_connected", server.workerCount)
            else -> t("config_airrelay_web_disconnected")
        })
    }

    private fun refreshPhone() {
        val info = Conversations.pairedPhoneInfo()
        setMeta(phoneMeta, if (info != null) "${info.host}:${info.port}" else t("config_airrelay_none"))
        phoneAction.visibility = if (info != null) VISIBLE else GONE
    }

    private fun refreshLink() {
        val status = Conversations.linkStatus()
        setMeta(linkMeta, when (status.link) {
            Conversations.Link.OFF -> t("config_airrelay_link_off")
            Conversations.Link.WEB -> t("config_airrelay_backend_web")
            Conversations.Link.UNPAIRED -> t("config_airrelay_link_unpaired")
            Conversations.Link.SEARCHING -> t("config_airrelay_link_searching")
            Conversations.Link.CONNECTED -> t("config_airrelay_link_connected", status.host)
            Conversations.Link.CONNECTING -> t("config_airrelay_link_connecting", status.host)
            Conversations.Link.RETRYING -> t("config_airrelay_link_retry", status.host, ((status.retryInMs + 999) / 1000).toInt())
            Conversations.Link.PAUSED -> t("config_airrelay_link_paused")
        })
        if (Conversations.isWebMode()) refreshWebStatus()
    }

    private fun refreshOpen() {
        setMeta(openMeta, t(if (RelayPrefs.openMini()) "config_airrelay_open_mini" else "config_airrelay_open_full"))
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        syncPoll()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPoll()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        super.onDetachedFromWindow()
    }

    private fun syncPoll() {
        handler.removeCallbacks(poll)
        if (!isShown) return
        refreshAll()
        handler.postDelayed(poll, POLL_MS)
    }
}

class WebLinkPanel : Panel("web-link") {
    override val tag: String get() = "WebLinkPanel"
    override val heightRatio: Double get() = 0.6
    override val parksToolbarRect: Boolean get() = false

    companion object {
        @Volatile private var current: WebLinkPanel? = null

        fun install() {
            RelayHooks.showWebLink = ::open
            RelayHooks.webLinkShowing = { current?.showing == true }
        }

        fun open() {
            val panel = current ?: WebLinkPanel()
            current = panel
            panel.present()
        }
    }

    private var content: LinearLayout? = null

    private fun present() {
        current = this
        if (showing) {
            rebuild()
            return
        }
        Panels.open(this) { shown -> if (!shown) Log.w(tag, "web link panel not shown") }
    }

    override fun build(root: LinearLayout) {
        val ctx = App.context
        root.addView(Widgets.panelHeader(ctx, Strings.get("config_airrelay_web_title")))
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            content = this
            rebuild()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Widgets.bottomBar(
            ctx,
            leftButtons = listOf(Widgets.outlinedButton(ctx, Strings.get("config_airrelay_web_new_link")) {
                Conversations.rotateWebToken()
                rebuild()
            }),
            rightButtons = listOf(Widgets.filledButton(ctx, Strings.get("btn_confirm")) { hide() }),
        ))
    }

    private fun rebuild() {
        val box = content ?: return
        val ctx = App.context
        box.removeAllViews()
        val server = Conversations.ensureWebServer()
        val url = server?.pageUrl()
        if (url != null) {
            box.addView(Widgets.qrCard(
                ctx,
                payload = url,
                title = Strings.get("config_airrelay_web_title"),
                desc = Strings.get("config_airrelay_web_desc"),
                link = url,
                wrapLink = true,
            ))
        } else {
            val key = if (server == null) "config_airrelay_web_not_running" else "config_airrelay_web_no_lan"
            box.addView(Widgets.empty(ctx, Strings.get(key)))
        }
    }

    override fun onHide() {
        content = null
        if (current === this) current = null
    }
}

object RelayTranslucency {
    private const val KEY_LEVEL = "translucent_level"
    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 65
    const val STEP = 5
    const val DEFAULT_LEVEL = 0

    var paperArgbValue: Int = argbOf(DEFAULT_LEVEL)
        private set

    var onChanged: (() -> Unit)? = null

    private fun prefs() = Prefs.of("relay")

    fun level(): Int = prefs().getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun setLevel(level: Int) {
        val value = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        prefs().edit().putInt(KEY_LEVEL, value).apply()
        paperArgbValue = argbOf(value)
        onChanged?.invoke()
    }

    fun load() {
        paperArgbValue = argbOf(level())
    }

    private fun argbOf(level: Int): Int {
        val seeThrough = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val alpha = (255 * (100 - seeThrough) / 100f).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or (Style.paper and 0x00FFFFFF)
    }
}
