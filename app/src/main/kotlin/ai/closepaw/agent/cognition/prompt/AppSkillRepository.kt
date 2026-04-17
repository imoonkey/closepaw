package ai.closepaw.agent.cognition.prompt

import android.content.res.AssetManager
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException

internal interface AppSkillRepository {
    fun load(packageName: String): String?
}

internal object EmptyAppSkillRepository : AppSkillRepository {
    override fun load(packageName: String): String? = null
}

internal class AssetAppSkillRepository(
    private val assets: AssetManager
) : AppSkillRepository {

    override fun load(packageName: String): String? {
        val normalizedPackage = packageName.trim()
        if (!PACKAGE_NAME_REGEX.matches(normalizedPackage)) {
            return null
        }

        val assetPath = "$APP_SKILLS_ROOT/$normalizedPackage/SKILL.md"
        return try {
            assets.open(assetPath).bufferedReader().use { it.readText().trim() }
                .let(::stripFrontmatter)
                .ifEmpty { null }
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "Failed to load app skill: $assetPath", e)
            null
        }
    }

    companion object {
        private const val TAG = "AssetAppSkillRepo"
        private const val APP_SKILLS_ROOT = "app_skills"
        private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val FRONTMATTER_REGEX = Regex("\\A---\\s*\\n.*?\\n---\\s*\\n?", RegexOption.DOT_MATCHES_ALL)

        private fun stripFrontmatter(content: String): String =
            content.replace(FRONTMATTER_REGEX, "").trim()
    }
}
