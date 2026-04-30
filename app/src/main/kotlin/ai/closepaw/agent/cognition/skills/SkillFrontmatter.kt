package ai.closepaw.agent.cognition.skills

data class SkillFrontmatter(
    val name: String,
    val description: String,
    val license: String? = null,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)
