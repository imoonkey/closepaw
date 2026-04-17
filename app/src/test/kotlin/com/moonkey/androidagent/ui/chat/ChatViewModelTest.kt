package com.moonkey.androidagent.ui.chat

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.protocol.SessionState
import com.moonkey.androidagent.protocol.TaskStarted
import com.moonkey.androidagent.session.AgentSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeSession(
        events: MutableSharedFlow<com.moonkey.androidagent.protocol.AgentEvent> =
            MutableSharedFlow(replay = 8, extraBufferCapacity = 64),
        state: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.Idle),
    ): AgentSession {
        val session = mockk<AgentSession>(relaxed = true)
        every { session.events } returns events
        every { session.state } returns state
        coEvery { session.submit(any()) } returns Unit
        return session
    }

    @Test
    fun `startEventCollection subscribes to events and feeds reducer`() = runTest {
        val events =
            MutableSharedFlow<com.moonkey.androidagent.protocol.AgentEvent>(
                replay = 8,
                extraBufferCapacity = 64
            )
        val session = fakeSession(events = events)
        val vm = ChatViewModel(sessionProvider = { session })

        vm.startEventCollection(session)
        advanceUntilIdle()

        events.emit(
            TaskStarted(
                sessionId = SessionId("sess-1"),
                timestamp = 100L,
                taskId = "task-1",
                input = "hello world"
            )
        )
        advanceUntilIdle()

        assertThat(vm.uiState.value.showEmptyState).isFalse()
        assertThat(vm.messages).isNotEmpty()
    }

    @Test
    fun `pending input is consumed and submitted to active session`() = runTest {
        val session = fakeSession()
        val vm = ChatViewModel(sessionProvider = { session })

        vm.reportStartupFailure("Open Settings", "boot failed")
        assertThat(vm.pendingInput.value).isEqualTo("Open Settings")
        assertThat(vm.startupError.value).isEqualTo("boot failed")

        val preserved = vm.consumePendingInput()
        assertThat(preserved).isEqualTo("Open Settings")
        assertThat(vm.pendingInput.value).isEmpty()

        vm.sendMessage(preserved)
        advanceUntilIdle()

        coVerify { session.submit(Op.UserInput("Open Settings")) }
    }

    @Test
    fun `teardown cancels event collection job`() = runTest {
        val events =
            MutableSharedFlow<com.moonkey.androidagent.protocol.AgentEvent>(
                replay = 8,
                extraBufferCapacity = 64
            )
        val session = fakeSession(events = events)
        val vm = ChatViewModel(sessionProvider = { session })

        vm.startEventCollection(session)
        advanceUntilIdle()

        val jobField = ChatViewModel::class.java.getDeclaredField("eventCollectionJob")
        jobField.isAccessible = true
        val job = jobField.get(vm) as Job
        assertThat(job.isActive).isTrue()

        val onCleared = ChatViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(vm)
        advanceUntilIdle()

        assertThat(job.isCancelled).isTrue()
        assertThat(job.isActive).isFalse()
    }
}
