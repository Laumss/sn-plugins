package me.laumss.notipal.relay.core

internal object VoiceTags {
    sealed interface Piece {
        val text: String

        data class Plain(override val text: String) : Piece
        data class Voice(override val text: String) : Piece
    }

    private val TAG = Regex("<voice>([\\s\\S]*?)</voice>")

    fun split(text: String): List<Piece> {
        if (!text.contains("<voice>")) return listOf(Piece.Plain(text))
        val out = ArrayList<Piece>()
        fun plain(part: String) = part.trim().takeIf { it.isNotEmpty() }?.let { out += Piece.Plain(it) }
        var at = 0
        for (match in TAG.findAll(text)) {
            plain(text.substring(at, match.range.first))
            match.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { out += Piece.Voice(it) }
            at = match.range.last + 1
        }
        plain(text.substring(at))
        return out
    }

    fun inline(text: String, label: String): String =
        if (!text.contains("<voice>")) text
        else TAG.replace(text) { "$label${it.groupValues[1].trim()} " }.trim()
}
