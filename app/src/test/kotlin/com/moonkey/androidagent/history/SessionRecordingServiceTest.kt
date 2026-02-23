package com.moonkey.androidagent.history

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.storage.SessionStorage
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.test.buildTestContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecordingServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `recordUserMessage persists after debounce`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val service = SessionRecordingService(storage, this)

        service.initializeNewSession(model = "gpt-5.2", appVersion = "1.0")
        val fileName = requireNotNull(service.getCurrentFileName())

        service.recordUserMessage(id = "u1", timestamp = 100L, text = "hello")

        advanceTimeBy(600L)
        advanceUntilIdle()

        val record = storage.readSession(fileName).getOrThrow()
        assertThat(record.messages).hasSize(1)
        val user = record.messages.first() as MessageRecord.User
        assertThat(user.text).isEqualTo("hello")
    }

    @Test
    fun `completeSession marks metadata and summary`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val service = SessionRecordingService(storage, this)

        service.initializeNewSession(model = "gpt-5.2", appVersion = "1.0")
        val fileName = requireNotNull(service.getCurrentFileName())

        service.recordUserMessage(id = "u1", timestamp = 100L, text = "short summary")
        service.startAgentMessage(id = "a1", timestamp = 120L)
        service.appendTextDelta("done")
        service.completeAgentMessage()
        service.completeSession()

        advanceTimeBy(600L)
        advanceUntilIdle()

        val record = storage.readSession(fileName).getOrThrow()
        assertThat(record.metadata.completedNormally).isTrue()
        assertThat(record.summary).isEqualTo("short summary")
        assertThat(record.messages.filterIsInstance<MessageRecord.Agent>()).hasSize(1)
    }

    @Test
    fun `recordAction updates agent message blocks`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val service = SessionRecordingService(storage, this)

        service.initializeNewSession(model = "gpt-5.2", appVersion = "1.0")
        val fileName = requireNotNull(service.getCurrentFileName())

        service.startAgentMessage(id = "a1", timestamp = 100L)
        service.appendTextDelta("doing it")
        service.recordAction(
            actionId = "act-1",
            toolName = "mobile_action",
            description = "Click",
            state = "executing"
        )
        service.updateActionState("act-1", "success", "ok")

        advanceTimeBy(600L)
        advanceUntilIdle()

        val record = storage.readSession(fileName).getOrThrow()
        val agent = record.messages.filterIsInstance<MessageRecord.Agent>().single()
        assertThat(agent.isComplete).isFalse()
        assertThat(agent.contentBlocks).containsAtLeast(
            com.moonkey.androidagent.history.model.ContentBlockRecord.Text("doing it"),
            com.moonkey.androidagent.history.model.ContentBlockRecord.Action(
                id = "act-1",
                toolName = "mobile_action",
                description = "Click",
                state = "success",
                resultSummary = "ok"
            )
        )
    }

    @Test
    fun `completeSession finalizes pending agent buffer before persisting metadata`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val service = SessionRecordingService(storage, this)

        service.initializeNewSession(model = "gpt-5.2", appVersion = "1.0")
        val fileName = requireNotNull(service.getCurrentFileName())

        service.recordUserMessage(id = "u1", timestamp = 100L, text = "hello")
        service.startAgentMessage(id = "a1", timestamp = 120L)
        service.appendTextDelta("final output")
        service.completeSession()

        advanceTimeBy(600L)
        advanceUntilIdle()

        val record = storage.readSession(fileName).getOrThrow()
        val agentMessages = record.messages.filterIsInstance<MessageRecord.Agent>()
        assertThat(agentMessages).hasSize(1)
        assertThat(agentMessages.single().isComplete).isTrue()
        assertThat(agentMessages.single().contentBlocks)
            .contains(com.moonkey.androidagent.history.model.ContentBlockRecord.Text("final output"))
    }

    @Test
    fun `completeSession with ERROR reason marks completedNormally false`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val service = SessionRecordingService(storage, this)

        service.initializeNewSession(model = "gpt-5.2", appVersion = "1.0")
        val fileName = requireNotNull(service.getCurrentFileName())

        service.recordUserMessage(id = "u1", timestamp = 100L, text = "hello")
        service.completeSession(CompletionReason.ERROR)

        advanceTimeBy(600L)
        advanceUntilIdle()

        val record = storage.readSession(fileName).getOrThrow()
        assertThat(record.metadata.completedNormally).isFalse()
    }

    @Test
    fun `completeSession with USER_STOPPED marks completedNormally false`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val service = SessionRecordingService(storage, this)

        service.initializeNewSession(model = "gpt-5.2", appVersion = "1.0")
        val fileName = requireNotNull(service.getCurrentFileName())

        service.recordUserMessage(id = "u1", timestamp = 100L, text = "hello")
        service.completeSession(CompletionReason.USER_STOPPED)

        advanceTimeBy(600L)
        advanceUntilIdle()

        val record = storage.readSession(fileName).getOrThrow()
        assertThat(record.metadata.completedNormally).isFalse()
    }

    @Test
    fun `recordUserMessage preserves finalized agent message`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val service = SessionRecordingService(storage, this)

        service.initializeNewSession(model = "gpt-5.2", appVersion = "1.0")
        val fileName = requireNotNull(service.getCurrentFileName())

        // Start an agent message with content
        service.startAgentMessage(id = "a1", timestamp = 100L)
        service.appendTextDelta("agent response")

        // Recording a user message should finalize the agent message AND preserve it
        service.recordUserMessage(id = "u1", timestamp = 200L, text = "follow up")

        advanceTimeBy(600L)
        advanceUntilIdle()

        val record = storage.readSession(fileName).getOrThrow()
        val agentMessages = record.messages.filterIsInstance<MessageRecord.Agent>()
        val userMessages = record.messages.filterIsInstance<MessageRecord.User>()
        assertThat(agentMessages).hasSize(1)
        assertThat(agentMessages.single().contentBlocks)
            .contains(com.moonkey.androidagent.history.model.ContentBlockRecord.Text("agent response"))
        assertThat(userMessages).hasSize(1)
        assertThat(userMessages.single().text).isEqualTo("follow up")
    }

    @Test
    fun `clearSessionAndAwait completes before new session can be created`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val service = SessionRecordingService(storage, this)

        // Create and populate first session
        val firstId = service.initializeNewSession(sessionId = "first", model = "m1", appVersion = "1.0")
        service.recordUserMessage(id = "u1", timestamp = 100L, text = "hello from first")
        advanceTimeBy(600L)
        advanceUntilIdle()

        // Clear and immediately create a new session
        service.clearSessionAndAwait()
        assertThat(service.hasActiveSession()).isFalse()
        assertThat(service.getCurrentSession()).isNull()
        assertThat(service.getCurrentFileName()).isNull()

        // New session should survive — not be wiped by a late clear
        val secondId = service.initializeNewSession(sessionId = "second", model = "m2", appVersion = "1.0")
        service.recordUserMessage(id = "u2", timestamp = 200L, text = "hello from second")
        advanceTimeBy(600L)
        advanceUntilIdle()

        assertThat(service.hasActiveSession()).isTrue()
        assertThat(service.getCurrentSessionId()).isEqualTo("second")

        val secondFile = requireNotNull(service.getCurrentFileName())
        val record = storage.readSession(secondFile).getOrThrow()
        assertThat(record.sessionId).isEqualTo("second")
        assertThat(record.messages).hasSize(1)
    }
}
