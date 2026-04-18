package ai.closepaw.session

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.Op
import ai.closepaw.protocol.SessionState
import io.mockk.coEvery
import io.mockk.coVerify
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
class SessionCoordinatorTest {

    private fun fakeSession(
        state: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.Idle)
    ): AgentSession {
        val session = mockk<AgentSession>(relaxed = true)
        every { session.state } returns state
        coEvery { session.submit(any()) } returns Unit
        return session
    }

    @Test
    fun `submit with active idle session sends immediately`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Idle)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        val result = coordinator.submit("hello")
        advanceUntilIdle()

        assertThat(result).isEqualTo(SubmitResult.SENT)
        coVerify { session.submit(Op.UserInput("hello")) }
    }

    @Test
    fun `submit while running queues input`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        val result = coordinator.submit("queued-input")
        advanceUntilIdle()

        assertThat(result).isEqualTo(SubmitResult.QUEUED)
        coVerify(exactly = 0) { session.submit(Op.UserInput("queued-input")) }
    }

    @Test
    fun `enqueue then createAndSubmit drains queued inputs after first`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Idle)
        val session = fakeSession(state)

        coordinator.enqueue("queued-before-create")

        val created = coordinator.createAndSubmit("first-input") { session }
        advanceUntilIdle()

        assertThat(created).isEqualTo(CreateResult.Success)
        coVerify { session.submit(Op.UserInput("first-input")) }
        coVerify { session.submit(Op.UserInput("queued-before-create")) }
    }

    @Test
    fun `createAndSubmit with null factory returns Aborted and clears pending inputs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)

        coordinator.enqueue("stale-input")

        val result = coordinator.createAndSubmit("rejected-input") { null }
        advanceUntilIdle()

        assertThat(result).isEqualTo(CreateResult.Aborted)
        assertThat(coordinator.currentSession).isNull()

        // Next session should NOT auto-run the stale or rejected input.
        val state = MutableStateFlow<SessionState>(SessionState.Idle)
        val session = fakeSession(state)
        val created = coordinator.createAndSubmit("fresh-input") { session }
        advanceUntilIdle()

        assertThat(created).isEqualTo(CreateResult.Success)
        coVerify { session.submit(Op.UserInput("fresh-input")) }
        coVerify(exactly = 0) { session.submit(Op.UserInput("stale-input")) }
        coVerify(exactly = 0) { session.submit(Op.UserInput("rejected-input")) }
    }

    @Test
    fun `submit without session returns NO_SESSION`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)

        val result = coordinator.submit("anything")

        assertThat(result).isEqualTo(SubmitResult.NO_SESSION)
    }

    @Test
    fun `consumeDeadSessionFileName via shutdown state returns filename once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Shutdown)
        val session = mockk<AgentSession>(relaxed = true)
        every { session.state } returns state
        val services = mockk<SessionServices>(relaxed = true)
        every { session.getServices() } returns services
        every { services.recordingService.getCurrentFileName() } returns "dead-session.json"
        coEvery { session.submit(any()) } returns Unit

        coordinator.attachSession(session)
        advanceUntilIdle()

        val result = coordinator.submit("input-while-dead")
        advanceUntilIdle()

        assertThat(result).isEqualTo(SubmitResult.SESSION_DEAD)
        assertThat(coordinator.consumeDeadSessionFileName()).isEqualTo("dead-session.json")
        assertThat(coordinator.consumeDeadSessionFileName()).isNull()
    }

    @Test
    fun `clearSession tears down state and shuts down session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Idle)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        coordinator.enqueue("to-be-cleared")
        coordinator.clearSession()
        advanceUntilIdle()

        coVerify { session.submit(Op.Shutdown) }
        assertThat(coordinator.currentSession).isNull()
        assertThat(coordinator.consumeDeadSessionFileName()).isNull()

        val resultAfter = coordinator.submit("after-clear")
        assertThat(resultAfter).isEqualTo(SubmitResult.NO_SESSION)
    }

    // --- SubmitResult guard coverage (every state branch in submit) ---

    @Test
    fun `submit with Created state sends immediately`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Created)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        val result = coordinator.submit("hi-from-created")
        advanceUntilIdle()

        assertThat(result).isEqualTo(SubmitResult.SENT)
        coVerify { session.submit(Op.UserInput("hi-from-created")) }
    }

    @Test
    fun `submit while Paused queues input`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Paused)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        val result = coordinator.submit("paused-input")
        advanceUntilIdle()

        assertThat(result).isEqualTo(SubmitResult.QUEUED)
        coVerify(exactly = 0) { session.submit(Op.UserInput("paused-input")) }
    }

    @Test
    fun `submit while TakeoverPending queues input`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.TakeoverPending)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        val result = coordinator.submit("takeover-input")
        advanceUntilIdle()

        assertThat(result).isEqualTo(SubmitResult.QUEUED)
        coVerify(exactly = 0) { session.submit(Op.UserInput("takeover-input")) }
    }

    // --- Drain on busy→idle transition: FIFO + state-change abort ---

    @Test
    fun `state observer drains queued inputs in FIFO order on Running to Idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        coordinator.submit("a")
        coordinator.submit("b")
        coordinator.submit("c")
        advanceUntilIdle()
        coVerify(exactly = 0) { session.submit(any<Op.UserInput>()) }

        state.value = SessionState.Idle
        advanceUntilIdle()

        coVerify { session.submit(Op.UserInput("a")) }
        coVerify { session.submit(Op.UserInput("b")) }
        coVerify { session.submit(Op.UserInput("c")) }
    }

    @Test
    fun `state observer drains on transition to Created`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        coordinator.submit("queued")
        advanceUntilIdle()

        state.value = SessionState.Created
        advanceUntilIdle()

        coVerify { session.submit(Op.UserInput("queued")) }
    }

    @Test
    fun `drain stops mid-loop when state transitions away from Idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val session = mockk<AgentSession>(relaxed = true)
        every { session.state } returns state
        // First UserInput drains "a" and flips state to Running mid-loop;
        // remaining inputs must stay queued.
        coEvery { session.submit(Op.UserInput("a")) } answers {
            state.value = SessionState.Running
        }
        coEvery { session.submit(Op.UserInput("b")) } returns Unit
        coEvery { session.submit(Op.UserInput("c")) } returns Unit

        coordinator.attachSession(session)
        advanceUntilIdle()

        coordinator.submit("a")
        coordinator.submit("b")
        coordinator.submit("c")
        advanceUntilIdle()

        state.value = SessionState.Idle
        advanceUntilIdle()

        coVerify(exactly = 1) { session.submit(Op.UserInput("a")) }
        coVerify(exactly = 0) { session.submit(Op.UserInput("b")) }
        coVerify(exactly = 0) { session.submit(Op.UserInput("c")) }
    }

    @Test
    fun `state observer does not drain on Shutdown transition`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        coordinator.submit("x")
        advanceUntilIdle()

        state.value = SessionState.Shutdown
        advanceUntilIdle()

        coVerify(exactly = 0) { session.submit(Op.UserInput("x")) }
    }

    // --- CreateResult coverage ---

    @Test
    fun `concurrent createAndSubmit returns LockBusy`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Idle)
        val session = fakeSession(state)
        val gate = CompletableDeferred<Unit>()

        val firstJob = scope.launch {
            coordinator.createAndSubmit("first") {
                gate.await()
                session
            }
        }
        runCurrent() // first acquires tryLock and is parked on gate

        val second = coordinator.createAndSubmit("second") { fakeSession() }
        assertThat(second).isEqualTo(CreateResult.LockBusy)

        gate.complete(Unit)
        firstJob.join()
        advanceUntilIdle()

        coVerify { session.submit(Op.UserInput("first")) }
        coVerify(exactly = 0) { session.submit(Op.UserInput("second")) }
    }

    // --- attach / detach side-effects ---

    @Test
    fun `detachSession clears state without shutting down session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        coordinator.enqueue("dropped-on-detach")
        advanceUntilIdle()

        coordinator.detachSession()
        advanceUntilIdle()

        assertThat(coordinator.currentSession).isNull()
        coVerify(exactly = 0) { session.submit(Op.Shutdown) }

        // Even if state would have triggered drain, observer is gone.
        state.value = SessionState.Idle
        advanceUntilIdle()
        coVerify(exactly = 0) { session.submit(Op.UserInput("dropped-on-detach")) }

        // After detach, submit reports NO_SESSION.
        assertThat(coordinator.submit("after")).isEqualTo(SubmitResult.NO_SESSION)
    }

    @Test
    fun `attachSession replaces previous observer so old session no longer drains`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)

        val firstState = MutableStateFlow<SessionState>(SessionState.Running)
        val firstSession = fakeSession(firstState)
        coordinator.attachSession(firstSession)
        advanceUntilIdle()

        val secondState = MutableStateFlow<SessionState>(SessionState.Running)
        val secondSession = fakeSession(secondState)
        coordinator.attachSession(secondSession)
        advanceUntilIdle()

        coordinator.submit("queued-on-second")
        advanceUntilIdle()

        // Old session transition must not trigger drain (its observer was cancelled).
        firstState.value = SessionState.Idle
        advanceUntilIdle()
        coVerify(exactly = 0) { firstSession.submit(Op.UserInput("queued-on-second")) }

        // New session transition drains.
        secondState.value = SessionState.Idle
        advanceUntilIdle()
        coVerify { secondSession.submit(Op.UserInput("queued-on-second")) }
    }

    // --- consumeDeadSessionFileName when no death recorded ---

    @Test
    fun `consumeDeadSessionFileName returns null when no session has died`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)

        assertThat(coordinator.consumeDeadSessionFileName()).isNull()
    }

    @Test
    fun `clearSession does not produce dead-session filename`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = SessionCoordinator(scope)
        val state = MutableStateFlow<SessionState>(SessionState.Idle)
        val session = fakeSession(state)
        coordinator.attachSession(session)
        advanceUntilIdle()

        coordinator.clearSession()
        advanceUntilIdle()

        assertThat(coordinator.consumeDeadSessionFileName()).isNull()
    }
}
