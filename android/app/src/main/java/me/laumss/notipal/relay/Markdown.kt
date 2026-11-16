package me.laumss.notipal.relay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.Strings
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.setLineHeightPx
import kotlin.math.ceil

data class SourceRange(
    val startOffset: Int,
    val endOffsetExclusive: Int,
) {
    init {
        require(startOffset >= 0)
        require(endOffsetExclusive >= startOffset)
    }
}

enum class MarkdownAlignment { LEFT, CENTER, RIGHT }

sealed class MarkdownBlock(open val sourceRange: SourceRange) {
    data class Heading(val level: Int, val text: String, override val sourceRange: SourceRange) : MarkdownBlock(sourceRange)
    data class Paragraph(
        val text: String,
        override val sourceRange: SourceRange,

        val lineIndents: List<Int> = emptyList(),
    ) : MarkdownBlock(sourceRange)
    data class Quote(val text: String, override val sourceRange: SourceRange) : MarkdownBlock(sourceRange)
    data class CodeFence(val code: String, override val sourceRange: SourceRange) : MarkdownBlock(sourceRange)
    data class ListItem(
        val text: String,
        val marker: String,
        val indent: Int,
        override val sourceRange: SourceRange,
    ) : MarkdownBlock(sourceRange)
    data class Rule(override val sourceRange: SourceRange) : MarkdownBlock(sourceRange)
    data class Table(
        val headers: List<String>,
        val alignments: List<MarkdownAlignment>,
        val rows: List<List<String>>,
        override val sourceRange: SourceRange,
    ) : MarkdownBlock(sourceRange)
}

data class MarkdownDocument(
    val source: String,
    val blocks: List<MarkdownBlock>,
)

object MarkdownDocumentParser {
    private data class SourceLine(
        val content: String,
        val startOffset: Int,
        val endOffsetExclusive: Int,
    )

    private data class TableStart(
        val headers: List<String>,
        val alignments: List<MarkdownAlignment>,
    )

    fun parse(source: String): MarkdownDocument {
        val lines = scanLines(source)
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0

        fun range(first: Int, last: Int): SourceRange = SourceRange(
            startOffset = lines[first].startOffset,
            endOffsetExclusive = lines[last].endOffsetExclusive,
        )

        while (i < lines.size) {
            val raw = lines[i].content
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.equals("<md>", ignoreCase = true) || trimmed.equals("</md>", ignoreCase = true)) {
                i++
                continue
            }

            val tableStart = parseTableStart(lines, i)
            val listItem = LIST_ITEM.matchEntire(trimmed)
            when {
                trimmed.startsWith("```") -> {
                    val start = i
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].content.trim().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i].content)
                        i++
                    }
                    if (i < lines.size) i++
                    blocks += MarkdownBlock.CodeFence(code.toString(), range(start, i - 1))
                }

                tableStart != null -> {
                    val start = i
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size) {
                        val body = lines[i].content
                        if (body.isBlank()) break
                        val cells = splitTableCells(body) ?: break
                        rows += cells.normalizedTo(tableStart.headers.size)
                        i++
                    }
                    blocks += MarkdownBlock.Table(
                        headers = tableStart.headers,
                        alignments = tableStart.alignments,
                        rows = rows,
                        sourceRange = range(start, i - 1),
                    )
                }

                HEADING.matches(trimmed) -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val headText = trimmed.drop(level).trim()
                    val text = roleHeading(headText)?.let { (role, rest) ->
                        val cleaned = rest.replace(UUID_PATTERN, "").trim()
                        if (cleaned.isEmpty()) "$role:" else "$role: $cleaned"
                    } ?: headText
                    blocks += MarkdownBlock.Heading(level, text, range(i, i))
                    i++
                }

                RULE.matches(trimmed) -> {
                    blocks += MarkdownBlock.Rule(range(i, i))
                    i++
                }

                trimmed.startsWith(">") -> {
                    val start = i
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].content.trim().startsWith(">")) {
                        if (quote.isNotEmpty()) quote.append('\n')
                        quote.append(lines[i].content.trim().removePrefix(">").trim())
                        i++
                    }
                    blocks += MarkdownBlock.Quote(quote.toString(), range(start, i - 1))
                }

                listItem != null -> {
                    val (bullet, text) = listItem.destructured
                    blocks += MarkdownBlock.ListItem(
                        text = text.trim(),
                        marker = if (bullet[0].isDigit()) bullet.dropLast(1) + "." else "•",
                        indent = (raw.length - raw.trimStart().length) / 2,
                        sourceRange = range(i, i),
                    )
                    i++
                }

                else -> {
                    val start = i
                    val paragraph = StringBuilder()
                    val indents = ArrayList<Int>()
                    while (i < lines.size && lines[i].content.isNotBlank()) {
                        if (i > start && isStandaloneBlockStart(lines, i)) break
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(lines[i].content.trim())
                        indents += lines[i].content.takeWhile { it == ' ' || it == '\t' }.fold(0) { sum, c -> sum + if (c == '\t') 4 else 1 }
                        i++
                    }
                    blocks += MarkdownBlock.Paragraph(
                        text = paragraph.toString().trim(),
                        sourceRange = range(start, i - 1),
                        lineIndents = indents,
                    )
                }
            }
        }

        return MarkdownDocument(source, blocks)
    }

    fun italicClose(text: String, open: Int): Int {
        if (text.getOrNull(open + 1)?.isWhitespace() != false) return -1
        return (open + 2 until text.length).firstOrNull { text[it] == '*' && !text[it - 1].isWhitespace() } ?: -1
    }

    fun pairClose(text: String, open: Int, delim: String): Int {
        val close = text.indexOf(delim, open + delim.length)
        val three = close >= 0 && text.getOrNull(open + delim.length) == delim[0] && text.getOrNull(close + delim.length) == delim[0]
        return if (three) close + 1 else close
    }

    fun plainInline(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("**", i) || text.startsWith("~~", i) -> {
                    val close = pairClose(text, i, text.substring(i, i + 2))
                    if (close < 0) { out.append(text, i, i + 2); i += 2 }
                    else { out.append(plainInline(text.substring(i + 2, close))); i = close + 2 }
                }
                c == '*' -> {
                    val close = italicClose(text, i)
                    if (close < 0) { out.append(c); i++ }
                    else { out.append(plainInline(text.substring(i + 1, close))); i = close + 1 }
                }
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close < 0) { out.append(c); i++ }
                    else { out.append(text, i + 1, close); i = close + 1 }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun isStandaloneBlockStart(lines: List<SourceLine>, index: Int): Boolean {
        val trimmed = lines[index].content.trim()
        return trimmed.startsWith("```") ||
            HEADING.matches(trimmed) ||
            RULE.matches(trimmed) ||
            trimmed.startsWith(">") ||
            LIST_ITEM.matches(trimmed) ||
            parseTableStart(lines, index) != null
    }

    private fun parseTableStart(lines: List<SourceLine>, index: Int): TableStart? {
        if (index + 1 >= lines.size) return null
        val headers = splitTableCells(lines[index].content) ?: return null
        val delimiters = splitTableCells(lines[index + 1].content) ?: return null
        if (headers.size < 2 || headers.size != delimiters.size) return null

        val alignments = delimiters.map { cell ->
            val value = cell.trim()
            if (!TABLE_DELIMITER.matches(value)) return null
            when {
                value.startsWith(':') && value.endsWith(':') -> MarkdownAlignment.CENTER
                value.endsWith(':') -> MarkdownAlignment.RIGHT
                else -> MarkdownAlignment.LEFT
            }
        }
        return TableStart(headers, alignments)
    }

    private fun splitTableCells(line: String): List<String>? {
        var value = line.trim()
        if (!containsUnescapedPipe(value)) return null
        if (value.startsWith('|')) value = value.drop(1)
        if (value.endsWithUnescapedPipe()) value = value.dropLast(1)

        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length && value[i + 1] == '|') {
                cell.append('|')
                i += 2
            } else if (ch == '|') {
                cells += cell.toString().trim()
                cell.clear()
                i++
            } else {
                cell.append(ch)
                i++
            }
        }
        cells += cell.toString().trim()
        return cells
    }

    private fun containsUnescapedPipe(value: String): Boolean {
        var slashCount = 0
        value.forEach { ch ->
            when (ch) {
                '\\' -> slashCount++
                '|' -> {
                    if (slashCount % 2 == 0) return true
                    slashCount = 0
                }
                else -> slashCount = 0
            }
        }
        return false
    }

    private fun String.endsWithUnescapedPipe(): Boolean {
        if (!endsWith('|')) return false
        var slashCount = 0
        var i = length - 2
        while (i >= 0 && this[i] == '\\') {
            slashCount++
            i--
        }
        return slashCount % 2 == 0
    }

    private fun List<String>.normalizedTo(size: Int): List<String> = when {
        this.size == size -> this
        this.size < size -> this + List(size - this.size) { "" }
        else -> take(size)
    }

    private fun scanLines(source: String): List<SourceLine> {
        if (source.isEmpty()) return emptyList()
        val lines = mutableListOf<SourceLine>()
        var start = 0
        var i = 0
        while (i < source.length) {
            when (source[i]) {
                '\r', '\n' -> {
                    val contentEnd = i
                    if (source[i] == '\r' && i + 1 < source.length && source[i + 1] == '\n') i += 2 else i++
                    lines += SourceLine(source.substring(start, contentEnd), start, i)
                    start = i
                }
                else -> i++
            }
        }
        if (start < source.length) {
            lines += SourceLine(source.substring(start), start, source.length)
        }
        return lines
    }

    private val HEADING = Regex("^#{1,6}\\s+.*")
    private val ROLE_HEADING = Regex("^(H|A|Human|Assistant)\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val UUID_PATTERN = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{1,12}",
        RegexOption.IGNORE_CASE,
    )

    fun roleHeading(headText: String): Pair<String, String>? {
        val m = ROLE_HEADING.matchEntire(headText) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }
    private val RULE = Regex("^([-*_])\\1{2,}$")
    private val LIST_ITEM = Regex("^([-*+]|\\d+[.)])\\s+(.*)")
    private val TABLE_DELIMITER = Regex("^:?-{3,}:?$")
}

internal const val LIST_INDENT_DP = 18
internal const val LIST_MARKER_DP = 26

private inline fun SpannableStringBuilder.styled(spans: List<Any>, block: SpannableStringBuilder.() -> Unit) {
    val start = length
    block()
    if (length > start) spans.forEach { setSpan(it, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
}

private fun inlineStyled(text: String): SpannableStringBuilder {
    val out = SpannableStringBuilder()
    var i = 0

    fun span(delim: String, spans: List<Any>, close: Int = text.indexOf(delim, i + delim.length), raw: Boolean = false) {
        if (close < 0) { out.append(delim); i += delim.length; return }
        val inner = text.substring(i + delim.length, close)
        out.styled(spans) { if (raw) append(inner) else append(inlineStyled(inner)) }
        i = close + delim.length
    }

    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("**", i) -> span("**", listOf(StyleSpan(Typeface.BOLD), ForegroundColorSpan(Style.ink)),
                close = MarkdownDocumentParser.pairClose(text, i, "**"))
            text.startsWith("~~", i) -> span("~~", listOf(StrikethroughSpan(), ForegroundColorSpan(Style.ink)),
                close = MarkdownDocumentParser.pairClose(text, i, "~~"))
            c == '*' -> span("*", listOf(StyleSpan(Typeface.ITALIC), ForegroundColorSpan(Style.ink)), close = MarkdownDocumentParser.italicClose(text, i))
            c == '`' -> span("`", listOf(TypefaceSpan("monospace"), BackgroundColorSpan(Style.faint), ForegroundColorSpan(Style.ink)), raw = true)
            c == '[' || text.startsWith("![", i) -> {
                val image = c == '!'
                val mid = text.indexOf("](", i)
                val end = if (mid < 0) -1 else text.indexOf(')', mid)
                if (end < 0) { out.append(c); i++ } else {
                    val label = text.substring(i + if (image) 2 else 1, mid)
                    if (image) out.styled(listOf(ForegroundColorSpan(Style.hint))) { append(Strings.get("relay_image_alt", label)) }
                    else out.styled(listOf(UnderlineSpan(), ForegroundColorSpan(Style.ink))) { append(label) }
                    i = end + 1
                }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out
}

private fun indentedLines(text: SpannableStringBuilder, indents: List<Int>, sizeSp: Int): SpannableStringBuilder {
    if (indents.none { it > 0 }) return text
    val lines = text.split("\n")
    if (lines.size != indents.size) return text
    val em = Style.rawSp(sizeSp)
    var start = 0
    for ((n, line) in lines.withIndex()) {
        val end = start + line.length
        if (indents[n] > 0 && end > start) {
            val px = (em * 0.5f * (indents[n] / 4).coerceAtLeast(1)).toInt()
            text.setSpan(LeadingMarginSpan.Standard(px), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        start = end + 1
    }
    return text
}

private fun mdText(
    ctx: Context,
    text: CharSequence,
    sizeSp: Number,
    face: Typeface?,
    color: Int = Style.ink,
    lineSp: Number? = null,
): TextView = TextView(ctx).apply {
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
    if (face != null) typeface = face

    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    if (lineSp != null) setLineHeightPx(Style.rawSp(lineSp))
    this.text = text
}

internal fun markdownBlockView(ctx: Context, block: MarkdownBlock, baseFont: Int, boldBody: Boolean = true, serifHeads: Boolean = false): View {
    val bodyFace = if (boldBody) Typeface.DEFAULT_BOLD else null
    val headFace = when {
        serifHeads -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        boldBody -> Style.weighted(800)
        else -> Typeface.DEFAULT_BOLD
    }
    return when (block) {
        is MarkdownBlock.Heading -> {
            val size = when (block.level) {
                1 -> baseFont + 10
                2 -> baseFont + 6
                3 -> baseFont + 3
                else -> baseFont + 1
            }
            val role = MarkdownDocumentParser.roleHeading(block.text)
            if (role != null) {
                val text = SpannableStringBuilder()
                text.styled(listOf(AbsoluteSizeSpan(Style.rawSp(size).toInt()), StyleSpan(Typeface.BOLD), ForegroundColorSpan(Style.ink))) {
                    append(role.first + ":")
                }
                if (role.second.isNotBlank()) {
                    text.append(" ")
                    text.append(inlineStyled(role.second))
                }
                mdText(ctx, text, baseFont, bodyFace, lineSp = size * 1.3f).apply {
                    setPadding(0, Style.rawDp(baseFont * 0.8f), 0, 0)
                }
            } else {
                mdText(ctx, inlineStyled(block.text), size, headFace, lineSp = size * 1.3f)
            }
        }

        is MarkdownBlock.Paragraph -> mdText(ctx, indentedLines(inlineStyled(block.text), block.lineIndents, baseFont), baseFont, bodyFace, lineSp = baseFont * 1.55f)

        is MarkdownBlock.Quote -> LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(ctx).apply { setBackgroundColor(Style.hint) }, LinearLayout.LayoutParams(Style.rawDp(3), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(
                mdText(ctx, inlineStyled(block.text), baseFont, bodyFace, Style.hint, baseFont * 1.5f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Style.rawDp(10) },
            )
        }

        is MarkdownBlock.CodeFence -> mdText(
            ctx, block.code, baseFont - 3,
            if (boldBody) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE,
            lineSp = baseFont * 1.35f,
        ).apply {
            background = Style.boxPx(Style.faint, Style.hint, Style.rawDp(1).coerceAtLeast(1))
            val pad = Style.rawDp(11)
            setPadding(pad, pad, pad, pad)
        }

        is MarkdownBlock.ListItem -> LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Style.rawDp(block.indent * LIST_INDENT_DP), 0, 0, 0)
            addView(
                mdText(ctx, block.marker, baseFont, bodyFace, lineSp = baseFont * 1.5f),
                LinearLayout.LayoutParams(Style.rawDp(LIST_MARKER_DP), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(
                mdText(ctx, inlineStyled(block.text), baseFont, bodyFace, lineSp = baseFont * 1.5f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        is MarkdownBlock.Rule -> FrameLayout(ctx).apply {
            addView(View(ctx).apply { setBackgroundColor(Style.hint) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Style.rawDp(1).coerceAtLeast(1)))
        }

        is MarkdownBlock.Table -> MarkdownTableView(ctx, block, baseFont, boldBody)
    }
}

class MarkdownView(
    context: Context,
    private val baseFont: Int,
    private val boldBody: Boolean = true,
    private val serifHeads: Boolean = false,
) : LinearLayout(context) {
    private var blocks: List<MarkdownBlock> = emptyList()
    init {
        orientation = VERTICAL
    }

    fun setDocument(document: MarkdownDocument) {
        val next = document.blocks
        var same = 0
        while (same < blocks.size && same < next.size && blocks[same] == next[same]) same++
        if (same == blocks.size && same == next.size) return
        removeViews(same, childCount - same)
        for (index in same until next.size) {
            addView(markdownBlockView(context, next[index], baseFont, boldBody, serifHeads), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = Style.rawDp(10)
            })
        }
        blocks = next
    }
}

@SuppressLint("ViewConstructor")
private class MarkdownTableView(
    context: Context,
    private val table: MarkdownBlock.Table,
    private val baseFont: Int,
    private val boldBody: Boolean,
) : LinearLayout(context) {
    private var builtFor = -1

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0 && width != builtFor) {
            builtFor = width
            build(width)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun build(width: Int) {
        removeAllViews()
        val maxDp = width / resources.displayMetrics.density
        val cols = table.headers.indices.filter { c -> table.headers[c].isNotBlank() || table.rows.any { it[c].isNotBlank() } }

        val sized = cols.map { c ->
            c to (maxOf(table.headers[c].length, table.rows.maxOfOrNull { it[c].length } ?: 0) * 8).coerceIn(80, 480)
        }
        val count = if (cols.isEmpty()) 0
            else ceil(sized.sumOf { it.second } / maxDp.coerceAtLeast(1f)).toInt().coerceIn(1, cols.size)
        var start = 0
        for (g in 0 until count) {
            val end = start + cols.size / count + if (g < cols.size % count) 1 else 0
            val group = sized.subList(start, end)
            start = end
            val box = LinearLayout(context).apply {
                orientation = VERTICAL
                foreground = Style.boxPx(Style.clear, Style.ink, Style.rawDp(1).coerceAtLeast(1))
                addView(tableRow(group, group.map { table.headers[it.first] }, Style.weighted(800), Style.faint))
                val face = if (boldBody) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                table.rows.forEach { row -> addView(tableRow(group, group.map { row[it.first] }, face, null)) }
            }
            addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (g > 0) topMargin = Style.rawDp(10)
            })
        }
    }

    private fun tableRow(group: List<Pair<Int, Int>>, cells: List<String>, face: Typeface, fill: Int?): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            cells.forEachIndexed { index, cell ->
                val (column, widthDp) = group[index]
                addView(mdText(context, inlineStyled(cell), baseFont, face, lineSp = baseFont * 1.4f).apply {
                    gravity = when (table.alignments[column]) {
                        MarkdownAlignment.LEFT -> Gravity.START
                        MarkdownAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
                        MarkdownAlignment.RIGHT -> Gravity.END
                    }
                    background = Style.boxPx(fill ?: Style.clear, Style.hint, Style.rawDp(0.5f).coerceAtLeast(1))
                    setPadding(Style.rawDp(10), Style.rawDp(6), Style.rawDp(10), Style.rawDp(6))
                }, LayoutParams(Style.rawDp(widthDp), LayoutParams.MATCH_PARENT))
            }
        }
}
