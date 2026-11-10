package me.laumss.notipal.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.filepicker.FilePickerDevice
import java.io.File
import kotlin.math.roundToInt

class ClipSlotPanel(ctx: ReactApplicationContext, toolbar: FloatingToolbarModule) : PanelBase(ctx, toolbar) {
    override val tag = "ClipSlotPanel"
    override val panelName = "clip-slot-picker"
    override val fullScreen = true

    class Slot(val slot: String, val filled: Boolean, val thumb: String?)

    private var slots: List<Slot> = emptyList()
    private var paste = false
    private var onResult: ((String?) -> Unit)? = null
    private var onClear: ((String) -> Unit)? = null

    private val cellHolders = HashMap<String, FrameLayout>()
    private var contentRoot: FrameLayout? = null
    private var confirmLayer: View? = null
    private var cellWidth = 0
    private var thumbHeight = 0
    private val deviceProfile = FilePickerDevice.profile(ctx)
    private val uiScale = deviceProfile.uiScale
    private val titleScale = deviceProfile.titleScale
    private fun px(v: Int) = (v * density * 1.25f * uiScale).roundToInt()
    private fun textSp(v: Float) = v * 1.25f * uiScale

    fun show(slots: List<Slot>, paste: Boolean, onClear: (String) -> Unit, onResult: (String?) -> Unit) {
        this.slots = slots
        this.paste = paste
        this.onClear = onClear
        this.onResult = onResult
        showPanel { shown -> if (!shown) finish(null) }
    }

    private fun finish(slot: String?) {
        val callback = onResult ?: return
        onResult = null
        callback(slot)
    }

    private fun choose(slot: String?) {
        finish(slot)
        hide()
    }

    override fun buildFullScreenContent(): View {
        val root = FrameLayout(reactContext).apply {
            setBackgroundColor(Color.TRANSPARENT)

            isClickable = true
        }
        contentRoot = root
        val cardWidth = px(600).coerceAtMost(screenW - px(32))
        val card = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(px(1), Color.BLACK); cornerRadius = px(12).toFloat()
            }
            clipToOutline = true
            setOnClickListener {}
        }
        fun divider() = View(reactContext).apply {
            setBackgroundColor(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        }
        card.addView(TextView(reactContext).apply {
            text = NativeLocale.t(if (paste) "clip_pick_paste_title" else "clip_pick_title")
            textSize = 23f * titleScale; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(px(16), px(22), px(16), px(22))
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(divider())

        val padH = px(28)
        val gap = px(14)
        val columns = 4
        cellWidth = (cardWidth - padH * 2 - gap * (columns - 1)) / columns
        thumbHeight = (cellWidth * 0.75f).roundToInt()
        cellHolders.clear()
        val grid = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, px(20), padH, px(24))
        }
        slots.chunked(columns).forEachIndexed { rowIndex, rowSlots ->
            val row = LinearLayout(reactContext).apply { orientation = LinearLayout.HORIZONTAL }
            rowSlots.forEachIndexed { col, slot ->
                val holder = FrameLayout(reactContext)
                holder.addView(slotCell(slot), FrameLayout.LayoutParams(-1, -2))
                cellHolders[slot.slot] = holder
                row.addView(holder, LinearLayout.LayoutParams(cellWidth, -2).apply {
                    if (col > 0) marginStart = gap
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = gap })
        }
        card.addView(grid)
        card.addView(divider())

        val bar = LinearLayout(reactContext).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(px(28), px(20), px(28), px(20))
        }
        bar.addView(button(NativeLocale.t("cancel"), filled = false) { choose(null) }, LinearLayout.LayoutParams(-2, -2))
        card.addView(bar)
        root.addView(card, FrameLayout.LayoutParams(cardWidth, -2, Gravity.CENTER))
        return root
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(reactContext).apply {
        text = label; textSize = textSp(17f)
        setTextColor(if (filled) Color.WHITE else Color.BLACK); gravity = Gravity.CENTER
        minWidth = px(106); minHeight = px(44); setPadding(px(16), 0, px(16), 0)
        background = GradientDrawable().apply {
            setColor(if (filled) Color.BLACK else Color.WHITE); setStroke(px(1), Color.BLACK)
        }
        setOnClickListener { onClick() }
    }

    private fun slotCell(slot: Slot): View {
        val width = cellWidth
        val cell = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            if (paste && !slot.filled) isClickable = true else setOnClickListener { choose(slot.slot) }

            if (slot.filled) setOnLongClickListener { confirmClear(slot.slot); true }
        }
        val box = FrameLayout(reactContext).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(px(1), Color.parseColor("#555555")); cornerRadius = px(6).toFloat()
            }
            clipToOutline = true
        }
        val bitmap = slot.thumb?.let { decodeThumb(it, width, thumbHeight) }
        if (bitmap != null) {
            box.addView(ImageView(reactContext).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(px(6), px(6), px(6), px(6))
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            box.addView(TextView(reactContext).apply {
                text = NativeLocale.t(if (slot.filled) "clip_slot_no_preview" else "clip_slot_empty")
                textSize = textSp(14f); setTextColor(Color.parseColor("#777777")); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        cell.addView(box, LinearLayout.LayoutParams(width, thumbHeight))
        cell.addView(TextView(reactContext).apply {
            text = slot.slot
            textSize = textSp(17f); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, px(8), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return cell
    }

    private fun confirmClear(slot: String) {
        val root = contentRoot ?: return
        if (confirmLayer != null) return
        val layer = FrameLayout(reactContext).apply { isClickable = true }
        val card = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); setStroke(px(1), Color.BLACK); cornerRadius = px(12).toFloat()
            }
            clipToOutline = true
            setOnClickListener {}
        }
        card.addView(TextView(reactContext).apply {
            text = NativeLocale.t("clip_clear_confirm", slot)
            textSize = 21f * titleScale; setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(px(16), px(26), px(16), px(8))
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(TextView(reactContext).apply {
            text = NativeLocale.t("clip_clear_confirm_hint")
            textSize = textSp(15f); setTextColor(Color.parseColor("#555555")); gravity = Gravity.CENTER
            setPadding(px(16), 0, px(16), px(24))
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(View(reactContext).apply { setBackgroundColor(Color.parseColor("#555555")) },
            LinearLayout.LayoutParams(-1, 1))
        val bar = LinearLayout(reactContext).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(px(28), px(20), px(28), px(20))
        }
        bar.addView(button(NativeLocale.t("cancel"), filled = false) { dismissConfirm() }, LinearLayout.LayoutParams(-2, -2))
        bar.addView(button(NativeLocale.t("clip_clear"), filled = true) { clearSlot(slot) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(16) })
        card.addView(bar)
        layer.addView(card, FrameLayout.LayoutParams(px(440).coerceAtMost(screenW - px(32)), -2, Gravity.CENTER))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        confirmLayer = layer
    }

    private fun dismissConfirm() {
        confirmLayer?.let { contentRoot?.removeView(it) }
        confirmLayer = null
    }

    private fun clearSlot(slot: String) {
        dismissConfirm()
        slots = slots.map { if (it.slot == slot) Slot(slot, false, null) else it }
        val cleared = slots.firstOrNull { it.slot == slot } ?: return
        cellHolders[slot]?.let { holder ->
            holder.removeAllViews()
            holder.addView(slotCell(cleared), FrameLayout.LayoutParams(-1, -2))
        }
        onClear?.invoke(slot)
    }

    private fun decodeThumb(path: String, width: Int, height: Int): Bitmap? = runCatching {
        if (!File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    override fun onHide() {
        finish(null)
        slots = emptyList()
        onClear = null
        cellHolders.clear()
        contentRoot = null
        confirmLayer = null
    }
}
