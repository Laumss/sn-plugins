package me.laumss.notipal.ui_common

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import kotlin.math.roundToInt

class ProgressPanel : Panel("progress") {
    override val fullScreen: Boolean get() = true
    override val tag: String get() = "ProgressPanel"

    private var title = ""
    private var status = ""
    private var cancel: (() -> Unit)? = null
    private var statusView: TextView? = null

    private val uiScale get() = Style.device().uiScale
    private val titleScale get() = Style.device().titleScale
    private fun px(v: Int) = (v * App.context.resources.displayMetrics.density * 1.25f * uiScale).roundToInt()

    fun open(title: String, status: String, onCancel: (() -> Unit)?, onShown: (Boolean) -> Unit) {
        Main.post {
            this.title = title
            this.status = status
            cancel = onCancel
            Panels.open(this, onShown)
        }
    }

    fun setStatus(value: String) {
        Main.post {
            status = value
            statusView?.text = value
        }
    }

    override fun build(root: LinearLayout) {
        val ctx = App.context
        root.setBackgroundColor(Style.clear)
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(Style.clear)
            setOnClickListener {}
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.boxPx(Style.paper, Style.ink, px(1), px(12).toFloat())
            clipToOutline = true
            setOnClickListener {}
        }
        fun divider() = View(ctx).apply {
            setBackgroundColor(Style.muted)
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        }
        card.addView(TextView(ctx).apply {
            text = title
            textSize = 23f * titleScale
            setTextColor(Style.ink)
            typeface = Style.bold
            gravity = Gravity.CENTER
            setPadding(px(16), px(22), px(16), px(22))
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(divider())
        statusView = TextView(ctx).apply {
            text = status
            textSize = 15f * 1.25f * uiScale
            setTextColor(Style.ink)
            setPadding(px(28), px(24), px(28), px(24))
        }.also { card.addView(it) }
        val onCancel = cancel
        if (onCancel != null) {
            card.addView(divider())
            val bar = LinearLayout(ctx).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(px(28), px(20), px(28), px(20))
            }
            bar.addView(TextView(ctx).apply {
                text = Strings.get("cancel")
                textSize = 17f * 1.25f * uiScale
                setTextColor(Style.ink)
                gravity = Gravity.CENTER
                minWidth = px(106)
                minHeight = px(44)
                setPadding(px(16), 0, px(16), 0)
                background = Style.boxPx(Style.paper, Style.ink, px(1))
                setOnClickListener {
                    isEnabled = false
                    setStatus(Strings.get("mosaic_note_cancelling"))
                    onCancel()
                }
            }, LinearLayout.LayoutParams(-2, -2))
            card.addView(bar)
        }
        frame.addView(card, FrameLayout.LayoutParams(px(480).coerceAtMost(screenW - px(32)), -2, Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onHide() {
        statusView = null
        cancel = null
    }
}
