package me.laumss.notipal.textinsert

import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class TextLayoutEngine {

    companion object {
        private const val FONT_SIZE = 36
        private const val PAGE_MARGIN_LEFT = 0.07
        private const val PAGE_MARGIN_RIGHT = 0.04
        private const val TOP_MARGIN_BASE = 150
        private const val BASE_PAGE_HEIGHT = 1872

        private const val WIDTH_ADJUSTMENT = 30

        private const val SYSTEM_FONT_WRAP_SAFETY_RATIO = 0.15
        private const val SYSTEM_FONT_WRAP_SAFETY_MIN_LINES = 8
        private const val MAX_INSERT_LEFT_RATIO = 0.60
        private const val MIN_TEXTBOX_WIDTH_RATIO = 0.20
        private const val MIN_TEXTBOX_FONT_COLUMNS = 8

        private const val DEVICE_LINE_HEIGHT = 42
        const val NOTE_RENDER_PAD = 28

        private const val MIN_TEXTBOX_HEIGHT = 48

        private const val EMOJI_PER_EXTRA_LINE = 4
        private val EMOJI_REGEX = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}]")

        private fun emojiExtraHeight(text: String): Int {
            val count = EMOJI_REGEX.findAll(text).count()
            if (count == 0) return 0
            return ((count + EMOJI_PER_EXTRA_LINE - 1) / EMOJI_PER_EXTRA_LINE) * DEVICE_LINE_HEIGHT
        }

        private fun noteRenderLineCount(measuredLineCount: Int): Int {
            if (measuredLineCount < SYSTEM_FONT_WRAP_SAFETY_MIN_LINES) return measuredLineCount
            val extraLines = ceil(measuredLineCount * SYSTEM_FONT_WRAP_SAFETY_RATIO).toInt()
            return measuredLineCount + extraLines
        }
    }

    private data class ModeConfig(
        val boxGap: Int,
        val threshold: Double,
        val lineHeightRatio: Double,
        val newlineGapLines: Double,
    )

    private val modeConfigs = mapOf(
        "nospacing" to ModeConfig(boxGap = 0, threshold = 0.87, lineHeightRatio = 1.4, newlineGapLines = 0.0),
        "paragraph" to ModeConfig(boxGap = 40, threshold = 0.80, lineHeightRatio = 1.6, newlineGapLines = 0.3),
    )

    private val textPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = FONT_SIZE.toFloat()
    }

    private fun buildStaticLayout(text: String, width: Int): StaticLayout {
        val measuredWidth = max(24, width - WIDTH_ADJUSTMENT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, measuredWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, textPaint, measuredWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true)
        }
    }

    private fun normalizeOverrideLeft(pageWidth: Int, right: Int, requestedLeft: Int): Int {
        val minTextboxWidth = min(
            right,
            max(FONT_SIZE * MIN_TEXTBOX_FONT_COLUMNS, floor(pageWidth * MIN_TEXTBOX_WIDTH_RATIO).toInt())
        )
        val safeMaxLeft = max(
            0,
            min(floor(pageWidth * MAX_INSERT_LEFT_RATIO).toInt(), right - minTextboxWidth)
        )
        return requestedLeft.coerceIn(0, safeMaxLeft)
    }

    fun calculateLayout(params: ReadableMap, promise: Promise) {
        try {
            val text = params.getString("text") ?: ""
            val mode = params.getString("mode") ?: "nospacing"
            val pageWidth = params.getInt("pageWidth")
            val pageHeight = params.getInt("pageHeight")
            val nextTop = params.getInt("nextTop")
            val occupiedArray = params.getArray("occupiedRanges")

            val cfg = modeConfigs[mode] ?: modeConfigs["nospacing"]!!

            val topMargin = (TOP_MARGIN_BASE * (pageHeight.toDouble() / BASE_PAGE_HEIGHT)).roundToInt()
            val defaultLeft = floor(pageWidth * PAGE_MARGIN_LEFT).toInt()
            val right = pageWidth - floor(pageWidth * PAGE_MARGIN_RIGHT).toInt()
            val overrideLeft = if (params.hasKey("overrideLeft") && !params.isNull("overrideLeft")) params.getInt("overrideLeft") else -1
            val left = if (overrideLeft >= 0) normalizeOverrideLeft(pageWidth, right, overrideLeft) else defaultLeft
            val maxH = floor(pageHeight * cfg.threshold).toInt()
            val boxWidth = right - left

            val layout = buildStaticLayout(text, boxWidth)
            val nativeHeight = layout.height
            val measuredLineCount = layout.lineCount
            val renderLineCount = noteRenderLineCount(measuredLineCount)

            val deviceHeight = renderLineCount * DEVICE_LINE_HEIGHT

            val segments = text.split("\n").filter { it.trim().isNotEmpty() }
            val gapCount = if (cfg.newlineGapLines > 0 && segments.size > 1) segments.size - 1 else 0
            val extraGap = ceil(gapCount * cfg.newlineGapLines * FONT_SIZE * cfg.lineHeightRatio).toInt()

            val boxH = max(MIN_TEXTBOX_HEIGHT,
                max(nativeHeight, deviceHeight) + extraGap + emojiExtraHeight(text) + NOTE_RENDER_PAD)

            val occupied = parseOccupiedRanges(occupiedArray)
            val top = skipOccupiedArea(nextTop, boxH, max(cfg.boxGap, 10), occupied)

            val bottom = min(top + boxH, pageHeight - topMargin)

            val usableHeight = pageHeight - 2 * topMargin
            val newPage = top >= maxH
                || (top > topMargin + 80 && top + boxH > pageHeight - topMargin)
                || (top <= topMargin + 80 && boxH > usableHeight)

            val remainingHeight = max(0, (pageHeight - topMargin) - top)

            val result = Arguments.createMap().apply {
                putInt("top", top)
                putInt("boxHeight", boxH)
                putInt("bottom", bottom)
                putBoolean("newPage", newPage)
                putInt("maxH", maxH)
                putInt("left", left)
                putInt("right", right)
                putInt("charsPerLine", if (measuredLineCount > 0) max(1, text.length / measuredLineCount) else 1)
                putInt("lines", renderLineCount)
                putInt("measuredLines", measuredLineCount)
                putInt("topMargin", topMargin)
                putInt("boxGap", cfg.boxGap)
                putInt("nativeHeight", nativeHeight)
                putInt("remainingHeight", remainingHeight)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("LAYOUT_ERROR", e.message, e)
        }
    }

    private fun parseOccupiedRanges(array: ReadableArray?): List<Pair<Int, Int>> {
        if (array == null) return emptyList()
        val ranges = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until array.size()) {
            val map = array.getMap(i) ?: continue
            val top = map.getInt("top")
            val bottom = map.getInt("bottom")
            ranges.add(Pair(top, bottom))
        }
        ranges.sortBy { it.first }
        return ranges
    }

    private fun skipOccupiedArea(candidateTop: Int, boxH: Int, gap: Int, ranges: List<Pair<Int, Int>>): Int {
        var top = candidateTop
        var iterations = 0
        while (iterations < 50) {
            iterations++
            var collision = false
            for ((rTop, rBottom) in ranges) {
                if (top < rBottom && (top + boxH) > rTop) {
                    top = rBottom + gap
                    collision = true
                    break
                }
            }
            if (!collision) break
        }
        return top
    }
}

internal class TextboxMetrics {

    fun splitTextForHeight(options: ReadableMap, promise: Promise) {
        try {
            val ctx = buildMeasurement(options)
            val layout = ctx.layout
            val availableHeight = options.getInt("availableHeight")

            if (layout.height <= availableHeight) {
                promise.resolve(splitResult(ctx.text, "", layout.height, layout.lineCount, false))
                return
            }

            val bottomPad = layout.height - layout.getLineBottom(layout.lineCount - 1)

            var lastFittingLine = -1
            for (i in 0 until layout.lineCount) {
                val lineBottom = layout.getLineBottom(i) + bottomPad
                if (lineBottom <= availableHeight) {
                    lastFittingLine = i
                } else {
                    break
                }
            }

            if (lastFittingLine < 0) {
                promise.resolve(splitResult("", ctx.text, 0, 0, true))
                return
            }

            val splitCharIndex = layout.getLineEnd(lastFittingLine)
            var fittingText = ctx.text.substring(0, splitCharIndex).trimEnd()
            var overflowText = ctx.text.substring(splitCharIndex).trimStart()

            var actualLastLine = lastFittingLine
            val betterSplit = findNaturalBreak(ctx.text, splitCharIndex)
            if (betterSplit > 0 && betterSplit < splitCharIndex) {
                fittingText = ctx.text.substring(0, betterSplit).trimEnd()
                overflowText = ctx.text.substring(betterSplit).trimStart()

                val breakLine = layout.getLineForOffset(max(0, betterSplit - 1))
                if (breakLine < lastFittingLine) actualLastLine = breakLine
            }

            val leadingPunct = "，。、；：！？）》」』”’,.;:!?)]}"
            while (overflowText.isNotEmpty() && overflowText.first() in leadingPunct &&
                fittingText.isNotEmpty()
            ) {
                overflowText = fittingText.last() + overflowText
                fittingText = fittingText.dropLast(1).trimEnd()
            }
            if (fittingText.isEmpty()) {

                promise.resolve(splitResult("", ctx.text, 0, 0, true))
                return
            }

            val fittingHeight = layout.getLineBottom(actualLastLine) + bottomPad
            promise.resolve(splitResult(fittingText, overflowText, fittingHeight, actualLastLine + 1, true))
        } catch (e: Throwable) {
            promise.reject("E_SPLIT_TEXT", e)
        }
    }

    private fun splitResult(
        fittingText: String,
        overflowText: String,
        fittingHeight: Int,
        fittingLineCount: Int,
        didSplit: Boolean
    ) = Arguments.createMap().apply {
        putString("fittingText", fittingText)
        putString("overflowText", overflowText)
        putInt("fittingHeight", fittingHeight)
        putInt("fittingLineCount", fittingLineCount)
        putBoolean("didSplit", didSplit)
    }

    private data class MeasurementContext(
        val text: String,
        val width: Int,
        val fontSize: Float,
        val includePad: Boolean,
        val layout: StaticLayout
    )

    private fun buildMeasurement(options: ReadableMap): MeasurementContext {
        val text = options.getString("text") ?: ""
        val width = options.getInt("width")
        val fontSize = options.getDouble("fontSize").toFloat()
        val includePad = if (options.hasKey("includePad")) options.getBoolean("includePad") else true
        val fontPath = if (options.hasKey("fontPath") && !options.isNull("fontPath"))
            options.getString("fontPath") else null

        if (width < 0) throw IllegalArgumentException("width must be >= 0")

        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = fontSize
            typeface = loadTypeface(fontPath)
        }

        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(includePad)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, includePad)
        }

        return MeasurementContext(text, width, fontSize, includePad, layout)
    }

    private fun loadTypeface(fontPath: String?): Typeface? {
        if (fontPath.isNullOrBlank()) return null
        val file = File(fontPath)
        if (!file.exists()) return null
        return try { Typeface.createFromFile(file) } catch (_: Throwable) { null }
    }

    private fun findNaturalBreak(text: String, splitCharIndex: Int): Int {

        val searchStart = max(0, splitCharIndex - (splitCharIndex * 0.3).toInt())

        val lastParaBreak = text.lastIndexOf("\n\n", splitCharIndex - 1)
        if (lastParaBreak >= searchStart && lastParaBreak > 0) {
            return lastParaBreak + 2
        }

        val lastNewline = text.lastIndexOf('\n', splitCharIndex - 1)
        if (lastNewline >= searchStart && lastNewline > 0) {
            return lastNewline + 1
        }

        val sentenceEnders = charArrayOf('。', '！', '？', '.', '!', '?')
        for (i in (splitCharIndex - 1) downTo searchStart) {
            if (text[i] in sentenceEnders) {
                val next = i + 1
                if (next <= splitCharIndex) return next
            }
        }

        return 0
    }
}
