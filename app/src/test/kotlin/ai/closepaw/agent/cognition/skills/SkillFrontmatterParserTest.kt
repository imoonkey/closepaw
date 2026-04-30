package ai.closepaw.agent.cognition.skills

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkillFrontmatterParserTest {

    @Test
    fun `parses valid frontmatter with all fields`() {
        val content = """
            |---
            |name: calendar-date-math
            |description: Compute exact date ranges for calendar queries.
            |license: MIT
            |compatibility: android
            |allowed-tools:
            |  - shell
            |  - mobile_action
            |metadata:
            |  author: closepaw
            |  version: "1.0"
            |---
            |Use this skill when computing date ranges.
        """.trimMargin()

        val result = SkillFrontmatterParser.parse(content)!!

        assertThat(result.frontmatter.name).isEqualTo("calendar-date-math")
        assertThat(result.frontmatter.description).isEqualTo("Compute exact date ranges for calendar queries.")
        assertThat(result.frontmatter.license).isEqualTo("MIT")
        assertThat(result.frontmatter.compatibility).isEqualTo("android")
        assertThat(result.frontmatter.allowedTools).containsExactly("shell", "mobile_action")
        assertThat(result.frontmatter.metadata).containsExactly("author", "closepaw", "version", "1.0")
        assertThat(result.body).isEqualTo("Use this skill when computing date ranges.")
    }

    @Test
    fun `parses minimal frontmatter with only name and description`() {
        val content = """
            |---
            |name: simple-skill
            |description: A simple skill.
            |---
            |Body text here.
        """.trimMargin()

        val result = SkillFrontmatterParser.parse(content)!!

        assertThat(result.frontmatter.name).isEqualTo("simple-skill")
        assertThat(result.frontmatter.description).isEqualTo("A simple skill.")
        assertThat(result.frontmatter.license).isNull()
        assertThat(result.frontmatter.compatibility).isNull()
        assertThat(result.frontmatter.allowedTools).isEmpty()
        assertThat(result.frontmatter.metadata).isEmpty()
        assertThat(result.body).isEqualTo("Body text here.")
    }

    @Test
    fun `malformed YAML falls back to regex extraction`() {
        val content = """
            |---
            |name: broken-yaml
            |description: Has unquoted colon: value here
            |some: invalid: yaml: nesting
            |---
            |Skill body after malformed YAML.
        """.trimMargin()

        val result = SkillFrontmatterParser.parse(content)!!

        assertThat(result.frontmatter.name).isEqualTo("broken-yaml")
        assertThat(result.frontmatter.description).isEqualTo("Has unquoted colon: value here")
        assertThat(result.body).isEqualTo("Skill body after malformed YAML.")
    }

    @Test
    fun `returns null when description is missing`() {
        val content = """
            |---
            |name: no-desc-skill
            |license: MIT
            |---
            |Body.
        """.trimMargin()

        assertThat(SkillFrontmatterParser.parse(content)).isNull()
    }

    @Test
    fun `returns null when description is blank`() {
        val content = """
            |---
            |name: blank-desc
            |description:
            |---
            |Body.
        """.trimMargin()

        assertThat(SkillFrontmatterParser.parse(content)).isNull()
    }

    @Test
    fun `returns null when no frontmatter delimiters`() {
        val content = "Just some text without frontmatter."

        assertThat(SkillFrontmatterParser.parse(content)).isNull()
    }

    @Test
    fun `returns null when name is missing`() {
        val content = """
            |---
            |description: A skill without a name.
            |---
            |Body.
        """.trimMargin()

        assertThat(SkillFrontmatterParser.parse(content)).isNull()
    }

    @Test
    fun `body is empty string when no content after frontmatter`() {
        val content = """
            |---
            |name: empty-body
            |description: Skill with no body.
            |---
        """.trimMargin()

        val result = SkillFrontmatterParser.parse(content)!!

        assertThat(result.body).isEmpty()
    }

    @Test
    fun `multiline body preserves content`() {
        val content = """
            |---
            |name: multiline
            |description: Multi-line body skill.
            |---
            |Line one.
            |
            |Line three.
        """.trimMargin()

        val result = SkillFrontmatterParser.parse(content)!!

        assertThat(result.body).contains("Line one.")
        assertThat(result.body).contains("Line three.")
    }

    @Test
    fun `allowed-tools as comma-separated string`() {
        val content = """
            |---
            |name: csv-tools
            |description: Tools as CSV.
            |allowed-tools: shell, mobile_action
            |---
            |Body.
        """.trimMargin()

        val result = SkillFrontmatterParser.parse(content)!!

        assertThat(result.frontmatter.allowedTools).containsExactly("shell", "mobile_action")
    }

    @Test
    fun `metadata with nested values flattened to string`() {
        val content = """
            |---
            |name: nested-meta
            |description: Skill with nested metadata.
            |metadata:
            |  simple: value
            |  nested:
            |    inner: deep
            |---
            |Body.
        """.trimMargin()

        val result = SkillFrontmatterParser.parse(content)!!

        assertThat(result.frontmatter.metadata).containsEntry("simple", "value")
        assertThat(result.frontmatter.metadata).containsKey("nested")
    }

    @Test
    fun `body containing triple-dash separator is preserved`() {
        val content = """
            |---
            |name: dash-body
            |description: Body has dashes.
            |---
            |Some instructions.
            |---
            |More instructions after separator.
        """.trimMargin()

        val result = SkillFrontmatterParser.parse(content)!!

        assertThat(result.frontmatter.name).isEqualTo("dash-body")
        assertThat(result.body).contains("Some instructions.")
        assertThat(result.body).contains("More instructions after separator.")
    }

    @Test
    fun `app skill with metadata package field`() {
        val content = """
            |---
            |name: app-chrome
            |description: Chrome browser automation.
            |metadata:
            |  package: com.android.chrome
            |---
            |Chrome-specific instructions.
        """.trimMargin()

        val result = SkillFrontmatterParser.parse(content)!!

        assertThat(result.frontmatter.name).isEqualTo("app-chrome")
        assertThat(result.frontmatter.metadata).containsExactly("package", "com.android.chrome")
        assertThat(result.body).isEqualTo("Chrome-specific instructions.")
    }
}
