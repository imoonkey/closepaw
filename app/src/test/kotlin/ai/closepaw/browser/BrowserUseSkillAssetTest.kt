package ai.closepaw.browser

import ai.closepaw.agent.cognition.skills.SkillFrontmatterParser
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

class BrowserUseSkillAssetTest {

    @Test
    fun `browser use skill asset and snippet bundles are present`() {
        val skillDir = browserUseSkillDir()
        val skillFile = File(skillDir, "SKILL.md")
        val page = File(skillDir, "scripts/page.js")
        val tabs = File(skillDir, "scripts/tabs.js")
        val input = File(skillDir, "scripts/input.js")

        assertThat(skillFile.isFile).isTrue()
        assertThat(page.isFile).isTrue()
        assertThat(tabs.isFile).isTrue()
        assertThat(input.isFile).isTrue()

        val skillContent = skillFile.readText()
        val parsed = SkillFrontmatterParser.parse(skillContent)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.frontmatter.name).isEqualTo("browser-use")
        assertThat(skillContent).contains("{{SKILL_DIR}}/scripts/page.js")
        assertThat(skillContent).contains("{{SKILL_DIR}}/scripts/tabs.js")
        assertThat(skillContent).contains("{{SKILL_DIR}}/scripts/input.js")
    }

    @Test
    fun `browser use snippets expose expected helper groupings`() {
        val skillDir = browserUseSkillDir()
        val page = File(skillDir, "scripts/page.js").readText()
        val tabs = File(skillDir, "scripts/tabs.js").readText()
        val input = File(skillDir, "scripts/input.js").readText()

        assertThat(page).contains("async function pageJs")
        assertThat(page).contains("async function waitForLoad")
        assertThat(page).contains("async function screenshot")
        assertThat(page).contains("navigate: load timeout")
        assertThat(page).contains("lineNumber")
        assertThat(page).contains("columnNumber")

        assertThat(tabs).contains("async function listTabs")
        assertThat(tabs).contains("async function currentTab")
        assertThat(tabs).contains("async function switchTab")
        assertThat(tabs).contains("async function newTab")
        assertThat(tabs).contains("{ targetId }")

        assertThat(input).contains("async function clickAt")
        assertThat(input).contains("async function typeText")
        assertThat(input).contains("Input.dispatchMouseEvent")
        assertThat(input).contains("Input.dispatchKeyEvent")
    }

    @Test
    fun `browser use snippets are self contained around cdp only`() {
        val scriptDir = File(browserUseSkillDir(), "scripts")
        scriptDir.listFiles { file -> file.extension == "js" }
            .orEmpty()
            .forEach { script ->
                val content = script.readText()
                assertWithMessage("${script.name} should use raw CDP")
                    .that(content)
                    .contains("cdp(")
                assertWithMessage("${script.name} should not reference the hidden bridge")
                    .that(content)
                    .doesNotContain("AndroidBrowserScript")
                assertWithMessage("${script.name} should not depend on CommonJS")
                    .that(content)
                    .doesNotContain("require(")
                assertWithMessage("${script.name} should not contain install placeholders")
                    .that(content)
                    .doesNotContain("{{SKILL_DIR}}")
            }
    }

    private fun browserUseSkillDir(): File = File("src/main/assets/agent_skills/browser-use")
}
