package ai.closepaw.agent.cognition.skills

import java.io.File

sealed class ActivationResult {
    data class Success(val name: String, val body: String) : ActivationResult()
    data class AlreadyActive(val name: String) : ActivationResult()
    data class NotFound(val name: String) : ActivationResult()
}

class AgentSkillManager(skillsDir: File) {

    private val catalog = AgentSkillCatalog(skillsDir)
    private val activeSkills = LinkedHashSet<String>()

    val entries: Map<String, AgentSkillEntry> get() = catalog.entries

    fun catalogPrompt(): String? = catalog.catalogPrompt()

    fun activate(name: String): ActivationResult {
        val entry = catalog.entries[name]
            ?: return ActivationResult.NotFound(name)

        if (!activeSkills.add(name)) {
            return ActivationResult.AlreadyActive(name)
        }

        val content = File(entry.filePath).readText()
        val body = SkillFrontmatterParser.parse(content)?.body ?: content
        return ActivationResult.Success(name, body)
    }

    fun activateExplicitMentions(text: String): List<ActivationResult> {
        return MENTION_REGEX.findAll(text)
            .map { it.groupValues[1] }
            .filter { catalog.entries.containsKey(it) }
            .distinct()
            .map { activate(it) }
            .toList()
    }

    companion object {
        private val MENTION_REGEX =
            Regex("""(?:^|(?<=\s))/([a-z][a-z0-9-]{0,63})(?!/)""")
    }
}
