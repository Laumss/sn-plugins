package me.laumss.notipal.ui_common

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {

    fun get(x: Int, y: Int): Boolean = modules[y][x]

    fun toBitmap(scale: Int, quiet: Int = 4): Bitmap {
        val dim = (size + quiet * 2) * scale
        val pixels = IntArray(dim * dim) { Color.WHITE }
        for (y in 0 until size) for (x in 0 until size) {
            if (!modules[y][x]) continue
            val px = (x + quiet) * scale
            val py = (y + quiet) * scale
            for (dy in 0 until scale) {
                val row = (py + dy) * dim + px
                for (dx in 0 until scale) pixels[row + dx] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.RGB_565)
    }

    companion object {
        private const val TAG = "QrCode"

        fun encode(text: String): QrCode? {
            if (text.isEmpty()) return null
            return try {
                val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
                val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
                val size = matrix.width
                val modules = Array(size) { y -> BooleanArray(size) { x -> matrix.get(x, y).toInt() == 1 } }
                QrCode(size, modules)
            } catch (e: Exception) {
                Log.w(TAG, "encode failed: ${e.message}")
                null
            }
        }
    }
}
