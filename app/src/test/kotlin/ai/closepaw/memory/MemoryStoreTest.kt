package ai.closepaw.memory

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
        val content = store.read(MemoryScope.APP, "com.example.app")
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
        val content = store.read(MemoryScope.USER)
        assertThat(content).contains("# User Memory")
        assertThat(content).contains("## Facts")
        assertThat(content).contains("## Preferences")
        assertThat(content).contains("User prefers dark mode")
    }

    @Test
    fun `appendDeviceFact creates canonical device file`() {
        store.appendDeviceFact("Screen size is 1080x2400")
        val content = store.read(MemoryScope.DEVICE)
        assertThat(content).contains("# Device Memory")
        assertThat(content).contains("## Facts")
        assertThat(content).contains("## Pitfalls")
        assertThat(content).contains("## Verification")
        assertThat(content).contains("Screen size is 1080x2400")
    }

    @Test
    fun `read returns null for nonexistent app`() {
        assertThat(store.read(MemoryScope.APP, "com.nonexistent")).isNull()
    }

    @Test
    fun `read returns null when no user file`() {
        assertThat(store.read(MemoryScope.USER)).isNull()
    }

    @Test
    fun `read returns null when no device file`() {
        assertThat(store.read(MemoryScope.DEVICE)).isNull()
    }

    @Test
    fun `app entries stay inside their target section`() {
        store.appendAppSkillOverride("com.test", "Search is more reliable than scrolling")
        store.appendAppPreference("com.test", "User prefers search when available")
        store.appendAppOperationalNote("com.test", "Developer Options is under System")

        val content = store.read(MemoryScope.APP, "com.test")!!
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
        val content = short.read(MemoryScope.APP, "com.test")!!
        val entry = content.lines().first { it.trimStart().startsWith("- [") }
        // Content should be truncated — full string would be 57 chars
        assertThat(entry).doesNotContain("exceeds the limit")
        assertThat(entry).contains("This is a very long")
    }

    @Test
    fun `multiple entries append to same section`() {
        store.appendAppOperationalNote("com.test", "First entry")
        store.appendAppOperationalNote("com.test", "Second entry")
        val content = store.read(MemoryScope.APP, "com.test")!!
        val entries = content.lines().filter { it.trimStart().startsWith("- [") }
        assertThat(entries.size).isEqualTo(2)
        assertThat(entries[0]).contains("First entry")
        assertThat(entries[1]).contains("Second entry")
    }

    @Test
    fun `rejects path traversal in package name`() {
        store.appendAppOperationalNote("../../etc/passwd", "malicious content")
        assertThat(store.read(MemoryScope.APP, "../../etc/passwd")).isNull()
    }

    @Test
    fun `rejects package name with slashes`() {
        store.appendAppOperationalNote("com/test/app", "content")
        assertThat(store.read(MemoryScope.APP, "com/test/app")).isNull()
    }

    @Test
    fun `entries use full timestamp format`() {
        store.appendUserFact("User's name is Qi")

        val content = store.read(MemoryScope.USER)!!
        val entry = content.lines().first { it.trimStart().startsWith("- [") }

        assertThat(entry).matches("""- \[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} .+\] .+""")
    }

    // --- Raw API ---

    @Test
    fun `read returns raw file content unchanged when written via write`() {
        val freeText = """
            # User Memory

            ## Custom Heading
            Some free text the user typed.

            ## Facts
            - Not a timestamped bullet.
            """.trimIndent() + "\n"
        val result = store.write(MemoryScope.USER, content = freeText)
        assertThat(result).isEqualTo(SaveResult.Success)
        assertThat(store.read(MemoryScope.USER)).isEqualTo(freeText)
    }

    @Test
    fun `write rejects content exceeding file byte cap`() {
        val store2 = MemoryStore(memoryDir, maxFileBytes = 64)
        val tooBig = "x".repeat(128)
        assertThat(store2.write(MemoryScope.USER, content = tooBig)).isEqualTo(SaveResult.TooLarge)
        assertThat(store2.read(MemoryScope.USER)).isNull()
    }

    @Test
    fun `write returns InvalidScope for app scope missing package name`() {
        val result = store.write(MemoryScope.APP, packageName = null, content = "x")
        assertThat(result).isEqualTo(SaveResult.InvalidScope)
    }

    @Test
    fun `delete removes file when present`() {
        store.appendUserFact("temp")
        assertThat(store.read(MemoryScope.USER)).isNotNull()
        assertThat(store.delete(MemoryScope.USER)).isTrue()
        assertThat(store.read(MemoryScope.USER)).isNull()
    }

    @Test
    fun `delete returns true when file does not exist`() {
        assertThat(store.delete(MemoryScope.USER)).isTrue()
    }

    @Test
    fun `listAppPackages enumerates only safe md files`() {
        store.appendAppOperationalNote("com.alpha", "x")
        store.appendAppOperationalNote("com.beta", "y")
        // Drop a junk file directly that shouldn't be reported.
        File(memoryDir, "apps").mkdirs()
        File(memoryDir, "apps/not-an-app.txt").writeText("ignore me")
        File(memoryDir, "apps/bad name.md").writeText("ignore me")

        val pkgs = store.listAppPackages()
        assertThat(pkgs).containsExactly("com.alpha", "com.beta").inOrder()
    }

    @Test
    fun `listAppPackages returns empty when no apps dir`() {
        assertThat(store.listAppPackages()).isEmpty()
    }

    // --- Sanitation ---

    @Test
    fun `sanitation folds newlines to space before stripping control chars`() {
        store.appendUserFact("foo\nbar")
        val content = store.read(MemoryScope.USER)!!
        val entry = content.lines().first { it.trimStart().startsWith("- [") }
        assertThat(entry).contains("foo bar")
        assertThat(entry).doesNotContain("foobar")
    }

    @Test
    fun `sanitation strips remaining control characters after folding whitespace`() {
        store.appendUserFact("hello world")
        val content = store.read(MemoryScope.USER)!!
        val entry = content.lines().first { it.trimStart().startsWith("- [") }
        assertThat(entry).contains("helloworld")
    }

    @Test
    fun `sanitation collapses runs of whitespace`() {
        store.appendUserFact("foo   \t\nbar")
        val content = store.read(MemoryScope.USER)!!
        val entry = content.lines().first { it.trimStart().startsWith("- [") }
        assertThat(entry).contains("foo bar")
    }

    @Test
    fun `sanitation rejects content that becomes empty`() {
        assertThat(store.appendUserFact("   \n\t  ")).isFalse()
        assertThat(store.read(MemoryScope.USER)).isNull()
    }

    // --- Append insertion rules ---

    @Test
    fun `append on new file creates full skeleton with entry under target`() {
        assertThat(store.appendDevicePitfall("BACK dismisses keyboard first")).isTrue()
        val content = store.read(MemoryScope.DEVICE)!!
        val pitfallIdx = content.indexOf("## Pitfalls")
        assertThat(pitfallIdx).isAtLeast(0)
        val afterPitfall = content.substring(pitfallIdx)
        assertThat(afterPitfall.lines()[1]).startsWith("- [")
        assertThat(afterPitfall.lines()[1]).contains("BACK dismisses keyboard first")
    }

    @Test
    fun `append on file with missing heading appends new section at EOF`() {
        // Write a hand-edited file that only has the title and Facts.
        val raw = "# User Memory\n\n## Facts\n- [2026-05-17 10:00:00 UTC] existing fact\n"
        store.write(MemoryScope.USER, content = raw)

        assertThat(store.appendUserPreference("New preference body")).isTrue()
        val content = store.read(MemoryScope.USER)!!
        assertThat(content).startsWith(raw)
        assertThat(content).contains("## Preferences\n")
        assertThat(content).contains("New preference body")
        // EOF append: preferences heading appears AFTER existing facts.
        assertThat(content.indexOf("## Facts")).isLessThan(content.indexOf("## Preferences"))
    }

    @Test
    fun `append with duplicate heading inserts under last occurrence`() {
        val raw = """
            # User Memory

            ## Preferences
            - [2026-01-01 00:00:00 UTC] first prefs section entry

            ## Facts
            - [2026-01-01 00:00:00 UTC] fact

            ## Preferences
            - [2026-01-02 00:00:00 UTC] later prefs section entry
        """.trimIndent() + "\n"
        store.write(MemoryScope.USER, content = raw)

        assertThat(store.appendUserPreference("inserted under later block")).isTrue()
        val content = store.read(MemoryScope.USER)!!

        val firstBlockEntries = content.substringAfter("## Preferences\n").substringBefore("\n## ")
        assertThat(firstBlockEntries).doesNotContain("inserted under later block")

        val lastBlock = content.substringAfterLast("## Preferences\n")
        assertThat(lastBlock).contains("later prefs section entry")
        assertThat(lastBlock).contains("inserted under later block")
        // New entry sits after the existing one, before any trailing whitespace/EOF.
        val lastBlockLines = lastBlock.trim().lines()
        assertThat(lastBlockLines.last()).contains("inserted under later block")
    }

    @Test
    fun `append under heading followed by free text inserts before next heading`() {
        val raw = """
            # User Memory

            ## Facts
            - [2026-01-01 00:00:00 UTC] existing fact

            Free text added by the user after the bullet.

            ## Preferences
            - [2026-01-01 00:00:00 UTC] pref
        """.trimIndent() + "\n"
        store.write(MemoryScope.USER, content = raw)

        assertThat(store.appendUserFact("new fact via append")).isTrue()
        val content = store.read(MemoryScope.USER)!!

        // Free text must still be present.
        assertThat(content).contains("Free text added by the user after the bullet.")
        // New entry inserted under Facts, BEFORE Preferences heading.
        val factsSection = content.substringAfter("## Facts\n").substringBefore("\n## ")
        assertThat(factsSection).contains("new fact via append")
        assertThat(factsSection).contains("existing fact")
        // Preferences block stays intact.
        assertThat(content).contains("## Preferences\n- [2026-01-01 00:00:00 UTC] pref")
    }

    @Test
    fun `append rejected when resulting file would exceed cap`() {
        val store2 = MemoryStore(memoryDir, maxFileBytes = 512)
        val padding = "x".repeat(400)
        val primed = "# User Memory\n\n## Facts\n- $padding\n\n## Preferences\n"
        assertThat(store2.write(MemoryScope.USER, content = primed)).isEqualTo(SaveResult.Success)
        val ok = store2.appendUserPreference("z".repeat(300))
        assertThat(ok).isFalse()
        val content = store2.read(MemoryScope.USER)!!
        assertThat(content).contains(padding)
        assertThat(content).doesNotContain("z".repeat(300))
    }
}
