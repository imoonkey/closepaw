package com.moonkey.androidagent.agent.cognition.prompt

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
    fun `load returns skill text for matching package asset`() {
        val assets = mockk<AssetManager>()
        every { assets.open("app_skills/net.gsantner.markor/SKILL.md") } returns
            ByteArrayInputStream("# Markor Skill".toByteArray())

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
    fun `load rejects invalid package names without touching assets`() {
        val assets = mockk<AssetManager>(relaxed = true)
        val repository = AssetAppSkillRepository(assets)

        val skill = repository.load("../etc/passwd")

        assertThat(skill).isNull()
        verify(exactly = 0) { assets.open(any()) }
    }
}
