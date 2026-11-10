package me.laumss.notipal.relay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.AIRelayCore
import me.laumss.notipal.relay.core.AppPrefs
import me.laumss.notipal.ui_common.PanelHost
import me.laumss.notipal.ui_common.QrLinkCard
import me.laumss.notipal.ui_common.SwitchMark
import me.laumss.notipal.ui_common.TranslucentSeekBar
import me.laumss.notipal.ui_common.snux.InkScrollView
import me.laumss.notipal.ui_common.snux.Snux

@SuppressLint("ViewConstructor")
internal class RelaySettingsPane(
    context: Context,
    private val host: PanelHost,
    private val core: AIRelayCore,
) : LinearLayout(context) {

    private companion object {
        const val POLL_MS = 2_000L
    }

    private class SettingRow(val view: View, val meta: TextView)

    private fun u(v: Number) = Snux.u(context, v)
    private fun t(key: String, vararg args: Any) = NativeLocale.t(key, *args)

    private val handler = Handler(Looper.getMainLooper())
    private val scroll = InkScrollView(context)
    private val backendMeta: TextView
    private val webBox = LinearLayout(context).apply { orientation = VERTICAL }
    private var webStatus: TextView? = null
    private val phoneMeta: TextView
    private val phoneAction: TextView
    private val linkMeta: TextView
    private val openMeta: TextView
    private val autoOpen: SwitchMark
    private val phoneFollows: SwitchMark
    private val slider: TranslucentSeekBar
    private val poll = object : Runnable {
        override fun run() {
            if (!isShown) return
            refreshLink()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        val content = scroll.content
        content.setPadding(u(Snux.PANE_INSET), 0, u(Snux.PANE_INSET), u(24))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        content.addView(Snux.sectionLabel(context, t("relay_settings_connection"), topU = 22))
        content.addView(Snux.rule(context))
        val backend = row(t("config_airrelay_backend"), right = action(t("config_airrelay_switch")) {
            core.setBackend(if (core.isWebMode()) AIRelayCore.Backend.RIKKAHUB else AIRelayCore.Backend.WEB)
            refreshBackend()
            refreshLink()
        })
        backendMeta = backend.meta
        content.addView(backend.view)
        content.addView(webBox)
        phoneAction = action(t("config_airrelay_unbind")) {
            core.unpairPhone()
            refreshPhone()
            refreshLink()
        }
        val phone = row(t("config_airrelay_phone"), right = phoneAction)
        phoneMeta = phone.meta
        content.addView(phone.view)
        val link = row(t("config_airrelay_link"), right = action(t("config_airrelay_search")) {
            core.searchAgain()
            refreshLink()
        }, last = true)
        linkMeta = link.meta
        content.addView(link.view)

        content.addView(Snux.sectionLabel(context, t("relay_settings_window")))
        content.addView(Snux.rule(context))

        val open = row(t("config_airrelay_open_way"), right = action(t("config_airrelay_switch")) {
            AppPrefs.setOpenMini(host.ctx, !AppPrefs.openMini(host.ctx))
            refreshOpen()
        })
        openMeta = open.meta
        content.addView(open.view)
        autoOpen = switch { AppPrefs.autoOpenDetail(host.ctx) }
        content.addView(row(t("config_airrelay_auto_open"), right = autoOpen, onClick = {
            AppPrefs.setAutoOpenDetail(host.ctx, !AppPrefs.autoOpenDetail(host.ctx))
            autoOpen.on = AppPrefs.autoOpenDetail(host.ctx)
        }).view)
        phoneFollows = switch { AppPrefs.phoneFollows(host.ctx) }
        content.addView(row(t("config_airrelay_phone_follow"), right = phoneFollows, onClick = {
            AppPrefs.setPhoneFollows(host.ctx, !AppPrefs.phoneFollows(host.ctx))
            phoneFollows.on = AppPrefs.phoneFollows(host.ctx)
        }).view)
        slider = TranslucentSeekBar(context).apply {
            max = (RelayTranslucency.MAX_LEVEL - RelayTranslucency.MIN_LEVEL) / RelayTranslucency.STEP
        }
        content.addView(row(t("config_airrelay_translucent"), right = sliderGroup(), last = true).view)

        refreshAll()
    }

    private fun row(title: String, right: View? = null, last: Boolean = false, onClick: (() -> Unit)? = null): SettingRow {
        val metaView = Snux.text(context, "", Snux.F_META, Snux.SUBTLE).apply {
            setPadding(0, u(4), 0, 0)
            visibility = GONE
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(Snux.text(context, title, Snux.F_BODY))
            addView(metaView)
        }
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = u(Snux.ROW_MIN)
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
            addView(if (last) Snux.rule(context) else Snux.dashes(context))
        }
        return SettingRow(view, metaView)
    }

    private fun setMeta(view: TextView, value: String) {
        view.text = value
        view.visibility = if (value.isEmpty()) GONE else VISIBLE
    }

    private fun action(label: String, onClick: () -> Unit): TextView =
        Snux.link(context, label, onClick = onClick).apply { setPadding(u(16), u(10), 0, u(10)) }

    private fun switch(get: () -> Boolean): SwitchMark = SwitchMark(context, get()).apply {
        layoutParams = LayoutParams(u(52), u(30))
    }

    private fun sliderGroup(): View {
        fun levelOf(progress: Int) = RelayTranslucency.MIN_LEVEL + progress * RelayTranslucency.STEP
        val percent = Snux.text(context, "", Snux.F_META).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            minWidth = u(44)
        }
        slider.progress = (RelayTranslucency.level(host.ctx) - RelayTranslucency.MIN_LEVEL) / RelayTranslucency.STEP
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
                RelayTranslucency.setLevel(host.ctx, levelOf(bar.progress))
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
        autoOpen.on = AppPrefs.autoOpenDetail(host.ctx)
        phoneFollows.on = AppPrefs.phoneFollows(host.ctx)
        slider.progress = (RelayTranslucency.level(host.ctx) - RelayTranslucency.MIN_LEVEL) / RelayTranslucency.STEP
    }

    private fun refreshBackend() {
        val web = core.isWebMode()
        setMeta(backendMeta, t(if (web) "config_airrelay_backend_web" else "config_airrelay_backend_rikkahub"))
        webBox.visibility = if (web) VISIBLE else GONE
        if (web) rebuildWeb() else {
            webBox.removeAllViews()
            webStatus = null
        }
    }

    private fun rebuildWeb() {
        webBox.removeAllViews()
        val server = core.ensureWebServer()
        val url = server?.pageUrl()
        if (url != null) {
            webBox.addView(QrLinkCard.build(
                host,
                payload = url,
                title = t("config_airrelay_web_title"),
                desc = t("config_airrelay_web_desc"),
                link = url,
                wrapLink = true,
            ).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = u(12); bottomMargin = u(4) }
            })
        } else {
            webBox.addView(Snux.text(
                context,
                t(if (server == null) "config_airrelay_web_not_running" else "config_airrelay_web_no_lan"),
                Snux.F_META, Snux.SUBTLE,
            ).apply { setPadding(0, u(12), 0, u(4)) })
        }
        val status = row(t("config_airrelay_web_status"), right = action(t("config_airrelay_web_new_link")) {
            core.rotateWebToken()
            rebuildWeb()
        })
        webStatus = status.meta
        webBox.addView(status.view)
        refreshWebStatus()
    }

    private fun refreshWebStatus() {
        val view = webStatus ?: return
        val server = core.webServer()
        setMeta(view, when {
            server == null -> t("config_airrelay_web_not_running")
            server.workerCount > 0 && !server.chatReady -> t("config_airrelay_web_connected_no_model", server.workerCount)
            server.workerCount > 0 -> t("config_airrelay_web_connected", server.workerCount)
            else -> t("config_airrelay_web_disconnected")
        })
    }

    private fun refreshPhone() {
        val info = core.pairedPhoneInfo()
        setMeta(phoneMeta, if (info != null) "${info.host}:${info.port}" else t("config_airrelay_none"))
        phoneAction.visibility = if (info != null) VISIBLE else GONE
    }

    private fun refreshLink() {
        val status = core.linkStatus()
        setMeta(linkMeta, when (status.link) {
            AIRelayCore.Link.OFF -> t("config_airrelay_link_off")
            AIRelayCore.Link.WEB -> t("config_airrelay_backend_web")
            AIRelayCore.Link.UNPAIRED -> t("config_airrelay_link_unpaired")
            AIRelayCore.Link.SEARCHING -> t("config_airrelay_link_searching")
            AIRelayCore.Link.CONNECTED -> t("config_airrelay_link_connected", status.host)
            AIRelayCore.Link.CONNECTING -> t("config_airrelay_link_connecting", status.host)
            AIRelayCore.Link.RETRYING -> t("config_airrelay_link_retry", status.host, ((status.retryInMs + 999) / 1000).toInt())
            AIRelayCore.Link.PAUSED -> t("config_airrelay_link_paused")
        })
        if (core.isWebMode()) refreshWebStatus()
    }

    private fun refreshOpen() {
        setMeta(openMeta, t(if (AppPrefs.openMini(host.ctx)) "config_airrelay_open_mini" else "config_airrelay_open_full"))
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
