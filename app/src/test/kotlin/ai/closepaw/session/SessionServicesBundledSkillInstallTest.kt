package ai.closepaw.session

import android.content.Context
import android.content.res.AssetManager
import ai.closepaw.agent.cognition.skills.BundledAgentSkillInstaller
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionServicesBundledSkillInstallTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `first bundled skill install failure aborts session bootstrap`() {
        val skillsDir = tempDir.newFolder("skills")
        val context = contextWithFailingAssets()

        val error = assertThrows(IllegalStateException::class.java) {
            SessionServices.installBundledAgentSkills(context, skillsDir)
        }

        assertThat(error).hasMessageThat().contains("Failed to install bundled browser-use skill")
    }

    @Test
    fun `partial browser use install without sentinel aborts session bootstrap`() {
        val skillsDir = tempDir.newFolder("skills")
        val skillFile = skillsDir.resolve("browser-use/SKILL.md")
        skillFile.parentFile!!.mkdirs()
        skillFile.writeText(
            """
            |---
            |name: browser-use
            |description: Existing browser guidance.
            |---
            |Existing install.
            """.trimMargin(),
        )
        val context = contextWithFailingAssets()

        val error = assertThrows(IllegalStateException::class.java) {
            SessionServices.installBundledAgentSkills(context, skillsDir)
        }

        assertThat(error).hasMessageThat().contains("Failed to install bundled browser-use skill")
        assertThat(skillFile.readText()).contains("Existing install.")
    }

    @Test
    fun `refresh failure keeps sentinel marked browser use install`() {
        val skillsDir = tempDir.newFolder("skills")
        val skillDir = skillsDir.resolve("browser-use")
        skillDir.resolve("scripts").mkdirs()
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.writeText(
            """
            |---
            |name: browser-use
            |description: Existing browser guidance.
            |---
            |Existing install.
            """.trimMargin(),
        )
        skillDir.resolve("scripts/page.js").writeText("page")
        skillDir.resolve("scripts/tabs.js").writeText("tabs")
        skillDir.resolve("scripts/input.js").writeText("input")
        skillDir.resolve(BundledAgentSkillInstaller.INSTALL_SENTINEL).writeText("skill=browser-use\n")
        val context = contextWithFailingAssets()

        SessionServices.installBundledAgentSkills(context, skillsDir)

        assertThat(skillFile.readText()).contains("Existing install.")
    }

    private fun contextWithFailingAssets(): Context {
        val assets = mockk<AssetManager>()
        every { assets.list(any<String>()) } throws IOException("asset read failed")
        val context = mockk<Context>()
        every { context.assets } returns assets
        return context
    }
}
