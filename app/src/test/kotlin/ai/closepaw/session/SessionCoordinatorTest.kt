package ai.closepaw.session

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.Op
import ai.closepaw.protocol.SessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
}
