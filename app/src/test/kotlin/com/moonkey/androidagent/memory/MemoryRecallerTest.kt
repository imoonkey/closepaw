package com.moonkey.androidagent.memory

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryRecallerTest {

    @get:Rule val tempDir = TemporaryFolder()
    private lateinit var store: MemoryStore
    private lateinit var recaller: MemoryRecaller

    @Before
    fun setUp() {
        val memoryDir = tempDir.newFolder("memory")
        store = MemoryStore(memoryDir)
        recaller = MemoryRecaller(store)
    }

    @Test
    fun `recall returns null when no memory exists`() {
        assertThat(recaller.recall("com.example.app")).isNull()
    }

    @Test
    fun `recall returns null with null package and no global memory`() {
        assertThat(recaller.recall(null)).isNull()
    }

    @Test
    fun `recall includes app memory when package matches`() {
        store.appendAppMemory("com.example.app", "[pitfall] Button is hidden behind keyboard")
        val result = recaller.recall("com.example.app")
        assertThat(result).isNotNull()
        assertThat(result).contains("## Recalled Memory")
        assertThat(result).contains("### App: com.example.app")
        assertThat(result).contains("[pitfall] Button is hidden behind keyboard")
    }

    @Test
    fun `recall includes user prefs and device memory`() {
        store.appendUserPref("Prefers English")
        store.appendDeviceMemory("Pixel 7, 1080x2400")
        val result = recaller.recall(null)
        assertThat(result).isNotNull()
        assertThat(result).contains("### Device")
        assertThat(result).contains("Pixel 7, 1080x2400")
        assertThat(result).contains("### User Preferences")
        assertThat(result).contains("Prefers English")
    }

    @Test
    fun `recall does not include unrelated app memory`() {
        store.appendAppMemory("com.other.app", "Some memory")
        val result = recaller.recall("com.example.app")
        assertThat(result).isNull()
    }

    @Test
    fun `recall combines all available sources`() {
        store.appendDeviceMemory("Test device")
        store.appendUserPref("Test pref")
        store.appendAppMemory("com.test", "Test app memory")
        val result = recaller.recall("com.test")!!
        assertThat(result).contains("### Device")
        assertThat(result).contains("### User Preferences")
        assertThat(result).contains("### App: com.test")
    }

    @Test
    fun `recall respects total budget`() {
        // Fill up memory with entries (within cap, but large content)
        for (i in 1..30) {
            store.appendAppMemory("com.test", "[workflow] ${"x".repeat(200)} entry $i")
        }
        val result = recaller.recall("com.test")!!
        // Total should be within 6KB + header overhead
        assertThat(result.toByteArray().size).isLessThan(7000)
    }

    @Test
    fun `recall header text is correct`() {
        store.appendAppMemory("com.test", "Test")
        val result = recaller.recall("com.test")!!
        assertThat(result).startsWith("## Recalled Memory")
        assertThat(result).contains("learnings from previous sessions")
    }
}
