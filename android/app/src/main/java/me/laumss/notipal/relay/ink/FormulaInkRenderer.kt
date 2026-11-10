package me.laumss.notipal.relay.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class FormulaInkRenderer(context: Context) {

    companion object {
        private const val TAG = "FormulaInk"
        private const val PX_PER_EM = 144f
        private const val PAD_EM = 0.08f
        private const val MAX_PX = 3200f

        private const val PEN_PX = 6f
    }

    private val assets = context.assets
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "FormulaInk").apply { isDaemon = true } }

    fun render(latex: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (latex.isBlank()) { main.post { onError("empty latex") }; return }
        worker.execute {
            val result = runCatching { renderNow(latex) }
            main.post {
                result.fold(onResult) { error ->
                    Log.e(TAG, "render failed: ${error.message}", error)
                    onError("ink: ${error.message}")
                }
            }
        }
    }

    fun warmup() {
        worker.execute { runCatching { HersheyGlyphs.load(assets) } }
    }

    fun release() = worker.shutdown()

    private fun renderNow(latex: String): String {
        val parser = TexParser(latex)
        val node = parser.parse()
        val layout = InkLayout(HersheyGlyphs.load(assets))
        val box = layout.layout(node, InkStyle.D)
        if (parser.unknown.isNotEmpty()) Log.w(TAG, "unknown commands: ${parser.unknown.joinToString()}")
        if (layout.missing.isNotEmpty()) {
            Log.w(TAG, "no glyph for: ${layout.missing.joinToString { "U+%04X".format(it) }}")
        }

        val wEm = box.w + 2 * PAD_EM
        val hEm = box.h + box.d + 2 * PAD_EM
        var scale = PX_PER_EM
        if (wEm * scale > MAX_PX) scale = MAX_PX / wEm
        if (hEm * scale > MAX_PX) scale = MAX_PX / hEm
        val pxW = max(1, (wEm * scale).roundToInt())
        val pxH = max(1, (hEm * scale).roundToInt())
        val pen = PEN_PX * scale / PX_PER_EM

        val bitmap = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = pen
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val strokes = JSONArray()
        for (s in box.strokes) {
            if (s.size < 4) continue
            val pts = JSONArray()
            val path = Path()
            for (i in 0 until s.size step 2) {
                val x = (s[i] + PAD_EM) * scale
                val y = (s[i + 1] + box.h + PAD_EM) * scale
                pts.put(round1(x)).put(round1(y))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
            strokes.put(JSONObject().put("w", round1(pen)).put("pts", pts))
        }
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        bitmap.recycle()
        Log.i(TAG, "ink: ${strokes.length()} strokes ${pxW}x$pxH em=${"%.2f".format(hEm)} latexLen=${latex.length}")
        return JSONObject()
            .put("width", pxW)
            .put("height", pxH)
            .put("em", (hEm * 100).roundToInt() / 100.0)
            .put("png", png)
            .put("strokes", strokes)
            .toString()
    }

    private fun round1(v: Float): Double = (v * 10).roundToInt() / 10.0
}
