package ai.closepaw.agent.cognition.skills

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

class AgentSkillManagerTest {

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
    fun `activate returns success with body on first call`() {
        createSkill("date-math", "Compute date ranges", "Use ISO-8601 format.")
        val manager = AgentSkillManager(tempDir.root)

        val result = manager.activate("date-math")

        assertThat(result).isInstanceOf(ActivationResult.Success::class.java)
        val success = result as ActivationResult.Success
        assertThat(success.name).isEqualTo("date-math")
        assertThat(success.body).isEqualTo("Use ISO-8601 format.")
    }

    @Test
    fun `activate returns body on re-activation`() {
        createSkill("date-math", "Compute date ranges", "Use ISO-8601 format.")
        val manager = AgentSkillManager(tempDir.root)
        manager.activate("date-math")

        val result = manager.activate("date-math")

        assertThat(result).isInstanceOf(ActivationResult.Success::class.java)
        val success = result as ActivationResult.Success
        assertThat(success.name).isEqualTo("date-math")
        assertThat(success.body).isEqualTo("Use ISO-8601 format.")
    }

    @Test
    fun `activate returns not found for unknown skill`() {
        val manager = AgentSkillManager(tempDir.root)

        val result = manager.activate("nonexistent")

        assertThat(result).isInstanceOf(ActivationResult.NotFound::class.java)
        assertThat((result as ActivationResult.NotFound).name).isEqualTo("nonexistent")
    }

    @Test
    fun `activate strips frontmatter and returns only body`() {
        val body = "# Step 1\nDo the thing.\n\n# Step 2\nDo the other thing."
        createSkill("multi-step", "Multi-step skill", body)
        val manager = AgentSkillManager(tempDir.root)

        val result = manager.activate("multi-step") as ActivationResult.Success

        assertThat(result.body).isEqualTo(body)
    }

    @Test
    fun `activate returns read failure when file is deleted after catalog build`() {
        createSkill("ephemeral", "Will be deleted")
        val manager = AgentSkillManager(tempDir.root)
        // Delete the file after catalog was built
        java.io.File(tempDir.root, "ephemeral/SKILL.md").delete()

        val result = manager.activate("ephemeral")

        assertThat(result).isInstanceOf(ActivationResult.ReadFailure::class.java)
        assertThat((result as ActivationResult.ReadFailure).name).isEqualTo("ephemeral")
    }

    @Test
    fun `activate read failure does not mark skill as active`() {
        createSkill("ephemeral", "Will be deleted", "Body.")
        val manager = AgentSkillManager(tempDir.root)
        java.io.File(tempDir.root, "ephemeral/SKILL.md").delete()

        manager.activate("ephemeral")

        // Restore the file — a retry should succeed, not return AlreadyActive
        tempDir.root.resolve("ephemeral/SKILL.md").writeText(
            """
            |---
            |name: ephemeral
            |description: Restored
            |---
            |Restored body.
            """.trimMargin()
        )
        val retry = manager.activate("ephemeral")
        assertThat(retry).isInstanceOf(ActivationResult.Success::class.java)
    }

    @Test
    fun `concurrent activations all return success with body`() {
        createSkill("shared-skill", "Concurrent test", "Body.")
        val manager = AgentSkillManager(tempDir.root)
        val barrier = CyclicBarrier(10)
        val successCount = AtomicInteger(0)

        val threads = (1..10).map {
            Thread {
                barrier.await()
                when (manager.activate("shared-skill")) {
                    is ActivationResult.Success -> successCount.incrementAndGet()
                    else -> {}
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(successCount.get()).isEqualTo(10)
    }

    @Test
    fun `catalogPrompt delegates to catalog`() {
        createSkill("date-math", "Compute date ranges")
        val manager = AgentSkillManager(tempDir.root)

        val prompt = manager.catalogPrompt()

        assertThat(prompt).isNotNull()
        assertThat(prompt).contains("date-math")
        assertThat(prompt).contains("Compute date ranges")
    }

    @Test
    fun `catalogPrompt returns null when no skills`() {
        val manager = AgentSkillManager(tempDir.root)

        assertThat(manager.catalogPrompt()).isNull()
    }

    @Test
    fun `entries exposes catalog entries`() {
        createSkill("date-math", "Compute date ranges")
        createSkill("table-read", "Read tables from screenshots")
        val manager = AgentSkillManager(tempDir.root)

        assertThat(manager.entries).hasSize(2)
        assertThat(manager.entries).containsKey("date-math")
        assertThat(manager.entries).containsKey("table-read")
    }

    @Test
    fun `activateExplicitMentions matches slash skill names`() {
        createSkill("date-math", "Compute date ranges", "Date body.")
        createSkill("table-read", "Read tables", "Table body.")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("Please /date-math and /table-read")

        assertThat(results).hasSize(2)
        assertThat(results[0]).isInstanceOf(ActivationResult.Success::class.java)
        assertThat((results[0] as ActivationResult.Success).name).isEqualTo("date-math")
        assertThat(results[1]).isInstanceOf(ActivationResult.Success::class.java)
        assertThat((results[1] as ActivationResult.Success).name).isEqualTo("table-read")
    }

    @Test
    fun `activateExplicitMentions returns bodies for injection`() {
        createSkill("date-math", "Compute date ranges", "Use ISO-8601.")
        createSkill("table-read", "Read tables", "Parse rows carefully.")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("Use /date-math and /table-read")

        val bodies = results
            .filterIsInstance<ActivationResult.Success>()
            .map { it.body }
        assertThat(bodies).containsExactly("Use ISO-8601.", "Parse rows carefully.").inOrder()
    }

    @Test
    fun `activateExplicitMentions at start of text`() {
        createSkill("date-math", "Compute date ranges", "Body.")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("/date-math compute something")

        assertThat(results).hasSize(1)
        assertThat((results[0] as ActivationResult.Success).name).isEqualTo("date-math")
    }

    @Test
    fun `activateExplicitMentions ignores file paths`() {
        createSkill("data", "Some data skill")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("push to /data/local/tmp")

        assertThat(results).isEmpty()
    }

    @Test
    fun `activateExplicitMentions ignores URLs`() {
        createSkill("something", "A skill")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("visit https://example.com/something")

        assertThat(results).isEmpty()
    }

    @Test
    fun `activateExplicitMentions ignores unknown skills`() {
        createSkill("date-math", "Compute date ranges")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("/date-math and /unknown-skill")

        assertThat(results).hasSize(1)
        assertThat((results[0] as ActivationResult.Success).name).isEqualTo("date-math")
    }

    @Test
    fun `activateExplicitMentions deduplicates mentions`() {
        createSkill("date-math", "Compute date ranges", "Body.")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("/date-math then /date-math again")

        assertThat(results).hasSize(1)
    }

    @Test
    fun `activateExplicitMentions returns body for pre-activated skills`() {
        createSkill("date-math", "Compute date ranges", "Body.")
        val manager = AgentSkillManager(tempDir.root)
        manager.activate("date-math")

        val results = manager.activateExplicitMentions("/date-math")

        assertThat(results).hasSize(1)
        assertThat(results[0]).isInstanceOf(ActivationResult.Success::class.java)
        assertThat((results[0] as ActivationResult.Success).body).isEqualTo("Body.")
    }

    @Test
    fun `active skills preserved on same instance`() {
        createSkill("date-math", "Compute date ranges", "Body.")
        val manager = AgentSkillManager(tempDir.root)
        manager.activate("date-math")

        val second = manager.activate("date-math")
        assertThat(second).isInstanceOf(ActivationResult.Success::class.java)
        assertThat((second as ActivationResult.Success).body).isEqualTo("Body.")
    }

    @Test
    fun `activation order is stable`() {
        createSkill("aaa-skill", "First", "A body.")
        createSkill("bbb-skill", "Second", "B body.")
        createSkill("ccc-skill", "Third", "C body.")
        val manager = AgentSkillManager(tempDir.root)

        manager.activate("ccc-skill")
        manager.activate("aaa-skill")
        manager.activate("bbb-skill")

        val results = manager.activateExplicitMentions("/ccc-skill /aaa-skill /bbb-skill")
        assertThat(results).hasSize(3)
        assertThat(results.map { (it as ActivationResult.Success).name })
            .containsExactly("ccc-skill", "aaa-skill", "bbb-skill")
            .inOrder()
    }

    @Test
    fun `activateExplicitMentions with empty text returns empty`() {
        createSkill("date-math", "Compute date ranges")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("")

        assertThat(results).isEmpty()
    }

    @Test
    fun `activateExplicitMentions with blank text returns empty`() {
        createSkill("date-math", "Compute date ranges")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("   ")

        assertThat(results).isEmpty()
    }

    @Test
    fun `activateExplicitMentions with no slash mentions returns empty`() {
        createSkill("date-math", "Compute date ranges")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("just some plain text")

        assertThat(results).isEmpty()
    }

    @Test
    fun `activateExplicitMentions rejects false positive with trailing underscore suffix`() {
        createSkill("date-math", "Compute date ranges")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("/date-math_tmp should not match")

        assertThat(results).isEmpty()
    }

    @Test
    fun `activateExplicitMentions rejects false positive with trailing alphanumeric`() {
        createSkill("date-math", "Compute date ranges")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("/date-mathx should not match")

        assertThat(results).isEmpty()
    }

    @Test
    fun `activateExplicitMentions matches skill followed by comma`() {
        createSkill("date-math", "Compute date ranges", "Body.")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("/date-math, please")

        assertThat(results).hasSize(1)
        assertThat((results[0] as ActivationResult.Success).name).isEqualTo("date-math")
    }

    @Test
    fun `activateExplicitMentions matches skill followed by period`() {
        createSkill("date-math", "Compute date ranges", "Body.")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("Run /date-math.")

        assertThat(results).hasSize(1)
        assertThat((results[0] as ActivationResult.Success).name).isEqualTo("date-math")
    }

    @Test
    fun `activateExplicitMentions matches skill at end of string`() {
        createSkill("date-math", "Compute date ranges", "Body.")
        val manager = AgentSkillManager(tempDir.root)

        val results = manager.activateExplicitMentions("Run /date-math")

        assertThat(results).hasSize(1)
        assertThat((results[0] as ActivationResult.Success).name).isEqualTo("date-math")
    }

    // ===== Disabled skill filtering =====

    @Test
    fun `disabled skill is omitted from catalog prompt`() {
        createSkill("alpha", "Alpha description")
        createSkill("beta", "Beta description")
        val manager = AgentSkillManager(tempDir.root, disabledNames = setOf("alpha"))

        val prompt = manager.catalogPrompt()!!
        assertThat(prompt).doesNotContain("alpha")
        assertThat(prompt).contains("beta")
    }

    @Test
    fun `disabled skill omitted from entries view`() {
        createSkill("alpha", "Alpha")
        createSkill("beta", "Beta")
        val manager = AgentSkillManager(tempDir.root, disabledNames = setOf("alpha"))

        assertThat(manager.entries.keys).containsExactly("beta")
    }

    @Test
    fun `catalog prompt is null when all skills disabled`() {
        createSkill("alpha", "Alpha")
        val manager = AgentSkillManager(tempDir.root, disabledNames = setOf("alpha"))

        assertThat(manager.catalogPrompt()).isNull()
    }

    @Test
    fun `activate returns Disabled for disabled skill`() {
        createSkill("alpha", "Alpha", "Alpha body.")
        val manager = AgentSkillManager(tempDir.root, disabledNames = setOf("alpha"))

        val result = manager.activate("alpha")

        assertThat(result).isInstanceOf(ActivationResult.Disabled::class.java)
        assertThat((result as ActivationResult.Disabled).name).isEqualTo("alpha")
    }

    @Test
    fun `disabled takes precedence over NotFound for known-but-disabled names`() {
        createSkill("alpha", "Alpha", "Body.")
        val manager = AgentSkillManager(tempDir.root, disabledNames = setOf("alpha"))

        val result = manager.activate("alpha")
        assertThat(result).isInstanceOf(ActivationResult.Disabled::class.java)
    }

    @Test
    fun `disabled set for unknown skill still returns Disabled`() {
        val manager = AgentSkillManager(tempDir.root, disabledNames = setOf("ghost"))
        // Disabled gate is checked before catalog lookup, so a name that is
        // in the disabled set always returns Disabled — keeps the gate cheap
        // and the failure message stable.
        val result = manager.activate("ghost")
        assertThat(result).isInstanceOf(ActivationResult.Disabled::class.java)
    }

    @Test
    fun `explicit mentions filter disabled skills`() {
        createSkill("alpha", "Alpha", "A body.")
        createSkill("beta", "Beta", "B body.")
        val manager = AgentSkillManager(tempDir.root, disabledNames = setOf("alpha"))

        val results = manager.activateExplicitMentions("Use /alpha and /beta")

        assertThat(results).hasSize(1)
        assertThat((results[0] as ActivationResult.Success).name).isEqualTo("beta")
    }

    @Test
    fun `toggle does not affect already-constructed manager`() {
        // Verifies "next session" semantics: changes to a disabled-set after
        // the manager is constructed cannot leak into the running session.
        createSkill("alpha", "Alpha", "A body.")
        val mutableDisabled = mutableSetOf<String>()
        val manager = AgentSkillManager(tempDir.root, disabledNames = mutableDisabled.toSet())

        // Simulate the user disabling alpha after the session started.
        mutableDisabled.add("alpha")

        val result = manager.activate("alpha")
        // Already-constructed manager has the original (empty) disabled-set.
        assertThat(result).isInstanceOf(ActivationResult.Success::class.java)
    }
}
