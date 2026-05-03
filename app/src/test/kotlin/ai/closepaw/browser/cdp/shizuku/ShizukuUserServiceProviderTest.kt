package ai.closepaw.browser.cdp.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
    fun obtain_throws_and_unbinds_when_service_disconnected_before_connected() =
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
                // Round 4 hardening: failure callbacks must release the binding so a failed
                // bind cannot leak the helper process until session shutdown.
                assertThat(binder.unbinds).isEqualTo(1)
            }
        }

    @Test
    fun obtain_throws_and_unbinds_when_null_binding_callback_fires() =
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
                assertThat(binder.unbinds).isEqualTo(1)
            }
        }

    @Test
    fun obtain_throws_and_unbinds_when_binding_dies() =
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
                assertThat(binder.unbinds).isEqualTo(1)
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

    /**
     * Round 4 hardening: a callback delivered AFTER [ShizukuUserServiceProvider.close]
     * must be silently dropped — it cannot resume a continuation that no longer exists,
     * and it must not trigger an extra unbind on top of the one [close] already
     * performed.
     */
    @Test
    fun stale_callback_after_close_is_ignored() = runTest(UnconfinedTestDispatcher()) {
        supervisorScope {
            val binder = ManualBinder()
            val provider =
                ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { provider.obtain() }
            val conn = checkNotNull(binder.captured)

            provider.close()
            assertThat(binder.unbinds).isEqualTo(1)

            // Each of these is a stale callback for a closed bind cycle. They must be
            // dropped: no resume, no state mutation, no extra unbind.
            conn.onServiceDisconnected(componentName)
            conn.onBindingDied(componentName)
            conn.onNullBinding(componentName)
            val mockBinder = mockk<IBinder>(relaxed = true)
            every { mockBinder.queryLocalInterface(any()) } returns null
            conn.onServiceConnected(componentName, mockBinder)

            assertThat(binder.unbinds).isEqualTo(1)

            val err = assertFails<DevtoolsSetupError.UserServiceSocketInaccessible> {
                deferred.await()
            }
            assertThat(err.cause?.message).contains("closed")
        }
    }

    /**
     * Round 4 hardening: when [obtain] times out, [withTimeout] cancels the bind cycle
     * and `invokeOnCancellation` releases the binding. A late callback from the now-dead
     * bind cycle must be dropped — it cannot corrupt provider state or resume any later
     * caller.
     */
    @Test
    fun stale_callback_after_timeout_is_ignored() = runTest {
        val binder = ManualBinder()
        val provider = ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 50L)

        val err = assertFails<DevtoolsSetupError.UserServiceSocketInaccessible> {
            provider.obtain()
        }
        assertThat(err.cause?.message).contains("timed out")
        // Cancellation from the timeout already released the binding.
        assertThat(binder.unbinds).isEqualTo(1)

        val staleConn = checkNotNull(binder.captured)
        staleConn.onServiceDisconnected(componentName)
        staleConn.onBindingDied(componentName)
        staleConn.onNullBinding(componentName)
        val mockBinder = mockk<IBinder>(relaxed = true)
        every { mockBinder.queryLocalInterface(any()) } returns null
        staleConn.onServiceConnected(componentName, mockBinder)

        // Stale callbacks must not produce any additional unbinds.
        assertThat(binder.unbinds).isEqualTo(1)
    }

    /**
     * Round 4 hardening: a stale callback from a prior, already-failed bind cycle must
     * not resume a newer pending [obtain]. Verifies the per-bind generation token
     * isolates each bind cycle's callbacks.
     */
    @Test
    fun stale_callback_does_not_resume_a_later_obtain() =
        runTest(UnconfinedTestDispatcher()) {
            supervisorScope {
                val binder = ManualBinder()
                val provider =
                    ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

                // First obtain fails via onServiceDisconnected.
                val deferred1 = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.obtain()
                }
                val conn1 = checkNotNull(binder.captured)
                conn1.onServiceDisconnected(componentName)
                assertFails<DevtoolsSetupError.UserServiceSocketInaccessible> {
                    deferred1.await()
                }
                assertThat(binder.unbinds).isEqualTo(1)

                // Second obtain starts a new bind with a different ServiceConnection.
                val deferred2 = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.obtain()
                }
                val conn2 = checkNotNull(binder.captured)
                assertThat(conn2).isNotSameInstanceAs(conn1)

                // Late callbacks from conn1 (the prior, failed cycle) must be dropped:
                // they must NOT resume deferred2, must NOT mutate provider state, and
                // must NOT trigger an extra unbind.
                conn1.onBindingDied(componentName)
                conn1.onNullBinding(componentName)
                val staleBinder = mockk<IBinder>(relaxed = true)
                every { staleBinder.queryLocalInterface(any()) } returns null
                conn1.onServiceConnected(componentName, staleBinder)

                assertThat(binder.unbinds).isEqualTo(1)
                assertThat(deferred2.isCompleted).isFalse()

                // The new cycle's callback completes the new obtain normally.
                val freshBinder = mockk<IBinder>(relaxed = true)
                every { freshBinder.queryLocalInterface(any()) } returns null
                conn2.onServiceConnected(componentName, freshBinder)
                val transport = deferred2.await()
                assertThat(transport).isNotNull()
                assertThat(binder.unbinds).isEqualTo(1)
            }
        }

    /**
     * Round 5 hardening (HIGH): two concurrent obtain() callers must share a single
     * in-flight bind cycle. Without single-flight, the second caller would create a
     * fresh ServiceConnection and overwrite the first cycle's connection field —
     * leaving the first connection bound but unreachable through provider state.
     * When the first caller later cancelled or timed out, the per-cycle generation
     * check would skip unbind, and the helper process would leak until close().
     */
    @Test
    fun concurrent_obtain_calls_share_a_single_bind_cycle() =
        runTest(UnconfinedTestDispatcher()) {
            supervisorScope {
                val binder = ManualBinder()
                val provider =
                    ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

                val deferred1 = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.obtain()
                }
                val conn1 = checkNotNull(binder.captured) { "first bind not started" }
                assertThat(binder.bindCalls).isEqualTo(1)

                // Second obtain arrives before any callback fires. Single-flight: must
                // reuse the existing ServiceConnection rather than spawn a second
                // bind cycle (which would leak conn1).
                val deferred2 = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.obtain()
                }
                assertThat(binder.bindCalls).isEqualTo(1)
                assertThat(binder.captured).isSameInstanceAs(conn1)

                // The single in-flight ServiceConnection delivers a transport — both
                // awaiters resume with the same instance.
                val mockBinder = mockk<IBinder>(relaxed = true)
                every { mockBinder.queryLocalInterface(any()) } returns null
                conn1.onServiceConnected(componentName, mockBinder)

                val t1 = deferred1.await()
                val t2 = deferred2.await()
                assertThat(t1).isSameInstanceAs(t2)
                // No leaked binding: the helper is still bound (close() will unbind it).
                assertThat(binder.unbinds).isEqualTo(0)

                provider.close()
                assertThat(binder.unbinds).isEqualTo(1)
            }
        }

    /**
     * Round 5 hardening (HIGH refcount path): when one of two concurrent awaiters
     * cancels before delivery, the bind cycle MUST continue for the remaining
     * awaiter. The cycle is only torn down when the last awaiter abandons it.
     */
    @Test
    fun cancellation_of_one_awaiter_keeps_bind_alive_for_the_other() =
        runTest(UnconfinedTestDispatcher()) {
            supervisorScope {
                val binder = ManualBinder()
                val provider =
                    ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

                val deferred1 = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.obtain()
                }
                val deferred2 = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.obtain()
                }
                val conn = checkNotNull(binder.captured)
                assertThat(binder.bindCalls).isEqualTo(1)

                // Caller 1 walks away. Caller 2 is still waiting, so the bind must
                // not be torn down.
                deferred1.cancel()
                assertThat(binder.unbinds).isEqualTo(0)

                val mockBinder = mockk<IBinder>(relaxed = true)
                every { mockBinder.queryLocalInterface(any()) } returns null
                conn.onServiceConnected(componentName, mockBinder)

                val transport = deferred2.await()
                assertThat(transport).isInstanceOf(UserServiceTransport::class.java)
                assertThat(binder.unbinds).isEqualTo(0)
            }
        }

    /**
     * Round 5 hardening (HIGH refcount terminal path): when ALL awaiters cancel
     * before delivery, the bind cycle is torn down — no helper process is leaked
     * waiting for callers that no longer exist.
     */
    @Test
    fun bind_is_unbound_when_all_awaiters_cancel_before_delivery() =
        runTest(UnconfinedTestDispatcher()) {
            supervisorScope {
                val binder = ManualBinder()
                val provider =
                    ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

                val deferred1 = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.obtain()
                }
                val deferred2 = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.obtain()
                }
                checkNotNull(binder.captured)
                assertThat(binder.bindCalls).isEqualTo(1)

                deferred1.cancel()
                assertThat(binder.unbinds).isEqualTo(0)
                deferred2.cancel()
                // Last awaiter gone: refcount hit 0 and the cycle was torn down.
                assertThat(binder.unbinds).isEqualTo(1)
            }
        }

    /**
     * Round 5 hardening (MEDIUM): a cancellation that arrives AFTER
     * onServiceConnected has delivered the transport must NOT unbind the now-active
     * service. The old single-cont design had a race where the cancellation handler
     * could pass the generation check (success path didn't bump it) and unbind the
     * live connection while leaving `transport` cached — future obtain() would
     * then return a transport backed by a dead binder.
     *
     * The single-flight design forecloses the race: the success path atomically
     * transitions the cycle out of "in-flight" before completing the deferred,
     * so any subsequent cancellation finds no inflight to tear down.
     */
    @Test
    fun cancellation_after_delivery_does_not_unbind_active_transport() =
        runTest(UnconfinedTestDispatcher()) {
            val binder = ManualBinder()
            val provider =
                ShizukuUserServiceProvider(binder = binder, bindTimeoutMs = 10_000L)

            val ownerJob = SupervisorJob()
            val ownerScope = CoroutineScope(coroutineContext + ownerJob)
            val deferred = ownerScope.async(start = CoroutineStart.UNDISPATCHED) {
                provider.obtain()
            }
            val conn = checkNotNull(binder.captured)

            val mockBinder = mockk<IBinder>(relaxed = true)
            every { mockBinder.queryLocalInterface(any()) } returns null
            conn.onServiceConnected(componentName, mockBinder)
            val firstTransport = deferred.await()

            // Cancel the owner scope AFTER delivery. The successful bind must not
            // be torn down.
            ownerJob.cancel()

            assertThat(binder.unbinds).isEqualTo(0)
            // Subsequent obtain returns the same cached transport — proves it was
            // not silently invalidated by the cancellation.
            val second = provider.obtain()
            assertThat(second).isSameInstanceAs(firstTransport)
            assertThat(binder.unbinds).isEqualTo(0)

            // close() is still the only thing that unbinds a delivered transport.
            provider.close()
            assertThat(binder.unbinds).isEqualTo(1)
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
    var bindCalls: Int = 0
        private set
    var unbinds: Int = 0
        private set

    override fun bind(conn: ServiceConnection) {
        captured = conn
        bindCalls++
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
