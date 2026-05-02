package ai.closepaw.agent.cognition.skills

import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledAgentSkillInstallerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `install copies browser use skill into filesDir skills with substituted paths`() {
        val filesDir = tempDir.newFolder("files")
        val skillsDir = File(filesDir, "skills")
        val assets = fakeAssets(browserUseAssets())

        BundledAgentSkillInstaller(assets).install(skillsDir)

        val installedDir = File(skillsDir, "browser-use")
        assertThat(File(installedDir, "SKILL.md").isFile).isTrue()
        assertThat(File(installedDir, BundledAgentSkillInstaller.INSTALL_SENTINEL).isFile).isTrue()
        assertThat(File(installedDir, "scripts/page.js").readText()).isEqualTo("page script")
        assertThat(File(installedDir, "scripts/tabs.js").readText()).isEqualTo("tabs script")
        assertThat(File(installedDir, "scripts/input.js").readText()).isEqualTo("input script")

        val installedSkill = File(installedDir, "SKILL.md").readText()
        assertThat(installedSkill).doesNotContain("{{SKILL_DIR}}")
        assertThat(installedSkill).contains("${installedDir.absolutePath}/scripts/page.js")
        assertThat(installedSkill).contains("${installedDir.absolutePath}/scripts/tabs.js")
        assertThat(installedSkill).contains("${installedDir.absolutePath}/scripts/input.js")

        val manager = AgentSkillManager(skillsDir)
        assertThat(manager.entries).containsKey("browser-use")
    }

    @Test
    fun `install is idempotent and overwrites stale browser use files`() {
        val filesDir = tempDir.newFolder("files")
        val skillsDir = File(filesDir, "skills")
        val assetFiles = browserUseAssets().toMutableMap()
        val assets = fakeAssets(assetFiles)
        val installer = BundledAgentSkillInstaller(assets)

        installer.install(skillsDir)

        val installedDir = File(skillsDir, "browser-use")
        File(installedDir, "SKILL.md").writeText("corrupt {{SKILL_DIR}}")
        File(installedDir, "scripts/page.js").writeText("corrupt")
        File(installedDir, "scripts/stale.js").writeText("stale")
        assetFiles["agent_skills/browser-use/scripts/page.js"] = "page script v2"

        installer.install(skillsDir)

        assertThat(File(installedDir, "SKILL.md").readText()).doesNotContain("{{SKILL_DIR}}")
        assertThat(File(installedDir, "SKILL.md").readText()).doesNotContain("corrupt")
        assertThat(File(installedDir, "scripts/page.js").readText()).isEqualTo("page script v2")
        assertThat(File(installedDir, "scripts/stale.js").exists()).isFalse()
    }

    @Test
    fun `install substitutes placeholders in real browser use asset`() {
        val skillsDir = tempDir.newFolder("skills")
        val assets = fileBackedAssets(File("src/main/assets"))

        BundledAgentSkillInstaller(assets).install(skillsDir)

        val installedDir = File(skillsDir, "browser-use")
        assertThat(File(installedDir, BundledAgentSkillInstaller.INSTALL_SENTINEL).isFile).isTrue()
        val installedSkill = File(installedDir, "SKILL.md").readText()
        assertThat(installedSkill).doesNotContain("{{SKILL_DIR}}")
        assertThat(installedSkill).contains("${installedDir.absolutePath}/scripts/page.js")
        assertThat(installedSkill).contains("${installedDir.absolutePath}/scripts/tabs.js")
        assertThat(installedSkill).contains("${installedDir.absolutePath}/scripts/input.js")
    }

    private fun browserUseAssets(): Map<String, String> = mapOf(
        "agent_skills/browser-use/SKILL.md" to """
            |---
            |name: browser-use
            |description: Browser automation guidance.
            |---
            |Page: {{SKILL_DIR}}/scripts/page.js
            |Tabs: {{SKILL_DIR}}/scripts/tabs.js
            |Input: {{SKILL_DIR}}/scripts/input.js
        """.trimMargin(),
        "agent_skills/browser-use/scripts/page.js" to "page script",
        "agent_skills/browser-use/scripts/tabs.js" to "tabs script",
        "agent_skills/browser-use/scripts/input.js" to "input script",
    )

    private fun fakeAssets(files: Map<String, String>): AssetManager {
        val assets = mockk<AssetManager>()
        every { assets.list(any<String>()) } answers {
            childNames(files.keys, firstArg())
        }
        every { assets.open(any<String>()) } answers {
            val path = firstArg<String>()
            val content = files[path] ?: throw FileNotFoundException(path)
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        }
        return assets
    }

    private fun fileBackedAssets(root: File): AssetManager {
        val assets = mockk<AssetManager>()
        every { assets.list(any<String>()) } answers {
            val file = File(root, firstArg<String>())
            if (file.isDirectory) file.list().orEmpty() else emptyArray()
        }
        every { assets.open(any<String>()) } answers {
            File(root, firstArg<String>()).inputStream()
        }
        return assets
    }

    private fun childNames(paths: Set<String>, directory: String): Array<String> {
        val prefix = "$directory/"
        return paths
            .asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore("/") }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
            .toTypedArray()
    }
}
