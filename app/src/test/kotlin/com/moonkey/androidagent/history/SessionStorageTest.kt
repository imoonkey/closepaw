package com.moonkey.androidagent.history

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.model.SessionRecord
import com.moonkey.androidagent.history.storage.SessionStorage
import com.moonkey.androidagent.test.buildTestContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `write and read session round trip`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)
        val record = SessionRecord(
            sessionId = "session-1",
            startTime = 100L,
            lastUpdated = 200L,
            messages = listOf(
                MessageRecord.User(id = "u1", timestamp = 150L, text = "hello")
            )
        )
        val fileName = storage.generateFileName(record.sessionId)

        val writeResult = storage.writeSession(fileName, record)
        assertThat(writeResult.isSuccess).isTrue()

        val readResult = storage.readSession(fileName).getOrThrow()
        assertThat(readResult.sessionId).isEqualTo("session-1")
        assertThat(readResult.messages).hasSize(1)
        val user = readResult.messages.first() as MessageRecord.User
        assertThat(user.text).isEqualTo("hello")
    }

    @Test
    fun `listSessionFiles returns newest first`() = runTest {
        val context = buildTestContext(tempFolder.newFolder("files"))
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val storage = SessionStorage(context, ioDispatcher)

        val record1 = SessionRecord(
            sessionId = "s1",
            startTime = 1L,
            lastUpdated = 2L,
            messages = emptyList()
        )
        val record2 = SessionRecord(
            sessionId = "s2",
            startTime = 3L,
            lastUpdated = 4L,
            messages = emptyList()
        )
        val file1 = storage.generateFileName(record1.sessionId)
        val file2 = storage.generateFileName(record2.sessionId)

        storage.writeSession(file1, record1)
        storage.writeSession(file2, record2)

        // Force ordering by touching modified times.
        storage.getSessionFile(file1).setLastModified(1000L)
        storage.getSessionFile(file2).setLastModified(2000L)

        val files = storage.listSessionFiles()
        assertThat(files).hasSize(2)
        assertThat(files[0].name).isEqualTo(file2)
        assertThat(files[1].name).isEqualTo(file1)
    }
}
