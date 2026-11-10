package me.laumss.notipal.relay.core

import me.laumss.notipal.relay.ui.ChatTurn

object RelayTurns {

    const val DEFAULT_MARKERS = "## H:\n## A:\n# H:\n# A:"

    private val USER_NAMES = setOf("h", "human", "user", "用户", "我")
    private val RULE = Regex("^([-*_])\\1{2,}$")

    class Markers(template: String) {
        val list: List<String> = template.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().sortedByDescending(String::length).toList()
    }

    fun isUserMarker(marker: String): Boolean =
        marker.filter(Char::isLetter).lowercase() in USER_NAMES

    fun split(
        messageId: String,
        text: String,
        markers: Markers,
        lead: String = "",
        startsUser: Boolean = isUserMarker(lead),
    ): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        var fromUser = startsUser
        var opened = false
        val buffer = mutableListOf<String>()
        fun flush() {
            val lines = buffer.toMutableList()
            buffer.clear()
            while (lines.isNotEmpty() && (lines.first().isBlank() || RULE.matches(lines.first().trim()))) lines.removeAt(0)
            while (lines.isNotEmpty() && (lines.last().isBlank() || RULE.matches(lines.last().trim()))) lines.removeAt(lines.lastIndex)
            if (lines.isNotEmpty()) {
                turns += ChatTurn("$messageId#${turns.size}", fromUser, lines.joinToString("\n").trim(), continues = !opened)
            }
        }
        for (raw in text.lines()) {
            val line = raw.removeSuffix("\r")
            val start = line.indexOfFirst { !it.isWhitespace() }
            val marker = if (start < 0) null else markers.list.firstOrNull { line.startsWith(it, start) }
            if (marker == null) {
                buffer += line
                continue
            }
            flush()
            opened = true
            fromUser = isUserMarker(marker)
            buffer += line.substring(start + marker.length).trimStart()
        }
        flush()
        return turns
    }

    fun endsInUserTurn(text: String, markers: Markers, lead: String = ""): Boolean =
        split("", text, markers, lead).lastOrNull()?.fromUser ?: isUserMarker(lead)

    fun merge(turns: List<ChatTurn>): List<ChatTurn> {
        val out = mutableListOf<ChatTurn>()
        for (turn in turns) {
            val last = out.lastOrNull()
            if (last != null && last.fromUser && turn.fromUser && turn.continues) {
                out[out.lastIndex] = last.copy(text = last.text + "\n" + turn.text)
            } else {
                out += turn
            }
        }
        return out
    }
}
