package ai.closepaw.browser.script

import ai.closepaw.trace.TraceRecorder
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface

internal class BrowserScriptJsInterface(
    private val bridge: BrowserScriptBridge,
    private val traceRecorder: TraceRecorder,
) {

    @JavascriptInterface
    fun send(message: String) {
        bridge.handleSend(message)
    }

    @JavascriptInterface
    fun done(message: String) {
        bridge.handleDone(message)
    }

    /**
     * Decode base64 [base64], hand the bytes to [TraceRecorder.storeBytes], and return the
     * resulting on-device absolute path so callers can reference the artifact without piping
     * 100+KB of base64 through the agent's context. Returns null when tracing is disabled
     * (NoopTraceRecorder), the recorder rejects the write, or decoding fails.
     */
    @JavascriptInterface
    fun storeArtifact(kind: String, filenameHint: String, base64: String, mimeType: String?): String? =
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val ref = traceRecorder.storeBytes(
                kind = kind.ifBlank { "browser-script" },
                filenameHint = filenameHint.ifBlank { "artifact.bin" },
                bytes = bytes,
                mimeType = mimeType,
                description = null,
            ) ?: return@runCatching null
            traceRecorder.runDirAbsolutePath?.let { "$it/${ref.path}" } ?: ref.path
        }.onFailure { Log.w(TAG, "storeArtifact failed: ${it.message}") }.getOrNull()

    companion object {
        private const val TAG = "BrowserScriptJsInterface"
    }
}
