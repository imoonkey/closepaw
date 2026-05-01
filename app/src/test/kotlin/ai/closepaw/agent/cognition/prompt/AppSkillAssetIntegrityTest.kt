package ai.closepaw.agent.cognition.prompt

import ai.closepaw.agent.cognition.skills.SkillFrontmatterParser
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

class AppSkillAssetIntegrityTest {

    @Test
    fun `all app skill assets have valid frontmatter`() {
        val appSkillsDir = File("src/main/assets/app_skills")

        assertWithMessage("app skill asset directory exists")
            .that(appSkillsDir.isDirectory)
            .isTrue()

        val skillDirs = appSkillsDir
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }

        assertWithMessage("app skill asset directories")
            .that(skillDirs)
            .isNotEmpty()

        skillDirs.forEach { skillDir ->
            val skillFile = File(skillDir, "SKILL.md")

            assertWithMessage("${skillDir.name}: SKILL.md exists")
                .that(skillFile.isFile)
                .isTrue()

            val parsed = SkillFrontmatterParser.parse(skillFile.readText())

            assertWithMessage("${skillDir.name}: frontmatter is valid and description is present")
                .that(parsed)
                .isNotNull()

            val frontmatter = parsed!!.frontmatter

            assertWithMessage("${skillDir.name}: frontmatter.name must match $APP_SKILL_NAME_REGEX")
                .that(frontmatter.name)
                .matches(APP_SKILL_NAME_REGEX.toPattern())

            assertWithMessage("${skillDir.name}: metadata.package must match asset directory")
                .that(frontmatter.metadata["package"])
                .isEqualTo(skillDir.name)
        }
    }

    private companion object {
        val APP_SKILL_NAME_REGEX = Regex("^app-[a-z0-9-]{1,63}$")
    }
}
