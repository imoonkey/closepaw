package ai.closepaw.browser.script

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Structural tests for the JS prelude. These tests do NOT execute the JS — they only validate
 * the source text we will inject into the WebView. Real Promise/async/cdp() semantics run inside
 * Android WebView V8 at runtime; this is intentionally not stubbed by a JVM JS engine
 * (Rhino / GraalJS) because divergent engine behavior would create false confidence.
 *
 * Authoritative semantic coverage of the prelude lives in
 * [BrowserScriptRunnerInstrumentedTest] under app/src/androidTest/, runnable on a real device:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.browser.script.BrowserScriptRunnerInstrumentedTest
 */
class BrowserScriptPreludeTest {

    @Test
    fun `prelude exposes globalThis cdp`() {
        assertThat(BrowserScriptPrelude.PRELUDE).contains("globalThis.cdp")
    }

    @Test
    fun `prelude exposes only cdp resolve and reject on globalThis`() {
        val matches = Regex("""globalThis\.\w+""")
            .findAll(BrowserScriptPrelude.PRELUDE)
            .map { it.value }
            .toSet()
        assertThat(matches).containsExactly(
            "globalThis.cdp",
            "globalThis.__cdpResolve",
            "globalThis.__cdpReject",
        )
    }

    @Test
    fun `prelude does not leak helper globals`() {
        val prelude = BrowserScriptPrelude.PRELUDE
        listOf("sleep", "log", "pageJs", "newTab", "clickAt", "typeText").forEach { name ->
            assertThat(prelude).doesNotContain("globalThis.$name")
        }
    }

    @Test
    fun `reject hook preserves code, name, and cause on the JS Error`() {
        val prelude = BrowserScriptPrelude.PRELUDE
        // The prelude must propagate `error.code`, `error.name`, and `error.cause` from the
        // Kotlin reject payload to the constructed JS Error so user scripts can inspect them.
        assertThat(prelude).contains("e.code = error.code")
        assertThat(prelude).contains("e.name = error.name")
        assertThat(prelude).contains("e.cause = error.cause")
    }
    @Test
    fun `bridge object name matches contract`() {
        assertThat(BrowserScriptPrelude.BRIDGE_OBJECT_NAME).isEqualTo("AndroidBrowserScript")
        assertThat(BrowserScriptPrelude.PRELUDE).contains("AndroidBrowserScript.send")
    }

    @Test
    fun `prelude scopes pending state inside an IIFE`() {
        val prelude = BrowserScriptPrelude.PRELUDE
        // Must not leak __pending or __nextId on globalThis
        assertThat(prelude).doesNotContain("globalThis.__pending")
        assertThat(prelude).doesNotContain("globalThis.__nextId")
        // IIFE wrapper opens and closes
        assertThat(prelude).contains("(function()")
        assertThat(prelude).contains("})();")
    }

    @Test
    fun `wrapScript wraps user script in async wrapper that calls done`() {
        val wrapped = BrowserScriptPrelude.wrapScript("return 42;")
        assertThat(wrapped).contains("async")
        assertThat(wrapped).contains("return 42;")
        assertThat(wrapped).contains("AndroidBrowserScript.done")
        // Both success and failure branches are present
        assertThat(wrapped).contains("ok: true")
        assertThat(wrapped).contains("ok: false")
        assertThat(wrapped).contains("try")
        assertThat(wrapped).contains("catch")
    }

    @Test
    fun `wrapScript handles multiline user scripts verbatim`() {
        val script = """
            const a = await cdp("Target.getTargets");
            return a.targetInfos.length;
        """.trimIndent()
        val wrapped = BrowserScriptPrelude.wrapScript(script)
        assertThat(wrapped).contains("Target.getTargets")
        assertThat(wrapped).contains("targetInfos.length")
    }
}
