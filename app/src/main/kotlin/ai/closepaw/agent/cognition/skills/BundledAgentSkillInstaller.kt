package ai.closepaw.agent.cognition.skills

import android.content.res.AssetManager
import java.io.File
import java.io.IOException

internal class BundledAgentSkillInstaller(
    private val assets: AssetManager,
) {
    @Throws(IOException::class)
    fun install(skillsDir: File) {
        installSkill(BROWSER_USE_SKILL, skillsDir)
    }

    @Throws(IOException::class)
    private fun installSkill(skillName: String, skillsDir: File) {
        require(SKILL_NAME_PATTERN.matches(skillName)) { "Invalid bundled skill name: $skillName" }

        if (skillsDir.exists() && !skillsDir.isDirectory) {
            throw IOException("Skill root is not a directory: ${skillsDir.absolutePath}")
        }
        if (!skillsDir.exists() && !skillsDir.mkdirs()) {
            throw IOException("Failed to create skill root: ${skillsDir.absolutePath}")
        }

        val assetPath = "$AGENT_SKILLS_ROOT/$skillName"
        val targetDir = File(skillsDir, skillName)
        val tempDir = File(skillsDir, ".$skillName.tmp")

        deleteIfExists(tempDir)
        copyAssetDirectory(
            assetPath = assetPath,
            targetDir = tempDir,
            installedSkillDir = targetDir.absolutePath,
        )
        writeInstallSentinel(tempDir, skillName)

        replaceSkillDirectory(tempDir, targetDir)
    }

    @Throws(IOException::class)
    private fun replaceSkillDirectory(tempDir: File, targetDir: File) {
        if (!targetDir.exists()) {
            renameDirectory(tempDir, targetDir)
            return
        }

        val backupDir = File(targetDir.parentFile, ".${targetDir.name}.backup")
        deleteIfExists(backupDir)
        renameDirectory(targetDir, backupDir)

        var installed = false
        try {
            renameDirectory(tempDir, targetDir)
            installed = true
        } catch (e: IOException) {
            restorePreviousInstall(backupDir, targetDir, e)
            throw e
        } finally {
            if (installed) deleteIfExists(backupDir)
            if (tempDir.exists()) deleteIfExists(tempDir)
        }
    }

    @Throws(IOException::class)
    private fun restorePreviousInstall(
        backupDir: File,
        targetDir: File,
        installFailure: IOException,
    ) {
        if (targetDir.exists()) deleteIfExists(targetDir)
        if (!backupDir.renameTo(targetDir)) {
            throw IOException(
                "Failed to restore previous bundled skill: ${targetDir.absolutePath}",
                installFailure,
            )
        }
    }

    @Throws(IOException::class)
    private fun renameDirectory(source: File, target: File) {
        if (!source.renameTo(target)) {
            throw IOException("Failed to rename ${source.absolutePath} to ${target.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun copyAssetDirectory(
        assetPath: String,
        targetDir: File,
        installedSkillDir: String,
    ) {
        val children = assets.list(assetPath)
            ?: throw IOException("Failed to list asset directory: $assetPath")
        if (children.isEmpty()) {
            throw IOException("Bundled skill asset directory is empty: $assetPath")
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create directory: ${targetDir.absolutePath}")
        }

        children.sorted().forEach { child ->
            val childAssetPath = "$assetPath/$child"
            val childTarget = File(targetDir, child)
            val grandChildren = assets.list(childAssetPath)
                ?: throw IOException("Failed to list asset path: $childAssetPath")
            if (grandChildren.isEmpty()) {
                copyAssetFile(childAssetPath, childTarget, installedSkillDir)
            } else {
                copyAssetDirectory(childAssetPath, childTarget, installedSkillDir)
            }
        }
    }

    @Throws(IOException::class)
    private fun copyAssetFile(
        assetPath: String,
        targetFile: File,
        installedSkillDir: String,
    ) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        if (assetPath.endsWith("/SKILL.md")) {
            val content = assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            targetFile.writeText(content.replace(SKILL_DIR_PLACEHOLDER, installedSkillDir), Charsets.UTF_8)
            return
        }

        assets.open(assetPath).use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Throws(IOException::class)
    private fun writeInstallSentinel(skillDir: File, skillName: String) {
        File(skillDir, INSTALL_SENTINEL).writeText("skill=$skillName\n", Charsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun deleteIfExists(file: File) {
        if (file.exists() && !file.deleteRecursively()) {
            throw IOException("Failed to delete: ${file.absolutePath}")
        }
    }

    companion object {
        const val INSTALL_SENTINEL = ".install-complete"
        private const val AGENT_SKILLS_ROOT = "agent_skills"
        private const val BROWSER_USE_SKILL = "browser-use"
        private const val SKILL_DIR_PLACEHOLDER = "{{SKILL_DIR}}"
        private val SKILL_NAME_PATTERN = Regex("^[a-z][a-z0-9-]{0,63}$")

        fun hasCompletedBrowserUseInstall(skillsDir: File): Boolean =
            File(skillsDir, "$BROWSER_USE_SKILL/$INSTALL_SENTINEL").isFile
    }
}
