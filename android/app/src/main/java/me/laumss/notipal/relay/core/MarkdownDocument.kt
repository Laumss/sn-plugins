package me.laumss.notipal.relay.core

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

    open val synthetic: Boolean get() = false

    data class Heading(val level: Int, val text: String, override val sourceRange: SourceRange) : MarkdownBlock(sourceRange)
    data class Paragraph(val text: String, override val sourceRange: SourceRange, override val synthetic: Boolean = false) : MarkdownBlock(sourceRange)
    data class Quote(val text: String, override val sourceRange: SourceRange) : MarkdownBlock(sourceRange)
    data class CodeFence(val code: String, override val sourceRange: SourceRange) : MarkdownBlock(sourceRange)
    data class ListItem(
        val text: String,
        val marker: String,
        val indent: Int,
        override val sourceRange: SourceRange,
        override val synthetic: Boolean = false,
    ) : MarkdownBlock(sourceRange)
    data class Rule(override val sourceRange: SourceRange) : MarkdownBlock(sourceRange)
    data class Math(val tex: String, override val sourceRange: SourceRange) : MarkdownBlock(sourceRange)
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

                trimmed.startsWith("$$") -> {
                    val start = i
                    val inline = trimmed.removePrefix("$$")
                    val close = inline.indexOf("$$")
                    if (close > 0) {

                        blocks += MarkdownBlock.Math(inline.substring(0, close).trim(), range(i, i))
                        val rest = inline.substring(close + 2).trim()
                        if (rest.isNotEmpty()) blocks += splitEmbeddedMath(MarkdownBlock.Paragraph(rest, range(i, i), synthetic = true))
                        i++
                    } else {
                        val tex = StringBuilder(inline)
                        i++
                        while (i < lines.size && !lines[i].content.trim().endsWith("$$")) {
                            if (tex.isNotEmpty()) tex.append('\n')
                            tex.append(lines[i].content)
                            i++
                        }
                        if (i < lines.size) {
                            if (tex.isNotEmpty()) tex.append('\n')
                            tex.append(lines[i].content.trim().removeSuffix("$$"))
                            i++
                        }
                        blocks += MarkdownBlock.Math(tex.toString().trim(), range(start, i - 1))
                    }
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
                    blocks += splitEmbeddedMath(
                        MarkdownBlock.ListItem(
                            text = text.trim(),
                            marker = if (bullet[0].isDigit()) bullet.dropLast(1) + "." else "•",
                            indent = (raw.length - raw.trimStart().length) / 2,
                            sourceRange = range(i, i),
                        )
                    )
                    i++
                }

                else -> {
                    val start = i
                    val paragraph = StringBuilder()
                    while (i < lines.size && lines[i].content.isNotBlank()) {
                        if (i > start && isStandaloneBlockStart(lines, i)) break
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(lines[i].content.trim())
                        i++
                    }
                    blocks += splitEmbeddedMath(
                        MarkdownBlock.Paragraph(
                            text = paragraph.toString().trim(),
                            sourceRange = range(start, i - 1),
                        )
                    )
                }
            }
        }

        return MarkdownDocument(source, blocks)
    }

    fun isInlineTex(content: String): Boolean =
        '\n' !in content && content.any { it == '\\' || it == '^' || it == '_' }

    private val EMBEDDED_MATH = Regex("\\$\\$(.+?)\\$\\$|\\$([^$\\n]+?)\\$", RegexOption.DOT_MATCHES_ALL)

    private fun splitEmbeddedMath(block: MarkdownBlock): List<MarkdownBlock> {
        val text = when (block) {
            is MarkdownBlock.Paragraph -> block.text
            is MarkdownBlock.ListItem -> block.text
            else -> return listOf(block)
        }
        if (!text.contains('$')) return listOf(block)

        val out = mutableListOf<MarkdownBlock>()
        var cursor = 0

        fun addText(segment: String) {
            val s = segment.trim()

            if (out.isEmpty() && block is MarkdownBlock.ListItem) out += block.copy(text = s, synthetic = true)
            else if (s.isNotEmpty()) out += MarkdownBlock.Paragraph(s, block.sourceRange, synthetic = true)
        }

        for (m in EMBEDDED_MATH.findAll(text)) {
            val (display, inline) = m.destructured
            val tex = display.ifEmpty { inline }.trim()
            if (tex.isEmpty() || (display.isEmpty() && !isInlineTex(inline))) continue
            addText(text.substring(cursor, m.range.first))
            out += MarkdownBlock.Math(tex, block.sourceRange)
            cursor = m.range.last + 1
        }
        if (cursor == 0) return listOf(block)
        addText(text.substring(cursor))
        return out
    }

    private fun isStandaloneBlockStart(lines: List<SourceLine>, index: Int): Boolean {
        val trimmed = lines[index].content.trim()
        return trimmed.startsWith("```") ||
            trimmed.startsWith("$$") ||
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
