package ai.closepaw.browser.script

import ai.closepaw.browser.cdp.ChromeCdpClient
import ai.closepaw.trace.TraceRecorder
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class BrowserScriptRunner(
    context: Context,
    private val cdpClient: ChromeCdpClient,
    private val traceRecorder: TraceRecorder,
    /**
     * Cumulative decoded-byte counter shared with the session-scoped owner
     * (BrowserSessionManager). Threaded through to [BrowserScriptJsInterface] so a
     * runaway script issuing repeated browser_script tool calls cannot bypass
     * [BrowserScriptJsInterface.MAX_BYTES_PER_SESSION] — a per-call counter would
     * reset every run() and turn the documented 500 MiB session cap into a
     * 500 MiB per-call cap. Defaults to a fresh counter so the androidTest
     * harness (single-run) works without plumbing.
     */
    private val sessionDecodedBytes: AtomicLong = AtomicLong(0L),
) {
    private val appContext: Context = context.applicationContext

    suspend fun run(script: String, timeoutMs: Long): ScriptResult {
        val mainHandler = Handler(Looper.getMainLooper())
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val webViewSlot = AtomicReference<WebView?>()
        // Set in finally BEFORE the drain post so any earlier-queued main-thread post
        // sees cancelled and either skips work or self-destroys a freshly-built WebView,
        // closing the leak window between "post queued" and "post executes".
        val cancelled = AtomicBoolean(false)
        var bridge: BrowserScriptBridge? = null

        try {
            val outcome: ScriptResult? = withTimeoutOrNull(timeoutMs) {
                val webViewReady = CompletableDeferred<WebView>()
                mainHandler.post {
                    if (cancelled.get()) return@post
                    val wv = try {
                        createHardenedWebView(appContext)
                    } catch (t: Throwable) {
                        webViewReady.completeExceptionally(t)
                        return@post
                    }
                    if (cancelled.get()) {
                        // Cancellation raced with creation; destroy what we just built.
                        try {
                            wv.destroy()
                        } catch (t: Throwable) {
                            Log.w(TAG, "Race-destroy of orphan WebView failed", t)
                        }
                        return@post
                    }
                    webViewSlot.set(wv)
                    webViewReady.complete(wv)
                }
                val wv = webViewReady.await()

                val evaluator = JsEvaluator { js ->
                    mainHandler.post { wv.evaluateJavascript(js, null) }
                }
                val theBridge = BrowserScriptBridge(cdpClient, evaluator, bridgeScope)
                bridge = theBridge
                val jsInterface = BrowserScriptJsInterface(
                    bridge = theBridge,
                    traceRecorder = traceRecorder,
                    sessionDecodedBytes = sessionDecodedBytes,
                )

                val pageReady = CompletableDeferred<Unit>()
                mainHandler.post {
                    if (cancelled.get()) return@post
                    try {
                        wv.addJavascriptInterface(jsInterface, BrowserScriptPrelude.BRIDGE_OBJECT_NAME)
                        wv.webViewClient = ScriptHostWebViewClient(wv, script, pageReady, cancelled)
                        wv.loadDataWithBaseURL(
                            "about:blank",
                            "<!doctype html><html><head></head><body></body></html>",
                            "text/html",
                            "utf-8",
                            null,
                        )
                    } catch (t: Throwable) {
                        pageReady.completeExceptionally(t)
                    }
                }
                pageReady.await()

                theBridge.awaitResult()
            }
            return outcome ?: run {
                bridge?.completeWithTimeout(timeoutMs)
                ScriptResult.Timeout(timeoutMs)
            }
        } catch (ce: CancellationException) {
            bridge?.cancelPending("coroutine cancelled: ${ce.message ?: "unknown"}")
            throw ce
        } catch (t: Throwable) {
            bridge?.completeWithHostError(t)
            throw t
        } finally {
            cancelled.set(true)
            bridgeScope.cancel()
            // Drain post is FIFO-ordered AFTER any earlier-queued posts. By the time it
            // runs, any post that built a WebView has already either stored it in the
            // slot or self-destroyed it. The slot is the single source of truth.
            val drained = CompletableDeferred<Unit>()
            mainHandler.post {
                try {
                    val wv = webViewSlot.getAndSet(null)
                    if (wv != null) destroyWebView(wv)
                } finally {
                    drained.complete(Unit)
                }
            }
            withContext(NonCancellable) { drained.await() }
        }
    }

    private fun destroyWebView(wv: WebView) {
        try {
            wv.removeJavascriptInterface(BrowserScriptPrelude.BRIDGE_OBJECT_NAME)
            wv.stopLoading()
            wv.webViewClient = WebViewClient()
            wv.loadUrl("about:blank")
            wv.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "WebView teardown failed", t)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createHardenedWebView(ctx: Context): WebView {
        val wv = WebView(ctx)
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.allowContentAccess = false
        s.allowFileAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.blockNetworkLoads = true
        s.blockNetworkImage = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        @Suppress("DEPRECATION")
        s.databaseEnabled = false
        s.domStorageEnabled = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.loadsImagesAutomatically = false
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setGeolocationEnabled(false)
        s.setSupportMultipleWindows(false)
        s.userAgentString = "ClosePaw-BrowserScript/1.0"
        return wv
    }

    private class ScriptHostWebViewClient(
        private val webView: WebView,
        private val userScript: String,
        private val pageReady: CompletableDeferred<Unit>,
        private val cancelled: AtomicBoolean,
    ) : WebViewClient() {
        private val initialLoaded = AtomicBoolean(false)

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return initialLoaded.get()
        }

        @Deprecated("Older WebView API still called on legacy paths")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            return initialLoaded.get()
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            if (!initialLoaded.get()) return null
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream(ByteArray(0)),
            )
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            handlePageFinished(
                cancelled = cancelled,
                initialLoaded = initialLoaded,
                userScript = userScript,
                pageReady = pageReady,
                evaluatePrelude = { onComplete ->
                    webView.evaluateJavascript(BrowserScriptPrelude.PRELUDE) { _ -> onComplete() }
                },
                evaluateUserScript = { js -> webView.evaluateJavascript(js, null) },
            )
        }
    }

    companion object {
        private const val TAG = "BrowserScriptRunner"
    }
}

/**
 * Page-load orchestration extracted for JVM-testable cancellation-guard coverage.
 *
 * Two guards close a race: a queued onPageFinished or prelude-eval callback can fire
 * after run()'s finally block has set [cancelled] but before the WebView is destroyed.
 * Without these checks, that window would inject the prelude (and then arbitrary user
 * script) into a WebView that's about to be torn down.
 */
internal fun handlePageFinished(
    cancelled: AtomicBoolean,
    initialLoaded: AtomicBoolean,
    userScript: String,
    pageReady: CompletableDeferred<Unit>,
    evaluatePrelude: (onComplete: () -> Unit) -> Unit,
    evaluateUserScript: (String) -> Unit,
) {
    if (cancelled.get()) return
    if (!initialLoaded.compareAndSet(false, true)) return
    evaluatePrelude {
        if (cancelled.get()) return@evaluatePrelude
        evaluateUserScript(BrowserScriptPrelude.wrapScript(userScript))
        pageReady.complete(Unit)
    }
}
