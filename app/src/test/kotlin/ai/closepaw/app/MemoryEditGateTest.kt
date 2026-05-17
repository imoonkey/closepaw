package ai.closepaw.app

import ai.closepaw.protocol.SessionState
import ai.closepaw.session.AgentSession
import ai.closepaw.session.SessionCoordinator
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryEditGateTest {

    private fun fakeSession(
        state: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.Idle)
    ): AgentSession {
        val session = mockk<AgentSession>(relaxed = true)
        every { session.state } returns state
        coEvery { session.submit(any()) } returns Unit
        return session
    }

    @Test
    fun `initial value is true before any upstream emission`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)

        // Safe default before stateIn collects first emission.
        assertThat(gate.memoryEditLocked.value).isTrue()
    }

    @Test
    fun `unlocked when no session and coordinator has emitted null`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)

        // Let the eager stateIn collect the initial null from coordinator.
        advanceUntilIdle()

        assertThat(gate.memoryEditLocked.value).isFalse()
    }

    @Test
    fun `locked while session is Created Running Idle TakeoverPending Paused`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)
        val state = MutableStateFlow<SessionState>(SessionState.Created)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        assertThat(gate.memoryEditLocked.value).isTrue()

        for (s in listOf(
            SessionState.Running,
            SessionState.Idle,
            SessionState.TakeoverPending,
            SessionState.Paused,
        )) {
            state.value = s
            advanceUntilIdle()
            assertThat(gate.memoryEditLocked.value).isTrue()
        }
    }

    @Test
    fun `unlocked when session reaches Shutdown`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()
        assertThat(gate.memoryEditLocked.value).isTrue()

        state.value = SessionState.Shutdown
        advanceUntilIdle()

        assertThat(gate.memoryEditLocked.value).isFalse()
    }

    @Test
    fun `Shutdown to Created flips lock back to true within next emission`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)
        val state = MutableStateFlow<SessionState>(SessionState.Shutdown)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()
        assertThat(gate.memoryEditLocked.value).isFalse()

        state.value = SessionState.Created
        advanceUntilIdle()

        assertThat(gate.memoryEditLocked.value).isTrue()
    }

    @Test
    fun `creation suspending while no AgentSession exists yet — gate remains locked`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)
        advanceUntilIdle()
        assertThat(gate.memoryEditLocked.value).isFalse()

        val sessionGate = CompletableDeferred<Unit>()
        val state = MutableStateFlow<SessionState>(SessionState.Idle)
        val session = fakeSession(state)

        scope.launch {
            coordinator.createAndSubmit("first") {
                sessionGate.await()
                session
            }
        }
        runCurrent() // first call enters createAndSubmit and parks on sessionGate
        advanceUntilIdle()

        // No AgentSession exists yet, but the gate must already be locked because
        // createAndSubmit emitted Created before invoking the suspending create block.
        assertThat(coordinator.currentSession).isNull()
        assertThat(coordinator.currentSessionState.value).isEqualTo(SessionState.Created)
        assertThat(gate.memoryEditLocked.value).isTrue()

        sessionGate.complete(Unit)
        advanceUntilIdle()

        assertThat(coordinator.currentSession).isEqualTo(session)
        assertThat(gate.memoryEditLocked.value).isTrue()
    }

    @Test
    fun `aborted creation unlocks the gate`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)
        advanceUntilIdle()

        coordinator.createAndSubmit("anything") { null }
        advanceUntilIdle()

        assertThat(coordinator.currentSessionState.value).isNull()
        assertThat(gate.memoryEditLocked.value).isFalse()
    }

    @Test
    fun `thrown creation unlocks the gate`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)
        advanceUntilIdle()

        try {
            coordinator.createAndSubmit("x") { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
            // expected
        }
        advanceUntilIdle()

        assertThat(coordinator.currentSessionState.value).isNull()
        assertThat(gate.memoryEditLocked.value).isFalse()
    }

    @Test
    fun `clearSession unlocks the gate`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)
        val state = MutableStateFlow<SessionState>(SessionState.Idle)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()
        assertThat(gate.memoryEditLocked.value).isTrue()

        coordinator.clearSession()
        advanceUntilIdle()

        assertThat(gate.memoryEditLocked.value).isFalse()
    }

    @Test
    fun `detached non-Shutdown session keeps gate locked until that session shuts down`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()
        assertThat(gate.memoryEditLocked.value).isTrue()

        coordinator.detachSession()
        advanceUntilIdle()

        // Detached but still Running — the session can still append, so the
        // gate must stay locked. Detaching is not shutdown.
        assertThat(gate.memoryEditLocked.value).isTrue()

        state.value = SessionState.Shutdown
        advanceUntilIdle()

        // Now the session is actually done.
        assertThat(gate.memoryEditLocked.value).isFalse()
    }

    @Test
    fun `attachSession of a Running session locks the gate without dispatcher advance`() {
        // No runTest — we must observe the snapshot taken inside attachSession
        // BEFORE the launched collector runs.
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val gate = MemoryEditGate(coordinator, scope)
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val session = fakeSession(state)

        coordinator.attachSession(session)

        // The eager stateIn has initialValue=true; the synchronous snapshot in
        // attachSession sets the upstream to Running; map() runs eagerly so the
        // downstream value reflects locked=true. Either way it must be locked.
        assertThat(gate.memoryEditLocked.value).isTrue()
        assertThat(coordinator.currentSessionState.value).isEqualTo(SessionState.Running)
    }
}
