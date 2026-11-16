package me.laumss.notipal.mosaic

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.LongFlows
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.MosaicPermissionResult
import me.laumss.notipal.host.PageRender
import me.laumss.notipal.links.DocLinks
import me.laumss.notipal.links.DocuCraftLink
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.BrowseDirs
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.FilePicker
import me.laumss.notipal.ui_common.FilePickerConfig
import me.laumss.notipal.ui_common.PagePicker
import me.laumss.notipal.ui_common.PageSource
import me.laumss.notipal.ui_common.ProgressPanel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object MosaicImport {
    private const val TAG = "MosaicImport"

    private const val MOSAIC_CLOSE_POLLS = 30
    private const val MOSAIC_CLOSE_POLL_MS = 100L
    private const val MOSAIC_CLOSE_SETTLE_MS = 400L

    private const val RESULT_POLL_MS = 300L

    val busy: Boolean get() = LongFlows.running(LongFlows.MOSAIC_IMPORT)

    internal fun attach(module: Any) {
        owner = module
        Tools.handle(Tools.NOTE_TO_MOSAIC, onTap = { Main.post { importCurrentNote() } })
        Tools.handle(Tools.DOC_TO_MOSAIC, onTap = { Main.post { importDocPages() } })
        MosaicLink.boardImportHandler = ::onBoardImportRequested
        LongFlows.releaseForNewModule(LongFlows.MOSAIC_IMPORT)
    }

    internal fun detach(module: Any) {
        if (owner !== module) return
        owner = null
        MosaicLink.boardImportHandler = null
    }

    @Volatile private var owner: Any? = null

    private fun begin(): LongFlows.Run? = LongFlows.begin(LongFlows.MOSAIC_IMPORT)

    private fun end(job: LongFlows.Run) = LongFlows.end(job)

    private fun importCurrentNote() {

        if (HostSession.state.document || MosaicLink.boardVisible) return
        val job = begin() ?: return
        val cancelled = AtomicBoolean(false)
        val progress = ProgressPanel()
        var finished = false

        fun finish(messageKey: String?) {
            if (finished) return
            finished = true
            progress.hide()
            end(job)
            if (messageKey == null) Toolbar.restore("note import ended")
            else Dialogs.result(Strings.get(messageKey)) { Toolbar.restore("note import ended") }
        }
        val finishFailed = { finish("mosaic_note_import_failed") }
        val react = App.react ?: return finishFailed()
        HostPlugins.requestStoragePermissions(
            what = Strings.get("mosaic_note_import"), showDeniedFeedback = false,
            onGranted = {
                HostPlugins.requestMosaicPluginPermissions { mosaicResult ->
                    if (mosaicResult != MosaicPermissionResult.GRANTED) {
                        finish(if (mosaicResult == MosaicPermissionResult.PLUGIN_UNAVAILABLE)
                            "mosaic_plugin_needed" else "mosaic_permission_needed")
                        return@requestMosaicPluginPermissions
                    }
                    PageRender.callHost(react, "getCurrentFilePath", emptyList()) { pathValue, _ ->
                        val path = (pathValue as? String)?.takeIf { it.endsWith(".note", ignoreCase = true) }
                            ?: return@callHost finishFailed()
                        PageRender.callHost(react, "getNoteTotalPageNum", listOf(path)) { totalValue, totalError ->
                            val total = (totalValue as? Number)?.toInt()?.takeIf { it > 0 } ?: run {
                                Log.w(TAG, "[NOTE-MOSAIC] page count unavailable: $totalError")
                                return@callHost finishFailed()
                            }
                            PageRender.callHost(react, "getCurrentPageNum", emptyList()) { pageValue, _ ->
                                val currentPage = (pageValue as? Number)?.toInt()?.takeIf { it in 0 until total } ?: 0
                                PagePicker(PageSource(path, total, currentPage)).open(
                                    onPicked = { pages ->
                                        convertNote(react, path, pages, total, progress, cancelled, ::finish) {
                                            finished = true
                                            end(job)
                                        }
                                    },
                                    onCancel = { finish(null) },
                                    onShown = { shown -> if (!shown) finishFailed() },
                                )
                            }
                        }
                    }
                }
            }, onDenied = finishFailed,
        )
    }

    private fun convertNote(
        react: ReactApplicationContext,
        path: String,
        pages: List<Int>,
        total: Int,
        progress: ProgressPanel,
        cancelled: AtomicBoolean,
        finish: (String?) -> Unit,
        imported: () -> Unit,
    ) {
        val pagesFile = MosaicLink.newNoteImportFile() ?: return finish("mosaic_note_import_failed")
        val name = File(path).nameWithoutExtension
        val title = if (pages.size >= total) name else name + "\n" + Strings.get("doc_mosaic_pages_label", pageRanges(pages))
        progress.open(
            title = Strings.get("mosaic_note_converting"),
            status = Strings.get("mosaic_note_importing"),
            onCancel = { cancelled.set(true) },
        ) shown@{ shown ->
            if (!shown) { finish("mosaic_note_import_failed"); return@shown }
            MosaicNotePreparation.convert(react, pagesFile, pages,
                onProgress = { page, count ->
                    progress.setStatus(Strings.get("mosaic_note_import_progress", page, count))
                    Log.i(TAG, "[NOTE-MOSAIC] reading page=$page/$count")
                },
                onFinalProgress = { percent -> progress.setStatus(Strings.get("mosaic_note_finalizing", percent)) },
                cancelled = { cancelled.get() },
            ) converted@{ exported, error ->
                if (exported == null || cancelled.get()) {
                    Log.w(TAG, "[NOTE-MOSAIC] conversion stopped: $error")
                    MosaicLink.discardNotePayload(pagesFile)
                    finish(if (cancelled.get()) null else "mosaic_note_import_failed")
                    return@converted
                }
                progress.hide()

                if (!MosaicLink.enqueueNote(pagesFile, title, "inkling-note-import")) {
                    MosaicLink.discardNotePayload(pagesFile)
                    finish("mosaic_note_import_failed")
                    return@converted
                }
                awaitResult(pagesFile) { outcome ->
                    if (outcome == NoteImportCompletion.Outcome.SUCCESS) imported()
                    else finish(if (outcome == NoteImportCompletion.Outcome.OPEN_FAILED)
                        "mosaic_note_open_failed" else "mosaic_note_import_failed")
                }
                HostPlugins.openMosaicPluginForPending()
            }
        }
    }

    private fun importDocPages() {
        if (!HostSession.isDocumentContext()) return
        val job = begin() ?: return
        var finished = false

        fun finish(messageKey: String?) {
            if (finished) return
            finished = true
            end(job)
            if (messageKey == null) Toolbar.restore("doc import ended")
            else Dialogs.result(Strings.get(messageKey)) { Toolbar.restore("doc import ended") }
        }
        val react = App.react ?: return finish("doc_mosaic_import_failed")
        HostPlugins.requestStoragePermissions(
            what = Strings.get("doc_mosaic_import"), showDeniedFeedback = false,
            onGranted = {
                HostPlugins.requestMosaicPluginPermissions { mosaicResult ->
                    if (mosaicResult != MosaicPermissionResult.GRANTED) {
                        finish(if (mosaicResult == MosaicPermissionResult.PLUGIN_UNAVAILABLE)
                            "mosaic_plugin_needed" else "mosaic_permission_needed")
                        return@requestMosaicPluginPermissions
                    }
                    PageRender.callHost(react, "getCurrentFilePath", emptyList()) { pathValue, _ ->
                        val path = (pathValue as? String)?.takeIf {
                            it.endsWith(".pdf", ignoreCase = true) && File(it).isFile
                        } ?: return@callHost offerDocuCraftConversion(::finish) {
                            finished = true
                            end(job)
                        }
                        PageRender.callHost(react, "getCurrentTotalPages", emptyList()) { totalValue, totalError ->
                            val total = (totalValue as? Number)?.toInt()?.takeIf { it > 0 } ?: run {
                                Log.w(TAG, "[DOC-MOSAIC] page count unavailable: $totalError")
                                return@callHost finish("doc_mosaic_import_failed")
                            }
                            PageRender.callHost(react, "getCurrentPageNum", emptyList()) { pageValue, _ ->
                                val currentPage = (pageValue as? Number)?.toInt()?.takeIf { it in 0 until total } ?: 0
                                PagePicker(PageSource(path, total, currentPage)).open(
                                    onPicked = { pages ->
                                        exportDocPages(react, path, pages, ::finish) { finished = true; end(job) }
                                    },
                                    onCancel = { finish(null) },
                                    onShown = { shown -> if (!shown) finish("doc_mosaic_import_failed") },
                                )
                            }
                        }
                    }
                }
            }, onDenied = { finish("doc_mosaic_import_failed") },
        )
    }

    private fun exportDocPages(
        react: ReactApplicationContext,
        path: String,
        pages: List<Int>,
        finish: (String?) -> Unit,
        imported: () -> Unit,
    ) {
        val outDir = MosaicLink.newDocImportDir() ?: return finish("doc_mosaic_import_failed")
        val name = File(path).nameWithoutExtension

        val title = name + "\n" + Strings.get("doc_mosaic_pages_label", pageRanges(pages))
        val cancelled = AtomicBoolean(false)
        val progress = ProgressPanel()
        progress.open(
            title = Strings.get("mosaic_note_converting"),
            status = Strings.get("doc_mosaic_importing"),
            onCancel = { cancelled.set(true) },
        ) shown@{ shown ->
            if (!shown) { finish("doc_mosaic_import_failed"); return@shown }
            DocMosaicExport.export(react, path, pages, outDir, title,
                onProgress = { page, total -> progress.setStatus(Strings.get("doc_mosaic_progress", page, total)) },
                cancelled = { cancelled.get() },
            ) { ok, error ->
                progress.hide()
                if (!ok || cancelled.get()) {
                    Log.w(TAG, "[DOC-MOSAIC] conversion stopped: $error")
                    outDir.deleteRecursively()
                    finish(when {
                        cancelled.get() -> null

                        error?.contains("page does not exist", ignoreCase = true) == true -> "doc_last_page_host_limit"
                        else -> "doc_mosaic_import_failed"
                    })
                    return@export
                }
                if (!MosaicLink.enqueueDoc(outDir, title, "inkling-doc-import")) {
                    outDir.deleteRecursively()
                    finish("doc_mosaic_import_failed")
                    return@export
                }
                awaitResult(outDir) { outcome ->
                    if (outcome == NoteImportCompletion.Outcome.SUCCESS) imported()
                    else finish(if (outcome == NoteImportCompletion.Outcome.OPEN_FAILED)
                        "mosaic_note_open_failed" else "doc_mosaic_import_failed")
                }
                HostPlugins.openMosaicPluginForPending()
            }
        }
    }

    private fun offerDocuCraftConversion(
        finish: (String?) -> Unit,
        askKey: String = "doc_mosaic_docucraft_ask",
        handedOff: () -> Unit,
    ) {
        val pdfOnly = Strings.get("doc_mosaic_pdf_only")
        if (!DocuCraftLink.installed()) {
            Dialogs.result(pdfOnly + "\n" + Strings.get("doc_mosaic_docucraft_intro")) { finish(null) }
            return
        }
        Dialogs.confirm(
            pdfOnly + "\n" + Strings.get(askKey),
            Strings.get("cancel"),
            Strings.get("doc_mosaic_open_docucraft"),
        ) { open ->
            if (!open) return@confirm finish(null)
            handedOff()
            Main.post {
                DocuCraftLink.openConversion(App.context) {
                    if (!DocuCraftLink.show()) {
                        Log.w(TAG, "[DOC-MOSAIC] DocuCraft did not open")
                        Dialogs.result(Strings.get("doc_mosaic_docucraft_open_failed")) { Toolbar.restore("docucraft not opened") }
                    }
                }
            }
        }
    }

    private fun onBoardImportRequested(kind: String, boardName: String, template: String): Boolean {
        val job = begin() ?: return false
        Main.post { importBoard(job, kind, boardName, template) }
        return true
    }

    private fun importBoard(job: LongFlows.Run, kind: String, boardName: String, template: String) {
        var finished = false

        fun finish(messageKey: String?) {
            if (finished) return
            finished = true
            end(job)
            if (messageKey == null) HostPlugins.openMosaicPluginForPending()
            else Dialogs.result(Strings.get(messageKey)) { HostPlugins.openMosaicPluginForPending() }
        }
        val imported = { finished = true; end(job) }
        val failedKey = if (kind == "doc") "doc_mosaic_import_failed" else "mosaic_note_import_failed"
        whenMosaicClosed {
            HostPlugins.requestStoragePermissions(
                what = Strings.get("mosaic_board_import"), showDeniedFeedback = false,
                onGranted = {
                    HostPlugins.requestMosaicPluginPermissions { mosaicResult ->
                        if (mosaicResult != MosaicPermissionResult.GRANTED) {
                            finish(if (mosaicResult == MosaicPermissionResult.PLUGIN_UNAVAILABLE)
                                "mosaic_plugin_needed" else "mosaic_permission_needed")
                            return@requestMosaicPluginPermissions
                        }
                        val react = App.react ?: return@requestMosaicPluginPermissions finish(failedKey)
                        FilePicker(
                            FilePickerConfig(
                                roots = BrowseDirs.documents,
                                titleKey = if (kind == "doc") "mosaic_board_pick_doc" else "mosaic_board_pick_note",
                                extensions = if (kind == "doc") DocLinks.DOC_EXTS - "note" else setOf("note"),
                                emptyKey = "doc_no_files",
                            )
                        ).open(
                            onPicked = { files ->
                                val path = files.firstOrNull()?.path
                                when {
                                    path == null -> finish(null)
                                    kind == "doc" -> pickDocPagesForBoard(react, path, boardName, template, ::finish, imported)
                                    else -> pickNotePagesForBoard(react, path, boardName, template, ::finish, imported)
                                }
                            },
                            onCancel = { finish(null) },
                            onShown = { shown -> if (!shown) finish(failedKey) },
                        )
                    }
                }, onDenied = { finish(failedKey) },
            )
        }
    }

    private fun whenMosaicClosed(attempt: Int = 0, then: () -> Unit) {
        if ((MosaicLink.boardVisible || HostSession.state.pluginViewOpen) && attempt < MOSAIC_CLOSE_POLLS) {
            Main.handler.postDelayed({ whenMosaicClosed(attempt + 1, then) }, MOSAIC_CLOSE_POLL_MS)
            return
        }
        Main.handler.postDelayed({ then() }, MOSAIC_CLOSE_SETTLE_MS)
    }

    private fun pickNotePagesForBoard(
        react: ReactApplicationContext,
        path: String,
        boardName: String,
        template: String,
        finish: (String?) -> Unit,
        imported: () -> Unit,
    ) {

        val failedKey = if (HostSession.state.document) "mosaic_board_note_in_doc" else "mosaic_note_import_failed"
        PageRender.callHost(react, "getNoteTotalPageNum", listOf(path)) { totalValue, totalError ->
            val total = (totalValue as? Number)?.toInt()?.takeIf { it > 0 } ?: run {
                Log.w(TAG, "[BOARD-IMPORT] note page count unavailable: $totalError")
                finish(failedKey)
                return@callHost
            }
            PagePicker(PageSource(path, total, -1, selectAll = true)).open(
                onPicked = { pages -> convertNoteForBoard(react, path, pages, boardName, template, failedKey, finish, imported) },
                onCancel = { finish(null) },
                onShown = { shown -> if (!shown) finish(failedKey) },
            )
        }
    }

    private fun convertNoteForBoard(
        react: ReactApplicationContext,
        path: String,
        pages: List<Int>,
        boardName: String,
        template: String,
        failedKey: String,
        finish: (String?) -> Unit,
        imported: () -> Unit,
    ) {
        val pagesFile = MosaicLink.newNoteImportFile() ?: return finish(failedKey)
        val name = File(path).nameWithoutExtension
        val cancelled = AtomicBoolean(false)
        val progress = ProgressPanel()
        progress.open(
            title = Strings.get("mosaic_note_converting"),
            status = Strings.get("mosaic_note_importing"),
            onCancel = { cancelled.set(true) },
        ) shown@{ shown ->
            if (!shown) { finish(failedKey); return@shown }
            MosaicNotePreparation.convert(react, pagesFile, pages,
                onProgress = { page, count -> progress.setStatus(Strings.get("mosaic_note_import_progress", page, count)) },
                onFinalProgress = {},
                cancelled = { cancelled.get() },
                notePath = path,
            ) converted@{ exported, error ->
                progress.hide()
                if (exported == null || cancelled.get()) {
                    Log.w(TAG, "[BOARD-IMPORT] note conversion stopped: $error")
                    MosaicLink.discardNotePayload(pagesFile)
                    finish(if (cancelled.get()) null else failedKey)
                    return@converted
                }

                val titles = pages.map { "$name ${Strings.get("doc_mosaic_pages_label", pageRanges(listOf(it)))}" }
                if (!MosaicLink.enqueueBoard("note", pagesFile, boardName.ifBlank { name }, template, titles, "inkling-board-import")) {
                    MosaicLink.discardNotePayload(pagesFile)
                    finish(failedKey)
                    return@converted
                }
                awaitBoardImport(pagesFile, failedKey, finish, imported)
            }
        }
    }

    private fun pickDocPagesForBoard(
        react: ReactApplicationContext,
        path: String,
        boardName: String,
        template: String,
        finish: (String?) -> Unit,
        imported: () -> Unit,
    ) {
        if (!path.endsWith(".pdf", ignoreCase = true)) {

            offerDocuCraftConversion(finish, askKey = "mosaic_board_docucraft_ask", handedOff = imported)
            return
        }
        Thread({
            val total = runCatching {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { it.pageCount }
                }
            }.onFailure { Log.w(TAG, "[BOARD-IMPORT] pdf unreadable: ${it.message}") }.getOrNull()
            Main.post {
                if (total == null || total <= 0) return@post finish("doc_mosaic_import_failed")
                PagePicker(PageSource(path, total, -1, selectAll = true)).open(
                    onPicked = { pages -> convertDocForBoard(react, path, pages, boardName, template, finish, imported) },
                    onCancel = { finish(null) },
                    onShown = { shown -> if (!shown) finish("doc_mosaic_import_failed") },
                )
            }
        }, "Notipal-board-import-pdf").start()
    }

    private fun convertDocForBoard(
        react: ReactApplicationContext,
        path: String,
        pages: List<Int>,
        boardName: String,
        template: String,
        finish: (String?) -> Unit,
        imported: () -> Unit,
    ) {
        val outDir = MosaicLink.newDocImportDir() ?: return finish("doc_mosaic_import_failed")
        val name = File(path).nameWithoutExtension
        val cancelled = AtomicBoolean(false)
        var annotationsSkipped = false
        val progress = ProgressPanel()
        progress.open(
            title = Strings.get("mosaic_note_converting"),
            status = Strings.get("doc_mosaic_importing"),
            onCancel = { cancelled.set(true) },
        ) shown@{ shown ->
            if (!shown) { finish("doc_mosaic_import_failed"); return@shown }
            DocMosaicExport.export(react, path, pages, outDir, name,
                onProgress = { page, total -> progress.setStatus(Strings.get("doc_mosaic_progress", page, total)) },
                cancelled = { cancelled.get() },
                onAnnotationsSkipped = { annotationsSkipped = true },
            ) { ok, error ->
                progress.hide()
                if (!ok || cancelled.get()) {
                    Log.w(TAG, "[BOARD-IMPORT] doc conversion stopped: $error")
                    outDir.deleteRecursively()
                    finish(if (cancelled.get()) null else "doc_mosaic_import_failed")
                    return@export
                }
                val publish = {

                    val titles = pages.map { name + "\n" + Strings.get("doc_mosaic_pages_label", pageRanges(listOf(it))) }
                    if (MosaicLink.enqueueBoard("doc", outDir, boardName.ifBlank { name }, template, titles, "inkling-board-import")) {
                        awaitBoardImport(outDir, "doc_mosaic_import_failed", finish, imported)
                    } else {
                        outDir.deleteRecursively()
                        finish("doc_mosaic_import_failed")
                    }
                }
                if (annotationsSkipped) Dialogs.result(Strings.get("mosaic_board_annotations_skipped")) { publish() } else publish()
            }
        }
    }

    private fun awaitBoardImport(payload: File, failedKey: String, finish: (String?) -> Unit, imported: () -> Unit) {
        awaitResult(payload) { outcome ->
            if (outcome == NoteImportCompletion.Outcome.SUCCESS) imported()
            else finish(if (outcome == NoteImportCompletion.Outcome.OPEN_FAILED) "mosaic_note_open_failed" else failedKey)
        }
        HostPlugins.openMosaicPluginForPending()
    }

    private fun awaitResult(payload: File, onDone: (NoteImportCompletion.Outcome) -> Unit) {
        val state = NoteImportCompletion(SystemClock.elapsedRealtime())
        val resultFile = MosaicLink.noteImportResultFile(payload)
        val requestId = resultFile.name.removeSuffix(".result")
        val poll = object : Runnable {
            override fun run() {
                val reported = runCatching {
                    if (!resultFile.isFile) null else JSONObject(resultFile.readText()).let {
                        if (it.optString("id") == requestId && it.has("success")) it.getBoolean("success") else null
                    }
                }.getOrNull()
                val outcome = state.observe(SystemClock.elapsedRealtime(), MosaicLink.boardVisible, reported)
                if (outcome == null) {
                    Main.handler.postDelayed(this, RESULT_POLL_MS)
                    return
                }
                if (outcome != NoteImportCompletion.Outcome.SUCCESS) MosaicLink.cancelPendingNoteImport(payload)
                resultFile.delete()
                Log.i(TAG, "[NOTE-MOSAIC] completed id=$requestId outcome=$outcome visible=${MosaicLink.boardVisible}")
                onDone(outcome)
            }
        }
        Main.handler.post(poll)
    }

    internal fun pageRanges(pages: List<Int>): String {
        val sorted = pages.distinct().sorted()
        val parts = ArrayList<String>()
        var start = 0
        for (i in sorted.indices) {
            if (i + 1 < sorted.size && sorted[i + 1] == sorted[i] + 1) continue
            parts.add(if (i == start) "${sorted[i] + 1}" else "${sorted[start] + 1}–${sorted[i] + 1}")
            start = i + 1
        }
        return parts.joinToString(", ")
    }
}
