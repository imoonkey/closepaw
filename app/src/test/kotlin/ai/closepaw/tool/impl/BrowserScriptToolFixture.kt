package ai.closepaw.tool.impl

import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.ToolExecutionContext

/**
 * Shared fixtures for the BrowserScriptTool test suite. Kept in production code style
 * (no test-specific marker classes) so the multiple test files all consume the same
 * helpers without duplication.
 */
internal class RecordingTraceSink : BrowserScriptTraceSink {
    val entries: MutableList<BrowserScriptTraceMetadata> = mutableListOf()
    override fun record(metadata: BrowserScriptTraceMetadata) {
        entries.add(metadata)
    }
}

internal fun staticGate(outcome: BrowserScriptCapabilityGate.Outcome) =
    object : BrowserScriptCapabilityGate {
        override suspend fun acquire(): BrowserScriptCapabilityGate.Outcome = outcome
    }

internal fun neverGate(): BrowserScriptCapabilityGate = staticGate(
    BrowserScriptCapabilityGate.Outcome.Unavailable("test", "tool not exercised"),
)

internal fun availableGate(invoker: BrowserScriptInvoker): BrowserScriptCapabilityGate =
    staticGate(BrowserScriptCapabilityGate.Outcome.Available(invoker))

/** Returns each tick in order; once exhausted, repeats the last value. */
internal fun sequenceClock(vararg ticks: Long): () -> Long {
    val iter = ticks.iterator()
    var last = 0L
    return {
        if (iter.hasNext()) last = iter.nextLong()
        last
    }
}

internal fun executionContext(callId: String, cancelled: Boolean = false): ToolExecutionContext =
    object : ToolExecutionContext {
        override val callId: String = callId
        override val platform = FakeAndroidPlatform()
        override val currentSnapshot = null
        override fun isCancelled(): Boolean = cancelled
    }
