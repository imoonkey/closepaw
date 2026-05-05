package ai.closepaw.termux

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TermuxRunCommandAdapterTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Throwable.toRunCommandStartError (start-stage error mapping) ---

    @Test
    fun `ForegroundServiceStartNotAllowed maps to TermuxProcessNotRunning`() {
        val fgs = ForegroundServiceStartNotAllowedException("BG-FGS-START denied")

        assertThat(fgs.toRunCommandStartError())
            .isEqualTo(RunCommandError.TermuxProcessNotRunning)
    }

    @Test
    fun `forbidden 3rd process SecurityException also maps to TermuxProcessNotRunning`() {
        // Vendor restriction surfaces as a SecurityException whose message names the
        // 3rd-process rule rather than the FGS exception. Must be distinguished from
        // a true permission denial below.
        val securityNumeric =
            SecurityException("It is forbidden to start a 3rd process by service")
        val securityWord =
            SecurityException("App forbidden to start a third process from background")

        assertThat(securityNumeric.toRunCommandStartError())
            .isEqualTo(RunCommandError.TermuxProcessNotRunning)
        assertThat(securityWord.toRunCommandStartError())
            .isEqualTo(RunCommandError.TermuxProcessNotRunning)
    }

    @Test
    fun `unrelated SecurityException maps to PermissionMissing`() {
        val security = SecurityException("Permission Denial: missing RUN_COMMAND")

        assertThat(security.toRunCommandStartError())
            .isEqualTo(RunCommandError.PermissionMissing)
    }

    @Test
    fun `Play-Store Termux without RunCommandService maps to TermuxNotAvailable`() {
        // 6f06c64c — the Play-Store Termux build strips RunCommandService per Play
        // Store policies. An explicit startForegroundService against a missing
        // component surfaces as ActivityNotFoundException or IllegalArgumentException;
        // both must funnel to TermuxNotAvailable so the bridge can surface the
        // TERMUX_RUN_COMMAND_UNAVAILABLE NeedsSetup hint to the user.
        assertThat(ActivityNotFoundException("RunCommandService").toRunCommandStartError())
            .isEqualTo(RunCommandError.TermuxNotAvailable)
        assertThat(IllegalArgumentException("Service Intent must be explicit").toRunCommandStartError())
            .isEqualTo(RunCommandError.TermuxNotAvailable)
    }

    // --- toRunCommandError / toRunCommandResult (broadcast parsing) ---

    @Test
    fun `success result with stdout, stderr, and zero exit code is parsed`() {
        val intent = makeBroadcastIntent {
            stdout = "hello\n"
            stderr = "warn\n"
            exitCode = 0
        }
        val adapter = newAdapter()

        val error = adapter.invokeToRunCommandError(intent)
        val result = adapter.invokeToRunCommandResult(intent)

        assertThat(error).isNull()
        assertThat(result.stdout).isEqualTo("hello\n")
        assertThat(result.stderr).isEqualTo("warn\n")
        assertThat(result.exitCode).isEqualTo(0)
    }

    @Test
    fun `non-zero exit code returns Success result, not a transport error`() {
        // Exit 127 = command-not-found at the script level. The broadcast still
        // completed successfully — the parser must NOT promote it to a
        // RunCommandError. Callers see exitCode in the RunCommandResult and decide
        // whether to treat it as a script-level failure.
        val intent = makeBroadcastIntent {
            stdout = ""
            stderr = "/bin/foo: not found\n"
            exitCode = 127
        }
        val adapter = newAdapter()

        val error = adapter.invokeToRunCommandError(intent)
        val result = adapter.invokeToRunCommandResult(intent)

        assertThat(error).isNull()
        assertThat(result.exitCode).isEqualTo(127)
        assertThat(result.stderr).isEqualTo("/bin/foo: not found\n")
    }

    @Test
    fun `errmsg containing allow-external-apps maps to AllowExternalAppsMissing`() {
        // Termux's RunCommandService rejects unauthorised callers with errmsg that
        // names the allow-external-apps setting. Distinct from FGS rejection
        // (which is a start-time Throwable, not a broadcast) and from
        // PermissionMissing (which the broadcast names PluginErrorCode_PERMISSION).
        val intent = makeBroadcastIntent {
            err = "100"
            errmsg = "Termux app is not allowing external apps; set allow-external-apps=true"
        }
        val adapter = newAdapter()

        assertThat(adapter.invokeToRunCommandError(intent))
            .isEqualTo(RunCommandError.AllowExternalAppsMissing)
    }

    @Test
    fun `err -1 with blank errmsg is treated as success not transport failure`() {
        // cdfe52a0: Termux v0.118+ uses err=-1 to mean "no execution-stage error".
        // Only positive err codes (or any non-empty errmsg) signal a real failure.
        // Without this case every successful command (including the bootstrap probe)
        // was rejected with RunCommandError.Other on the QA emulator.
        val intent = makeBroadcastIntent {
            err = -1
            stdout = "ok\n"
            stderr = ""
            exitCode = 0
        }
        val adapter = newAdapter()

        assertThat(adapter.invokeToRunCommandError(intent)).isNull()
        val result = adapter.invokeToRunCommandResult(intent)
        assertThat(result.stdout).isEqualTo("ok\n")
        assertThat(result.exitCode).isEqualTo(0)
    }

    // --- Receiver-level filter (stale / unrelated broadcast) ---

    @Test
    fun `broadcast with stale request id is silently dropped by receiver`() = runTest {
        // The receiver filters EXTRA_REQUEST_ID before parsing — a broadcast for a
        // cancelled or unrelated call must not race-resume the continuation. To
        // isolate the request-id guard (and not the upstream action filter), the
        // stale broadcast carries the SAME resultAction the receiver expects but a
        // different request id. We recover the real request id by capturing the
        // EXTRA_REQUEST_ID value production puts onto the PendingIntent, then
        // rebuild resultAction the same way production does.
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)

        mockkConstructor(Intent::class)
        val capturedRequestId = slot<String>()
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setClassName(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Array<String>>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<PendingIntent>()) } answers { self as Intent }
        // Specific stub registered last so mockk evaluates it before the catch-all.
        every {
            anyConstructed<Intent>().putExtra(eq("ai.closepaw.termux.requestId"), capture(capturedRequestId))
        } answers { self as Intent }

        mockkConstructor(IntentFilter::class)

        val context = mockContext()
        val receiverSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(receiverSlot), any<IntentFilter>()) } returns null
        every { context.startForegroundService(any()) } returns ComponentName("com.termux", "X")

        val adapter = TermuxRunCommandAdapter(context)
        val deferred = async {
            runCatching { adapter.run("/bin/echo", listOf("hi"), timeoutMs = 5_000L) }
        }
        runCurrent()

        val realRequestId = capturedRequestId.captured
        val resultAction = "ai.closepaw.termux.RUN_COMMAND_RESULT.$realRequestId"

        val staleIntent = mockk<Intent>(relaxed = true)
        every { staleIntent.action } returns resultAction
        every { staleIntent.getStringExtra("ai.closepaw.termux.requestId") } returns "stale-uuid"

        receiverSlot.captured.onReceive(context, staleIntent)
        runCurrent()

        // Continuation must still be suspended — only the timeout resolves it.
        // If the request-id guard were removed, the receiver would parse the stale
        // broadcast (default empty Bundle) and resume successfully, failing this
        // assertion.
        advanceTimeBy(6_000L)
        runCurrent()

        val outcome = deferred.await()
        assertThat(outcome.exceptionOrNull()).isInstanceOf(RunCommandError.Timeout::class.java)
    }

    // --- helpers ---

    private fun newAdapter(): TermuxRunCommandAdapter = TermuxRunCommandAdapter(mockContext())

    private fun mockContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.packageName } returns "ai.closepaw"
        return context
    }

    private fun TermuxRunCommandAdapter.invokeToRunCommandError(intent: Intent): RunCommandError? {
        val method = TermuxRunCommandAdapter::class.java.getDeclaredMethod(
            "toRunCommandError", Intent::class.java
        )
        method.isAccessible = true
        return method.invoke(this, intent) as RunCommandError?
    }

    private fun TermuxRunCommandAdapter.invokeToRunCommandResult(intent: Intent): RunCommandResult {
        val method = TermuxRunCommandAdapter::class.java.getDeclaredMethod(
            "toRunCommandResult", Intent::class.java
        )
        method.isAccessible = true
        return method.invoke(this, intent) as RunCommandResult
    }

    private class BroadcastSpec {
        var stdout: String? = null
        var stderr: String? = null
        var exitCode: Int? = null
        var err: Any? = null
        var errmsg: String? = null
    }

    private fun makeBroadcastIntent(configure: BroadcastSpec.() -> Unit): Intent {
        val spec = BroadcastSpec().apply(configure)

        val bundle = mockk<Bundle>(relaxed = true)
        every { bundle.getString("stdout") } returns spec.stdout
        every { bundle.getString("stderr") } returns spec.stderr
        every { bundle.getString("errmsg") } returns spec.errmsg
        every { bundle.containsKey("errmsg") } returns (spec.errmsg != null)
        every { bundle.containsKey("exitCode") } returns (spec.exitCode != null)
        spec.exitCode?.let { every { bundle.getInt("exitCode") } returns it }
        every { bundle.containsKey("err") } returns (spec.err != null)
        spec.err?.let { every { bundle.get("err") } returns it }

        val intent = mockk<Intent>(relaxed = true)
        every { intent.getBundleExtra("result") } returns bundle
        // Fallbacks invoked when the Bundle is absent — we route everything via the
        // Bundle so these defaults (null/empty/0) keep the parser on the bundle path.
        every { intent.getStringExtra(any()) } returns null
        every { intent.hasExtra(any()) } returns false
        every { intent.extras } returns null
        return intent
    }
}
