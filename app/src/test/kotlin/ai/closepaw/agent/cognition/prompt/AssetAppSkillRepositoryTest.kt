package ai.closepaw.agent.cognition.prompt

import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.junit.Test

class AssetAppSkillRepositoryTest {

    @Test
    fun `load returns body for skill with valid frontmatter`() {
        val assets = mockk<AssetManager>()
        val content = """
            ---
            name: app-markor
            description: Markor app guidance.
            ---

            # Markor Skill
        """.trimIndent()
        every { assets.open("app_skills/net.gsantner.markor/SKILL.md") } returns
            ByteArrayInputStream(content.toByteArray())

        val repository = AssetAppSkillRepository(assets)

        val skill = repository.load("net.gsantner.markor")

        assertThat(skill).isEqualTo("# Markor Skill")
    }

    @Test
    fun `load returns null when skill asset is missing`() {
        val assets = mockk<AssetManager>()
        every { assets.open("app_skills/com.example.missing/SKILL.md") } throws
            FileNotFoundException("missing")

        val repository = AssetAppSkillRepository(assets)

        val skill = repository.load("com.example.missing")

        assertThat(skill).isNull()
    }

    @Test
    fun `load returns null when frontmatter is malformed`() {
        val assets = mockk<AssetManager>()
        every { assets.open("app_skills/com.example.bad/SKILL.md") } returns
            ByteArrayInputStream("# No frontmatter here".toByteArray())

        val repository = AssetAppSkillRepository(assets)

        val skill = repository.load("com.example.bad")

        assertThat(skill).isNull()
    }

    @Test
    fun `load rejects invalid package names without touching assets`() {
        val assets = mockk<AssetManager>(relaxed = true)
        val repository = AssetAppSkillRepository(assets)

        val skill = repository.load("../etc/passwd")

        assertThat(skill).isNull()
        verify(exactly = 0) { assets.open(any()) }
    }
}
