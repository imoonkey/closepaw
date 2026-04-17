package ai.closepaw.memory

import android.util.Log
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent markdown-based memory store for scope-first V2 memory files.
 */
class MemoryStore(
    private val memoryDir: File,
    val maxContentLength: Int = DEFAULT_MAX_CONTENT_LENGTH
) {
    companion object {
        private const val TAG = "MemoryStore"
        const val DEFAULT_MAX_CONTENT_LENGTH = 2000
        private const val APPS_DIR = "apps"
        private const val USER_FILE = "user.md"
        private const val LEGACY_USER_PREFS_FILE = "user_prefs.md"
        private const val DEVICE_FILE = "device.md"
        private val SAFE_PACKAGE_PATTERN = Regex("^[a-zA-Z0-9_.]+$")
        private val LEGACY_KIND_PREFIX = Regex("""^\[(?:workflow|fact|preference|pitfall|verification|override|app_skill_override)]\s*""")
        private val ENTRY_PATTERN = Regex("""^- \[(.+?)]\s+(.+)$""")
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
    }

    private data class DocumentSpec(
        val scope: MemoryScope,
        val file: File,
        val title: String,
        val intro: String? = null,
        val sections: List<MemorySection>
    )

    private val writtenThisSession = AtomicBoolean(false)

    fun hasWrittenThisSession(): Boolean = writtenThisSession.get()

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
        try {
            val spec = buildSpec(scope, packageName) ?: return false
            val normalizedContent = normalizeContent(content).take(maxContentLength)
            if (normalizedContent.isEmpty()) {
                Log.w(TAG, "Rejected empty memory content for $scope/$section")
                return false
            }
            val sections = loadSections(spec)
            sections.getValue(section).add(formatEntry(normalizedContent))
            if (writeCanonicalDocument(spec, sections)) {
                writtenThisSession.set(true)
                return true
            }
            return false
        } catch (e: IOException) {
            Log.w(TAG, "Memory write failed for $scope/$section", e)
            return false
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

    @Synchronized
    fun readUserMemory(): String? = readDocument(buildSpec(MemoryScope.USER))

    @Synchronized
    fun readDeviceMemory(): String? = readDocument(buildSpec(MemoryScope.DEVICE))

    @Synchronized
    fun readAppMemory(packageName: String): String? = readDocument(buildSpec(MemoryScope.APP, packageName))

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
                    sections = MemorySchema.sectionsFor(scope)
                )
            MemoryScope.DEVICE ->
                DocumentSpec(
                    scope = scope,
                    file = File(memoryDir, DEVICE_FILE),
                    title = "# Device Memory",
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

    private fun readDocument(spec: DocumentSpec?): String? {
        if (spec == null) return null
        try {
            val legacyUserPrefsFile =
                if (spec.scope == MemoryScope.USER) File(memoryDir, LEGACY_USER_PREFS_FILE) else null
            if (!spec.file.exists() && legacyUserPrefsFile?.exists() == true) {
                migrateLegacyUserPrefs(spec, legacyUserPrefsFile)
            }
            if (!spec.file.exists()) return null

            val sections = loadSections(spec)
            if (sections.values.all { it.isEmpty() }) return null
            val canonical = buildCanonicalDocument(spec, sections)
            if (!sameContent(spec.file, canonical)) {
                writeCanonicalDocument(spec, sections)
            }
            return canonical.trim()
        } catch (e: IOException) {
            Log.w(TAG, "Memory read failed: ${spec.file.name}", e)
            return null
        }
    }

    private fun loadSections(spec: DocumentSpec): LinkedHashMap<MemorySection, MutableList<String>> {
        val sections =
            linkedMapOf<MemorySection, MutableList<String>>().apply {
                spec.sections.forEach { put(it, mutableListOf()) }
            }
        if (!spec.file.exists()) return sections

        val lines = spec.file.readLines()
        var currentSection: MemorySection? = null
        var sawKnownHeading = false

        for (line in lines) {
            val trimmed = line.trim()
            val matchedSection = spec.sections.firstOrNull { trimmed == "## ${it.heading}" }
            if (matchedSection != null) {
                currentSection = matchedSection
                sawKnownHeading = true
                continue
            }
            if (!trimmed.startsWith("- [")) continue

            if (sawKnownHeading) {
                currentSection?.let { sections.getValue(it).add(normalizeExistingEntry(trimmed)) }
            } else {
                val (section, entry) = migrateLegacyEntry(spec.scope, trimmed)
                sections.getValue(section).add(entry)
            }
        }
        return sections
    }

    private fun migrateLegacyUserPrefs(spec: DocumentSpec, legacyFile: File) {
        val sections =
            linkedMapOf<MemorySection, MutableList<String>>().apply {
                spec.sections.forEach { put(it, mutableListOf()) }
            }
        legacyFile.readLines()
            .map { it.trim() }
            .filter { it.startsWith("- [") }
            .map { migrateLegacyEntry(MemoryScope.USER, it) }
            .forEach { (section, entry) -> sections.getValue(section).add(entry) }
        if (sections.values.all { it.isEmpty() }) return
        if (writeCanonicalDocument(spec, sections)) {
            legacyFile.delete()
        }
    }

    private fun migrateLegacyEntry(scope: MemoryScope, entry: String): Pair<MemorySection, String> {
        val match = ENTRY_PATTERN.matchEntire(entry) ?: return defaultLegacySection(scope) to entry.trim()
        val timestamp = match.groupValues[1]
        val body = match.groupValues[2].trim()
        val rawTag = LEGACY_KIND_PREFIX.find(body)?.value?.trim()
        val normalizedBody = normalizeContent(body)
        val section =
            when (scope) {
                MemoryScope.USER ->
                    if (rawTag == "[fact]") MemorySection.FACTS else MemorySection.PREFERENCES
                MemoryScope.DEVICE ->
                    when (rawTag) {
                        "[pitfall]" -> MemorySection.PITFALLS
                        "[verification]" -> MemorySection.VERIFICATION
                        else -> MemorySection.FACTS
                    }
                MemoryScope.APP ->
                    when (rawTag) {
                        "[preference]" -> MemorySection.PREFERENCES
                        "[override]", "[app_skill_override]" -> MemorySection.APP_SKILL_OVERRIDES
                        else -> MemorySection.OPERATIONAL_NOTES
                    }
            }
        return section to "- [$timestamp] $normalizedBody"
    }

    private fun defaultLegacySection(scope: MemoryScope): MemorySection =
        when (scope) {
            MemoryScope.USER -> MemorySection.PREFERENCES
            MemoryScope.DEVICE -> MemorySection.FACTS
            MemoryScope.APP -> MemorySection.OPERATIONAL_NOTES
        }

    private fun normalizeExistingEntry(entry: String): String {
        val match = ENTRY_PATTERN.matchEntire(entry.trim()) ?: return entry.trim()
        val timestamp = match.groupValues[1]
        val body = normalizeContent(match.groupValues[2])
        return "- [$timestamp] $body"
    }

    private fun normalizeContent(content: String): String =
        content.trim()
            .removePrefix("- ")
            .replace(LEGACY_KIND_PREFIX, "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun formatEntry(content: String): String =
        "- [${ZonedDateTime.now().format(TIMESTAMP_FORMATTER)}] $content"

    private fun buildCanonicalDocument(
        spec: DocumentSpec,
        sections: LinkedHashMap<MemorySection, MutableList<String>>
    ): String =
        buildString {
            appendLine(spec.title)
            spec.intro?.let {
                appendLine()
                appendLine(it)
            }
            for (section in spec.sections) {
                appendLine()
                appendLine("## ${section.heading}")
                sections.getValue(section).forEach { appendLine(it) }
            }
        }.trimEnd() + "\n"

    private fun writeCanonicalDocument(
        spec: DocumentSpec,
        sections: LinkedHashMap<MemorySection, MutableList<String>>
    ): Boolean {
        try {
            spec.file.parentFile?.mkdirs()
            val content = buildCanonicalDocument(spec, sections)
            val tmpFile = File(spec.file.parentFile, "${spec.file.name}.tmp")
            tmpFile.writeText(content)
            if (!tmpFile.renameTo(spec.file)) {
                throw IOException("Failed to replace ${spec.file.name}")
            }
            return true
        } catch (e: IOException) {
            Log.w(TAG, "Memory write failed: ${spec.file.name}", e)
            return false
        }
    }

    private fun sameContent(file: File, expected: String): Boolean =
        file.readText().trimEnd() == expected.trimEnd()
}
