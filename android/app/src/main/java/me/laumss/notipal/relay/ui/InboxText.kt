package me.laumss.notipal.relay.ui

import me.laumss.notipal.NativeLocale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatInboxTime(timestamp: Long): String = SimpleDateFormat(
    if (System.currentTimeMillis() - timestamp < 20 * 3_600_000L) "HH:mm" else "MM-dd HH:mm",
    Locale.getDefault(),
).format(Date(timestamp))

internal fun sourceLabel(source: String) = when (source) {
    "llm" -> NativeLocale.t("relay_source_ai")
    "manual" -> NativeLocale.t("relay_source_manual")
    else -> NativeLocale.t("relay_source_dictation")
}

private val INLINE_MARKS = listOf(
    Regex("!\\[([^\\]]*)]\\([^)]*\\)") to "$1",
    Regex("\\[([^\\]]+)]\\([^)]*\\)") to "$1",
    Regex("\\*\\*(.+?)\\*\\*") to "$1",
    Regex("__(.+?)__") to "$1",
    Regex("~~(.+?)~~") to "$1",
    Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*") to "$1",
    Regex("`([^`]+)`") to "$1",
    Regex("\\$\\$(.+?)\\$\\$") to "$1",
    Regex("\\$([^$\\n]+?)\\$") to "$1",
)

internal fun plainInline(text: String): String =
    INLINE_MARKS.fold(text) { acc, (regex, replacement) -> acc.replace(regex, replacement) }
        .lines().joinToString(" ") { it.trim() }.trim()

internal fun inboxDay(time: Long): Int {
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 24 * 3_600_000L
    return when {
        time >= start -> 0
        time >= start - day -> 1
        time >= start - 6 * day -> 2
        else -> 3
    }
}

internal fun inboxDayLabel(day: Int): String = NativeLocale.t(when (day) {
    0 -> "relay_day_today"
    1 -> "relay_day_yesterday"
    2 -> "relay_day_week"
    else -> "relay_day_earlier"
})

internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
