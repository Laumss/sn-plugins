package me.laumss.notipal.render

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.util.Size
import android.util.SizeF
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class PageDisplayFrame(val width: Float, val height: Float, val source: String) {
    fun coordinates(pageWidth: Float, pageHeight: Float): NotePageCoordinates =
        NotePageCoordinates(pageWidth, pageHeight, width, height)

    override fun toString(): String = "${width.toInt()}x${height.toInt()}($source)"
}

internal object PageDisplayFrames {
    private const val TAG = "PageDisplayFrame"

    fun screenRotation(ctx: Context): Int = try {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.rotation
    } catch (_: Exception) {
        Surface.ROTATION_0
    }

    fun isLandscape(rotation: Int): Boolean =
        rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

    fun naturalScreen(ctx: Context): Size = try {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
        }
        Size(min(w, h), max(w, h))
    } catch (_: Exception) {
        Size(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
            .let { Size(min(it.width, it.height), max(it.width, it.height)) }
    }

    fun expected(pageWidth: Float, pageHeight: Float, screenLandscape: Boolean, natural: Size): SizeF {
        val short = natural.width.toFloat()
        val long = natural.height.toFloat()
        val pageLandscape = pageWidth > pageHeight
        val base = if (pageLandscape) SizeF(long, short) else SizeF(short, long)
        if (pageLandscape == screenLandscape) return base
        val grow = long / short
        return SizeF(base.width * grow, base.height * grow)
    }

    fun resolve(ctx: Context, hostValue: Any?, hostError: String?, pageWidth: Float, pageHeight: Float, label: String): PageDisplayFrame {
        val rotation = screenRotation(ctx)
        val landscape = isLandscape(rotation)
        val natural = naturalScreen(ctx)
        val computed = expected(pageWidth, pageHeight, landscape, natural)
        val host = when (hostValue) {
            is SizeF -> hostValue
            is Size -> SizeF(hostValue.width.toFloat(), hostValue.height.toFloat())
            else -> null
        }?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
        val frame = if (host != null) PageDisplayFrame(host.width, host.height, "host")
            else PageDisplayFrame(computed.width, computed.height, "screen-rotation")
        val agrees = host != null && sameFrame(host, computed)
        Log.i(TAG, "[$label] display frame=$frame rotation=$rotation landscape=$landscape " +
            "screen=${natural.width}x${natural.height} page=${pageWidth.toInt()}x${pageHeight.toInt()} " +
            "computed=${computed.width.toInt()}x${computed.height.toInt()} host=${host?.let { "${it.width.toInt()}x${it.height.toInt()}" } ?: "none"}" +
            (if (hostError != null) " hostError=$hostError" else "") +
            (if (host != null && !agrees) " MISMATCH: host display size differs from the screen-rotation rule" else ""))
        return frame
    }

    private fun sameFrame(a: SizeF, b: SizeF): Boolean =
        abs(a.width - b.width) <= 2f && abs(a.height - b.height) <= 2f
}
