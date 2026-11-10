package me.laumss.notipal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dalvik.system.PathClassLoader
import java.io.File
import java.util.concurrent.Executors

internal object MosaicNotePreparation {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Notipal-Mosaic-prepare").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun convert(ctx: com.facebook.react.bridge.ReactApplicationContext, source: File, pages: List<Int>?,
                onProgress: (Int, Int) -> Unit, onFinalProgress: (Int) -> Unit,
                cancelled: () -> Boolean, notePath: String? = null,
                done: (me.laumss.notipal.render.NoteMosaicExport.Exported?, String?) -> Unit) {
        worker.execute {
            val opened = runCatching {
                Pipeline(openPreparer(ctx), source, onFinalProgress, cancelled, onePerPage = notePath != null)
            }
            main.post {
                val pipeline = opened.getOrElse { done(null, it.toString()); return@post }
                val finished: (me.laumss.notipal.render.NoteMosaicExport.Exported?, String?) -> Unit = { exported, error ->

                    worker.execute {
                        if (exported == null) pipeline.abort()
                        main.post { done(exported, error) }
                    }
                }
                if (notePath == null) me.laumss.notipal.render.NoteMosaicExport.exportCurrentNote(ctx, source, pages, onProgress, cancelled, pipeline, finished)
                else me.laumss.notipal.render.NoteMosaicExport.exportNote(ctx, notePath, source, pages, onProgress, cancelled, pipeline, finished)
            }
        }
    }

    private fun openPreparer(ctx: Context): Class<*> {
        val managerType = Class.forName("com.ratta.supernote.pluginhost.manager.PluginManager", false, ctx.classLoader)
        val manager = managerType.getMethod("getInstance").invoke(null)
        val app = managerType.getMethod("getPluginApp", String::class.java).invoke(manager, "mosaic20260730ab")
            ?: error("Mosaic unavailable")
        val field = app.javaClass.getDeclaredField("nativeCodePackage").apply { isAccessible = true }
        val apk = File(field.get(app) as String)
        check(apk.isFile) { "Mosaic native package unavailable" }
        return Class.forName("me.laumss.mosaic.note.PreparedNoteImport", true, PathClassLoader(apk.absolutePath, managerType.classLoader))
    }

    internal class Pipeline(preparer: Class<*>, source: File, onProgress: (Int) -> Unit,
                            val cancelled: () -> Boolean, onePerPage: Boolean = false) : me.laumss.notipal.render.NoteMosaicExport.PageSink {
        private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        private val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        private val ended = java.util.concurrent.CountDownLatch(1)
        private val queue = java.util.concurrent.ArrayBlockingQueue<Map<String, Any>>(1)
        private fun isCancelled() = stopped.get() || cancelled()
        private fun checkActive() {
            error.get()?.let { throw it }
            if (isCancelled()) throw java.io.InterruptedIOException("cancelled")
            check(ended.count > 0) { "Note conversion session closed" }
        }
        init {
            @Suppress("UNCHECKED_CAST")
            val session = (if (onePerPage) {
                preparer.getMethod("openCardSession", String::class.java, java.util.function.BooleanSupplier::class.java)
                    .invoke(null, source.absolutePath, java.util.function.BooleanSupplier { isCancelled() })
            } else {
                preparer.getMethod("openPageSession", String::class.java,
                    java.util.function.IntConsumer::class.java, java.util.function.BooleanSupplier::class.java)
                    .invoke(null, source.absolutePath,
                        java.util.function.IntConsumer { percent -> main.post { if (!isCancelled()) onProgress(percent) } },
                        java.util.function.BooleanSupplier { isCancelled() })
            }) as java.util.function.Consumer<Map<String, Any>>
            Thread({
                try {
                    while (true) {
                        if (isCancelled()) throw java.io.InterruptedIOException("cancelled")
                        val message = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        session.accept(message)
                        if (message["op"] == "finish") break
                    }
                } catch (failure: Throwable) {
                    error.set(failure)
                    runCatching { session.accept(mapOf("op" to "abort")) }
                } finally {
                    queue.clear(); ended.countDown()
                }
            }, "Notipal-Mosaic-pages").apply { isDaemon = true }.start()
        }
        override fun accept(page: Map<String, Any>) {
            while (true) {
                checkActive()
                if (queue.offer(page, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    error.get()?.let { throw it }
                    return
                }
            }
        }
        override fun finish() {
            accept(mapOf("op" to "finish"))
            ended.await()
            error.get()?.let { throw it }
        }
        override fun abort() { stopped.set(true); ended.await() }
    }

    fun discard(source: File) {
        val stage = File(source.parentFile, source.nameWithoutExtension + ".prepared")
        if (source.name.startsWith("inkling-note-") && source.extension == "pages") stage.deleteRecursively()
        source.delete()
    }
}
