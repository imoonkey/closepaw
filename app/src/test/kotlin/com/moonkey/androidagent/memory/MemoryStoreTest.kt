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
    fun `appendAppOperationalNote creates canonical app file`() {
        store.appendAppOperationalNote("com.example.app", "Open settings via gear icon")
        val content = store.readAppMemory("com.example.app")
        assertThat(content).contains("# App Memory: com.example.app")
        assertThat(content).contains("> Local delta over app skill. If conflict exists, trust this file.")
        assertThat(content).contains("## App Skill Overrides")
        assertThat(content).contains("## Preferences")
        assertThat(content).contains("## Operational Notes")
        assertThat(content).contains("Open settings via gear icon")
    }

    @Test
    fun `appendUserPreference creates canonical user file`() {
        store.appendUserPreference("User prefers dark mode")
        val content = store.readUserMemory()
        assertThat(content).contains("# User Memory")
        assertThat(content).contains("## Facts")
        assertThat(content).contains("## Preferences")
        assertThat(content).contains("User prefers dark mode")
    }

    @Test
    fun `appendDeviceFact creates canonical device file`() {
        store.appendDeviceFact("Screen size is 1080x2400")
        val content = store.readDeviceMemory()
        assertThat(content).contains("# Device Memory")
        assertThat(content).contains("## Facts")
        assertThat(content).contains("## Pitfalls")
        assertThat(content).contains("## Verification")
        assertThat(content).contains("Screen size is 1080x2400")
    }

    @Test
    fun `readAppMemory returns null for nonexistent app`() {
        assertThat(store.readAppMemory("com.nonexistent")).isNull()
    }

    @Test
    fun `readUserMemory returns null when no file`() {
        assertThat(store.readUserMemory()).isNull()
    }

    @Test
    fun `readDeviceMemory returns null when no file`() {
        assertThat(store.readDeviceMemory()).isNull()
    }

    @Test
    fun `app entries stay inside their target section`() {
        store.appendAppSkillOverride("com.test", "Search is more reliable than scrolling")
        store.appendAppPreference("com.test", "User prefers search when available")
        store.appendAppOperationalNote("com.test", "Developer Options is under System")

        val content = store.readAppMemory("com.test")!!
        assertThat(content).contains("## App Skill Overrides\n- [")
        assertThat(content).contains("Search is more reliable than scrolling")
        assertThat(content).contains("## Preferences\n- [")
        assertThat(content).contains("User prefers search when available")
        assertThat(content).contains("## Operational Notes\n- [")
        assertThat(content).contains("Developer Options is under System")
    }

    @Test
    fun `hasWrittenThisSession tracks writes`() {
        assertThat(store.hasWrittenThisSession()).isFalse()
        store.appendAppOperationalNote("com.test", "something")
        assertThat(store.hasWrittenThisSession()).isTrue()
    }

    @Test
    fun `content is truncated to maxContentLength`() {
        val short = MemoryStore(memoryDir, maxContentLength = 20)
        short.appendAppOperationalNote("com.test", "This is a very long content string that exceeds the limit")
        val content = short.readAppMemory("com.test")!!
        val entry = content.lines().first { it.trimStart().startsWith("- [") }
        // Content should be truncated — full string would be 57 chars
        assertThat(entry).doesNotContain("exceeds the limit")
        assertThat(entry).contains("This is a very long")
    }

    @Test
    fun `multiple entries append to same section`() {
        store.appendAppOperationalNote("com.test", "First entry")
        store.appendAppOperationalNote("com.test", "Second entry")
        val content = store.readAppMemory("com.test")!!
        val entries = content.lines().filter { it.trimStart().startsWith("- [") }
        assertThat(entries.size).isEqualTo(2)
        assertThat(entries[0]).contains("First entry")
        assertThat(entries[1]).contains("Second entry")
    }

    @Test
    fun `rejects path traversal in package name`() {
        store.appendAppOperationalNote("../../etc/passwd", "malicious content")
        assertThat(store.readAppMemory("../../etc/passwd")).isNull()
    }

    @Test
    fun `rejects package name with slashes`() {
        store.appendAppOperationalNote("com/test/app", "content")
        assertThat(store.readAppMemory("com/test/app")).isNull()
    }

    @Test
    fun `legacy kind prefixes are stripped from operational notes`() {
        store.appendAppOperationalNote("com.test", "[pitfall] BACK may dismiss keyboard first")

        val content = store.readAppMemory("com.test")!!

        assertThat(content).doesNotContain("[pitfall]")
        assertThat(content).contains("BACK may dismiss keyboard first")
    }

    @Test
    fun `entries use full timestamp format`() {
        store.appendUserFact("User's name is Qi")

        val content = store.readUserMemory()!!
        val entry = content.lines().first { it.trimStart().startsWith("- [") }

        assertThat(entry).matches("""- \[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} .+\] .+""")
    }
}
