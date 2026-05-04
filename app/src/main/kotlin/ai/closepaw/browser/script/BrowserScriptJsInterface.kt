package ai.closepaw.browser.script

import ai.closepaw.trace.TraceRecorder
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import java.util.concurrent.atomic.AtomicLong

internal class BrowserScriptJsInterface(
    private val bridge: BrowserScriptBridge,
    private val traceRecorder: TraceRecorder,
    private val maxBytesPerCall: Int = MAX_BYTES_PER_CALL,
    private val maxBytesPerSession: Long = MAX_BYTES_PER_SESSION,
) {

    // Atomic because the WebView dispatches @JavascriptInterface calls on its own
    // worker thread and bridge.handleSend / cdp() helpers can re-enter this class.
    private val sessionDecodedBytes = AtomicLong(0L)

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
     * (NoopTraceRecorder), the recorder rejects the write, decoding fails, or the call would
     * breach [maxBytesPerCall] / [maxBytesPerSession].
     */
    @JavascriptInterface
    fun storeArtifact(kind: String, filenameHint: String, base64: String, mimeType: String?): String? {
        if (base64.length > maxBytesPerCall) {
            Log.w(
                TAG,
                "storeArtifact rejected: base64 length ${base64.length} exceeds per-call cap $maxBytesPerCall",
            )
            return null
        }
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val projected = sessionDecodedBytes.get() + bytes.size
            if (projected > maxBytesPerSession) {
                Log.w(
                    TAG,
                    "storeArtifact rejected: session decoded bytes would reach $projected, cap $maxBytesPerSession",
                )
                return@runCatching null
            }
            val ref = traceRecorder.storeBytes(
                kind = kind.ifBlank { "browser-script" },
                filenameHint = filenameHint.ifBlank { "artifact.bin" },
                bytes = bytes,
                mimeType = mimeType,
                description = null,
            ) ?: return@runCatching null
            sessionDecodedBytes.addAndGet(bytes.size.toLong())
            traceRecorder.runDirAbsolutePath?.let { "$it/${ref.path}" } ?: ref.path
        }.onFailure { Log.w(TAG, "storeArtifact failed: ${it.message}") }.getOrNull()
    }

    companion object {
        private const val TAG = "BrowserScriptJsInterface"

        /**
         * Per-call cap on the base64 input string length. 50 MiB encoded ≈ 37 MiB decoded.
         *
         * WHY 50 MiB: typical screenshots are <500 KiB; a worst-case full-page 4K capture
         * rarely exceeds ~5 MiB. 50 MiB leaves an order-of-magnitude headroom for legitimate
         * edge cases (very tall pages, video frames) while keeping the decode allocation
         * (~150 MiB peak: input string + decoded ByteArray) well under the 256-512 MiB
         * Dalvik heap budget. Anything larger is almost certainly a buggy or malicious
         * script and the decode itself would risk OOM.
         */
        const val MAX_BYTES_PER_CALL: Int = 50 * 1024 * 1024

        /**
         * Cumulative cap on decoded bytes admitted during a single session.
         *
         * WHY 500 MiB: 10× the per-call cap, which lets a session legitimately store dozens
         * of full-page screenshots while bounding disk pressure on /sdcard/Android/data.
         * A runaway loop would otherwise fill the trace directory and either OOM the
         * recorder or wedge the device (and is the exact DoS vector this guard exists
         * to close).
         */
        const val MAX_BYTES_PER_SESSION: Long = 500L * 1024L * 1024L
    }
}
