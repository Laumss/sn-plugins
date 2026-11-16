package me.laumss.notipal.relay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Strings
import me.laumss.notipal.ui_common.EmptyState
import me.laumss.notipal.ui_common.LineIcon
import me.laumss.notipal.ui_common.ScrollHost
import me.laumss.notipal.ui_common.SearchBox
import me.laumss.notipal.ui_common.SideNav
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

enum class RelayPage { INBOX, DETAIL, COMPOSE, SETTINGS, TRANSCRIPT, SELECT }

interface RelayHost {

    fun open(page: RelayPage)

    fun back()

    val small: Boolean

    fun flashStatus(text: String, ms: Long)

    fun reveal(why: String)

    fun openCard(id: String, fresh: Boolean = false)

    fun openHere(id: String)

    fun question(replyId: String): String?

    fun attachmentsChanged()

    val voice: VoiceStage

    val collapsed: Boolean

    fun detailChanged()

    fun awaitReply()

    fun close()

    fun growForImage()

    fun backToSmall()

    fun pageAction(actionId: String)

    fun attachPages()

    fun showInsertOnPage()
}

object InboxStore : RelayInbox {
    private const val TAG = "RelayInbox"
    private const val FILE_NAME = "relay_inbox.json"
    private const val MAX_MESSAGES = 200
    private const val SAVE_DEBOUNCE_MS = 800L

    private val messages = mutableListOf<RelayMessage>()
    private val handler = Handler(Looper.getMainLooper())

    private val saveRunnable = Runnable {
        val copy = synchronized(messages) { messages.map { it.copy() } }
        saver.execute { write(copy) }
    }
    private val saver = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RelayInboxSave").apply { isDaemon = true }
    }

    private var loaded = false

    fun install() {
        RelayHooks.inbox = this
    }

    override fun load() {
        val again = synchronized(messages) {
            if (loaded) messages.forEach { it.closed = true }
            loaded
        }
        if (again) return
        val f = File(App.context.filesDir, FILE_NAME)
        if (!f.exists()) {
            synchronized(messages) { loaded = true }
            return
        }
        try {
            val list = RelayMessage.listFromJson(JSONArray(f.readText()))
            synchronized(messages) {
                messages.clear()
                messages.addAll(list.onEach { it.closed = true })
                loaded = true
            }
            Log.i(TAG, "loaded ${list.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
        }
    }

    override fun snapshot(): List<RelayMessage> =
        synchronized(messages) { messages.map { it.copy() } }

    override fun get(id: String): RelayMessage? =
        synchronized(messages) { messages.firstOrNull { it.id == id }?.copy() }

    override fun contains(id: String): Boolean =
        synchronized(messages) { messages.any { it.id == id } }

    override fun upsert(
        id: String, conversationId: String, text: String, kind: String, closed: Boolean,
        lead: String, source: String, backend: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        val created = synchronized(messages) {
            val existing = messages.firstOrNull { it.id == id }
            if (existing != null) {

                val kindChanged = kind.isNotEmpty() && existing.kind != kind
                if (existing.text == text && existing.closed == closed && existing.lead == lead && !kindChanged) return false

                if (!kindChanged && text.length < existing.text.length && existing.text.startsWith(text)) {

                    if (!closed || existing.closed) return false
                    existing.closed = true
                    existing.updatedAt = now
                    return@synchronized false
                }
                if (!kindChanged && existing.closed && !closed && text == existing.text) return false
                if (kindChanged) existing.kind = kind
                existing.text = text
                existing.closed = closed
                existing.lead = lead
                existing.updatedAt = now
                false
            } else {
                if (text.isBlank()) return false
                messages.add(0, RelayMessage(
                    id = id, text = text, source = source, createdAt = now, updatedAt = now,
                    closed = closed, kind = kind, conversationId = conversationId, lead = lead, seen = false,
                    backend = backend,
                ))

                while (messages.size > MAX_MESSAGES) {
                    val oldest = messages.indexOfLast { !it.starred }
                    if (oldest < 0) break
                    messages.removeAt(oldest)
                }
                true
            }
        }
        notifyChanged()
        return created
    }

    override fun markInserted(id: String) {
        var changed = false
        synchronized(messages) {
            messages.firstOrNull { it.id == id }?.let {
                if (!it.inserted) { it.inserted = true; changed = true }
            }
        }
        if (changed) notifyChanged()
    }

    override fun setStarred(ids: Collection<String>, starred: Boolean) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        var changed = false
        synchronized(messages) {
            messages.filter { it.id in set && it.starred != starred }.forEach { it.starred = starred; changed = true }
        }
        if (changed) notifyChanged()
    }

    override fun markSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        var changed = false
        synchronized(messages) {
            messages.filter { it.id in set && !it.seen }.forEach { it.seen = true; changed = true }
        }
        if (changed) notifyChanged()
    }

    override fun deleteMany(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        val changed = synchronized(messages) { messages.removeAll { it.id in set } }
        if (changed) notifyChanged()
    }

    private fun notifyChanged() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
        handler.post { Conversations.inboxChanged() }
    }

    private fun write(list: List<RelayMessage>) {
        try {
            File(App.context.filesDir, FILE_NAME).writeText(RelayMessage.listToJson(list).toString())
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
        }
    }

    override fun flush() {
        handler.removeCallbacks(saveRunnable)

        saveRunnable.run()
    }
}

fun formatInboxTime(timestamp: Long): String = SimpleDateFormat(
    if (System.currentTimeMillis() - timestamp < 20 * 3_600_000L) "HH:mm" else "MM-dd HH:mm",
    Locale.getDefault(),
).format(Date(timestamp))

internal fun sourceLabel(source: String) = when (source) {
    "llm" -> Strings.get("relay_source_ai")
    "manual" -> Strings.get("relay_source_manual")
    else -> Strings.get("relay_source_dictation")
}

private val INLINE_MARKS = listOf(
    Regex("!\\[([^\\]]*)]\\([^)]*\\)") to "$1",
    Regex("\\[([^\\]]+)]\\([^)]*\\)") to "$1",
    Regex("\\*\\*(.+?)\\*\\*") to "$1",
    Regex("__(.+?)__") to "$1",
    Regex("~~(.+?)~~") to "$1",
    Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*") to "$1",
    Regex("`([^`]+)`") to "$1",
)

internal fun plainLine(text: String): String =
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

internal fun inboxDayLabel(day: Int): String = Strings.get(when (day) {
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
            is MarkdownBlock.Heading -> plainLine(block.text)
            is MarkdownBlock.Paragraph -> plainLine(block.text)
            is MarkdownBlock.Quote -> plainLine(block.text)
            is MarkdownBlock.ListItem -> "${block.marker} ${plainLine(block.text)}"
            is MarkdownBlock.CodeFence -> block.code.trim()
            is MarkdownBlock.Table -> block.headers.joinToString(" · ") { plainLine(it) }
            is MarkdownBlock.Rule -> null
        }?.takeIf { it.isNotBlank() }
    }

internal fun excerptOf(lines: List<String>): String = lines.joinToString(" ").take(EXCERPT_CHARS)

internal fun rowTitle(question: String?, firstLine: String): String =
    question?.let { VoiceTags.inline(it, Strings.get("relay_voice_inline")) }?.trim()?.takeIf { it.isNotEmpty() }
        ?: firstLine.ifEmpty { Strings.get("relay_msg_unavailable") }

internal fun conversationCards(message: RelayMessage, all: List<RelayMessage>): List<RelayMessage> =
    if (message.conversationId.isEmpty()) listOf(message)
    else all.filter { it.conversationId == message.conversationId }

internal class CheckMark(context: Context) : View(context) {
    var checked = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tick = Path()

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val k = size / 30f
        val stroke = 2f * k
        paint.style = Paint.Style.FILL
        paint.color = if (checked) Style.ink else Style.paper
        canvas.drawRect(k, k, size - k, size - k, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = Style.ink
        canvas.drawRect(k, k, size - k, size - k, paint)
        if (!checked) return
        tick.reset()
        tick.moveTo(7f * k, 15.5f * k)
        tick.lineTo(12.5f * k, 21f * k)
        tick.lineTo(23f * k, 9f * k)
        paint.color = Style.paper
        paint.strokeWidth = 2.6f * k
        canvas.drawPath(tick, paint)
    }
}

@SuppressLint("ViewConstructor")
class InboxPane(
    private val host: RelayHost,

    private val settings: View,
) : LinearLayout(App.context) {

    private enum class Filter(val id: String) { CHAT("chat"), AUDIO("audio"), STARRED("starred"), SETTINGS("settings") }

    private companion object {

        const val AUDIO_KEY = "audio:"
        const val SEARCH_DELAY_MS = 300L

        const val RENDER_DELAY_MS = 500L
    }

    val view: View get() = this

    private fun u(v: Number) = Style.u(v)
    private fun t(key: String) = Strings.get(key)

    private var messages: List<RelayMessage> = emptyList()
    private var filter = Filter.CHAT
    private var editing = false
    private var query = ""
    private val picked = LinkedHashSet<String>()

    private val pickedAudio = LinkedHashSet<String>()

    private var shownTranscripts: List<Transcripts.Transcript> = emptyList()

    private var backToStarred = false

    private val checks = HashMap<String, CheckMark>()

    private var stale = false
    private var renderPending = false

    private var navKey = ""

    private val summaries = HashMap<String, Triple<Long, String, String>>()

    private val nav = SideNav(context, ::onNav)

    private val pane = LinearLayout(context).apply { orientation = VERTICAL }
    private val transcripts = TranscriptPane(host).apply {
        onChanged = {
            renderNav()
            if (filter == Filter.STARRED && starredTranscripts() != shownTranscripts) renderSoon()
        }
        onPicksChanged = { renderActions() }
        onListDrawn = { rows -> if (audioShown) updateHasRows(rows) }
        onDetailClosed = {
            if (backToStarred) {
                backToStarred = false
                onNav(Filter.STARRED.id)
            }
        }
        visibility = GONE
    }
    val search: SearchBox = SearchBox(context, onClear = { setQuery("") }, onGo = { hideKeyboard(); setQuery(searchText()) },
        goLabel = t("relay_search_go"), clearLabel = t("picker_clear"))

    private val scroll = ScrollHost(context).apply { follow = true }
    private val empty = EmptyState(context).apply { visibility = GONE }

    var onPicks: ((Int, Boolean) -> Unit)? = null

    val audioShown: Boolean get() = filter == Filter.AUDIO

    var hasRows = false
        private set
    var onRowsChanged: (() -> Unit)? = null
    private val runSearch = Runnable { setQuery(searchText()) }
    private val renderLater = Runnable {
        renderPending = false
        render(keepScroll = true)
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Style.paper)
        addView(nav, LayoutParams(u(Style.SIDEBAR), LayoutParams.MATCH_PARENT))

        addView(View(context).apply { setBackgroundColor(Style.ink) }, LayoutParams(Style.inkPx(2).coerceAtLeast(1), LayoutParams.MATCH_PARENT))
        search.input.hint = t("relay_search_hint")
        search.input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            hideKeyboard()
            setQuery(searchText())
            true
        }
        search.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                removeCallbacks(runSearch)
                postDelayed(runSearch, SEARCH_DELAY_MS)
            }
        })
        pane.addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = u(Style.PANE_INSET); rightMargin = u(Style.PANE_INSET); topMargin = u(24)
        })
        scroll.content.setPadding(0, 0, maxOf(u(Style.PANE_INSET), scroll.followLaneWidthPx), u(24))

        pane.addView(FrameLayout(context).apply {

            setPadding(u(Style.PANE_INSET), 0, 0, 0)
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                .apply { rightMargin = u(Style.PANE_INSET) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(pane, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(transcripts, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        settings.visibility = GONE
        addView(settings, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        render(keepScroll = false)
    }

    fun setMessages(list: List<RelayMessage>, now: Boolean = false) {
        messages = list
        picked.retainAll(groups().mapTo(HashSet()) { it.key })
        summaries.keys.retainAll(list.mapTo(HashSet()) { it.id })
        if (now) {
            render(keepScroll = false)
            return
        }
        renderSoon()
    }

    private fun renderSoon() {
        if (visibility != VISIBLE) {
            stale = true
            return
        }
        if (renderPending) return
        renderPending = true
        postDelayed(renderLater, RENDER_DELAY_MS)
    }

    fun refresh() {
        if (stale) render(keepScroll = true)
        if (audioShown) transcripts.refreshIfStale()
    }

    fun back(): Boolean = audioShown && transcripts.back()

    fun showTranscript(id: String?) {
        backToStarred = false
        onNav(Filter.AUDIO.id)
        transcripts.open(id)
    }

    fun showSettings() {
        backToStarred = false
        onNav(Filter.SETTINGS.id)
    }

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        picked.clear()
        pickedAudio.clear()
        transcripts.setEditing(value)
        if (value) hideKeyboard()
        render(keepScroll = true)
    }

    fun hideKeyboard(): Boolean {
        val input = search.input
        if (!input.isFocused) return false
        input.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        (parent as? View)?.requestFocus()
        return true
    }

    private fun searchText(): String = search.input.text?.toString()?.trim().orEmpty()

    private fun setQuery(value: String) {
        removeCallbacks(runSearch)
        if (value.isEmpty() && searchText().isNotEmpty()) search.input.setText("")
        if (value == query) return
        query = value

        picked.retainAll(shown(groups()).mapTo(HashSet()) { it.key })
        render(keepScroll = false)
    }

    private fun onNav(id: String) {
        val next = Filter.values().firstOrNull { it.id == id } ?: return
        if (next == filter) return
        backToStarred = false
        filter = next
        picked.clear()
        pickedAudio.clear()
        transcripts.clearPicks()
        render(keepScroll = false)
    }

    private fun groups(): List<InboxGroup> = inboxGroups(messages)

    private fun matches(group: InboxGroup): Boolean {
        if (query.isEmpty()) return true
        return group.replies.any { reply ->
            reply.text.contains(query, ignoreCase = true) ||
                host.question(reply.id)?.contains(query, ignoreCase = true) == true
        }
    }

    private fun shown(all: List<InboxGroup>): List<InboxGroup> = when (filter) {
        Filter.AUDIO, Filter.SETTINGS -> emptyList()
        Filter.STARRED -> all.filter { it.starred }
        else -> all
    }.filter(::matches)

    private fun starredTranscripts(): List<Transcripts.Transcript> =
        if (filter != Filter.STARRED) emptyList()
        else Transcripts.transcripts().filter { item ->
            Transcripts.isStarred(item.id) && (query.isEmpty() || item.title.contains(query, ignoreCase = true) || item.text.contains(query, ignoreCase = true))
        }

    private fun render(keepScroll: Boolean) {
        stale = false
        if (renderPending) {
            renderPending = false
            removeCallbacks(renderLater)
        }
        val all = groups()
        renderNav(all)
        val audio = audioShown
        val settingsShown = filter == Filter.SETTINGS
        pane.visibility = if (audio || settingsShown) GONE else VISIBLE
        transcripts.visibility = if (audio) VISIBLE else GONE
        settings.visibility = if (settingsShown) VISIBLE else GONE
        if (audio || settingsShown) hideKeyboard()
        if (audio) transcripts.refreshIfStale()
        val offset = scroll.scrollOffset
        val content = scroll.content
        content.removeAllViews()
        checks.clear()
        val rows = shown(all)
        val audioRows = starredTranscripts()
        shownTranscripts = audioRows
        pickedAudio.retainAll(audioRows.filterNot { it.live }.mapTo(HashSet()) { it.id })
        val entries = ArrayList<Pair<Long, (Boolean) -> View>>()
        rows.forEach { group -> entries.add(group.updated to { last: Boolean -> row(group, last) }) }
        audioRows.forEach { item -> entries.add(item.createdAt to { last: Boolean -> transcriptRow(item, last) }) }

        updateHasRows(if (audio) transcripts.hasRows else !settingsShown && entries.isNotEmpty())
        empty.visibility = if (entries.isEmpty()) VISIBLE else GONE
        if (entries.isEmpty()) {
            showEmpty()
        } else {
            entries.groupBy { inboxDay(it.first) }.toSortedMap().forEach { (day, inDay) ->
                content.addView(Widgets.sectionLabel(context, inboxDayLabel(day), topU = 22))
                content.addView(Widgets.rule(context))
                inDay.sortedByDescending { it.first }.forEachIndexed { i, entry -> content.addView(entry.second(i == inDay.lastIndex)) }
            }
        }
        renderActions()
        scroll.scrollToOffset(if (keepScroll) offset else 0)
    }

    private fun updateHasRows(value: Boolean) {
        if (hasRows == value) return
        hasRows = value
        onRowsChanged?.invoke()
    }

    private fun renderNav(all: List<InboxGroup> = groups()) {
        val recordings = Transcripts.transcripts().size.toString().let { if (Transcripts.state.active) "● $it" else it }
        val starred = all.count { it.starred } + Transcripts.transcripts().count { Transcripts.isStarred(it.id) }
        val key = "${all.size}|$starred|$recordings|${filter.id}"
        if (key == navKey) return
        navKey = key
        nav.setEntries(
            listOf(
                SideNav.Entry.Item(Filter.CHAT.id, LineIcon.Glyph.CHAT, t("relay_nav_chat"), all.size.toString()),
                SideNav.Entry.Dots,
                SideNav.Entry.Item(Filter.AUDIO.id, LineIcon.Glyph.MIC, t("relay_nav_audio"), recordings),
                SideNav.Entry.Rule,
                SideNav.Entry.Item(Filter.STARRED.id, LineIcon.Glyph.STAR, t("relay_tab_starred"), starred.toString()),
                SideNav.Entry.Rule,
            ),

            listOf(
                SideNav.Entry.Rule,
                SideNav.Entry.Item(Filter.SETTINGS.id, LineIcon.Glyph.GEAR, t("relay_nav_settings")),
            ),
        )
        nav.setActive(filter.id)
    }

    private fun showEmpty() {
        when {
            query.isNotEmpty() -> empty.set(LineIcon.Glyph.SEARCH, Strings.get("relay_search_none", query), t("relay_search_none_desc"))
            filter == Filter.STARRED -> empty.set(LineIcon.Glyph.STAR, t("relay_starred_empty"), t("relay_starred_hint"))
            else -> empty.set(LineIcon.Glyph.CHAT, t("relay_empty"), t("relay_empty_desc"))
        }
    }

    private fun row(group: InboxGroup, last: Boolean): View {
        val first = group.replies.first()
        val title = rowTitle(host.question(first.id), summary(first).second)
        val meta = buildList {
            add(Strings.get("relay_row_replies", group.replies.size))
            if (group.replies.any { it.kind == "transcript" }) add(t("relay_kind_transcript"))
            if (group.starred) add(t("relay_tab_starred"))
            if (group.replies.any { it.inserted }) add(t("relay_sent_to_note"))
        }.joinToString(" · ")
        val latest = group.replies.maxByOrNull { it.updatedAt } ?: first
        val status = Widgets.oneLine(Widgets.text(context,
            if (group.generating) t("relay_chat_generating") else summary(latest).third,
            Style.F_META, if (group.generating) Style.ink else Style.soft, bold = group.generating))
        val lead: View = if (editing) CheckMark(context).apply { checked = group.key in picked }.also { checks[group.key] = it }
            else LineIcon(context, if (first.kind == "transcript") LineIcon.Glyph.MIC else LineIcon.Glyph.CHAT, Style.ICON_U)
        lead.layoutParams = LayoutParams(u(Style.ICON_U), u(Style.ICON_U))
        return Widgets.listRow(
            context, title,
            meta = meta,
            right = formatInboxTime(group.updated),
            solid = last,
            titleBold = group.unseen,
            lead = lead,
            extra = status,
            onClick = if (editing) ({ toggle(group) }) else ({ host.openCard(first.id) }),
        ).view
    }

    private fun transcriptRow(item: Transcripts.Transcript, last: Boolean): View =
        transcripts.row(item, last, check = if (editing && !item.live) audioCheck(item.id) else null) {
            when {
                !editing -> {
                    showTranscript(item.id)
                    backToStarred = true
                }
                !item.live -> {
                    if (!pickedAudio.remove(item.id)) pickedAudio.add(item.id)
                    checks[AUDIO_KEY + item.id]?.checked = item.id in pickedAudio
                    renderActions()
                }
            }
        }

    private fun audioCheck(id: String): CheckMark =
        CheckMark(context).apply { checked = id in pickedAudio }.also { checks[AUDIO_KEY + id] = it }

    private fun toggle(group: InboxGroup) {
        if (!picked.remove(group.key)) picked.add(group.key)
        checks[group.key]?.checked = group.key in picked
        renderActions()
    }

    private fun pickedIds(): Set<String> =
        groups().filter { it.key in picked }.flatMapTo(LinkedHashSet()) { it.ids }

    private fun pickedCount(): Int = if (audioShown) transcripts.pickedCount else picked.size + pickedAudio.size

    private fun selectDone() = host.open(RelayPage.INBOX)

    fun starPicked() {
        if (audioShown) {
            if (transcripts.starPicked()) selectDone()
            return
        }
        val star = filter != Filter.STARRED
        val audio = pickedAudio.toList()
        val ids = pickedIds()
        if (audio.isNotEmpty()) Transcripts.setStarred(audio, star)
        if (ids.isNotEmpty()) {
            Conversations.setStarred(ids, star)
            selectDone()
        } else if (audio.isNotEmpty()) selectDone()
    }

    fun deletePicked() {
        if (pickedCount() == 0) return
        if (audioShown) {
            transcripts.deletePicked()
            selectDone()
            return
        }
        val ids = pickedIds()
        val audio = pickedAudio.toList()
        pickedAudio.clear()
        transcripts.delete(audio)
        if (ids.isNotEmpty()) {
            Conversations.deleteMany(ids)
            selectDone()
        } else if (audio.isNotEmpty()) selectDone()
    }

    fun renderActions() {
        if (!editing) return
        onPicks?.invoke(pickedCount(), if (audioShown) transcripts.pickedAllStarred else filter == Filter.STARRED)
    }

    private fun summary(reply: RelayMessage): Triple<Long, String, String> =
        summaries[reply.id]?.takeIf { it.first == reply.updatedAt } ?: summaryLines(reply.text).let { lines ->
            Triple(reply.updatedAt, lines.firstOrNull() ?: reply.text.trim(), excerptOf(lines))
        }.also { summaries[reply.id] = it }
}

private const val RECENT_ROW_U = 78f

@SuppressLint("ViewConstructor")
internal class RecentConversations(
    context: Context,

    private val questionOf: (String) -> String?,

    private val onOpen: (String) -> Unit,
    onInbox: () -> Unit,
    emptyHint: String,
) : LinearLayout(context) {

    private fun u(v: Number) = Style.u(v)

    private var messages: List<RelayMessage> = emptyList()
    private var groups: List<InboxGroup> = emptyList()

    private var shownGroups: List<InboxGroup>? = null
    private var shownFits = -1
    private val parsed = HashMap<String, Pair<Long, List<String>>>()

    private val empty = Widgets.emptyLine(context, emptyHint).apply { visibility = GONE }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val linkText = Widgets.text(context, Strings.get("relay_inbox_link"), Style.F_META, Style.ink, bold = true)
    private var textScale = 1f
    private val recentLabel = Widgets.metaLabel(context, Strings.get("relay_recent"))

    private val body = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) syncRows(MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    init {
        orientation = VERTICAL
        val inset = u(Style.PANE_INSET)
        val link = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(16), u(8), 0, u(8))
            addView(linkText)
            addView(LineIcon(context, LineIcon.Glyph.CHEVRON, 100), LayoutParams(u(Style.F_META), u(Style.F_META)).apply { leftMargin = u(4) })
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
        val scale = if (small) Style.MINI_TEXT_SCALE else 1f
        if (scale == textScale) return
        textScale = scale
        linkText.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Style.F_META * scale).toFloat())
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
        val rowHeight = u(RECENT_ROW_U)

        val rule = Style.inkPx(2).coerceAtLeast(1)
        val fits = ((height - rule) / rowHeight).coerceIn(1, groups.size.coerceAtLeast(1))
        if (shownGroups === groups && fits == shownFits) return
        shownGroups = groups
        shownFits = fits
        empty.visibility = if (groups.isEmpty()) VISIBLE else GONE
        rows.removeAllViews()
        if (groups.isEmpty()) return
        rows.addView(Widgets.rule(context))
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
        val line = if (group.generating) Strings.get("relay_chat_generating") else excerpt
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(Widgets.oneLine(Widgets.text(context, title, Style.F_BODY * textScale, Style.ink, bold = group.unseen)))
            if (line.isNotEmpty()) addView(
                Widgets.oneLine(Widgets.text(context, line, Style.F_META * textScale, if (group.generating) Style.ink else Style.soft)),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = u(4) },
            )
        }
        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(Widgets.oneLine(Widgets.text(context, formatInboxTime(group.updated), Style.F_META * textScale, Style.subtle)),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = u(16) })
        }
        return FrameLayout(context).apply {
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            if (dashed) addView(Widgets.dashes(context, insetPx = 0), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Style.inkPx(2).coerceAtLeast(1), Gravity.BOTTOM,
            ))
            setOnClickListener { onOpen(first.id) }
        }
    }
}
