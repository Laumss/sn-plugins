package me.laumss.notipal.panels

import android.widget.LinearLayout
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.AIRelayCore
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.PanelWidgets
import me.laumss.notipal.ui_common.QrLinkCard

class WebLinkPanel(
    ctx: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule,
) : PanelBase(ctx, toolbar) {
    override val tag = "WebLinkPanel"
    override val panelName = "webLink"
    override val heightRatio = 0.6

    companion object {
        @Volatile var currentInstance: WebLinkPanel? = null

        fun open(ctx: ReactApplicationContext, toolbar: FloatingToolbarModule) {
            val panel = currentInstance ?: WebLinkPanel(ctx, toolbar)
            currentInstance = panel
            panel.show()
        }
    }

    private var content: LinearLayout? = null

    fun show() {
        currentInstance = this
        if (isShowing) {
            rebuild()
            return
        }
        toolbar.parkToolbarUnderPanel()
        showPanel { shown -> if (!shown) toolbar.restoreToolbar() }
    }

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("config_airrelay_web_title"))
            custom { h ->
                LinearLayout(h.ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    content = this
                    rebuild()
                }
            }
            custom { _ ->
                makeBottomBar(
                    leftButtons = listOf(makeOutlinedBtn(NativeLocale.t("config_airrelay_web_new_link")) {
                        AIRelayCore.get(reactContext).rotateWebToken()
                        rebuild()
                    }),
                    rightButtons = listOf(makeFilledBtn(NativeLocale.t("btn_confirm")) { close() }),
                )
            }
        }
    }

    private fun rebuild() {
        val box = content ?: return
        box.removeAllViews()
        val server = AIRelayCore.get(reactContext).ensureWebServer()
        val url = server?.pageUrl()
        if (url != null) {
            box.addView(QrLinkCard.build(
                dslHost,
                payload = url,
                title = NativeLocale.t("config_airrelay_web_title"),
                desc = NativeLocale.t("config_airrelay_web_desc"),
                link = url,
                wrapLink = true,
            ))
        } else {
            val key = if (server == null) "config_airrelay_web_not_running" else "config_airrelay_web_no_lan"
            box.addView(PanelWidgets.emptyView(dslHost, NativeLocale.t(key)))
        }
    }

    private fun close() {
        hide()
        toolbar.restoreToolbar()
    }

    override fun onHide() {
        content = null
        if (currentInstance === this) currentInstance = null
    }
}
