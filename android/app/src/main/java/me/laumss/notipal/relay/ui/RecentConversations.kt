package me.laumss.notipal.relay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.model.RelayMessage
import me.laumss.notipal.ui_common.snux.LineIcon
import me.laumss.notipal.ui_common.snux.Snux

private const val ROW_U = 78f

@SuppressLint("ViewConstructor")
internal class RecentConversations(
    context: Context,

    private val questionOf: (String) -> String?,

    private val onOpen: (String) -> Unit,
    onInbox: () -> Unit,
    emptyHint: String,
) : LinearLayout(context) {

    private fun u(v: Number) = Snux.u(context, v)

    private var messages: List<RelayMessage> = emptyList()
    private var groups: List<InboxGroup> = emptyList()

    private var shownGroups: List<InboxGroup>? = null
    private var shownFits = -1
    private val parsed = HashMap<String, Pair<Long, List<String>>>()

    private val empty = emptyLine(context, emptyHint).apply { visibility = GONE }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val linkText = Snux.text(context, NativeLocale.t("relay_inbox_link"), Snux.F_META, INK, bold = true)
    private var textScale = 1f
    private val recentLabel = metaLabel(context, NativeLocale.t("relay_recent"))

    private val body = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) syncRows(MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    init {
        orientation = VERTICAL
        val inset = u(Snux.PANE_INSET)
        val link = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(16), u(8), 0, u(8))
            addView(linkText)
            addView(LineIcon(context, LineIcon.Glyph.CHEVRON, 100), LayoutParams(u(Snux.F_META), u(Snux.F_META)).apply { leftMargin = u(4) })
            setOnClickListener { onInbox() }
        }

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(inset, u(10), inset, 0)
            addView(recentLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(link)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.setPadding(inset, 0, inset, 0)
        body.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        body.addView(rows, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setSmall(small: Boolean) {
        val scale = if (small) MINI_TEXT_SCALE else 1f
        if (scale == textScale) return
        textScale = scale
        linkText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(Snux.F_META * scale).toFloat())
        shownGroups = null
        body.requestLayout()
    }

    fun setNewChat(newChat: Boolean) {
        recentLabel.visibility = if (newChat) INVISIBLE else VISIBLE
    }

    fun setMessages(list: List<RelayMessage>) {
        if (list == messages && shownGroups != null) return
        messages = list
        groups = inboxGroups(list)
        shownGroups = null
        parsed.keys.retainAll(list.mapTo(HashSet()) { it.id })
        body.requestLayout()
    }

    private fun syncRows(height: Int) {
        val rowHeight = u(ROW_U)

        val rule = Snux.px(context, 2).coerceAtLeast(1)
        val fits = ((height - rule) / rowHeight).coerceIn(1, groups.size.coerceAtLeast(1))
        if (shownGroups === groups && fits == shownFits) return
        shownGroups = groups
        shownFits = fits
        empty.visibility = if (groups.isEmpty()) VISIBLE else GONE
        rows.removeAllViews()
        if (groups.isEmpty()) return
        rows.addView(Snux.rule(context))
        groups.take(fits).forEachIndexed { index, group ->
            rows.addView(row(group, dashed = index < fits - 1), LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        }
    }

    private fun lines(reply: RelayMessage): List<String> =
        parsed[reply.id]?.takeIf { it.first == reply.updatedAt }?.second
            ?: summaryLines(reply.text).also { parsed[reply.id] = reply.updatedAt to it }

    private fun row(group: InboxGroup, dashed: Boolean): FrameLayout {
        val first = group.replies.first()
        val latest = group.replies.maxByOrNull { it.updatedAt } ?: first
        val question = questionOf(first.id)
        val firstLines = lines(first)
        val title = rowTitle(question, firstLines.firstOrNull() ?: first.text.trim())
        val excerpt = if (latest.id == first.id) excerptOf(if (question == null) firstLines.drop(1) else firstLines)
            else excerptOf(lines(latest))
        val line = if (group.generating) NativeLocale.t("relay_chat_generating") else excerpt
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(Snux.oneLine(Snux.text(context, title, Snux.F_BODY * textScale, INK, bold = group.unseen)))
            if (line.isNotEmpty()) addView(
                Snux.oneLine(Snux.text(context, line, Snux.F_META * textScale, if (group.generating) INK else Snux.SOFT)),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = u(4) },
            )
        }
        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(Snux.oneLine(Snux.text(context, formatInboxTime(group.updated), Snux.F_META * textScale, Snux.SUBTLE)),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = u(16) })
        }
        return FrameLayout(context).apply {
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            if (dashed) addView(Snux.dashes(context, insetPx = 0), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Snux.px(context, 2).coerceAtLeast(1), Gravity.BOTTOM,
            ))
            setOnClickListener { onOpen(first.id) }
        }
    }
}
