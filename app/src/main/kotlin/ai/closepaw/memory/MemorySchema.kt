package ai.closepaw.memory

enum class MemoryScope(val wireValue: String) {
    USER("user"),
    DEVICE("device"),
    APP("app");

    companion object {
        fun fromWireValue(value: String): MemoryScope? =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
    }
}

enum class MemorySection(val wireValue: String, val heading: String) {
    FACTS("facts", "Facts"),
    PREFERENCES("preferences", "Preferences"),
    PITFALLS("pitfalls", "Pitfalls"),
    VERIFICATION("verification", "Verification"),
    APP_SKILL_OVERRIDES("app_skill_overrides", "App Skill Overrides"),
    OPERATIONAL_NOTES("operational_notes", "Operational Notes");

    companion object {
        fun fromWireValue(value: String): MemorySection? =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
    }
}

sealed class SaveResult {
    object Success : SaveResult()
    object TooLarge : SaveResult()
    object InvalidScope : SaveResult()
    data class IoError(val message: String) : SaveResult()
}

object MemorySchema {
    fun sectionsFor(scope: MemoryScope): List<MemorySection> =
        when (scope) {
            MemoryScope.USER ->
                listOf(
                    MemorySection.FACTS,
                    MemorySection.PREFERENCES
                )
            MemoryScope.DEVICE ->
                listOf(
                    MemorySection.FACTS,
                    MemorySection.PITFALLS,
                    MemorySection.VERIFICATION
                )
            MemoryScope.APP ->
                listOf(
                    MemorySection.APP_SKILL_OVERRIDES,
                    MemorySection.PREFERENCES,
                    MemorySection.OPERATIONAL_NOTES
                )
        }

    fun isSectionAllowed(scope: MemoryScope, section: MemorySection): Boolean =
        section in sectionsFor(scope)
}
