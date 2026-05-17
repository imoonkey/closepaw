package ai.closepaw.memory

import android.util.Log
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent markdown-based memory store. Read is side-effect-free (raw file
 * contents). Writes go through atomic temp-file replace. Append performs
 * schema-aware insertion under the target section heading per explicit rules
 * (see design_claude.md → Append insertion rules).
 */
class MemoryStore(
    private val memoryDir: File,
    val maxContentLength: Int = DEFAULT_MAX_CONTENT_LENGTH,
    val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES
) {
    companion object {
        private const val TAG = "MemoryStore"
        const val DEFAULT_MAX_CONTENT_LENGTH = 2000
        const val DEFAULT_MAX_FILE_BYTES = 8192
        private const val APPS_DIR = "apps"
        private const val USER_FILE = "user.md"
        private const val DEVICE_FILE = "device.md"
        private val SAFE_PACKAGE_PATTERN = Regex("^[a-zA-Z0-9_.]+$")
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
    }

    private data class DocumentSpec(
        val scope: MemoryScope,
        val file: File,
        val title: String,
        val intro: String?,
        val sections: List<MemorySection>
    )

    private val writtenThisSession = AtomicBoolean(false)

    fun hasWrittenThisSession(): Boolean = writtenThisSession.get()

    @Synchronized
    fun read(scope: MemoryScope, packageName: String? = null): String? {
        val spec = buildSpec(scope, packageName) ?: return null
        if (!spec.file.exists()) return null
        return try {
            spec.file.readText()
        } catch (e: IOException) {
            Log.w(TAG, "Memory read failed: ${spec.file.name}", e)
            null
        }
    }

    @Synchronized
    fun write(scope: MemoryScope, packageName: String? = null, content: String): SaveResult {
        val spec = buildSpec(scope, packageName) ?: return SaveResult.InvalidScope
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxFileBytes) return SaveResult.TooLarge
        return try {
            atomicWrite(spec.file, bytes)
            writtenThisSession.set(true)
            SaveResult.Success
        } catch (e: IOException) {
            Log.w(TAG, "Memory write failed: ${spec.file.name}", e)
            SaveResult.IoError(e.message ?: "unknown")
        }
    }

    @Synchronized
    fun delete(scope: MemoryScope, packageName: String? = null): Boolean {
        val spec = buildSpec(scope, packageName) ?: return false
        if (!spec.file.exists()) return true
        return spec.file.delete()
    }

    @Synchronized
    fun listAppPackages(): List<String> {
        val appsDir = File(memoryDir, APPS_DIR)
        if (!appsDir.isDirectory) return emptyList()
        return appsDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.mapNotNull { file ->
                val name = file.nameWithoutExtension
                if (SAFE_PACKAGE_PATTERN.matches(name)) name else null
            }
            ?.sorted()
            .orEmpty()
    }

    @Synchronized
    fun append(
        scope: MemoryScope,
        section: MemorySection,
        content: String,
        packageName: String? = null
    ): Boolean {
        if (!MemorySchema.isSectionAllowed(scope, section)) {
            Log.w(TAG, "Rejected invalid memory section $section for scope $scope")
            return false
        }
        val spec = buildSpec(scope, packageName) ?: return false
        val sanitized = sanitizeContent(content)
        if (sanitized.isEmpty()) {
            Log.w(TAG, "Rejected empty memory content for $scope/$section")
            return false
        }
        val entry = formatEntry(sanitized)
        val newContent =
            if (!spec.file.exists()) {
                buildSkeleton(spec, section, entry)
            } else {
                val existing =
                    try {
                        spec.file.readText()
                    } catch (e: IOException) {
                        Log.w(TAG, "Memory read failed during append: ${spec.file.name}", e)
                        return false
                    }
                insertEntry(existing, section, entry)
            }
        val bytes = newContent.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxFileBytes) {
            Log.w(TAG, "memory append rejected: file would exceed 8 KB ($scope/$section)")
            return false
        }
        return try {
            atomicWrite(spec.file, bytes)
            writtenThisSession.set(true)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Memory write failed: ${spec.file.name}", e)
            false
        }
    }

    fun appendUserFact(content: String): Boolean =
        append(MemoryScope.USER, MemorySection.FACTS, content)

    fun appendUserPreference(content: String): Boolean =
        append(MemoryScope.USER, MemorySection.PREFERENCES, content)

    fun appendDeviceFact(content: String): Boolean =
        append(MemoryScope.DEVICE, MemorySection.FACTS, content)

    fun appendDevicePitfall(content: String): Boolean =
        append(MemoryScope.DEVICE, MemorySection.PITFALLS, content)

    fun appendDeviceVerification(content: String): Boolean =
        append(MemoryScope.DEVICE, MemorySection.VERIFICATION, content)

    fun appendAppSkillOverride(packageName: String, content: String): Boolean =
        append(MemoryScope.APP, MemorySection.APP_SKILL_OVERRIDES, content, packageName)

    fun appendAppPreference(packageName: String, content: String): Boolean =
        append(MemoryScope.APP, MemorySection.PREFERENCES, content, packageName)

    fun appendAppOperationalNote(packageName: String, content: String): Boolean =
        append(MemoryScope.APP, MemorySection.OPERATIONAL_NOTES, content, packageName)

    private fun validatePackageName(packageName: String): String? {
        val trimmed = packageName.trim()
        if (!SAFE_PACKAGE_PATTERN.matches(trimmed)) {
            Log.w(TAG, "Rejected unsafe package name: ${trimmed.take(50)}")
            return null
        }
        return trimmed
    }

    private fun buildSpec(scope: MemoryScope, packageName: String? = null): DocumentSpec? {
        return when (scope) {
            MemoryScope.USER ->
                DocumentSpec(
                    scope = scope,
                    file = File(memoryDir, USER_FILE),
                    title = "# User Memory",
                    intro = null,
                    sections = MemorySchema.sectionsFor(scope)
                )
            MemoryScope.DEVICE ->
                DocumentSpec(
                    scope = scope,
                    file = File(memoryDir, DEVICE_FILE),
                    title = "# Device Memory",
                    intro = null,
                    sections = MemorySchema.sectionsFor(scope)
                )
            MemoryScope.APP -> {
                val safeName = validatePackageName(packageName ?: return null) ?: return null
                DocumentSpec(
                    scope = scope,
                    file = File(File(memoryDir, APPS_DIR), "$safeName.md"),
                    title = "# App Memory: $safeName",
                    intro = "> Local delta over app skill. If conflict exists, trust this file.",
                    sections = MemorySchema.sectionsFor(scope)
                )
            }
        }
    }

    private fun sanitizeContent(content: String): String {
        // 1) Fold whitespace newlines/tabs to single space (do this BEFORE stripping
        //    control chars — otherwise tokens glue together).
        var s = content.replace(Regex("[\\r\\n\\t]"), " ")
        // 2) Strip remaining Unicode Cc control characters.
        s = s.filterNot { it.category == CharCategory.CONTROL }
        // 3) Collapse runs of whitespace; trim outer.
        s = s.replace(Regex("\\s+"), " ").trim()
        // 4) Truncate to maxContentLength (characters).
        return s.take(maxContentLength)
    }

    private fun formatEntry(content: String): String =
        "- [${ZonedDateTime.now().format(TIMESTAMP_FORMATTER)}] $content"

    private fun buildSkeleton(spec: DocumentSpec, target: MemorySection, entry: String): String =
        buildString {
            appendLine(spec.title)
            spec.intro?.let {
                appendLine()
                appendLine(it)
            }
            for (s in spec.sections) {
                appendLine()
                appendLine("## ${s.heading}")
                if (s == target) appendLine(entry)
            }
        }.trimEnd() + "\n"

    private fun insertEntry(existing: String, section: MemorySection, entry: String): String {
        val heading = "## ${section.heading}"
        val trailingNewline = existing.endsWith("\n")
        val lines = existing.split("\n").toMutableList()
        if (trailingNewline && lines.lastOrNull() == "") {
            lines.removeAt(lines.size - 1)
        }
        val headingIndices = lines.mapIndexedNotNull { i, line ->
            if (line.trim() == heading) i else null
        }
        if (headingIndices.isEmpty()) {
            // Rule 5: heading missing → append "\n## heading\nentry\n" at EOF.
            val sb = StringBuilder(existing)
            if (!existing.endsWith("\n")) sb.append('\n')
            sb.append('\n').append(heading).append('\n').append(entry).append('\n')
            return sb.toString()
        }
        if (headingIndices.size > 1) {
            Log.w(TAG, "Duplicate heading '$heading' — inserting under last occurrence")
        }
        // Rule 4/6: under (last) heading, insert before the next "## " line, or at EOF.
        val anchor = headingIndices.last()
        var nextHeading = lines.size
        for (i in (anchor + 1) until lines.size) {
            if (lines[i].trimStart().startsWith("## ")) {
                nextHeading = i
                break
            }
        }
        // Skip back over blank lines that separate sections so the new bullet
        // sits next to the existing ones, not after the gap.
        var insertAt = nextHeading
        while (insertAt > anchor + 1 && lines[insertAt - 1].isBlank()) {
            insertAt--
        }
        lines.add(insertAt, entry)
        val joined = lines.joinToString("\n")
        return if (trailingNewline) "$joined\n" else joined
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmpFile = File(target.parentFile, "${target.name}.tmp")
        tmpFile.writeBytes(bytes)
        if (!tmpFile.renameTo(target)) {
            tmpFile.delete()
            throw IOException("Failed to replace ${target.name}")
        }
    }
}
