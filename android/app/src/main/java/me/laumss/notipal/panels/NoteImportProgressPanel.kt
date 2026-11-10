package me.laumss.notipal.panels

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.filepicker.FilePickerDevice
import kotlin.math.roundToInt

class NoteImportProgressPanel(ctx: ReactApplicationContext, toolbar: FloatingToolbarModule) : PanelBase(ctx, toolbar) {
    override val tag = "NoteImportProgress"
    override val panelName = "note-import-progress"
    override val fullScreen = true
    private var status = ""
    private var statusView: TextView? = null
    private var cancelButton: TextView? = null
    private var cancel: (() -> Unit)? = null
    private var title = ""
    private val deviceProfile = FilePickerDevice.profile(ctx)
    private val uiScale = deviceProfile.uiScale
    private val titleScale = deviceProfile.titleScale
    private fun px(v: Int) = (v * density * 1.25f * uiScale).roundToInt()

    fun show(
        onCancel: (() -> Unit)?,
        initialStatus: String = NativeLocale.t("mosaic_note_importing"),
        titleText: String = NativeLocale.t("mosaic_note_converting"),
        onShown: (Boolean) -> Unit,
    ) {
        cancel = onCancel
        status = initialStatus
        title = titleText
        showPanel(onShown)
    }

    fun setStatus(value: String) { status = value; statusView?.text = value }

    override fun buildFullScreenContent(): View {
        val root = FrameLayout(reactContext).apply { setBackgroundColor(Color.TRANSPARENT); setOnClickListener {} }
        val card = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(px(1), Color.BLACK); cornerRadius = px(12).toFloat() }
            clipToOutline = true
            setOnClickListener {}
        }
        fun divider() = View(reactContext).apply {
            setBackgroundColor(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        }
        card.addView(TextView(reactContext).apply {
            text = title
            textSize = 23f * titleScale; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(px(16), px(22), px(16), px(22))
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(divider())
        statusView = TextView(reactContext).apply {
            text = status; textSize = 15f * 1.25f * uiScale; setTextColor(Color.BLACK)
            setPadding(px(28), px(24), px(28), px(24))
        }.also { card.addView(it) }
        if (cancel == null) {
            root.addView(card, FrameLayout.LayoutParams(px(480).coerceAtMost(screenW - px(32)), -2, Gravity.CENTER))
            return root
        }
        card.addView(divider())
        val bar = LinearLayout(reactContext).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(px(28), px(20), px(28), px(20))
        }
        cancelButton = TextView(reactContext).apply {
            text = NativeLocale.t("cancel"); textSize = 17f * 1.25f * uiScale
            setTextColor(Color.BLACK); gravity = Gravity.CENTER
            minWidth = px(106); minHeight = px(44); setPadding(px(16), 0, px(16), 0)
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(px(1), Color.BLACK) }
            setOnClickListener {
                isEnabled = false
                setStatus(NativeLocale.t("mosaic_note_cancelling"))
                cancel?.invoke()
            }
        }.also { bar.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        card.addView(bar)
        root.addView(card, FrameLayout.LayoutParams(px(480).coerceAtMost(screenW - px(32)), -2, Gravity.CENTER))
        return root
    }

    override fun onHide() { statusView = null; cancelButton = null; cancel = null }
}
