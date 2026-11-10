package me.laumss.notipal.relay.ink

import android.content.res.AssetManager

internal class HersheyGlyph(val left: Float, val right: Float, val strokes: List<FloatArray>) {
    val top: Float = strokes.minOfOrNull { s -> (1 until s.size step 2).minOf { s[it] } } ?: BASELINE
    val bottom: Float = strokes.maxOfOrNull { s -> (1 until s.size step 2).maxOf { s[it] } } ?: BASELINE

    companion object {
        const val BASELINE = 9f
    }
}

internal class HersheyGlyphs private constructor(private val glyphs: Map<Int, HersheyGlyph>) {
    operator fun get(cp: Int): HersheyGlyph? = glyphs[cp]

    companion object {
        private const val ASSET = "formula/hershey.txt"
        @Volatile private var cached: HersheyGlyphs? = null

        fun load(assets: AssetManager): HersheyGlyphs = cached ?: synchronized(this) {
            cached ?: read(assets).also { cached = it }
        }

        private fun read(assets: AssetManager): HersheyGlyphs {
            val map = HashMap<Int, HersheyGlyph>()
            assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trimEnd('\r')
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    val parts = line.split('\t')
                    if (parts.size < 3) return@forEach
                    val strokes = parts.getOrNull(3).orEmpty().split(';').filter { it.isNotBlank() }.map { stroke ->
                        stroke.trim().split(' ').flatMap { it.split(',') }.map { it.toFloat() }.toFloatArray()
                    }
                    map[parts[0].toInt(16)] = HersheyGlyph(parts[1].toFloat(), parts[2].toFloat(), strokes)
                }
            }
            return HersheyGlyphs(map)
        }
    }
}
