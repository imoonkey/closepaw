package ai.closepaw.agent.cognition.skills

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSkillCatalogTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createSkill(name: String, description: String, body: String = "Instructions.") {
        val dir = tempDir.newFolder(name)
        dir.resolve("SKILL.md").writeText(
            """
            |---
            |name: $name
            |description: $description
            |---
            |$body
            """.trimMargin()
        )
    }

    @Test
    fun `discovers valid skills one level deep`() {
        createSkill("calendar-date-math", "Compute date ranges.")
        createSkill("image-table-reading", "Extract tabular values.")

        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).hasSize(2)
        assertThat(catalog.entries).containsKey("calendar-date-math")
        assertThat(catalog.entries).containsKey("image-table-reading")
        assertThat(catalog.entries["calendar-date-math"]!!.description)
            .isEqualTo("Compute date ranges.")
    }

    @Test
    fun `entry filePath points to SKILL md`() {
        createSkill("my-skill", "A skill.")

        val catalog = AgentSkillCatalog(tempDir.root)
        val entry = catalog.entries["my-skill"]!!

        assertThat(entry.filePath).endsWith("my-skill/SKILL.md")
        assertThat(java.io.File(entry.filePath).isFile).isTrue()
    }

    @Test
    fun `skips directory without SKILL md`() {
        tempDir.newFolder("empty-dir")
        createSkill("valid-skill", "Valid.")

        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).hasSize(1)
        assertThat(catalog.entries).containsKey("valid-skill")
    }

    @Test
    fun `skips skill with invalid name characters`() {
        val dir = tempDir.newFolder("Invalid_Name")
        dir.resolve("SKILL.md").writeText(
            """
            |---
            |name: Invalid_Name
            |description: Bad name.
            |---
            |Body.
            """.trimMargin()
        )

        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).isEmpty()
    }

    @Test
    fun `skips skill when directory name does not match frontmatter name`() {
        val dir = tempDir.newFolder("dir-name")
        dir.resolve("SKILL.md").writeText(
            """
            |---
            |name: different-name
            |description: Mismatched.
            |---
            |Body.
            """.trimMargin()
        )

        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).isEmpty()
    }

    @Test
    fun `skips skill with missing description`() {
        val dir = tempDir.newFolder("no-desc")
        dir.resolve("SKILL.md").writeText(
            """
            |---
            |name: no-desc
            |---
            |Body.
            """.trimMargin()
        )

        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).isEmpty()
    }

    @Test
    fun `skips skill with missing frontmatter`() {
        val dir = tempDir.newFolder("no-frontmatter")
        dir.resolve("SKILL.md").writeText("Just plain text, no frontmatter.")

        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).isEmpty()
    }

    @Test
    fun `empty directory produces empty catalog`() {
        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).isEmpty()
    }

    @Test
    fun `nonexistent directory produces empty catalog`() {
        val catalog = AgentSkillCatalog(java.io.File(tempDir.root, "does-not-exist"))

        assertThat(catalog.entries).isEmpty()
    }

    @Test
    fun `catalogPrompt returns null when catalog is empty`() {
        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.catalogPrompt()).isNull()
    }

    @Test
    fun `catalogPrompt returns formatted listing`() {
        createSkill("alpha-skill", "Alpha description.")
        createSkill("beta-skill", "Beta description.")

        val catalog = AgentSkillCatalog(tempDir.root)
        val prompt = catalog.catalogPrompt()!!

        assertThat(prompt).startsWith("## Available Skills")
        assertThat(prompt).contains("Call activate_skill")
        assertThat(prompt).contains("- alpha-skill: Alpha description.")
        assertThat(prompt).contains("- beta-skill: Beta description.")
    }

    @Test
    fun `valid skills are discovered alongside invalid ones`() {
        createSkill("good-skill", "Valid skill.")

        val badDir = tempDir.newFolder("BAD")
        badDir.resolve("SKILL.md").writeText(
            """
            |---
            |name: BAD
            |description: Invalid uppercase name.
            |---
            |Body.
            """.trimMargin()
        )

        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).hasSize(1)
        assertThat(catalog.entries).containsKey("good-skill")
    }

    @Test
    fun `name must start with lowercase letter`() {
        val dir = tempDir.newFolder("1-starts-with-digit")
        dir.resolve("SKILL.md").writeText(
            """
            |---
            |name: 1-starts-with-digit
            |description: Starts with digit.
            |---
            |Body.
            """.trimMargin()
        )

        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).isEmpty()
    }

    @Test
    fun `name longer than 64 chars is rejected`() {
        val longName = "a" + "-long".repeat(13) // 66 chars
        val dir = tempDir.newFolder(longName)
        dir.resolve("SKILL.md").writeText(
            """
            |---
            |name: $longName
            |description: Too long.
            |---
            |Body.
            """.trimMargin()
        )

        val catalog = AgentSkillCatalog(tempDir.root)

        assertThat(catalog.entries).isEmpty()
    }
}
