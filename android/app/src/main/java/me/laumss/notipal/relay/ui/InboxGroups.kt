package me.laumss.notipal.relay.ui

import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.core.MarkdownBlock
import me.laumss.notipal.relay.core.MarkdownDocumentParser
import me.laumss.notipal.relay.core.VoiceTags
import me.laumss.notipal.relay.model.RelayMessage

private const val EXCERPT_CHARS = 120

internal class InboxGroup(val key: String, val replies: List<RelayMessage>) {
    val updated = replies.maxOf { it.updatedAt }
    val starred = replies.any { it.starred }
    val generating = replies.any { !it.closed }

    val unseen = replies.any { !it.seen }
    val ids: Set<String> = replies.mapTo(LinkedHashSet()) { it.id }
}

internal fun inboxGroups(messages: List<RelayMessage>): List<InboxGroup> =
    messages.groupBy { it.conversationId.ifEmpty { "card:" + it.id } }
        .map { (key, replies) -> InboxGroup(key, replies.sortedBy { it.createdAt }) }
        .sortedByDescending { it.updated }

internal fun summaryLines(text: String): List<String> =
    MarkdownDocumentParser.parse(text).blocks.mapNotNull { block ->
        when (block) {
            is MarkdownBlock.Heading -> plainInline(block.text)
            is MarkdownBlock.Paragraph -> plainInline(block.text)
            is MarkdownBlock.Quote -> plainInline(block.text)
            is MarkdownBlock.ListItem -> "${block.marker} ${plainInline(block.text)}"
            is MarkdownBlock.CodeFence -> block.code.trim()
            is MarkdownBlock.Math -> block.tex.trim()
            is MarkdownBlock.Table -> block.headers.joinToString(" · ") { plainInline(it) }
            is MarkdownBlock.Rule -> null
        }?.takeIf { it.isNotBlank() }
    }

internal fun excerptOf(lines: List<String>): String = lines.joinToString(" ").take(EXCERPT_CHARS)

internal fun rowTitle(question: String?, firstLine: String): String =
    question?.let { VoiceTags.inline(it, NativeLocale.t("relay_voice_inline")) }?.trim()?.takeIf { it.isNotEmpty() }
        ?: firstLine.ifEmpty { NativeLocale.t("relay_msg_unavailable") }
