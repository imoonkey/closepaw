package com.moonkey.androidagent.history

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.storage.SessionStorage
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
}
