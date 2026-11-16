package me.laumss.notipal.relay

import me.laumss.notipal.common.App
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.LongFlows
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostPlugins
import me.laumss.notipal.host.PageRender
import me.laumss.notipal.host.Pdf
import me.laumss.notipal.links.DocuCraftLink
import me.laumss.notipal.mosaic.MosaicImport
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.PagePicker
import me.laumss.notipal.ui_common.PageSource
import me.laumss.notipal.ui_common.ProgressPanel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal object RelayPages {
    private const val TAG = "RelayPages"
    private const val KEEP_MS = 24L * 60 * 60 * 1000

    val busy: Boolean get() = LongFlows.running(LongFlows.RELAY_PAGES)

    fun releaseForNewModule() = LongFlows.releaseForNewModule(LongFlows.RELAY_PAGES)

    fun attach(noteAsImages: Boolean, onResult: (files: List<File>, pages: Int) -> Unit) {
        Main.assertMain()
        if (LongFlows.busy) return
        val react = App.react ?: return
        val run = LongFlows.begin(LongFlows.RELAY_PAGES) ?: return
        val cancelled = AtomicBoolean(false)
        val progress = ProgressPanel()
        var finished = false

        fun finish(messageKey: String?, files: List<File>, count: Int) {
            if (finished) return
            finished = true
            progress.hide()
            LongFlows.end(run)
            val done = {
                Toolbar.restore("relay pages ended")
                onResult(files, count)
            }
            if (messageKey == null) done() else Dialogs.result(Strings.get(messageKey)) { done() }
        }
        HostPlugins.requestStoragePermissions(
            what = "AIRelay pages", showDeniedFeedback = false,
            onGranted = {
                PageRender.callHost(react, "getCurrentFilePath", emptyList()) { pathValue, pathError ->
                    val path = (pathValue as? String)?.takeIf { File(it).isFile } ?: run {
                        Log.w(TAG, "[RELAY-PAGES] no readable file: value=$pathValue error=$pathError")
                        return@callHost finish("relay_pages_no_file", emptyList(), 0)
                    }
                    val note = path.endsWith(".note", ignoreCase = true)
                    if (!note && !path.endsWith(".pdf", ignoreCase = true)) {
                        return@callHost finish("relay_pages_not_pdf", emptyList(), 0)
                    }
                    val countMethod = if (note) "getNoteTotalPageNum" else "getCurrentTotalPages"
                    val countArgs: List<Any?> = if (note) listOf(path) else emptyList()
                    PageRender.callHost(react, countMethod, countArgs) { totalValue, totalError ->
                        val total = (totalValue as? Number)?.toInt()?.takeIf { it > 0 } ?: run {
                            Log.w(TAG, "[RELAY-PAGES] page count unavailable: $totalError")
                            return@callHost finish("relay_pages_no_file", emptyList(), 0)
                        }
                        PageRender.callHost(react, "getCurrentPageNum", emptyList()) { pageValue, _ ->
                            val current = (pageValue as? Number)?.toInt()?.takeIf { it in 0 until total } ?: 0
                            PagePicker(PageSource(path, total, current, title = Strings.get("relay_pages_pick_title"))).open(
                                onPicked = { pages ->
                                    make(path, pages, total, note && noteAsImages, progress, cancelled) { key, files, count ->
                                        finish(key, files, count)
                                    }
                                },
                                onCancel = { finish(null, emptyList(), 0) },
                            ) { shown -> if (!shown) finish("relay_pages_failed", emptyList(), 0) }
                        }
                    }
                }
            },
            onDenied = { finish("file_read_permission_needed", emptyList(), 0) },
        )
    }

    private fun make(
        path: String,
        pages: List<Int>,
        total: Int,
        asImages: Boolean,
        progress: ProgressPanel,
        cancelled: AtomicBoolean,
        finish: (String?, List<File>, Int) -> Unit,
    ) {
        val react = App.react ?: return finish("relay_pages_failed", emptyList(), 0)
        val note = path.endsWith(".note", ignoreCase = true)
        val root = File(Dirs.aiCapture, "pages")
        val dir = File(root, System.currentTimeMillis().toString())
        val base = File(path).nameWithoutExtension.take(60)
        val ranges = MosaicImport.pageRanges(pages).replace("–", "-").replace(", ", ",")
        val output = File(dir, if (pages.size >= total) "$base.pdf" else "$base p$ranges".take(96) + ".pdf")
        var cancelExport: () -> Unit = {}
        progress.open(
            title = Strings.get(if (note) "relay_tool_pages_note" else "relay_tool_pages_doc"),
            status = Strings.get(if (asImages) "relay_pages_reading" else "relay_pages_making"),
            onCancel = {
                cancelled.set(true)
                cancelExport()
                finish(null, emptyList(), 0)
            },
        ) shown@{ shown ->
            if (!shown) {
                finish("relay_pages_failed", emptyList(), 0)
                return@shown
            }

            fun convert(source: File, notice: String?, temp: File?) {
                thread(name = "Notipal-relay-pages", isDaemon = true) {

                    PluginIdentity.claim()

                    root.listFiles()?.forEach {
                        if (System.currentTimeMillis() - it.lastModified() > KEEP_MS) it.deleteRecursively()
                    }
                    if (!dir.mkdirs()) {
                        temp?.deleteRecursively()
                        Main.post { finish("relay_pages_failed", emptyList(), 0) }
                        return@thread
                    }
                    val progressKey = if (asImages) "relay_pages_reading_progress" else "relay_pages_progress"
                    val onPage = { done: Int -> progress.setStatus(Strings.get(progressKey, done + 1, pages.size)) }
                    val images = ArrayList<File>()
                    val result = when {
                        asImages -> Pdf.noteImages(react, path, pages, dir, images, { cancelled.get() }, onPage)
                        note -> Pdf.fromNote(react, path, pages, output, { cancelled.get() }, onPage)
                        else -> Pdf.fromPdf(source, pages, output, { cancelled.get() }, onPage)
                    }
                    val files: List<File> = if (asImages) images else listOf(output)
                    temp?.deleteRecursively()
                    val made = result == Pdf.Result.OK && !cancelled.get()
                    val size = if (asImages) "${images.size} images" else "${output.length()} bytes"
                    Log.i(TAG, "[RELAY-PAGES] ${pages.size}/$total pages of ${source.name}: $result, $size")
                    if (!made) dir.deleteRecursively()
                    Main.post {
                        when {
                            made -> finish(notice, files, pages.size)
                            cancelled.get() || result == Pdf.Result.CANCELLED -> finish(null, emptyList(), 0)
                            result == Pdf.Result.TOO_LARGE -> finish("relay_pages_too_large", emptyList(), 0)
                            else -> finish("relay_pages_failed", emptyList(), 0)
                        }
                    }
                }
            }
            val original = File(path)
            val annotated = !note && File("$path.mark").isFile && DocuCraftLink.installed()
            if (!annotated) {
                convert(original, null, null)
                return@shown
            }
            val exports = File(App.context.cacheDir, "notipal_relay_pages")
            exports.deleteRecursively()
            val temp = File(exports, System.nanoTime().toString())
            if (!temp.mkdirs()) {
                convert(original, "relay_pages_plain", null)
                return@shown
            }
            val exported = File(temp, original.name)
            progress.setStatus(Strings.get("doc_share_exporting"))

            cancelExport = DocuCraftLink.exportAnnotated(App.context, path, exported.absolutePath, {
                !MosaicLink.boardVisible && DocuCraftLink.show()
            }) { failure ->
                if (cancelled.get()) {
                    temp.deleteRecursively()
                    return@exportAnnotated
                }
                progress.setStatus(Strings.get("relay_pages_making"))
                if (failure == null && exported.length() > 0L) {
                    convert(exported, null, temp)
                } else {
                    temp.deleteRecursively()
                    Log.w(TAG, "[RELAY-PAGES] annotated export failed: $failure")
                    convert(original, when {
                        failure == DocuCraftLink.ExportFailure.OUTDATED -> "relay_pages_plain_outdated"
                        failure == DocuCraftLink.ExportFailure.CANNOT_OPEN && MosaicLink.boardVisible -> null
                        else -> "relay_pages_plain"
                    }, null)
                }
            }
        }
    }
}
