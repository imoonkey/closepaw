package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * Regression guard for [ShizukuRuntimeGateway.requestPermissionAndAwait] — pins the atomic
 * resume contract used inside its `OnRequestPermissionResultListener`.
 *
 * Why this lives here instead of driving the real gateway: `rikka.shizuku.Shizuku`'s static
 * initializer constructs `new Handler(Looper.getMainLooper())`, which throws on the JVM (the
 * stub `Looper` returns null). We can't load the class in a unit test, so we cannot mock its
 * static surface either. Instead, this test exercises the exact resume idiom the gateway uses
 * (`tryResume` → null token check → `completeResume`) so that any future revert to plain
 * `cont.resume(...)` will surface here as a double-resume `IllegalStateException`.
 */
@OptIn(InternalCoroutinesApi::class)
class ShizukuRuntimeGatewayTest {

    @Test
    fun `atomic resume idiom tolerates duplicate listener fire without throwing`() = runTest {
        val resumeCount = AtomicInteger()
        var listener: ((PermissionRequestResult) -> Unit)? = null

        val deferred = async {
            suspendCancellableCoroutine { cont: CancellableContinuation<PermissionRequestResult> ->
                listener = { result ->
                    val token = cont.tryResume(result)
                    if (token != null) {
                        cont.completeResume(token)
                        resumeCount.incrementAndGet()
                    }
                }
            }
        }
        yield()
        val fire = listener ?: error("listener was not captured before suspension")

        // First fire: continuation accepts the resume.
        fire(PermissionRequestResult.Granted)
        // Duplicate fire: tryResume returns null, completeResume is skipped — no exception.
        fire(PermissionRequestResult.Granted)
        // A racing late fire with a different outcome must also be a silent no-op.
        fire(PermissionRequestResult.Denied)

        assertThat(deferred.await()).isEqualTo(PermissionRequestResult.Granted)
        assertThat(resumeCount.get()).isEqualTo(1)
    }
}
