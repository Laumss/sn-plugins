package me.laumss.notipal.toolbar

import android.content.ClipData
import android.content.SharedPreferences
import android.graphics.Typeface
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.AiAccess
import me.laumss.notipal.common.App
import me.laumss.notipal.common.AppInfo
import me.laumss.notipal.common.Cancelable
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostNotice
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.HostProbe
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.Panel
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.ScrollHost
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import java.net.URLEncoder

class SettingKey<T : Any>(val name: String, val default: T)

object Settings {
    private const val TAG = "Settings"

    private const val HOST_ENTRY_CHECK_DELAY_MS = 200L

    internal class Section(val titleKey: String, val build: (LinearLayout) -> Unit)

    private val sections = ArrayList<Section>()
    private val listeners = ArrayList<(String) -> Unit>()
    @Volatile private var panel: SettingsPanel? = null

    @Volatile private var showPending = false
    private var installed = false

    private fun prefs(): SharedPreferences = Prefs.of("settings")

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: SettingKey<T>): T {
        val p = prefs()
        return when (val d = key.default) {
            is Boolean -> p.getBoolean(key.name, d)
            is Int -> p.getInt(key.name, d)
            is Long -> p.getLong(key.name, d)
            is Float -> p.getFloat(key.name, d)
            is String -> p.getString(key.name, d) ?: d
            else -> throw IllegalArgumentException("unsupported setting type: ${key.name}")
        } as T
    }

    fun <T : Any> set(key: SettingKey<T>, value: T) {
        val e = prefs().edit()
        when (value) {
            is Boolean -> e.putBoolean(key.name, value)
            is Int -> e.putInt(key.name, value)
            is Long -> e.putLong(key.name, value)
            is Float -> e.putFloat(key.name, value)
            is String -> e.putString(key.name, value)
            else -> throw IllegalArgumentException("unsupported setting type: ${key.name}")
        }
        e.apply()
        Main.post {
            for (l in listeners.toList()) {
                try {
                    l(key.name)
                } catch (ex: Exception) {
                    Log.e(TAG, "setting listener failed: ${key.name}", ex)
                }
            }
        }
    }

    fun addListener(l: (name: String) -> Unit): Cancelable {
        Main.post { listeners += l }
        return Cancelable { Main.post { listeners -= l } }
    }

    fun registerSection(titleKey: String, build: (LinearLayout) -> Unit) {
        Main.post {

            sections.removeAll { it.titleKey == titleKey }
            sections += Section(titleKey, build)
        }
    }

    internal fun sections(): List<Section> = sections.toList()

    fun blocksToolbar(): Boolean = panel?.visibleNow == true || showPending

    fun install() {
        HostSession.settingsBlocksToolbar = { blocksToolbar() }
        if (installed) return
        installed = true
        HostSession.addNoticeListener { notice ->
            if (notice is HostNotice.PluginView) onPluginView(notice)
        }
    }

    private val hostEntryCheck = Runnable {
        if (!HostSession.moduleAttached || blocksToolbar()) return@Runnable
        val snapshot = HostProbe.readHostEntrySnapshot()
        if (snapshot?.opensConfig() != true) return@Runnable
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host entry opens settings snapshot=$snapshot")
        open()
    }

    private fun onPluginView(n: HostNotice.PluginView) {
        Main.handler.removeCallbacks(hostEntryCheck)
        if (n.showing && n.ownsHost && !n.selfInitiated) {
            Main.handler.postDelayed(hostEntryCheck, HOST_ENTRY_CHECK_DELAY_MS)
        }
    }

    fun open() {
        Main.post {
            Main.handler.removeCallbacks(hostEntryCheck)
            val p = panel ?: SettingsPanel().also { panel = it }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "show requested showing=${p.showing} visible=${p.visibleNow}")
            showPending = true

            Panels.hideForPanel(p)
            Toolbar.closeForSettings()
            if (p.showing) p.resumeVisibility()
            p.show { shown ->
                showPending = false
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "show result=$shown")
            }
            HostPlugins.closePluginView()
        }
    }

    internal fun onPanelHidden(p: SettingsPanel) {
        if (panel === p && !showPending) panel = null
    }
}

class SettingsPanel : Panel("settings") {
    override val parksToolbarRect: Boolean get() = false
    override val tag: String get() = "SettingsPanel"

    companion object {
        private const val GRID_COLUMNS = 5
        private const val GRID_CELL_HEIGHT_DP = 84
        private const val GRID_ICON_SIZE_DP = 44

        private const val GRID_ICON_STROKE_THIN_DP = 1.2f
        private const val SECTION_TITLE_SP = 21.11f
        private const val SECTION_HINT_SP = 16.15f

        const val DISCORD_URL = "https://discord.gg/Z8NwyGWrbj"

        const val FEEDBACK_EMAIL = "yalums@supernote.com"

        private var lastTabKey = "config_tab_tools"

        private val SHARED_IDS = listOf(Tools.CLIP_MANAGER, Tools.IMAGE, Tools.SMART_LASSO, Tools.PEN_SLOTS)

        private val OPTIONAL_IDS = listOf(Tools.AI_RELAY, Tools.LINK)

        private val NOTE_IDS = listOf(Tools.LINK)
        private val DEFAULT_NOTE_HIDDEN = setOf(Tools.LINK)

        fun settingRow(title: String, value: TextView?, action: View?): LinearLayout {
            val ctx = App.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Style.dp(28), Style.dp(16), Style.dp(28), Style.dp(16))
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Style.dp(16) }
            }
            col.addView(TextView(ctx).apply {
                text = title
                textSize = Style.sp(17f)
                setTextColor(Style.ink)
            })
            if (value != null) col.addView(value)
            row.addView(col)
            if (action != null) row.addView(action)
            return row
        }

        fun valueText(): TextView = TextView(App.context).apply {
            textSize = Style.sp(14f)
            setTextColor(Style.settingsValue)
            setPadding(0, Style.dp(4), 0, 0)
        }

        fun smallButton(label: String, onClick: () -> Unit): TextView = TextView(App.context).apply {
            text = label
            textSize = Style.sp(15f)
            setTextColor(Style.ink)
            gravity = Gravity.CENTER
            minWidth = Style.dp(88)
            setPadding(Style.dp(16), Style.dp(8), Style.dp(16), Style.dp(8))
            background = Style.box(Style.paper, Style.ink, 1f)
            setOnClickListener { onClick() }
        }
    }

    private enum class Scope { SHARED, NOTE }

    private var shared = ArrayList<String>()
    private var note = ArrayList<String>()
    private var noteHidden = HashSet<String>()

    private var aiOn = false
    private var aiBanRow: LinearLayout? = null

    private var scrollHost: ScrollHost? = null
    private var sharedGrid: GridLayout? = null
    private var noteGrid: GridLayout? = null
    private var draggingId: String? = null
    private var draggingScope = Scope.NOTE
    private val pages = ArrayList<View>()
    private var restoreBtn: View? = null

    internal val visibleNow: Boolean
        get() = windowView?.let { it.visibility == View.VISIBLE && it.isAttachedToWindow } == true

    private val ctx get() = App.context
    private var currentTab = 0
    private var tabKeys: List<String> = emptyList()

    override fun onHide() {
        scrollHost = null
        sharedGrid = null
        noteGrid = null
        aiBanRow = null
        draggingId = null
        pages.clear()
        restoreBtn = null
        Settings.onPanelHidden(this)
    }

    private fun optionalIds(): List<String> =
        if (AiAccess.banned()) OPTIONAL_IDS.filter { it != Tools.AI_RELAY } else OPTIONAL_IDS

    private fun loadTools() {
        val order = Tools.order()
        shared = ArrayList(order.filter { it in SHARED_IDS })
        note = ArrayList(order.filter { it in optionalIds() })
        noteHidden = HashSet(Tools.hidden().filter { it in NOTE_IDS })
        aiOn = AiAccess.enabled()
    }

    private fun saveTools() {

        val main = shared + note
        Tools.setOrder(main + Tools.order().filter { it !in main })
        Tools.setHidden(noteHidden)
        if (!AiAccess.banned()) AiAccess.setEnabled(aiOn)
    }

    private fun restoreDefaults() {
        shared = ArrayList(SHARED_IDS)
        note = ArrayList(optionalIds())
        noteHidden = HashSet(DEFAULT_NOTE_HIDDEN)
        aiOn = false
        rebindGrids()
    }

    private fun optionalHidden(id: String): Boolean = if (id == Tools.AI_RELAY) !aiOn else id in noteHidden

    override fun build(root: LinearLayout) {
        loadTools()
        pages.clear()
        val sections = Settings.sections()
        val tabKeys = ArrayList<String>().apply {
            add("config_tab_tools")
            if (sections.isNotEmpty()) add("config_tab_connections")
            add("config_tab_about")
        }
        this.tabKeys = tabKeys
        currentTab = tabKeys.indexOf(lastTabKey).coerceAtLeast(0)
        root.addView(Widgets.panelHeader(ctx, "Notipal"))
        root.addView(Widgets.tabBar(ctx, tabKeys.map { Strings.get(it) }, initial = currentTab) { idx -> selectTab(idx) })

        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val host = ScrollHost(ctx, ScrollHost.Mode.FREE)
        scrollHost = host
        buildToolsPage(host.content)
        val all = ArrayList<View>().apply {
            add(host)
            if (sections.isNotEmpty()) add(buildConnectionsPage(sections))
            add(buildAboutPage())
        }
        for (page in all) {
            frame.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            pages.add(page)
        }
        root.addView(frame)

        val restore = Widgets.outlinedButton(ctx, Strings.get("config_restore_defaults")) { restoreDefaults() }
        restoreBtn = restore
        root.addView(Widgets.bottomBar(
            ctx,
            leftButtons = listOf(
                Widgets.outlinedButton(ctx, Strings.get("btn_cancel")) { finish(save = false) },
                restore,
            ),
            rightButtons = listOf(Widgets.filledButton(ctx, Strings.get("btn_confirm")) { finish(save = true) }),
        ))
        selectTab(currentTab)
    }

    private fun finish(save: Boolean) {
        if (save) saveTools()

        Toolbar.close()
    }

    private fun selectTab(idx: Int) {
        currentTab = idx
        tabKeys.getOrNull(idx)?.let { lastTabKey = it }
        for (i in pages.indices) pages[i].visibility = if (i == idx) View.VISIBLE else View.GONE
        restoreBtn?.visibility = if (idx == 0) View.VISIBLE else View.GONE
        if (idx == 0) scrollHost?.refreshThumb()
    }

    private fun pageSection(title: String): TextView = TextView(ctx).apply {
        text = title
        textSize = Style.sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Style.subtle)
        setPadding(Style.dp(28), Style.dp(22), Style.dp(28), Style.dp(4))
    }

    private fun scrollPage(build: (LinearLayout) -> Unit): View {
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, Style.dp(20))
        }
        build(content)
        return ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false
            addView(content)
        }
    }

    private fun buildConnectionsPage(sections: List<Settings.Section>): View = scrollPage { page ->
        for (section in sections) {
            page.addView(pageSection(Strings.get(section.titleKey)))
            try {
                section.build(page)
            } catch (e: Exception) {
                Log.e(tag, "settings section failed: ${section.titleKey}", e)
            }
        }
    }

    private fun feedbackMailto(): String {
        fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val version = AppInfo.versionLabel()
        val subject = "Notipal ${Strings.get("about_feedback_subject")} $version".trim()
        val body = "${Strings.get("about_feedback_body")}\n\n\n--\nNotipal $version\n${AppInfo.deviceLabel()}"
        return "mailto:$FEEDBACK_EMAIL?subject=${encode(subject)}&body=${encode(body)}"
    }

    private fun buildAboutPage(): View = scrollPage { page ->
        page.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Style.dp(28), Style.dp(24), Style.dp(28), Style.dp(4))
            addView(TextView(ctx).apply {
                text = "Notipal"
                textSize = Style.sp(22f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Style.ink)
            })
            val version = AppInfo.versionLabel()
            addView(TextView(ctx).apply {
                text = if (version.isNotEmpty()) Strings.get("about_version", version) else Strings.get("about_tagline")
                textSize = Style.sp(14f)
                setTextColor(Style.settingsValue)
                setPadding(0, Style.dp(4), 0, 0)
            })
        })
        page.addView(Widgets.qrCard(
            ctx,
            payload = DISCORD_URL,
            title = Strings.get("about_discord_title"),
            desc = Strings.get("about_discord_desc"),
            link = DISCORD_URL.removePrefix("https://"),
        ))
        if (FEEDBACK_EMAIL.isNotEmpty()) {
            page.addView(Widgets.qrCard(
                ctx,
                payload = feedbackMailto(),
                title = Strings.get("about_feedback_title"),
                desc = Strings.get("about_feedback_desc"),
                link = FEEDBACK_EMAIL,
            ))
        }
        page.addView(logExportRow())
    }

    private fun logExportRow(): View {
        val status = valueText().apply { text = Strings.get("about_log_desc") }
        var running = false
        lateinit var btn: TextView
        btn = smallButton(Strings.get("about_log_export")) {
            if (running) return@smallButton
            running = true
            btn.text = Strings.get("about_log_exporting")
            Thread({
                val result = runCatching { Log.export() }
                result.exceptionOrNull()?.let { Log.w(tag, "log export failed: ${it.javaClass.simpleName}: ${it.message}") }
                Main.post {
                    running = false
                    btn.text = Strings.get("about_log_export")
                    status.text = result.fold(
                        { Strings.get("about_log_saved", it.path) },
                        { Strings.get("about_log_failed", it.message ?: it.javaClass.simpleName) },
                    )
                }
            }, "NotipalLogExport").start()
        }
        return settingRow(Strings.get("about_log_title"), status, btn)
    }

    private fun buildToolsPage(content: LinearLayout) {
        fun addSectionTitle(title: String, hint: String) {
            content.addView(TextView(ctx).apply {
                text = title
                textSize = Style.sp(SECTION_TITLE_SP)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Style.soft)
                setPadding(Style.dp(10), Style.dp(12), Style.dp(10), Style.dp(3))
            })
            content.addView(TextView(ctx).apply {
                text = hint
                textSize = Style.sp(SECTION_HINT_SP)
                setTextColor(Style.muted)
                setPadding(Style.dp(10), 0, Style.dp(10), Style.dp(8))
            })
        }
        addSectionTitle(Strings.get("config_shared_tools"), Strings.get("config_shared_hint"))
        sharedGrid = makeGrid().also(content::addView)
        addSectionTitle(Strings.get("config_optional_tools"), Strings.get("config_optional_hint"))
        noteGrid = makeGrid().also(content::addView)
        rebindGrids()
        val banRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        aiBanRow = banRow
        fillAiBanRow(banRow)
        content.addView(banRow)
    }

    private fun fillAiBanRow(row: LinearLayout) {
        row.removeAllViews()
        if (AiAccess.banned()) {
            row.addView(settingRow(Strings.get("config_ai_ban_title"),
                valueText().apply { text = Strings.get("config_ai_banned") }, null))
            return
        }
        val button = smallButton(Strings.get("config_ai_ban_button")) {
            Dialogs.confirm(Strings.get("config_ai_ban_confirm")) { confirmed ->
                if (!confirmed) return@confirm
                Main.post {
                    AiAccess.ban()
                    aiOn = false
                    note.remove(Tools.AI_RELAY)
                    rebindGrids()
                    aiBanRow?.let { fillAiBanRow(it) }
                }
            }
        }
        row.addView(settingRow(Strings.get("config_ai_ban_title"),
            valueText().apply { text = Strings.get("config_ai_ban_desc") }, button))
    }

    private fun makeGrid(): GridLayout = GridLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
        orientation = GridLayout.HORIZONTAL
        columnCount = GRID_COLUMNS
        isColumnOrderPreserved = true
        useDefaultMargins = false
        setBackgroundColor(Style.paper)
    }

    private fun list(scope: Scope): ArrayList<String> = if (scope == Scope.SHARED) shared else note
    private fun grid(scope: Scope): GridLayout? = if (scope == Scope.SHARED) sharedGrid else noteGrid

    private fun rebindGrids() {
        draggingId = null
        for (scope in Scope.values()) {
            val target = grid(scope) ?: continue
            val items = list(scope)
            target.removeAllViews()
            target.columnCount = GRID_COLUMNS
            for (id in items) Tools.get(id)?.let { target.addView(makeCell(it, scope)) }
            val rows = (items.size + GRID_COLUMNS - 1) / GRID_COLUMNS
            target.rowCount = rows.coerceAtLeast(1)
            target.layoutParams = (target.layoutParams as LinearLayout.LayoutParams).apply { height = rows * Style.dp(GRID_CELL_HEIGHT_DP) }
            target.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            target.requestLayout()
        }
        scrollHost?.refreshThumb()
    }

    private fun strokeDeltaPx(): Float = -Style.dp(GRID_ICON_STROKE_THIN_DP).toFloat()

    private fun applyIcon(icon: ImageView, tool: Tool, hidden: Boolean) {
        icon.alpha = 1f
        icon.clearColorFilter()
        icon.setImageDrawable(Style.iconPx(tool.icon, Style.dp(GRID_ICON_SIZE_DP), if (hidden) Style.settingsToolHidden else Style.toolbarBorder, strokeDeltaPx()))
    }

    private fun makeCell(tool: Tool, scope: Scope): FrameLayout {
        val gridWidth = scrollHost?.availableContentWidth(cardW) ?: cardW
        val cell = FrameLayout(ctx).apply {
            tag = tool.id
            layoutParams = GridLayout.LayoutParams().apply {
                width = gridWidth / GRID_COLUMNS
                height = Style.dp(GRID_CELL_HEIGHT_DP)
            }
            isClickable = true
            isFocusable = true
            contentDescription = Strings.get(tool.labelKey)
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = FrameLayout.LayoutParams(Style.dp(GRID_ICON_SIZE_DP), Style.dp(GRID_ICON_SIZE_DP), Gravity.CENTER)
        }
        applyIcon(icon, tool, scope == Scope.NOTE && optionalHidden(tool.id))
        cell.addView(icon)
        cell.setOnClickListener {
            if (scope != Scope.NOTE) return@setOnClickListener
            if (tool.id == Tools.AI_RELAY) {
                aiOn = !aiOn
            } else if (!noteHidden.remove(tool.id)) {
                noteHidden.add(tool.id)
            }
            applyIcon(icon, tool, optionalHidden(tool.id))
        }
        cell.setOnLongClickListener {
            draggingId = tool.id
            draggingScope = scope
            val started = cell.startDragAndDrop(ClipData.newPlainText("tool", tool.id), View.DragShadowBuilder(cell), tool.id, 0)
            if (started) {
                icon.visibility = View.INVISIBLE
                cell.background = Style.dashedBox(Style.paper, Style.ink)
            } else {
                draggingId = null
            }
            started
        }
        cell.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                DragEvent.ACTION_DRAG_ENTERED -> {
                    draggingId?.let { source -> if (source != tool.id && draggingScope == scope) moveDragged(source, tool.id, scope) }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    finishDrag()
                    true
                }
                else -> true
            }
        }
        return cell
    }

    private fun cellOf(scope: Scope, id: String): View? =
        grid(scope)?.let { g -> (0 until g.childCount).map(g::getChildAt).firstOrNull { it.tag == id } }

    private fun moveDragged(sourceId: String, targetId: String, scope: Scope) {
        val items = list(scope)
        val from = items.indexOf(sourceId)
        val to = items.indexOf(targetId)
        if (from !in items.indices || to !in items.indices || from == to) return
        items.add(to, items.removeAt(from))
        val g = grid(scope) ?: return
        val dragged = cellOf(scope, sourceId) ?: return
        g.removeView(dragged)
        g.addView(dragged, to)
        g.requestLayout()
        g.invalidate()
    }

    private fun finishDrag() {
        val sourceId = draggingId ?: return
        val dragged = cellOf(draggingScope, sourceId)
        dragged?.background = null
        val icon = (dragged as? FrameLayout)?.getChildAt(0)
        icon?.visibility = View.VISIBLE
        if (icon is ImageView) Tools.get(sourceId)?.let { applyIcon(icon, it, draggingScope == Scope.NOTE && optionalHidden(sourceId)) }
        dragged?.invalidate()
        grid(draggingScope)?.invalidate()
        draggingId = null
    }
}
