package me.laumss.notipal.relay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.ui_common.BitmapSampling
import me.laumss.notipal.ui_common.snux.LineIcon
import me.laumss.notipal.ui_common.snux.Snux
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class StagedImage(val imagePath: String, val maskPath: String)

data class VoiceChip(val id: String, val title: String, val detail: String)

class AttachmentStrip(context: Context, private val onRemove: (StagedImage) -> Unit) : HorizontalScrollView(context) {

    var onRemoveVoice: ((String) -> Unit)? = null

    private fun u(v: Number) = Snux.u(context, v)
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(u(Snux.PANE_INSET), u(10), u(Snux.PANE_INSET), 0)
    }
    private val thumbs = HashMap<StagedImage, Bitmap?>()
    private val linePaint = Paint().apply { color = Color.BLACK }

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.WHITE)
        setWillNotDraw(false)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(scrollX.toFloat(), 0f, scrollX + width.toFloat(), Snux.px(context, 2).toFloat().coerceAtLeast(1f), linePaint)
    }

    fun setItems(items: List<StagedImage>, voices: List<VoiceChip> = emptyList()) {
        thumbs.keys.retainAll(items.toSet())
        row.removeAllViews()
        voices.forEach { voice -> row.addView(voiceChip(voice)) }
        items.forEach { item -> row.addView(chip(item)) }
        visibility = if (items.isEmpty() && voices.isEmpty()) GONE else VISIBLE
    }

    private fun voiceChip(voice: VoiceChip): FrameLayout {
        val height = u(THUMB_HEIGHT_U)
        val close = u(30)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(u(OUTLINE_U).coerceAtLeast(1), Color.BLACK) }
            setPadding(u(10), u(6), u(14), u(6))
            addView(LineIcon(context, LineIcon.Glyph.MIC, 100), LinearLayout.LayoutParams(u(28), u(28)).apply { rightMargin = u(8) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = voice.title
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Snux.F_META).toFloat())
                    typeface = Typeface.DEFAULT_BOLD
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = u(260)
                })
                addView(TextView(context).apply {
                    text = voice.detail
                    setTextColor(0xFF555555.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Snux.F_META).toFloat())
                    isSingleLine = true
                })
            })
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height + close / 2).apply { rightMargin = u(10) }
            addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, height, Gravity.BOTTOM or Gravity.START).apply {
                marginEnd = close / 2
            })
            addView(TextView(context).apply {
                text = "×"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Snux.F_META).toFloat())
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
                setOnClickListener { onRemoveVoice?.invoke(voice.id) }
            }, FrameLayout.LayoutParams(close, close, Gravity.TOP or Gravity.END))
        }
    }

    private fun chip(item: StagedImage): FrameLayout {
        val height = u(THUMB_HEIGHT_U)
        val bitmap = thumbs.getOrPut(item) { loadThumb(item, height) }
        val width = bitmap?.let { (height * it.width / it.height.toFloat()).roundToInt().coerceIn(height / 2, height * 3) } ?: height
        val close = u(30)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(width + close / 2, height + close / 2).apply { rightMargin = u(10) }
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
                background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(u(OUTLINE_U).coerceAtLeast(1), Color.BLACK) }
                setPadding(u(1), u(1), u(1), u(1))
            }, FrameLayout.LayoutParams(width, height, Gravity.BOTTOM or Gravity.START))
            addView(TextView(context).apply {
                text = "×"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Snux.F_META).toFloat())
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
                setOnClickListener { onRemove(item) }
            }, FrameLayout.LayoutParams(close, close, Gravity.TOP or Gravity.END))
        }
    }

    private fun loadThumb(item: StagedImage, height: Int): Bitmap? = loadStagedImage(item, height)

    private companion object {
        const val THUMB_HEIGHT_U = 64
        const val OUTLINE_U = 1.24f
    }
}

fun loadStagedImage(item: StagedImage, height: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(item.imagePath, bounds)
    val box = maskBox(item.maskPath, bounds.outWidth, bounds.outHeight)
    val sample = BitmapSampling.powerOfTwoSampleSize(box[2], box[3], 1, height)
    val source = BitmapFactory.decodeFile(item.imagePath, BitmapFactory.Options().apply { inSampleSize = sample })
    source?.let {
        val x = (box[0] / sample).coerceIn(0, it.width - 1)
        val y = (box[1] / sample).coerceIn(0, it.height - 1)
        val w = (box[2] / sample).coerceIn(1, it.width - x)
        val h = (box[3] / sample).coerceIn(1, it.height - y)
        if (x == 0 && y == 0 && w == it.width && h == it.height) it
        else Bitmap.createBitmap(it, x, y, w, h).also { _ -> it.recycle() }
    }
} catch (_: Exception) { null }

private fun maskBox(maskPath: String, imageW: Int, imageH: Int): IntArray {
    val whole = intArrayOf(0, 0, imageW.coerceAtLeast(1), imageH.coerceAtLeast(1))
    if (maskPath.isBlank()) return whole
    val box = runCatching { JSONObject(File(maskPath).readText()).optJSONObject("boundingBox") }.getOrNull() ?: return whole
    val w = box.optInt("w")
    val h = box.optInt("h")
    return if (w > 0 && h > 0) intArrayOf(box.optInt("x"), box.optInt("y"), w, h) else whole
}
