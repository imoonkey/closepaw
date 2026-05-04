package ai.closepaw.browser.script

import ai.closepaw.trace.TraceArtifactRef
import ai.closepaw.trace.TraceRecorder
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class BrowserScriptJsInterfaceTest {

    @Before
    fun setUp() {
        // Robolectric/android.jar stubs return null/0 for android.util.Base64; route through
        // java.util.Base64 so storeArtifact() actually decodes payloads under the unit-test JVM.
        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        // Quiet the warning path; otherwise the failure-case test pulls the real Log.w stub.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
        unmockkStatic(Log::class)
    }

    @Test
    fun `JavascriptInterface methods are exactly send done storeArtifact`() {
        val annotation = JavascriptInterface::class.java
        val exposed = BrowserScriptJsInterface::class.java.declaredMethods
            .filter { it.isAnnotationPresent(annotation) }
            .map { it.name }
            .toSet()
        assertThat(exposed).containsExactly("send", "done", "storeArtifact")
    }

    @Test
    fun `send and done both accept a single String argument`() {
        val annotation = JavascriptInterface::class.java
        BrowserScriptJsInterface::class.java.declaredMethods
            .filter { it.isAnnotationPresent(annotation) && it.name in setOf("send", "done") }
            .forEach { method ->
                assertThat(method.parameterTypes.toList())
                    .containsExactly(String::class.java)
            }
    }

    @Test
    fun `storeArtifact decodes base64, forwards bytes to recorder, returns absolute path`() {
        val recorder = mockk<TraceRecorder>(relaxed = true)
        every { recorder.runDirAbsolutePath } returns "/sdcard/trace/run-1"
        every {
            recorder.storeBytes(any(), any(), any(), any(), any())
        } returns TraceArtifactRef(
            kind = "browser-script",
            path = "artifacts/screenshot-7.png",
            mimeType = "image/png",
            description = null,
        )
        val iface = BrowserScriptJsInterface(mockk(relaxed = true), recorder)

        val result = iface.storeArtifact(
            kind = "browser-script",
            filenameHint = "screenshot-7.png",
            base64 = "SGVsbG8gV29ybGQh", // "Hello World!"
            mimeType = "image/png",
        )

        assertThat(result).isEqualTo("/sdcard/trace/run-1/artifacts/screenshot-7.png")
        val bytesSlot = slot<ByteArray>()
        verify(exactly = 1) {
            recorder.storeBytes(
                kind = "browser-script",
                filenameHint = "screenshot-7.png",
                bytes = capture(bytesSlot),
                mimeType = "image/png",
                description = null,
            )
        }
        assertThat(String(bytesSlot.captured)).isEqualTo("Hello World!")
    }

    @Test
    fun `storeArtifact substitutes blank kind and filename with safe defaults`() {
        val recorder = mockk<TraceRecorder>(relaxed = true)
        every { recorder.runDirAbsolutePath } returns null
        every {
            recorder.storeBytes(any(), any(), any(), any(), any())
        } returns TraceArtifactRef(
            kind = "browser-script",
            path = "artifacts/data-1.bin",
            mimeType = null,
            description = null,
        )
        val iface = BrowserScriptJsInterface(mockk(relaxed = true), recorder)

        val result = iface.storeArtifact(kind = "", filenameHint = "", base64 = "AAAA", mimeType = null)

        assertThat(result).isEqualTo("artifacts/data-1.bin")
        verify(exactly = 1) {
            recorder.storeBytes(
                kind = "browser-script",
                filenameHint = "artifact.bin",
                bytes = any(),
                mimeType = null,
                description = null,
            )
        }
    }

    @Test
    fun `storeArtifact returns null when recorder rejects write`() {
        val recorder = mockk<TraceRecorder>(relaxed = true)
        every { recorder.runDirAbsolutePath } returns "/sdcard/trace/run-1"
        every { recorder.storeBytes(any(), any(), any(), any(), any()) } returns null
        val iface = BrowserScriptJsInterface(mockk(relaxed = true), recorder)

        val result = iface.storeArtifact("k", "f.png", "AAAA", "image/png")

        assertThat(result).isNull()
    }

    @Test
    fun `storeArtifact returns null on decode failure`() {
        val recorder = mockk<TraceRecorder>(relaxed = true)
        every { Base64.decode(any<String>(), any()) } throws IllegalArgumentException("bad b64")
        val iface = BrowserScriptJsInterface(mockk(relaxed = true), recorder)

        val result = iface.storeArtifact("k", "f.png", "!!!", "image/png")

        assertThat(result).isNull()
        verify(exactly = 0) { recorder.storeBytes(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `storeArtifact rejects payloads above per-call cap without decoding or storing`() {
        val recorder = mockk<TraceRecorder>(relaxed = true)
        val iface = BrowserScriptJsInterface(
            bridge = mockk(relaxed = true),
            traceRecorder = recorder,
            maxBytesPerCall = 16,
            maxBytesPerSession = 1_024L,
        )

        // 17-char base64 string: one byte over the configured per-call cap.
        val oversized = "AAAAAAAAAAAAAAAAA"
        val result = iface.storeArtifact("k", "f.bin", oversized, null)

        assertThat(result).isNull()
        // Decode must not run for over-cap payloads — the length check is the OOM guard.
        verify(exactly = 0) { Base64.decode(any<String>(), any()) }
        verify(exactly = 0) { recorder.storeBytes(any(), any(), any(), any(), any()) }
        verify(atLeast = 1) { Log.w(any<String>(), match<String> { it.contains("per-call cap") }) }
    }

    @Test
    fun `storeArtifact rejects when cumulative session bytes would exceed cap`() {
        val recorder = mockk<TraceRecorder>(relaxed = true)
        every { recorder.runDirAbsolutePath } returns "/sdcard/trace/run-1"
        every {
            recorder.storeBytes(any(), any(), any(), any(), any())
        } returns TraceArtifactRef(
            kind = "browser-script",
            path = "artifacts/a.bin",
            mimeType = null,
            description = null,
        )
        // Per-session cap of 12 bytes; "Hello World!" decodes to exactly 12 bytes.
        val iface = BrowserScriptJsInterface(
            bridge = mockk(relaxed = true),
            traceRecorder = recorder,
            maxBytesPerCall = 1_024,
            maxBytesPerSession = 12L,
        )

        val first = iface.storeArtifact("k", "a.bin", "SGVsbG8gV29ybGQh", null) // 12 decoded bytes
        val second = iface.storeArtifact("k", "b.bin", "QQ==", null) // 1 more decoded byte → over

        assertThat(first).isEqualTo("/sdcard/trace/run-1/artifacts/a.bin")
        assertThat(second).isNull()
        verify(exactly = 1) { recorder.storeBytes(any(), any(), any(), any(), any()) }
        verify(atLeast = 1) { Log.w(any<String>(), match<String> { it.contains("session decoded bytes") }) }
    }

    @Test
    fun `storeArtifact does not consume session quota when recorder rejects the write`() {
        val recorder = mockk<TraceRecorder>(relaxed = true)
        every { recorder.runDirAbsolutePath } returns "/sdcard/trace/run-1"
        // First call: recorder rejects (returns null) → quota must not advance.
        // Second call: recorder accepts → would only succeed if quota wasn't burned by call #1.
        every {
            recorder.storeBytes(any(), any(), any(), any(), any())
        } returnsMany listOf(
            null,
            TraceArtifactRef(
                kind = "browser-script",
                path = "artifacts/ok.bin",
                mimeType = null,
                description = null,
            ),
        )
        val iface = BrowserScriptJsInterface(
            bridge = mockk(relaxed = true),
            traceRecorder = recorder,
            maxBytesPerCall = 1_024,
            maxBytesPerSession = 12L, // exactly one "Hello World!" worth
        )

        val rejected = iface.storeArtifact("k", "a.bin", "SGVsbG8gV29ybGQh", null)
        val accepted = iface.storeArtifact("k", "b.bin", "SGVsbG8gV29ybGQh", null)

        assertThat(rejected).isNull()
        assertThat(accepted).isEqualTo("/sdcard/trace/run-1/artifacts/ok.bin")
    }
}
