package ai.closepaw.agent.cognition.skills

import org.yaml.snakeyaml.Yaml

data class SkillParseResult(
    val frontmatter: SkillFrontmatter,
    val body: String
)

internal object SkillFrontmatterParser {

    private val FRONTMATTER_REGEX =
        Regex("\\A---\\s*\\n(.*?)\\n---\\s*\\n?", RegexOption.DOT_MATCHES_ALL)

    private val NAME_REGEX = Regex("^name:\\s*(.+)", RegexOption.MULTILINE)
    private val DESCRIPTION_REGEX = Regex("^description:\\s*(.+)", RegexOption.MULTILINE)

    fun parse(content: String): SkillParseResult? {
        val match = FRONTMATTER_REGEX.find(content) ?: return null
        val yamlBlock = match.groupValues[1]
        val body = content.substring(match.range.last + 1).trim()

        return (parseYaml(yamlBlock) ?: parseRegexFallback(yamlBlock))
            ?.let { SkillParseResult(it, body) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseYaml(yamlBlock: String): SkillFrontmatter? = try {
        val map = Yaml().load<Map<String, Any?>>(yamlBlock) ?: return null
        val name = (map["name"] as? String)?.trim() ?: return null
        val description = (map["description"] as? String)?.trim()
        if (description.isNullOrBlank()) return null

        val allowedTools = when (val tools = map["allowed-tools"]) {
            is List<*> -> tools.filterIsInstance<String>()
            is String -> tools.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }

        val rawMetadata = map["metadata"] as? Map<*, *>
        val metadata = rawMetadata
            ?.entries
            ?.mapNotNull { (k, v) ->
                val key = k as? String ?: return@mapNotNull null
                val value = v?.toString() ?: return@mapNotNull null
                key to value
            }
            ?.toMap()
            .orEmpty()

        SkillFrontmatter(
            name = name,
            description = description,
            license = (map["license"] as? String)?.trim(),
            compatibility = (map["compatibility"] as? String)?.trim(),
            allowedTools = allowedTools,
            metadata = metadata
        )
    } catch (_: Exception) {
        null
    }

    private fun parseRegexFallback(yamlBlock: String): SkillFrontmatter? {
        val name = NAME_REGEX.find(yamlBlock)?.groupValues?.get(1)?.trim() ?: return null
        val description = DESCRIPTION_REGEX.find(yamlBlock)?.groupValues?.get(1)?.trim()
        if (description.isNullOrBlank()) return null
        return SkillFrontmatter(name = name, description = description)
    }
}
