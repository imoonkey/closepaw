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
    fun `recall includes full app memory file when package matches`() {
        store.appendAppOperationalNote("com.example.app", "Button is hidden behind keyboard")
        val result = recaller.recall("com.example.app")
        assertThat(result).isNotNull()
        assertThat(result).contains("## Recalled Memory")
        assertThat(result).contains("# App Memory: com.example.app")
        assertThat(result).contains("## Operational Notes")
        assertThat(result).contains("Button is hidden behind keyboard")
    }

    @Test
    fun `recall includes user and device memory`() {
        store.appendUserPreference("Prefers English")
        store.appendDeviceFact("Pixel 7, 1080x2400")
        val result = recaller.recall(null)
        assertThat(result).isNotNull()
        assertThat(result).contains("# User Memory")
        assertThat(result).contains("## Preferences")
        assertThat(result).contains("# Device Memory")
        assertThat(result).contains("## Facts")
        assertThat(result).contains("Pixel 7, 1080x2400")
        assertThat(result).contains("Prefers English")
    }

    @Test
    fun `recall does not include unrelated app memory`() {
        store.appendAppOperationalNote("com.other.app", "Some memory")
        val result = recaller.recall("com.example.app")
        assertThat(result).isNull()
    }

    @Test
    fun `recall combines all available sources in deterministic order`() {
        store.appendUserPreference("Test pref")
        store.appendDeviceFact("Test device")
        store.appendAppOperationalNote("com.test", "Test app memory")
        val result = recaller.recall("com.test")!!
        assertThat(result.indexOf("# User Memory")).isLessThan(result.indexOf("# Device Memory"))
        assertThat(result.indexOf("# Device Memory")).isLessThan(result.indexOf("# App Memory: com.test"))
    }

    @Test
    fun `recall omits missing files but keeps existing ones`() {
        store.appendDevicePitfall("BACK may dismiss keyboard first")

        val result = recaller.recall(null)!!

        assertThat(result).contains("# Device Memory")
        assertThat(result).doesNotContain("# User Memory")
        assertThat(result).doesNotContain("# App Memory:")
    }

    @Test
    fun `recall header text is correct`() {
        store.appendAppOperationalNote("com.test", "Test")
        val result = recaller.recall("com.test")!!
        assertThat(result).startsWith("## Recalled Memory")
        assertThat(result).contains("durable learnings from previous sessions")
    }
}
