package com.moonkey.androidagent.memory

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStoreTest {

    @get:Rule val tempDir = TemporaryFolder()
    private lateinit var memoryDir: File
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        memoryDir = tempDir.newFolder("memory")
        store = MemoryStore(memoryDir)
    }

    @Test
    fun `appendAppMemory creates file and writes entry`() {
        store.appendAppMemory("com.example.app", "[workflow] Open settings via gear icon")
        val content = store.readAppMemory("com.example.app")
        assertThat(content).contains("# App Memory: com.example.app")
        assertThat(content).contains("[workflow] Open settings via gear icon")
    }

    @Test
    fun `appendUserPref creates file and writes entry`() {
        store.appendUserPref("[workflow] User prefers dark mode")
        val content = store.readUserPrefs()
        assertThat(content).contains("# User Preferences")
        assertThat(content).contains("[workflow] User prefers dark mode")
    }

    @Test
    fun `appendDeviceMemory creates file and writes entry`() {
        store.appendDeviceMemory("Screen size is 1080x2400")
        val content = store.readDevice()
        assertThat(content).contains("# Device")
        assertThat(content).contains("Screen size is 1080x2400")
    }

    @Test
    fun `readAppMemory returns null for nonexistent app`() {
        assertThat(store.readAppMemory("com.nonexistent")).isNull()
    }

    @Test
    fun `readUserPrefs returns null when no file`() {
        assertThat(store.readUserPrefs()).isNull()
    }

    @Test
    fun `readDevice returns null when no file`() {
        assertThat(store.readDevice()).isNull()
    }

    @Test
    fun `entry cap enforcement removes oldest entries`() {
        val small = MemoryStore(memoryDir)
        // Write more than APP_ENTRY_CAP entries
        for (i in 1..35) {
            small.appendAppMemory("com.test", "Entry $i")
        }
        val content = store.readAppMemory("com.test")!!
        val entries = content.lines().filter { it.trimStart().startsWith("- [") }
        assertThat(entries.size).isEqualTo(MemoryStore.APP_ENTRY_CAP)
        // Oldest should be gone, newest should remain
        assertThat(entries.last()).contains("Entry 35")
        assertThat(entries.first()).contains("Entry 6")
    }

    @Test
    fun `readOnly mode skips writes`() {
        val readOnly = MemoryStore(memoryDir, readOnly = true)
        readOnly.appendAppMemory("com.test", "should not be saved")
        assertThat(store.readAppMemory("com.test")).isNull()
    }

    @Test
    fun `hasWrittenThisSession tracks writes`() {
        assertThat(store.hasWrittenThisSession()).isFalse()
        store.appendAppMemory("com.test", "something")
        assertThat(store.hasWrittenThisSession()).isTrue()
    }

    @Test
    fun `readOnly mode does not set hasWrittenThisSession`() {
        val readOnly = MemoryStore(memoryDir, readOnly = true)
        readOnly.appendAppMemory("com.test", "ignored")
        assertThat(readOnly.hasWrittenThisSession()).isFalse()
    }

    @Test
    fun `content is truncated to maxContentLength`() {
        val short = MemoryStore(memoryDir, maxContentLength = 20)
        short.appendAppMemory("com.test", "This is a very long content string that exceeds the limit")
        val content = short.readAppMemory("com.test")!!
        val entry = content.lines().first { it.trimStart().startsWith("- [") }
        // Content should be truncated — full string would be 57 chars
        assertThat(entry).doesNotContain("exceeds the limit")
        assertThat(entry).contains("This is a very long")
    }

    @Test
    fun `multiple entries append to same file`() {
        store.appendAppMemory("com.test", "First entry")
        store.appendAppMemory("com.test", "Second entry")
        val content = store.readAppMemory("com.test")!!
        val entries = content.lines().filter { it.trimStart().startsWith("- [") }
        assertThat(entries.size).isEqualTo(2)
        assertThat(entries[0]).contains("First entry")
        assertThat(entries[1]).contains("Second entry")
    }

    @Test
    fun `rejects path traversal in package name`() {
        store.appendAppMemory("../../etc/passwd", "malicious content")
        assertThat(store.readAppMemory("../../etc/passwd")).isNull()
    }

    @Test
    fun `rejects package name with slashes`() {
        store.appendAppMemory("com/test/app", "content")
        assertThat(store.readAppMemory("com/test/app")).isNull()
    }
}
