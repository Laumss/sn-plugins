package me.laumss.notipal.relay.ui

import me.laumss.notipal.relay.core.MarkdownBlock
import me.laumss.notipal.relay.core.MarkdownDocument
import me.laumss.notipal.relay.core.MarkdownDocumentParser
import me.laumss.notipal.relay.core.SourceRange
import me.laumss.notipal.relay.core.VoiceTags

data class SelectionTurn(val firstBlock: Int, val lastBlock: Int, val fromUser: Boolean)

class SelectionDocument(val document: MarkdownDocument, val turns: List<SelectionTurn>) {

    fun blockOfAnswer(text: String): Int? {
        val index = sourceTurns.indexOfLast { !it.fromUser && it.text == text }
        return if (index < 0) null else turnOfSource[index]?.firstBlock
    }

    private var sourceTurns: List<ChatTurn> = emptyList()
    private var turnOfSource: Map<Int, SelectionTurn> = emptyMap()

    companion object {
        fun of(chat: List<ChatTurn>): SelectionDocument {
            val source = StringBuilder()
            val blocks = ArrayList<MarkdownBlock>()
            val turns = ArrayList<SelectionTurn>()
            val ofSource = HashMap<Int, SelectionTurn>()
            chat.forEachIndexed { index, turn ->
                val first = blocks.size
                if (turn.fromUser) {
                    VoiceTags.split(turn.text).flatMap { it.text.lines() }.filter { it.isNotBlank() }.forEach { line ->
                        val start = source.length
                        source.append(line)
                        blocks += MarkdownBlock.Paragraph(line, SourceRange(start, source.length))
                        source.append('\n')
                    }
                } else {
                    val parsed = MarkdownDocumentParser.parse(turn.text)
                    val offset = source.length
                    parsed.blocks.forEach { blocks += moved(it, offset) }
                    source.append(parsed.source).append('\n')
                }
                if (blocks.size > first) {
                    val selectionTurn = SelectionTurn(first, blocks.lastIndex, turn.fromUser)
                    turns += selectionTurn
                    ofSource[index] = selectionTurn
                }
            }
            return SelectionDocument(MarkdownDocument(source.toString(), blocks), turns).also {
                it.sourceTurns = chat
                it.turnOfSource = ofSource
            }
        }

        private fun moved(block: MarkdownBlock, by: Int): MarkdownBlock {
            val range = SourceRange(block.sourceRange.startOffset + by, block.sourceRange.endOffsetExclusive + by)
            return when (block) {
                is MarkdownBlock.Heading -> block.copy(sourceRange = range)
                is MarkdownBlock.Paragraph -> block.copy(sourceRange = range)
                is MarkdownBlock.Quote -> block.copy(sourceRange = range)
                is MarkdownBlock.CodeFence -> block.copy(sourceRange = range)
                is MarkdownBlock.ListItem -> block.copy(sourceRange = range)
                is MarkdownBlock.Rule -> block.copy(sourceRange = range)
                is MarkdownBlock.Math -> block.copy(sourceRange = range)
                is MarkdownBlock.Table -> block.copy(sourceRange = range)
            }
        }
    }
}
