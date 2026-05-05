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
    /**
     * Cumulative decoded-byte counter shared with the session-scoped owner
     * (BrowserSessionManager). Atomic because the WebView dispatches
     * @JavascriptInterface calls on its own worker thread, and because multiple
     * BrowserScriptRunner.run() invocations within one session share this same
     * counter — a per-instance counter would scope the cap to a single call
     * and let a runaway script bypass the cap by issuing repeated browser_script
     * tool calls. Defaults to a fresh counter so isolated unit tests need not
     * thread one through.
     */
    private val sessionDecodedBytes: AtomicLong = AtomicLong(0L),
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
            val byteCount = bytes.size.toLong()
            // Atomic reserve: getAndAccumulate retries the lambda under contention so
            // the read-and-add is one indivisible step. The earlier check-then-add
            // pattern let two concurrent calls both observe `current+n <= cap` and
            // both add, breaching the cap. Lambda returns `current` to refuse the
            // reservation when it would exceed the cap.
            val before = sessionDecodedBytes.getAndAccumulate(byteCount) { current, requested ->
                if (current + requested > maxBytesPerSession) current else current + requested
            }
            if (before + byteCount > maxBytesPerSession) {
                Log.w(
                    TAG,
                    "storeArtifact rejected: session decoded bytes would reach ${before + byteCount}, cap $maxBytesPerSession",
                )
                return@runCatching null
            }
            val ref = try {
                traceRecorder.storeBytes(
                    kind = kind.ifBlank { "browser-script" },
                    filenameHint = filenameHint.ifBlank { "artifact.bin" },
                    bytes = bytes,
                    mimeType = mimeType,
                    description = null,
                )
            } catch (t: Throwable) {
                sessionDecodedBytes.addAndGet(-byteCount)
                throw t
            }
            if (ref == null) {
                sessionDecodedBytes.addAndGet(-byteCount)
                return@runCatching null
            }
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
