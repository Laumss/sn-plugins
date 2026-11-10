package me.laumss.notipal

import android.graphics.Rect
import android.util.Log

object SubviewLogMonitor {

    private const val TAG = "SubviewLogMonitor"

    private const val MAX_RESTARTS = 5
    private const val RESTART_BASE_DELAY_MS = 1000L
    private const val RESTART_MAX_DELAY_MS = 30_000L

    private val LOGCAT_CMD = arrayOf(
        "logcat", "-T", "1", "-v", "brief", "-s",
        "FragmentNavigator:I", "SideBarView:I",
        "NoteInsidePagesActivity:I", "NoteLinkTypeSelectView:I",
        "AreaSelectionMenuView:D", "AreaSelectionMoreMenuView:D",
        "AreaSelectionView:V",
        "DialogLib:E",
        "KnowledgeInsertView:I",
        "Toolbar:I",

        "HandWriteClient:D",
        "DigestHandWriteClient:D",

        "HandWritePresenter:V",
        "RattaSnNoteLib:I",
        "PagingView:I",

        "Document:D",

        "DocumentActivity:D",
        "DigestDetailViewFragment:D",
    )

    interface Listener {
        fun onDocTextSelectionChanged(active: Boolean, generation: Int) {}

        fun onDocTextSelectedChanged(selected: Boolean, generation: Int) {}

        fun onSubviewOpened(generation: Int)

        fun onSubviewClosed(generation: Int)

        fun onToolbarMenuChanged(open: Boolean, pluginListMenu: Boolean, generation: Int)

        fun onLassoMenuChanged(open: Boolean, generation: Int)

        fun onMovedLassoGone(generation: Int)

        fun onNativePenAreasChanged(rects: List<Rect>, generation: Int)

        fun onHostFullScreenDisableArea(generation: Int)

        fun onDocWriteAreaRebuilt(generation: Int)

        fun onDocWritableEnabled(generation: Int)

        fun onDocLassoReleased(reason: String, generation: Int)

        fun onDocLassoStarted(confirmed: Boolean, generation: Int)

        fun onPluginImageCommitted(generation: Int) {}
    }

    private sealed class Op {
        class ShowExclusive(val name: String) : Op()

        class Add(val name: String) : Op()
        class Clear(val name: String) : Op()
        object ClearAll : Op()

        class ToolbarMenu(val open: Boolean, val pluginListMenu: Boolean = false) : Op()

        class LassoMenu(val open: Boolean) : Op()

        object MovedLassoGone : Op()
    }

    private class Rule(val regex: Regex, val toOp: (MatchResult) -> Op?)

    private val LASSO_MENU_CLASSES = setOf("AreaSelectionMenuView", "AreaSelectionMoreMenuView")

    private var pendingNativeAreaCount = -1
    private val pendingNativeAreas = mutableMapOf<Int, Rect>()

    private val AREA_RECT_REGEX =
        Regex("""AreaSelectionView.*handleActionUp: areaRect left (\d+) top (\d+) width (\d+) height (\d+)""")
    private val PLUGIN_IMAGE_COMMIT_REGEX =
        Regex("""RattaSnNoteLib.*NotePresenter: forcedToLoadLayer onPlugImageTransition""")
    private val LASSO_STATE_SHOWN_REGEX = Regex("""onChangeLassoState:\s*0\b""")
    private val LASSO_STATE_REGEX = Regex("""onChangeLassoState:\s*(\d+)""")
    private val VISIBILITY_GONE_REGEX = Regex("""onVisibilityChanged\s+8\b""")
    private val RECT_LIST_SIZE_REGEX = Regex("""rectList\.size\(\)\s+(\d+)""")
    private val RECT_LIST_ITEM_TUPLE_REGEX =
        Regex("""rectList\.get\((\d+)\)\s*:\s*\((-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\)""")
    private val RECT_LIST_ITEM_SPACED_REGEX =
        Regex("""rectList\.get\((\d+)\)\s*:\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)""")

    private val floatingBarDisableRectRegex = Regex(
        """getDisableRect:\s*floatingBar\s+rect(?:\s|\u3000)*[（(]\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*[）)]"""
    )

    private var toolbarMenuOpen = false

    private var toolbarMenuPluginList = false

    private var toolbarMenuRevision = 0L

    internal fun toolbarMenuProbeToken(): Pair<Int, Long>? = synchronized(lock) {
        if (toolbarMenuOpen) gen to toolbarMenuRevision else null
    }

    internal fun closeDocumentToolbarMenu(token: Pair<Int, Long>) {
        synchronized(lock) {
            if (!isCurrent(token.first) || toolbarMenuRevision != token.second ||
                !toolbarMenuOpen || !FloatingToolbarModule.isDocumentContext()) return
            applyOp(token.first, Op.ToolbarMenu(false), "document sidebar menus gone")
        }
    }

    private var lassoMenuOpen = false

    private val ownedDialogPending = ArrayDeque<Pair<Long, Long>>()
    private val ownedDialogShown = ArrayDeque<Long>()

    private const val OWNED_DIALOG_SHOW_WINDOW_MS = 10_000L

    private val rules: List<Rule> = listOf(

        Rule(Regex("""FragmentNavigator.*showExclusive (\w+)""")) { m ->
            val name = m.groupValues[1]
            if (name in LASSO_MENU_CLASSES) Op.LassoMenu(true) else Op.ShowExclusive(name)
        },

        Rule(Regex("""FragmentNavigator.*clear (\w+|all)""")) { m ->
            val what = m.groupValues[1]
            when {
                what == "all" -> Op.ClearAll
                what in LASSO_MENU_CLASSES -> Op.LassoMenu(false)
                else -> Op.Clear(what)
            }
        },

        Rule(Regex("""NoteLinkTypeSelectView.*setInitData""")) { Op.Add("NoteLinkTypeSelectView") },

        Rule(Regex("""RattaSnNoteLib.*NotePresenter: beginLassoRecognition""")) { Op.Add("LassoRecognition") },
        Rule(Regex("""NoteInsidePagesActivity.*onLassoRecognitionResult""")) { Op.Clear("LassoRecognition") },

        Rule(Regex("""AreaSelectionMenuView.*setButtonList""")) { Op.LassoMenu(true) },
        Rule(Regex("""AreaSelectionMoreMenuView.*setButtonList""")) { Op.LassoMenu(true) },

        Rule(Regex("""HandWriteClient.*setFullAuto: false lasso view gone""")) { Op.LassoMenu(false) },

        Rule(Regex("""HandWriteClient.*setRegalStatus open lasso view action_move""")) {
            lassoRelocating = true
            null
        },

        Rule(Regex("""HandWriteClient.*setRegalStatus open lasso view gone""")) {
            if (lassoRelocating || !isLassoMenuOpen()) Op.MovedLassoGone else Op.LassoMenu(false)
        },

        Rule(Regex("""SideBarView.*getDisableRect:\s*(buttonEntryView|pluginListView|layerSelectView|eraserSelectView|penSelectView|quickAccessView|pageTurningModeView|toolbarPositionSelectView)""")) { m ->
            Op.ToolbarMenu(true, pluginListMenu = m.groupValues[1] == "pluginListView")
        },

        Rule(Regex("""SideBarView.*showMoreView""")) { Op.Add("MoreFunctionView") },

        Rule(Regex("""NoteInsidePagesActivity.*showKeywordRecognitionSucceed""")) { Op.Add("KeywordCreateView") },

        Rule(Regex("""NoteInsidePagesActivity.*turns off keyword locked frequency""")) { Op.Clear("KeywordCreateView") },

        Rule(Regex("""SideBarView.*sidebarPreferredClick""")) { Op.Add("PreferredSettingView") },

        Rule(Regex("""SideBarView.*sidebarGestureSettingsClick""")) { Op.Add("GestureSettingView") },

        Rule(Regex("""DialogLib.*dialog show""")) { Op.Add("RattaDialog") },
        Rule(Regex("""DialogLib.*dialog dismiss""")) { Op.Clear("RattaDialog") },

        Rule(Regex("""KnowledgeInsertView.*showDigestList""")) { Op.Add("KnowledgeInsertView") },

        Rule(Regex("""Document.*\bshowDigestDetailView:""")) { Op.Add("DigestDetailViewFragment") },
        Rule(Regex("""DigestDetailViewFragment.*\bonResume:""")) { Op.Add("DigestDetailViewFragment") },
        Rule(Regex("""DocumentActivity.*\bhideDigestDetailView:""")) { Op.Clear("DigestDetailViewFragment") },
        Rule(Regex("""DigestDetailViewFragment.*\bonDestroyView\b""")) { Op.Clear("DigestDetailViewFragment") },

        Rule(Regex("""SideBarView.*sidebarRenameClick""")) { Op.Add("SideBarRenameView") },

        Rule(Regex("""SideBarView.*sidebarPageClick""")) { Op.Add("UnifiedView") },
        Rule(Regex("""PageBarView\.java.*hidePageBar""")) { Op.Clear("UnifiedView") },
        Rule(Regex("""NotePresenter: notePageJumps""")) { Op.Clear("UnifiedView") },

        Rule(Regex("""Document.*DoubleSlideBarManager\.java.*setQuickPageTurningMode true""")) { Op.Add("UnifiedView") },
        Rule(Regex("""Document.*DoubleSlideBarManager\.java.*exitQuickPageTurningMode""")) { Op.Clear("UnifiedView") },

        Rule(Regex("""PagingView.*setPageInfo""")) { Op.Add("PagingView") },
        Rule(Regex("""PageBarView\.java.*hidePageBar""")) { Op.Clear("PagingView") },
        Rule(Regex("""NotePresenter: notePageJumps""")) { Op.Clear("PagingView") },
    )

    private val POPUP_CLASSES = setOf(
        "MoreFunctionView", "KeywordCreateView", "PreferredSettingView",
        "GestureSettingView", "KnowledgeInsertView", "SideBarRenameView", "UnifiedView",
        "PagingView", "CustomToolbarView", "NoteTextSearchView",
    )

    private val WATCH_CLASSES = setOf("CustomToolbarView", "NoteTextSearchView", "PagingView")

    private const val POPUP_POLL_MS = 1200L

    private const val POPUP_MISS_THRESHOLD = 2

    private const val POPUP_POLL_MAX_FAILURES = 3

    private val lock = Any()

    @Volatile private var gen = 0

    private var listener: Listener? = null
    private var process: Process? = null
    private val openSubviews = mutableSetOf<String>()
    private var anyOpen = false

    private var popupPollerRunning = false

    fun isSubviewOpen(): Boolean = synchronized(lock) { anyOpen }

    fun isToolbarMenuOpen(): Boolean = synchronized(lock) { toolbarMenuOpen }

    fun isLassoMenuOpen(): Boolean = synchronized(lock) { lassoMenuOpen }

    @Volatile private var lassoRelocating = false

    @Volatile private var observedLassoDisplayRect: Rect? = null

    fun getObservedLassoDisplayRect(): Rect? = observedLassoDisplayRect?.let(::Rect)

    fun isLassoRelocating(): Boolean = lassoRelocating

    fun clearLassoRelocating() {
        lassoRelocating = false
    }

    fun armOwnedDialog(token: Long) {
        synchronized(lock) { ownedDialogPending.addLast(token to android.os.SystemClock.elapsedRealtime()) }
    }

    fun closeLassoMenuFromFallback(reason: String) {
        val state = synchronized(lock) {
            if (!lassoMenuOpen) return
            lassoMenuOpen = false
            listener to gen
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "lasso menu changed: open=false fallback=$reason")
        state.first?.onLassoMenuChanged(false, state.second)
    }

    fun isCurrentGeneration(candidate: Int): Boolean = candidate == gen

    fun currentGeneration(): Int = gen

    fun start(l: Listener) {
        val myGen: Int
        synchronized(lock) {
            gen++
            myGen = gen
            listener = l
            lassoRelocating = false

            openSubviews.clear()
            anyOpen = false
            toolbarMenuOpen = false
            toolbarMenuPluginList = false
            lassoMenuOpen = false
            ownedDialogPending.clear()
            ownedDialogShown.clear()
            docTextSelectionActive = false
            docTextSelected = false
            docAreaQueryOpen = false
            docAreaQueryDisabled = false

            try { process?.destroy() } catch (_: Exception) {}
            process = null

            popupPollerRunning = false
        }
        Thread({ runLoop(myGen) }, "$TAG-g$myGen").apply {
            isDaemon = true
            start()
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "started (gen=$myGen)")
    }

    fun stop() {
        synchronized(lock) {
            gen++
            listener = null
            openSubviews.clear()
            anyOpen = false
            toolbarMenuOpen = false
            toolbarMenuPluginList = false
            lassoMenuOpen = false
            ownedDialogPending.clear()
            ownedDialogShown.clear()
            docTextSelectionActive = false
            docTextSelected = false
            docAreaQueryOpen = false
            docAreaQueryDisabled = false
            try { process?.destroy() } catch (_: Exception) {}
            process = null
            popupPollerRunning = false
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "stopped (gen=$gen)")
    }

    private fun isCurrent(myGen: Int) = myGen == gen

    private fun bootstrapNativePenAreas(myGen: Int) {
        pendingNativeAreaCount = -1
        pendingNativeAreas.clear()
        var bootstrap: Process? = null
        try {
            bootstrap = ProcessBuilder(
                "logcat", "-d", "-t", "300", "-v", "brief", "-s",
                "HandWriteClient:D", "DigestHandWriteClient:D"
            ).redirectErrorStream(true).start()
            bootstrap.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!isCurrent(myGen)) return@useLines
                    if (SystemKeyboard.isKeyboardLogLine(line)) return@forEach
                    if (line.contains("rectList.size()") || line.contains("rectList.get(") ||
                        line.contains("getDisableRect: floatingBar")) {
                        handleNativePenAreaLine(
                            myGen = myGen,
                            line = line,
                            detectRebuildSignals = false
                        )
                    }
                }
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "native pen bootstrap complete gen=$myGen")
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(TAG, "native pen bootstrap failed gen=$myGen: ${e.message}")
            }
        } finally {
            try { bootstrap?.destroy() } catch (_: Exception) {}
        }
    }

    private fun runLoop(myGen: Int) {

        var restarts = 0
        while (isCurrent(myGen)) {
            var proc: Process? = null
            try {

                proc = ProcessBuilder(*LOGCAT_CMD).redirectErrorStream(true).start()
                synchronized(lock) {
                    if (!isCurrent(myGen)) {

                        try { proc.destroy() } catch (_: Exception) {}
                        return
                    }
                    process = proc
                }

                bootstrapDocumentTool(myGen)
                bootstrapNativePenAreas(myGen)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "logcat attached (gen=$myGen restarts=$restarts)")
                proc.inputStream.bufferedReader().use { reader ->
                    var firstLine = true
                    while (isCurrent(myGen)) {
                        val line = reader.readLine() ?: break
                        if (firstLine) {
                            firstLine = false

                            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "first line: $line")
                        }
                        handleLine(myGen, line)
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "logcat read error (gen=$myGen): ${e.message}")
            } finally {
                try { proc?.destroy() } catch (_: Exception) {}
                synchronized(lock) { if (process === proc) process = null }
            }
            if (!isCurrent(myGen)) break

            restarts++
            if (restarts > MAX_RESTARTS) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "logcat died $restarts times, give up (gen=$myGen)")
                break
            }
            val delay = (RESTART_BASE_DELAY_MS shl (restarts - 1))
                .coerceAtMost(RESTART_MAX_DELAY_MS)
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "logcat died, restart #$restarts in ${delay}ms (gen=$myGen)")
            try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "reader thread exit (gen=$myGen)")
    }

    private val SIDEBAR_LAYOUT_SIGNAL = Regex(
        """sidebar (show|hide)|updateStatus: floating state|screenChanged|setExitFullScreenMode"""
    )

    private const val DOC_DISABLED_OVERLAY = "DocDisabledOverlay"
    @Volatile private var docAreaQueryOpen = false
    @Volatile private var docAreaQueryDisabled = false
    @Volatile private var docTextSelectionActive = false
    internal fun isDocTextSelectionActive(): Boolean = docTextSelectionActive

    @Volatile private var docTextSelected = false
    internal fun isDocTextSelected(): Boolean = docTextSelected

    private fun setDocTextSelected(myGen: Int, selected: Boolean) {
        if (docTextSelected == selected) return
        docTextSelected = selected
        val currentListener = synchronized(lock) { if (isCurrent(myGen)) listener else null }
        currentListener?.onDocTextSelectedChanged(selected, myGen)
    }
    private val DOC_TEXT_SELECTION_REGEX = Regex("""Document.*\bonSelectTextClick\s+b:\s*(true|false)\b""")
    private val DOC_WRITING_MODE_REGEX = Regex("""Document.*\bchangeEditModel\s+selectModel:\s*(-?\d+)\b""")

    private fun documentTextToolState(line: String): Boolean? {
        val mode = DOC_WRITING_MODE_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
        if (mode != null) return when (mode) { 0, 1 -> true; -1 -> false; else -> null }
        return DOC_TEXT_SELECTION_REGEX.find(line)?.let { it.groupValues[1] == "true" }
    }

    private fun bootstrapDocumentTool(myGen: Int) {
        if (!FloatingToolbarModule.isDocumentContext() || !isCurrent(myGen)) return
        var snapshot: Process? = null
        try {
            val pidQuery = ProcessBuilder("pidof", "com.supernote.document").start()
            val pid = pidQuery.inputStream.bufferedReader().use { it.readText().trim() }
            if (pidQuery.waitFor() != 0 || pid.toIntOrNull() == null) return
            snapshot = ProcessBuilder("logcat", "-d", "--pid=$pid", "-v", "brief", "-s", "Document:I")
                .redirectErrorStream(true).start()
            var latest: String? = null
            snapshot.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!isCurrent(myGen)) return@useLines
                    if (documentTextToolState(line) != null) latest = line
                }
            }
            if (snapshot.waitFor() == 0 && isCurrent(myGen) && FloatingToolbarModule.isDocumentContext()) {
                latest?.let { trackDocDisabledOverlay(myGen, it) }
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "document tool bootstrap pid=$pid active=$docTextSelectionActive observed=${latest != null}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "document tool bootstrap failed: ${e.message}")
        } finally {
            try { snapshot?.destroy() } catch (_: Exception) {}
        }
    }

    private fun trackDocDisabledOverlay(myGen: Int, line: String) {
        if (!isCurrent(myGen)) return
        val textMode = documentTextToolState(line)
        if (textMode != null) {
            docTextSelectionActive = textMode
            if (!textMode) setDocTextSelected(myGen, false)
            docAreaQueryOpen = false
            docAreaQueryDisabled = false
            val currentListener = synchronized(lock) { if (isCurrent(myGen)) listener else null }
            currentListener?.onDocTextSelectionChanged(textMode, myGen)

            applyOp(myGen, Op.Clear(DOC_DISABLED_OVERLAY), line)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "document text selection active=$docTextSelectionActive")
            }
            return
        }
        when {
            line.contains("showSelectTextPopView") -> setDocTextSelected(myGen, true)
            line.contains("DocumentImageView") && line.contains("clearRectList") -> setDocTextSelected(myGen, false)
        }
        when {
            line.contains("handwrite action onSendDisableWriteArea") -> {
                docAreaQueryOpen = true
                docAreaQueryDisabled = false
            }
            line.contains("onGetDisableAreaList disabled") -> {
                docAreaQueryDisabled = true
                if (docTextSelectionActive) {
                    applyOp(myGen, Op.Clear(DOC_DISABLED_OVERLAY), line)
                } else {
                    applyOp(myGen, Op.Add(DOC_DISABLED_OVERLAY), line)
                }
            }
            docAreaQueryOpen && line.contains("HandWritePresenter") &&
                line.contains("DocumentActivity sendDisableWriteArea") -> {
                docAreaQueryOpen = false
                if (!docAreaQueryDisabled) applyOp(myGen, Op.Clear(DOC_DISABLED_OVERLAY), line)
            }
        }
    }

    private fun handleLine(myGen: Int, line: String) {
        if (SystemKeyboard.isKeyboardLogLine(line)) return
        AREA_RECT_REGEX
            .find(line)?.let { match ->
                val left = match.groupValues[1].toIntOrNull()
                val top = match.groupValues[2].toIntOrNull()
                val width = match.groupValues[3].toIntOrNull()
                val height = match.groupValues[4].toIntOrNull()
                if (left != null && top != null && width != null && height != null && width > 1 && height > 1) {
                    observedLassoDisplayRect = Rect(left, top, left + width, top + height)
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "observed live lasso display rect=$observedLassoDisplayRect")
                    }
                }
            }
        handlePluginImageLine(myGen, line)
        trackDocDisabledOverlay(myGen, line)
        if (FloatingToolbarModule.isDocumentContext()) {
            when {
                (line.contains("DocumentActivity") && line.contains("pageBarVisible")) ||
                    (line.contains("SideBarView") && line.contains("setPageBarVisible")) ->
                    DocChromeProbe.request("document page bar visibility requested")
                line.contains("DoubleSlideBarManager") && line.contains("setPenFloatingStatus: false") ->
                    DocChromeProbe.request("document pen floating ended")
            }
        }

        if (line.contains("SideBarView") && SIDEBAR_LAYOUT_SIGNAL.containsMatchIn(line)) {
            DocChromeProbe.request("sidebar: ${line.substringAfter("SideBarView").trim().take(60)}")
        }

        if (isForeignDialogLine(line)) return
        if (handleNativePenAreaLine(myGen, line)) return
        if (handleOwnedDialogLine(myGen, line)) return
        if (handleDocLassoLine(myGen, line)) return
        for (rule in rules) {
            val match = rule.regex.find(line) ?: continue
            val op = rule.toOp(match)
            if (BuildConfig.ENABLE_DEBUG) {
                val opName = op?.let { it::class.simpleName } ?: "null(side-effect-only)"
                Log.d(TAG, "[menu-state] line=${line.trim().take(120)} op=$opName toolbarMenuOpen=${isToolbarMenuOpen()} openSubviews=$openSubviews")
            }
            op ?: continue
            applyOp(myGen, op, line)

            if (op is Op.Add && op.name in POPUP_CLASSES) ensurePopupPoller(myGen)
        }
    }

    private fun handlePluginImageLine(myGen: Int, line: String) {
        if (!line.contains("RattaSnNoteLib") || !PLUGIN_IMAGE_COMMIT_REGEX.containsMatchIn(line)) return
        val currentListener = synchronized(lock) { if (isCurrent(myGen)) listener else null }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "plugin image committed to layer")
        currentListener?.onPluginImageCommitted(myGen)
    }

    private fun handleDocLassoLine(myGen: Int, line: String): Boolean {
        val isLassoStart = line.contains("LASSOTRAIL_SIGNAL")
        val selectionShown = line.contains("showAreaSelection rect:") ||
            (line.contains("DocumentActivity") && LASSO_STATE_SHOWN_REGEX.containsMatchIn(line))
        if (!line.contains("Document") && !line.contains("HandWritePresenter") && !isLassoStart) return false
        if (isLassoStart && !selectionShown) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "doc lasso candidate: awaiting selection display")
            return true
        }
        if (selectionShown) {
            val currentListener = synchronized(lock) { if (isCurrent(myGen)) listener else null }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "doc lasso started line=${line.trim().take(120)}")
            currentListener?.onDocLassoStarted(true, myGen)
            return true
        }
        val reason = when {
            line.contains("onChangeLassoState:") -> {
                val state = LASSO_STATE_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (state == 2) "doc lasso state removed" else return true
            }
            line.contains("tag: appendTrail") -> "doc sticker appended as trail"
            line.contains("DocumentActivity") &&
                VISIBILITY_GONE_REGEX.containsMatchIn(line) -> "doc lasso menu gone"
            else -> return false
        }
        val currentListener = synchronized(lock) { if (isCurrent(myGen)) listener else null }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "doc lasso released: $reason line=${line.trim().take(120)}")
        currentListener?.onDocLassoReleased(reason, myGen)
        return true
    }

    private val NOTE_DIALOG_PACKAGES = setOf(
        "com.ratta.supernote.note",
        "com.ratta.supernote.pluginhost",
    )

    private val DIALOG_PID_REGEX = Regex("""DialogLib\(\s*(\d+)\)""")

    private val dialogPackageCache = HashMap<Int, String?>()

    private fun packageOfPid(pid: Int): String? {
        synchronized(dialogPackageCache) {
            if (dialogPackageCache.containsKey(pid)) return dialogPackageCache[pid]
        }
        val pkg = try {
            java.io.File("/proc/$pid/cmdline").readText()
                .substringBefore('\u0000')
                .substringBefore(':')
                .trim()
                .ifEmpty { null }
        } catch (_: Exception) {
            null
        }
        synchronized(dialogPackageCache) { dialogPackageCache[pid] = pkg }
        return pkg
    }

    private fun isForeignDialogLine(line: String): Boolean {
        if (!line.contains("DialogLib")) return false
        if (!line.contains("dialog show") && !line.contains("dialog dismiss")) return false
        val pid = DIALOG_PID_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        val pkg = packageOfPid(pid) ?: return false
        val foreign = pkg !in NOTE_DIALOG_PACKAGES
        if (foreign && BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "ignore foreign dialog pkg=$pkg pid=$pid line=${line.trim().take(120)}")
        }
        return foreign
    }

    private fun handleOwnedDialogLine(myGen: Int, line: String): Boolean {
        val shown = line.contains("DialogLib") && line.contains("dialog show")
        val dismissed = line.contains("DialogLib") && line.contains("dialog dismiss")
        if (!shown && !dismissed) return false
        val pid = DIALOG_PID_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
        if (pid != null && pid != android.os.Process.myPid()) {
            if (shown && synchronized(lock) { ownedDialogPending.isNotEmpty() }) {
                Log.i(TAG, "dialog show from pid=$pid (mine=${android.os.Process.myPid()}) while owned dialog pending")
            }
            return false
        }
        var token: Long? = null
        synchronized(lock) {
            if (!isCurrent(myGen)) return true
            if (shown) {
                val now = android.os.SystemClock.elapsedRealtime()
                while (ownedDialogPending.isNotEmpty() &&
                    now - ownedDialogPending.first().second > OWNED_DIALOG_SHOW_WINDOW_MS) {
                    Log.w(TAG, "owned dialog token=${ownedDialogPending.first().first} never shown; dropped")
                    ownedDialogPending.removeFirst()
                }
                token = ownedDialogPending.removeFirstOrNull()?.first ?: return false
                ownedDialogShown.addLast(token!!)
            } else {
                token = ownedDialogShown.removeFirstOrNull() ?: return false
            }
        }
        Log.i(TAG, "owned dialog ${if (shown) "shown" else "dismissed"} token=$token gen=$myGen")
        if (dismissed) me.laumss.notipal.ui_common.Dialog.onOwnedDismissLogged(token!!)
        return true
    }

    private fun handleNativePenAreaLine(
        myGen: Int,
        line: String,
        detectRebuildSignals: Boolean = true
    ): Boolean {
        val floatingBarMatch = floatingBarDisableRectRegex.find(line)
        if (floatingBarMatch != null) {
            val values = floatingBarMatch.groupValues.drop(1).map {
                val value = it.toDoubleOrNull() ?: return true
                if (!value.isFinite() || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return true
                value.toInt()
            }
            val (left, top, width, height) = values
            val right = left.toLong() + width
            val bottom = top.toLong() + height
            if (width > 0 && height > 0 &&
                right in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                bottom in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                pendingNativeAreaCount = -1
                pendingNativeAreas.clear()
                val snapshot = listOf(Rect(left, top, right.toInt(), bottom.toInt()))
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "native pen areas inferred from floatingBar: $snapshot line=$line")
                }

                notifyNativePenAreas(myGen, snapshot)
            }
            return true
        }

        val rebuildSignal = if (detectRebuildSignals) when {
            line.contains("sendFullScreenDisableArea") -> "full-screen"
            line.contains("sendToolBarDisableArea") -> "toolbar"

            line.contains("HandWritePresenter") && line.contains("DisableWriteArea") -> "doc-write-area"
            line.contains("sendWritable: true") -> "doc-writable-true"
            else -> null
        } else null
        if (rebuildSignal != null) {

            val hasSnapshotHeader = line.contains("rectList.size()")
            val currentListener = synchronized(lock) { if (isCurrent(myGen)) listener else null }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "host pen table rebuild signal=$rebuildSignal " +
                    "snapshotHeader=$hasSnapshotHeader line=$line")
            }
            if (rebuildSignal == "doc-write-area") currentListener?.onDocWriteAreaRebuilt(myGen)
            if (rebuildSignal == "doc-writable-true") currentListener?.onDocWritableEnabled(myGen)
            if (!hasSnapshotHeader) {
                pendingNativeAreaCount = -1
                pendingNativeAreas.clear()
                currentListener?.onHostFullScreenDisableArea(myGen)
                return true
            }
            currentListener?.onHostFullScreenDisableArea(myGen)
        }
        val size = RECT_LIST_SIZE_REGEX.find(line)
        if (size != null) {
            pendingNativeAreaCount = size.groupValues[1].toInt()
            pendingNativeAreas.clear()
            if (pendingNativeAreaCount == 0) notifyNativePenAreas(myGen)
            return true
        }
        val item = RECT_LIST_ITEM_TUPLE_REGEX.find(line)
            ?: RECT_LIST_ITEM_SPACED_REGEX.find(line)
            ?: return false
        if (pendingNativeAreaCount < 0) return true
        val index = item.groupValues[1].toInt()
        val left = item.groupValues[2].toInt()
        val top = item.groupValues[3].toInt()
        val width = item.groupValues[4].toInt()
        val height = item.groupValues[5].toInt()
        val right = left.toLong() + width
        val bottom = top.toLong() + height

        if (width == 0 && height == 0) {
            pendingNativeAreas[index] = Rect()
            if (pendingNativeAreas.size >= pendingNativeAreaCount) notifyNativePenAreas(myGen)
            return true
        }
        if (width <= 0 || height <= 0 ||
            right !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            bottom !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(TAG, "invalid native pen area: index=$index x=$left y=$top width=$width height=$height")
            }
            pendingNativeAreaCount = -1
            pendingNativeAreas.clear()
            return true
        }

        pendingNativeAreas[index] = Rect(left, top, right.toInt(), bottom.toInt())
        if (pendingNativeAreas.size >= pendingNativeAreaCount) notifyNativePenAreas(myGen)
        return true
    }

    private fun notifyNativePenAreas(myGen: Int, directSnapshot: List<Rect>? = null) {
        val count = pendingNativeAreaCount

        val snapshot = directSnapshot?.map(::Rect)
            ?: (0 until count).mapNotNull { pendingNativeAreas[it]?.takeIf { area -> !area.isEmpty }?.let(::Rect) }
        val received = if (directSnapshot == null) (0 until count).count { pendingNativeAreas[it] != null } else snapshot.size
        if (directSnapshot == null && BuildConfig.ENABLE_DEBUG && received < count) {
            Log.w(TAG, "native pen areas incomplete: declared=$count received=$received")
        }
        pendingNativeAreaCount = -1
        pendingNativeAreas.clear()
        val currentListener = synchronized(lock) { if (isCurrent(myGen)) listener else null }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "native pen areas changed: $snapshot")
        currentListener?.onNativePenAreasChanged(snapshot, myGen)

        val menuFullScreen = snapshot.any(PenAreaGeometry::isHostFullScreenRect)
        if (!menuFullScreen) applyOp(myGen, Op.ToolbarMenu(false), "native pen area returned to normal")
    }

    private fun ensurePopupPoller(myGen: Int) {
        synchronized(lock) {
            if (!isCurrent(myGen) || popupPollerRunning) return
            if (openSubviews.none { it in POPUP_CLASSES }) return
            popupPollerRunning = true
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "popup poller started (gen=$myGen)")
        Thread({ popupPollLoop(myGen) }, "$TAG-popup-g$myGen").apply {
            isDaemon = true
            start()
        }
    }

    private fun popupPollLoop(myGen: Int) {
        var failures = 0
        var emptyRounds = 0
        val missCounts = mutableMapOf<String, Int>()
        while (true) {
            try { Thread.sleep(POPUP_POLL_MS) } catch (_: InterruptedException) {}
            val pending: List<String>
            synchronized(lock) {
                if (!isCurrent(myGen)) {
                    popupPollerRunning = false
                    return
                }
                pending = openSubviews.filter { it in POPUP_CLASSES }
            }
            if (pending.isEmpty()) {
                emptyRounds++
                if (emptyRounds > 1) {
                    synchronized(lock) { popupPollerRunning = false }
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "popup poller exit: no pending popups (gen=$myGen)")
                    return
                }
            } else {
                emptyRounds = 0
            }
            val targets = (pending + WATCH_CLASSES).distinct()
            val visible = queryVisibleViews(targets)
            if (visible == null) {
                failures++
                if (failures >= POPUP_POLL_MAX_FAILURES) {

                    Log.e(TAG, "popup poll: dumpsys failed x$failures, fail-open clearing $pending")
                    for (name in pending) applyOp(myGen, Op.Clear(name), "popup-poll fail-open: $name")
                }
                continue
            }
            failures = 0
            for (name in pending) {
                if (name in visible) {
                    missCounts.remove(name)
                } else {
                    val misses = (missCounts[name] ?: 0) + 1
                    missCounts[name] = misses
                    if (misses >= POPUP_MISS_THRESHOLD) {
                        applyOp(myGen, Op.Clear(name), "popup-poll: $name not visible for $misses rounds")
                        missCounts.remove(name)
                    }
                }
            }

            for (watch in WATCH_CLASSES) {
                if (watch in visible && watch !in pending) {
                    applyOp(myGen, Op.Add(watch), "popup-poll: discovered $watch visible")
                }
            }
        }
    }

    private fun queryVisibleViews(targets: List<String>): Set<String>? {
        return try {
            val proc = ProcessBuilder("dumpsys", "activity", "top")
                .redirectErrorStream(true).start()
            val found = mutableSetOf<String>()
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    for (t in targets) {
                        val idx = line.indexOf("$t{")
                        if (idx < 0) continue
                        val sp = line.indexOf(' ', idx)
                        if (sp > 0 && sp + 1 < line.length && line[sp + 1] == 'V') found.add(t)
                        if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[popup-poll] $t: ${line.trim().take(120)}")
                    }
                }
            }
            proc.waitFor()
            found
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "popup poll dumpsys failed: ${e.message}")
            null
        }
    }

    private fun applyOp(myGen: Int, op: Op, line: String) {
        if (op is Op.ToolbarMenu) {
            var menuIsPluginList = false
            val l = synchronized(lock) {
                if (!isCurrent(myGen)) return
                toolbarMenuRevision++
                if (toolbarMenuOpen == op.open) return
                toolbarMenuOpen = op.open
                menuIsPluginList = if (op.open) {
                    toolbarMenuPluginList = op.pluginListMenu
                    op.pluginListMenu
                } else {
                    toolbarMenuPluginList.also { toolbarMenuPluginList = false }
                }
                listener
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu changed: open=${op.open} pluginList=$menuIsPluginList")
            l?.onToolbarMenuChanged(op.open, menuIsPluginList, myGen)
            return
        }
        if (op is Op.MovedLassoGone) {
            val l = synchronized(lock) {
                if (!isCurrent(myGen)) return
                lassoRelocating = false
                listener
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "moved lasso selection gone")
            l?.onMovedLassoGone(myGen)
            return
        }
        if (op is Op.LassoMenu) {
            if (op.open) observedLassoDisplayRect = null
            val l = synchronized(lock) {
                if (!isCurrent(myGen) || lassoMenuOpen == op.open) return
                lassoMenuOpen = op.open
                listener
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "lasso menu changed: open=${op.open}")
            l?.onLassoMenuChanged(op.open, myGen)
            return
        }
        if (op is Op.ClearAll) {
            val lassoListener = synchronized(lock) {
                if (!isCurrent(myGen) || !lassoMenuOpen) null else {
                    lassoMenuOpen = false
                    listener
                }
            }
            if (lassoListener != null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "lasso menu changed: open=false (clear all)")
                lassoListener.onLassoMenuChanged(false, myGen)
            }
        }
        val nowOpen: Boolean
        val snapshot: String
        val l: Listener?
        synchronized(lock) {
            if (!isCurrent(myGen)) return
            val wasOpen = openSubviews.isNotEmpty()
            when (op) {
                is Op.ShowExclusive -> {

                    openSubviews.clear()
                    openSubviews.add(op.name)
                }
                is Op.Add -> openSubviews.add(op.name)
                is Op.Clear -> openSubviews.remove(op.name)
                Op.ClearAll -> openSubviews.clear()
                is Op.ToolbarMenu -> return
                is Op.LassoMenu -> return
                Op.MovedLassoGone -> return
            }
            nowOpen = openSubviews.isNotEmpty()
            anyOpen = nowOpen
            snapshot = openSubviews.toString()
            if (wasOpen == nowOpen) {
                if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "op on [$line] -> open=$snapshot (no edge)")
                return
            }
            l = listener
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "subview state changed: open=$nowOpen ($snapshot)")
        if (l == null) return

        if (nowOpen) l.onSubviewOpened(myGen) else l.onSubviewClosed(myGen)
    }
}
