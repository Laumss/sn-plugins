package me.laumss.notipal.ui_common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.SeekBar
import kotlin.math.roundToInt

class TranslucentSeekBar(context: Context) : SeekBar(context) {
    companion object {
        private const val TAG = "TranslucentSeekBar"

        private const val A2_MODE = 4
    }

    private var tracking = false

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    init {
        splitTrack = false
        progressTintList = null
        progressBackgroundTintList = null
        thumbTintList = null
        background = null
        val track = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(2f / 1.875f), Color.BLACK)
            cornerRadius = dp(8f / 1.875f).toFloat()
        }
        progressDrawable = LayerDrawable(arrayOf(track)).apply {
            setId(0, android.R.id.background)
            setLayerHeight(0, dp(9f))
            setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
        }
        thumb = thumbDrawable(false)
    }

    private fun thumbDrawable(changing: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (changing) Color.BLACK else Color.WHITE)
        if (!changing) setStroke(dp(2f / 1.875f), Color.BLACK)
        val size = dp(26f)
        setSize(size, size)
    }

    fun setTracking(changing: Boolean) {
        if (tracking == changing) return
        tracking = changing
        try {
            if (changing) View::class.java.getMethod("setEinkUpdateMode", Int::class.javaPrimitiveType).invoke(this, A2_MODE)
            else View::class.java.getMethod("resetEinkUpdateMode").invoke(this)
        } catch (e: Exception) {
            Log.w(TAG, "E-ink A2 unavailable: ${e.message}")
        }
        thumb = thumbDrawable(changing)
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) setTracking(false)
    }

    override fun onDetachedFromWindow() {
        setTracking(false)
        super.onDetachedFromWindow()
    }
}
