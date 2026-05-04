package ai.closepaw.browser.script

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cancellation-guard coverage for [handlePageFinished], the page-load callback
 * orchestrator extracted from `ScriptHostWebViewClient.onPageFinished`.
 *
 * The guards exist because Android may queue an `onPageFinished` (or its inner
 * prelude `evaluateJavascript` callback) after [BrowserScriptRunner.run] has set
 * its `cancelled` flag in `finally` but before the WebView teardown post drains.
 * Without these guards, agent JS (PRELUDE + user script) would be injected into
 * a WebView that's about to be destroyed.
 */
class ScriptHostPageLoadCancelGuardTest {

    @Test
    fun `cancel before onPageFinished prevents prelude injection`() {
        val cancelled = AtomicBoolean(true)
        val initialLoaded = AtomicBoolean(false)
        val pageReady = CompletableDeferred<Unit>()
        var preludeCalled = false
        var userScriptCalled = false

        handlePageFinished(
            cancelled = cancelled,
            initialLoaded = initialLoaded,
            userScript = "return 1;",
            pageReady = pageReady,
            evaluatePrelude = { _ -> preludeCalled = true },
            evaluateUserScript = { userScriptCalled = true },
        )

        assertThat(preludeCalled).isFalse()
        assertThat(userScriptCalled).isFalse()
        assertThat(pageReady.isCompleted).isFalse()
        // Guard short-circuits before consuming the initial-load latch, so a later
        // (post-cancel) re-entry would also be rejected by the same guard.
        assertThat(initialLoaded.get()).isFalse()
    }

    @Test
    fun `cancel between prelude eval and prelude callback prevents user script injection`() {
        val cancelled = AtomicBoolean(false)
        val initialLoaded = AtomicBoolean(false)
        val pageReady = CompletableDeferred<Unit>()
        var preludeCalled = false
        var userScriptCalled = false
        var capturedOnComplete: (() -> Unit)? = null

        handlePageFinished(
            cancelled = cancelled,
            initialLoaded = initialLoaded,
            userScript = "return 1;",
            pageReady = pageReady,
            evaluatePrelude = { onComplete ->
                preludeCalled = true
                capturedOnComplete = onComplete
            },
            evaluateUserScript = { userScriptCalled = true },
        )

        assertThat(preludeCalled).isTrue()
        assertThat(userScriptCalled).isFalse()

        // Race: cancellation observed while the prelude eval was in flight.
        cancelled.set(true)
        capturedOnComplete!!.invoke()

        assertThat(userScriptCalled).isFalse()
        assertThat(pageReady.isCompleted).isFalse()
    }

    @Test
    fun `happy path injects prelude then user script and signals pageReady`() {
        val cancelled = AtomicBoolean(false)
        val initialLoaded = AtomicBoolean(false)
        val pageReady = CompletableDeferred<Unit>()
        val evaluations = mutableListOf<String>()
        var capturedOnComplete: (() -> Unit)? = null

        handlePageFinished(
            cancelled = cancelled,
            initialLoaded = initialLoaded,
            userScript = "return 42;",
            pageReady = pageReady,
            evaluatePrelude = { onComplete ->
                evaluations += "PRELUDE"
                capturedOnComplete = onComplete
            },
            evaluateUserScript = { js -> evaluations += js },
        )

        capturedOnComplete!!.invoke()

        assertThat(evaluations).hasSize(2)
        assertThat(evaluations[0]).isEqualTo("PRELUDE")
        assertThat(evaluations[1]).contains("return 42;")
        assertThat(pageReady.isCompleted).isTrue()
    }

    @Test
    fun `re-entrant onPageFinished is ignored even without cancellation`() {
        val cancelled = AtomicBoolean(false)
        val initialLoaded = AtomicBoolean(false)
        val pageReady = CompletableDeferred<Unit>()
        var preludeCalls = 0

        repeat(3) {
            handlePageFinished(
                cancelled = cancelled,
                initialLoaded = initialLoaded,
                userScript = "return 1;",
                pageReady = pageReady,
                evaluatePrelude = { _ -> preludeCalls++ },
                evaluateUserScript = { /* not exercised here */ },
            )
        }

        assertThat(preludeCalls).isEqualTo(1)
    }
}
