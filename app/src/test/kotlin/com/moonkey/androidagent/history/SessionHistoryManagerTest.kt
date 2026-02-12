package com.moonkey.androidagent.history

import com.google.common.truth.Truth.assertThat
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
class SessionHistoryManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `listSessions returns active session and load works`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val manager = SessionHistoryManager.create(storage, this)

        val sessionId = manager.startNewSession(model = "gpt-5.2", appVersion = "1.0")
        val recording = manager.getRecordingService()
        recording.recordUserMessage(id = "u1", timestamp = 100L, text = "first task")

        advanceTimeBy(600L)
        advanceUntilIdle()

        val sessions = manager.listSessions()
        assertThat(sessions).hasSize(1)
        val info = sessions.single()
        assertThat(info.id).isEqualTo(sessionId)
        assertThat(info.isActive).isTrue()
        assertThat(info.firstUserMessage).isEqualTo("first task")

        val resumed = manager.loadSession(sessionId).getOrThrow()
        assertThat(resumed.session.sessionId).isEqualTo(sessionId)
    }

    @Test
    fun `loadSession fails when session is missing`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val manager = SessionHistoryManager.create(storage, this)

        val result = manager.loadSession("missing-session")

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `startNewSession preserves model and appVersion metadata`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val manager = SessionHistoryManager.create(storage, this)

        val sessionId = manager.startNewSession(model = "gpt-5.2", appVersion = "1.0")

        advanceTimeBy(600L)
        advanceUntilIdle()

        val file = storage.listSessionFiles().single()
        val record = storage.readSession(file.name).getOrThrow()
        assertThat(record.sessionId).isEqualTo(sessionId)
        assertThat(record.sessionId).isNotEqualTo("gpt-5.2")
        assertThat(record.metadata.model).isEqualTo("gpt-5.2")
        assertThat(record.metadata.appVersion).isEqualTo("1.0")
    }
}
