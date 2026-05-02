package ai.closepaw.browser.cdp.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Coverage for the bind/lifecycle hardening in [ShizukuUserServiceProvider] (review round 3,
 * remaining HIGH). Uses the internal [ShizukuUserServiceProvider.Binder] indirection so the
 * test can drive every [ServiceConnection] callback and timeout path without a real Shizuku
 * binder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuUserServiceProviderTest {

    private val componentName = ComponentName("test.pkg", "test.cls")

    @Test
    fun obtain_throws_user_service_socket_inaccessible_when_bind_times_out() = runTest {
        val binder = NeverFiringBinder()
        val provider = ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 50L)

        val err = assertFails<DevtoolsSetupError.UserServiceSocketInaccessible> {
            provider.obtain()
        }
        assertThat(err.cause).isInstanceOf(IOException::class.java)
        assertThat(err.cause?.message).contains("timed out")
        // Cancellation triggered by withTimeout must release the binding.
        assertThat(binder.unbinds).isAtLeast(1)
    }

    @Test
    fun obtain_throws_when_service_disconnected_before_connected() =
        runTest(UnconfinedTestDispatcher()) {
            supervisorScope {
                val binder = ManualBinder()
                val provider =
                    ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

                val deferred = async(start = CoroutineStart.UNDISPATCHED) { provider.obtain() }
                val conn = checkNotNull(binder.captured) { "bind was not called" }
                conn.onServiceDisconnected(componentName)

                val err = assertFails<DevtoolsSetupError.UserServiceSocketInaccessible> {
                    deferred.await()
                }
                assertThat(err.cause).isInstanceOf(IOException::class.java)
                assertThat(err.cause?.message).contains("disconnected")
            }
        }

    @Test
    fun obtain_throws_when_null_binding_callback_fires() =
        runTest(UnconfinedTestDispatcher()) {
            supervisorScope {
                val binder = ManualBinder()
                val provider =
                    ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

                val deferred = async(start = CoroutineStart.UNDISPATCHED) { provider.obtain() }
                checkNotNull(binder.captured).onNullBinding(componentName)

                val err = assertFails<DevtoolsSetupError.UserServiceSocketInaccessible> {
                    deferred.await()
                }
                assertThat(err.cause).isInstanceOf(IllegalStateException::class.java)
                assertThat(err.cause?.message).contains("null")
            }
        }

    @Test
    fun obtain_throws_when_binding_dies() =
        runTest(UnconfinedTestDispatcher()) {
            supervisorScope {
                val binder = ManualBinder()
                val provider =
                    ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

                val deferred = async(start = CoroutineStart.UNDISPATCHED) { provider.obtain() }
                checkNotNull(binder.captured).onBindingDied(componentName)

                val err = assertFails<DevtoolsSetupError.UserServiceSocketInaccessible> {
                    deferred.await()
                }
                assertThat(err.cause?.message).contains("died")
            }
        }

    @Test
    fun close_during_pending_bind_resumes_caller_with_error() =
        runTest(UnconfinedTestDispatcher()) {
            supervisorScope {
                val binder = ManualBinder()
                val provider =
                    ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

                val deferred = async(start = CoroutineStart.UNDISPATCHED) { provider.obtain() }
                assertThat(binder.captured).isNotNull() // bind() called, caller suspended

                provider.close()

                val err = assertFails<DevtoolsSetupError.UserServiceSocketInaccessible> {
                    deferred.await()
                }
                assertThat(err.cause?.message).contains("closed")
                // close() must release the helper process even though the bind never completed.
                assertThat(binder.unbinds).isEqualTo(1)
            }
        }

    @Test
    fun close_is_idempotent_and_safe_when_no_bind_was_started() {
        val binder = ManualBinder()
        val provider = ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)
        provider.close()
        provider.close()
        provider.close()
        assertThat(binder.unbinds).isEqualTo(0)
    }

    @Test
    fun close_after_successful_bind_unbinds_exactly_once() =
        runTest(UnconfinedTestDispatcher()) {
            supervisorScope {
                val binder = ManualBinder()
                val provider =
                    ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

                val deferred = async(start = CoroutineStart.UNDISPATCHED) { provider.obtain() }
                val mockBinder = mockk<IBinder>(relaxed = true)
                every { mockBinder.queryLocalInterface(any()) } returns null
                checkNotNull(binder.captured).onServiceConnected(componentName, mockBinder)
                deferred.await()

                provider.close()
                provider.close()
                assertThat(binder.unbinds).isEqualTo(1)
            }
        }

    @Test
    fun obtain_after_close_throws_user_service_socket_inaccessible() = runTest {
        val provider = ShizukuUserServiceProvider(
            binder = NeverFiringBinder(), bindTimeoutMs = 10_000L,
        )
        provider.close()
        val err = assertFails<DevtoolsSetupError.UserServiceSocketInaccessible> {
            provider.obtain()
        }
        assertThat(err.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(err.cause?.message).contains("closed")
    }

    private inline fun <reified E : Throwable> assertFails(block: () -> Unit): E {
        try {
            block()
        } catch (t: Throwable) {
            if (t is E) return t
            throw AssertionError(
                "expected ${E::class.java.name} but got ${t::class.java.name}: $t", t,
            )
        }
        throw AssertionError("expected ${E::class.java.name} but no exception was thrown")
    }
}

private class ManualBinder : ShizukuUserServiceProvider.Binder {
    var captured: ServiceConnection? = null
        private set
    var unbinds: Int = 0
        private set

    override fun bind(conn: ServiceConnection) {
        captured = conn
    }

    override fun unbind(conn: ServiceConnection, remove: Boolean) {
        unbinds++
    }
}

private class NeverFiringBinder : ShizukuUserServiceProvider.Binder {
    var unbinds: Int = 0
        private set

    override fun bind(conn: ServiceConnection) {
        // intentionally never invoke any ServiceConnection callback
    }

    override fun unbind(conn: ServiceConnection, remove: Boolean) {
        unbinds++
    }
}
