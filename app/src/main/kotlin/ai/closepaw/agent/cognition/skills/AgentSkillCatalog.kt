package ai.closepaw.agent.cognition.skills

import android.util.Log
import java.io.File

class AgentSkillCatalog(skillsDir: File) {

    val entries: Map<String, AgentSkillEntry>

    init {
        entries = buildCatalog(skillsDir)
    }

    fun catalogPrompt(): String? {
        if (entries.isEmpty()) return null
        return buildString {
            append("## Available Skills\n")
            append("The following skills provide specialized instructions. Call activate_skill\n")
            append("with a skill's name to load its full instructions.\n")
            for ((name, entry) in entries) {
                append("\n- $name: ${entry.description}")
            }
        }
    }

    companion object {
        private const val TAG = "AgentSkillCatalog"
        private const val MAX_DESCRIPTION_LENGTH = 1024
        private val NAME_PATTERN = Regex("^[a-z][a-z0-9-]{0,63}$")
    }

    private fun buildCatalog(skillsDir: File): Map<String, AgentSkillEntry> {
        if (!skillsDir.isDirectory) return emptyMap()

        val result = mutableMapOf<String, AgentSkillEntry>()
        val dirs = skillsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?: return emptyMap()

        for (dir in dirs) {
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.isFile) continue

            val content = try {
                skillFile.readText()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read ${skillFile.path}: ${e.message}")
                continue
            }

            val parseResult = SkillFrontmatterParser.parse(content)
            if (parseResult == null) {
                Log.w(TAG, "Invalid frontmatter in ${skillFile.path}")
                continue
            }

            val fm = parseResult.frontmatter
            if (!NAME_PATTERN.matches(fm.name)) {
                Log.w(TAG, "Invalid skill name '${fm.name}' in ${dir.name}")
                continue
            }

            if (fm.name != dir.name) {
                Log.w(TAG, "Directory '${dir.name}' does not match skill name '${fm.name}'")
                continue
            }

            val sanitizedDescription = fm.description
                .replace(Regex("[\\r\\n\\t\\x00-\\x1F]"), " ")
                .trim()

            if (sanitizedDescription.isEmpty()) {
                Log.w(TAG, "Empty description after sanitization in ${dir.name}")
                continue
            }

            if (sanitizedDescription.length > MAX_DESCRIPTION_LENGTH) {
                Log.w(TAG, "Description too long (${sanitizedDescription.length} chars) in ${dir.name}")
                continue
            }

            result[fm.name] = AgentSkillEntry(
                name = fm.name,
                description = sanitizedDescription,
                filePath = skillFile.absolutePath
            )
        }

        return result.toMap()
    }
}
