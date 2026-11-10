package me.laumss.notipal.relay.core

import org.json.JSONArray
import org.json.JSONObject

data class SelectionRowInfo(
    val blockIndex: Int,
    val charStart: Int = 0,
    val charEnd: Int = 0,
    val atomic: Boolean = true,
)

object FormulaBlocks {

    data class Payload(val plainText: String, val blocksJson: String, val hasMath: Boolean)

    private class Builder {
        val text = StringBuilder()
        private val blocks = JSONArray()
        private val plain = StringBuilder()
        private var hasMath = false

        fun flush() {
            val t = text.toString().trim('\r', '\n')
            text.setLength(0)
            if (t.isNotBlank()) put("text", t, t)
        }

        fun math(tex: String) {
            flush()
            if (tex.isBlank()) return
            hasMath = true
            put("math", tex, "$$$tex$$")
        }

        private fun put(type: String, content: String, plainForm: String) {
            blocks.put(JSONObject().put("type", type).put("content", content))
            if (plain.isNotEmpty()) plain.append("\n\n")
            plain.append(plainForm)
        }

        fun build(): Payload {
            flush()
            return Payload(plain.toString(), blocks.toString(), hasMath)
        }
    }

    fun selectableText(block: MarkdownBlock): String? = when (block) {
        is MarkdownBlock.Paragraph -> block.text
        is MarkdownBlock.Quote -> block.text
        is MarkdownBlock.ListItem -> block.text
        is MarkdownBlock.Heading -> block.text
        is MarkdownBlock.CodeFence -> block.code
        else -> null
    }

    private fun MarkdownDocument.slice(from: MarkdownBlock, to: MarkdownBlock = from) =
        source.substring(from.sourceRange.startOffset, to.sourceRange.endOffsetExclusive)

    private fun blockText(document: MarkdownDocument, block: MarkdownBlock): String = when (block) {
        is MarkdownBlock.ListItem -> "${block.marker} ${block.text}".trimEnd()
        is MarkdownBlock.Table -> document.slice(block).trim('\r', '\n')
        else -> selectableText(block).orEmpty()
    }

    fun payload(document: MarkdownDocument, indices: List<Int>): Payload {
        val out = Builder()
        val run = mutableListOf<MarkdownBlock>()
        fun flushRun() {
            if (run.isEmpty()) return
            out.text.append(
                if (run.any { it.synthetic }) run.joinToString("\n") { blockText(document, it) }
                else document.slice(run.first(), run.last())
            )
            out.flush()
            run.clear()
        }

        var prev = -2
        for (i in indices.sorted()) {
            val block = document.blocks.getOrNull(i) ?: continue
            if (i != prev + 1) flushRun()
            prev = i
            when (block) {
                is MarkdownBlock.Math -> { flushRun(); out.math(block.tex) }
                is MarkdownBlock.Rule -> flushRun()
                else -> run += block
            }
        }
        flushRun()
        return out.build()
    }

    fun payload(document: MarkdownDocument, rows: List<SelectionRowInfo>, selected: List<Int>): Payload {
        val out = Builder()
        var prevIndex = -2
        for (ri in selected) {
            val row = rows.getOrNull(ri) ?: continue
            val block = document.blocks.getOrNull(row.blockIndex) ?: continue
            when (block) {
                is MarkdownBlock.Math -> out.math(block.tex)
                is MarkdownBlock.Rule -> out.flush()
                else -> {
                    val piece = if (row.atomic) blockText(document, block) else lineText(block, row)
                    if (!row.atomic || piece.isNotBlank()) {
                        val continues = ri == prevIndex + 1 && rows[prevIndex].blockIndex == row.blockIndex
                        if (out.text.isNotEmpty() && !continues) out.text.append('\n')
                        out.text.append(piece)
                    }
                }
            }
            prevIndex = ri
        }
        return out.build()
    }

    private fun lineText(block: MarkdownBlock, row: SelectionRowInfo): String {
        val full = selectableText(block).orEmpty()
        val line = full.substring(row.charStart.coerceAtMost(full.length), row.charEnd.coerceAtMost(full.length))
        return if (block is MarkdownBlock.ListItem && row.charStart == 0) "${block.marker} $line" else line
    }

    private val RECOGNIZED_TITLES = listOf("识别", "recognized", "recognition")
    private val ANSWER_TITLES = listOf("解答", "answer", "solution")

    private fun headingMatches(block: MarkdownBlock, titles: List<String>): Boolean {
        if (block !is MarkdownBlock.Heading) return false
        val t = block.text.trim().lowercase()
        return titles.any { t == it || t.startsWith(it) }
    }

    fun recognizedIndices(document: MarkdownDocument): List<Int> {
        val blocks = document.blocks
        val start = blocks.indexOfFirst { headingMatches(it, RECOGNIZED_TITLES) }
        if (start < 0) return emptyList()
        val end = blocks.drop(start + 1).indexOfFirst { headingMatches(it, ANSWER_TITLES) }
            .let { if (it < 0) blocks.size else start + 1 + it }
        return ((start + 1) until end).toList()
    }
}
