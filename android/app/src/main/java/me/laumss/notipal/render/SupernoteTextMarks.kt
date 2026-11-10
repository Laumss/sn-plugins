package me.laumss.notipal.render

import android.util.Base64
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

internal object SupernoteTextMarks {
    private const val GRAYSCALE_HIGHLIGHT = 0xFFC8C8C8.toInt()

    data class Rect(val x0: Float, val y0: Float, val x1: Float, val y1: Float)
    data class Mark(val page: Int, val type: Int, val rgb: Int, val opacity: Float,
                    val id: String, val contents: String, val rects: List<Rect>)
    data class Snapshot(val present: Boolean, val marks: List<Mark>)

    fun draw(canvas: Canvas, marks: List<Mark>, grayscale: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        for (mark in marks) {
            if (grayscale) {
                paint.color = if (mark.type == 0) GRAYSCALE_HIGHLIGHT else 0xff000000.toInt()
            } else {
                paint.color = 0xff000000.toInt() or mark.rgb
                paint.alpha = (mark.opacity * 255).toInt().coerceIn(0, 255)
            }

            paint.xfermode = if (mark.type == 0) PorterDuffXfermode(PorterDuff.Mode.DARKEN) else null
            paint.style = if (mark.type == 0) Paint.Style.FILL else Paint.Style.STROKE
            for (rect in mark.rects) {
                val height = rect.y1 - rect.y0
                if (mark.type == 0) {
                    val margin = height / 4.2425f
                    path.reset()
                    path.moveTo(rect.x0, rect.y1)
                    path.cubicTo(rect.x0 - margin, rect.y1 - margin, rect.x0 - margin, rect.y0 + margin, rect.x0, rect.y0)
                    path.lineTo(rect.x1, rect.y0)
                    path.cubicTo(rect.x1 + margin, rect.y0 + margin, rect.x1 + margin, rect.y1 - margin, rect.x1, rect.y1)
                    path.close()
                    canvas.drawPath(path, paint)
                } else {
                    paint.strokeWidth = height / 16f
                    val y = rect.y1 - height / 7f
                    canvas.drawLine(rect.x0, y, rect.x1, y, paint)
                }
            }
        }
    }

    fun read(file: File): Snapshot {
        if (!file.isFile) return Snapshot(false, emptyList())
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 12) { "Truncated Supernote mark file" }
            fun uint(): Long = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
            fun block(offset: Long): String {
                require(offset >= 0 && offset <= input.length() - 4) { "Invalid mark record offset" }
                input.seek(offset)
                val size = uint()
                require(size <= 64L * 1024 * 1024 && size <= input.length() - input.filePointer) { "Invalid mark record size" }
                val bytes = ByteArray(size.toInt())
                input.readFully(bytes)
                return bytes.toString(Charsets.UTF_8)
            }
            fun pointer(record: String, key: String): Long? =
                Regex("<$key:(\\d+)>").find(record)?.groupValues?.get(1)?.toLong()
            input.seek(input.length() - 8)
            val magic = ByteArray(4).also { input.readFully(it) }
            require(magic.contentEquals("tail".toByteArray())) { "Unsupported Supernote mark footer" }
            val footer = block(uint())
            val feature = pointer(footer, "FILE_FEATURE") ?: error("Mark feature record missing")
            val highlight = pointer(block(feature), "HIGHLIGHTINFO") ?: 0
            if (highlight == 0L) return Snapshot(true, emptyList())
            val encoded = block(highlight)
            if (encoded.isBlank()) return Snapshot(true, emptyList())
            return Snapshot(true, parse(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)))
        }
    }

    fun parse(json: String): List<Mark> {
        val pages = JSONObject(json)
        val result = ArrayList<Mark>()
        for (key in pages.keys()) {
            val entries = pages.getJSONArray(key)
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val type = entry.getInt("annotationType")
                require(type in 0..1) { "Unsupported text annotation type: $type" }
                val page = entry.optInt("page", key.toInt())
                require(page >= 0) { "Invalid annotation page" }
                val rectList = entry.getJSONArray("mupdfRectList")
                val rects = (0 until rectList.length()).map { r ->
                    val rect = rectList.getJSONObject(r)
                    Rect(rect.getDouble("x0").toFloat(), rect.getDouble("y0").toFloat(),
                        rect.getDouble("x1").toFloat(), rect.getDouble("y1").toFloat()).also {
                        require(listOf(it.x0, it.y0, it.x1, it.y1).all(Float::isFinite) && it.x1 > it.x0 && it.y1 > it.y0) {
                            "Invalid text annotation bounds"
                        }
                    }
                }
                if (rects.isEmpty()) continue

                val color = entry.optInt("colorType", if (type == 0) 0 else 4)
                val rgb = when (color) { 0 -> 0xffff00; 1 -> 0x00ff00; 2 -> 0xff0000; 3 -> 0x0000ff; else -> 0x000000 }
                val opacity = if (type == 1) 1f else when (color) { 0, 1 -> .4f; 2 -> .31f; 3 -> .5f; else -> .3f }
                val id = "notipal:text:$page:${entry.optLong("time", index.toLong())}:$index"
                result.add(Mark(page, type, rgb, opacity, id, entry.optString("annotationContent", ""), rects))
            }
        }
        return result.sortedBy { it.page }
    }
}
