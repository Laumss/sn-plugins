package me.laumss.notipal.relay.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.ArrayDeque

class FormulaImageRenderer(private val context: Context) {

    companion object {
        private const val TAG = "FormulaImage"
        private const val RENDER_TIMEOUT_MS = 25_000L
        private const val ENGINE_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var engineReady = false

    private class Job(
        val latex: String,
        val onResult: (String) -> Unit,
        val onError: (String) -> Unit,
    )

    private val queue = ArrayDeque<Job>()
    private var active: Job? = null
    private var timeoutRunnable: Runnable? = null

    fun render(latex: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (latex.isBlank()) { handler.post { onError("empty latex") }; return }
        handler.post {
            queue.add(Job(latex, onResult, onError))
            ensureWebView()
            pump()
        }
    }

    fun warmup() { handler.post { ensureWebView() } }

    fun release() { handler.post { dropEngine("released") } }

    private fun dropEngine(reason: String) {
        webView?.destroy()
        webView = null
        engineReady = false
        failAll(reason)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null) return
        try {
            Log.i(TAG, "webview: uid=${Process.myUid()} pid=${Process.myPid()} provider=${providerState()}")
            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = true
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                    Log.i(TAG, "JS[${cm.lineNumber()}] ${cm.message()}")
                    return true
                }
            }
            wv.addJavascriptInterface(JsBridge(), "AndroidFormula")
            wv.loadUrl("file:///android_asset/formula/index.html")
            webView = wv
            handler.postDelayed({
                if (!engineReady && webView === wv) dropEngine("engine timeout (assets/formula/tex-svg.js missing or broken?)")
            }, ENGINE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "WebView init failed: ${e.message}", e)
            dropEngine("webview-init: ${e.message}")
        }
    }

    private fun providerState(): String = try {
        val field = Class.forName("android.webkit.WebViewFactory").getDeclaredField("sProviderInstance")
        field.isAccessible = true
        if (field.get(null) != null) "present" else "none"
    } catch (e: Throwable) {
        "unknown (${e.javaClass.simpleName})"
    }

    private fun pump() {
        if (active != null || !engineReady) return
        val job = queue.poll() ?: return
        active = job
        val b64 = Base64.encodeToString(job.latex.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        webView?.evaluateJavascript("renderFormulaB64('$b64')", null)
        timeoutRunnable = Runnable {
            if (active === job) {
                active = null
                job.onError("formula render timeout")
                pump()
            }
        }.also { handler.postDelayed(it, RENDER_TIMEOUT_MS) }
    }

    private fun finishActive(block: (Job) -> Unit) {
        handler.post {
            val job = active ?: return@post
            active = null
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = null
            block(job)
            pump()
        }
    }

    private fun failAll(msg: String) {
        active?.onError?.invoke(msg)
        active = null
        while (queue.isNotEmpty()) queue.poll()?.onError?.invoke(msg)
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onPageLoaded() { Log.i(TAG, "formula page loaded") }

        @JavascriptInterface
        fun onLog(msg: String) { Log.i(TAG, "flog: $msg") }

        @JavascriptInterface
        fun onEngineReady() {
            Log.i(TAG, "MathJax engine ready")
            handler.post {
                engineReady = true
                pump()
            }
        }

        @JavascriptInterface
        fun onResult(json: String) {
            Log.i(TAG, "render ok, ${json.length} bytes")
            finishActive { it.onResult(json) }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            Log.e(TAG, "render error: $msg")
            if (msg == "mathjax-load-failed") handler.post { dropEngine("MathJax load failed (bundle assets/formula/tex-svg.js)") }
            else finishActive { it.onError(msg) }
        }
    }
}
