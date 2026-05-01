package ai.closepaw.agent.cognition.skills

import java.io.File
import java.io.IOException

sealed class ActivationResult {
    data class Success(val name: String, val body: String) : ActivationResult()
    data class AlreadyActive(val name: String) : ActivationResult()
    data class NotFound(val name: String) : ActivationResult()
    data class ReadFailure(val name: String, val cause: IOException) : ActivationResult()
}

class AgentSkillManager(skillsDir: File) {

    private val catalog = AgentSkillCatalog(skillsDir)
    private val activeSkills = LinkedHashSet<String>()

    val entries: Map<String, AgentSkillEntry> get() = catalog.entries

    fun catalogPrompt(): String? = catalog.catalogPrompt()

    @Synchronized
    fun activate(name: String): ActivationResult {
        val entry = catalog.entries[name]
            ?: return ActivationResult.NotFound(name)

        val content = try {
            File(entry.filePath).readText()
        } catch (e: IOException) {
            return ActivationResult.ReadFailure(name, e)
        }

        val body = SkillFrontmatterParser.parse(content)?.body ?: content
        activeSkills.add(name)
        return ActivationResult.Success(name, body)
    }

    @Synchronized
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
            Regex("""(?:^|(?<=\s))/([a-z][a-z0-9-]{0,63})(?![/\w-])""")
    }
}
