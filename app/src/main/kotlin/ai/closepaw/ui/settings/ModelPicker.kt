package ai.closepaw.ui.settings

import ai.closepaw.llm.ModelEntry

/**
 * One displayable row in the searchable grouped model picker. Carries the
 * canonical catalog key (`modelId` here is the catalog name, e.g.
 * `"openrouter:anthropic/claude-opus-4.7"`) plus the user-facing
 * `displayName` and the upstream `created` timestamp used for in-group sort.
 */
data class ModelPickerRow(
    val name: String,
    val modelId: String,
    val displayName: String,
    val created: Long,
)

/**
 * A non-empty group of [ModelPickerRow]s rendered as a collapsible section.
 * `key` is the group identifier (e.g. `"anthropic"`, `"openai"`, `"(other)"`),
 * also used as the display label.
 */
data class ModelPickerGroup(
    val key: String,
    val rows: List<ModelPickerRow>,
)

/**
 * Pure picker model — search box + grouped collapsible sections + sort.
 * Designed so all behavior (filtering, grouping, sort, expansion gating)
 * is unit-testable on the JVM. The Compose layer is a thin renderer of
 * the [ModelPickerState] produced here.
 */
object ModelPicker {

    /** Group keys pinned to the top of the list in this order. */
    private val PINNED_GROUPS = listOf("anthropic", "openai", "google")

    /** Group key for models whose id doesn't have a `vendor/` prefix. */
    private const val OTHER_GROUP_KEY = "(other)"

    /**
     * Build picker state for [allEntries] with optional search [query] and
     * the currently selected catalog key. When [query] is blank, the result
     * is grouped; when non-blank, all matches collapse into a single
     * `(search)` group so the user can scan results without re-navigating
     * collapsed sections.
     *
     * Within each group, rows are sorted by `created` descending (newest
     * first) so refreshed catalogs surface new model ids immediately. Ties
     * fall back to `displayName` ascending for stable ordering.
     *
     * @param expandedKeys keys the user has explicitly expanded. The group
     * containing [selectedName] is always considered expanded on initial
     * open so the user can see the current selection without scrolling.
     */
    fun buildState(
        allEntries: List<ModelEntry>,
        query: String,
        selectedName: String?,
        expandedKeys: Set<String>,
    ): ModelPickerState {
        val rows = allEntries.map { it.toRow() }
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            val filtered = rows.filter { it.matches(trimmed) }.sortedByCreatedDesc()
            return ModelPickerState(
                query = query,
                isSearching = true,
                groups = if (filtered.isEmpty()) emptyList()
                else listOf(ModelPickerGroup(key = "(search)", rows = filtered)),
                expandedKeys = setOf("(search)"),
                selectedName = selectedName,
            )
        }

        val grouped = rows.groupBy(::groupKeyFor)
        val ordered = groupKeysInDisplayOrder(grouped.keys)
        val groups = ordered.map { key ->
            ModelPickerGroup(key = key, rows = grouped.getValue(key).sortedByCreatedDesc())
        }
        val selectedGroup = selectedName?.let { sel ->
            groups.firstOrNull { g -> g.rows.any { it.name == sel } }?.key
        }
        return ModelPickerState(
            query = query,
            isSearching = false,
            groups = groups,
            expandedKeys = expandedKeys + listOfNotNull(selectedGroup),
            selectedName = selectedName,
        )
    }

    /**
     * Compute the group key for a row by splitting `modelId` on the first
     * `/`. Discovered entries are namespaced `provider:vendor/model` so the
     * picker peels off the namespace prefix before splitting. Entries
     * without a `/` (e.g. seed bare `gpt-5`, or OTHER discovery with no
     * vendor prefix) land in the `(other)` group.
     */
    internal fun groupKeyFor(row: ModelPickerRow): String {
        val id = row.modelId
        val slash = id.indexOf('/')
        if (slash <= 0) return OTHER_GROUP_KEY
        return id.substring(0, slash).lowercase()
    }

    /**
     * Pinned groups (anthropic / openai / google) lead in fixed order; the
     * rest fall through alphabetically. `(other)` always tails so it never
     * overshadows a real vendor group.
     */
    internal fun groupKeysInDisplayOrder(keys: Set<String>): List<String> {
        val pinned = PINNED_GROUPS.filter { it in keys }
        val rest = (keys - pinned.toSet() - OTHER_GROUP_KEY).sorted()
        val tail = if (OTHER_GROUP_KEY in keys) listOf(OTHER_GROUP_KEY) else emptyList()
        return pinned + rest + tail
    }

    private fun ModelEntry.toRow(): ModelPickerRow = ModelPickerRow(
        name = name,
        modelId = modelId,
        displayName = displayName,
        created = created,
    )

    private fun List<ModelPickerRow>.sortedByCreatedDesc(): List<ModelPickerRow> =
        sortedWith(compareByDescending<ModelPickerRow> { it.created }.thenBy { it.displayName.lowercase() })

    private fun ModelPickerRow.matches(needle: String): Boolean {
        val n = needle.lowercase()
        return displayName.lowercase().contains(n) || modelId.lowercase().contains(n)
    }
}

/**
 * Computed picker view model — what the Compose layer draws each frame.
 *
 * `selectedRowIndex` is a flat index across the visible (expanded) rows in
 * `groups`, intended for `LazyListState.scrollToItem`. `-1` means the
 * selected row is not present in the currently-visible result set.
 */
data class ModelPickerState(
    val query: String,
    val isSearching: Boolean,
    val groups: List<ModelPickerGroup>,
    val expandedKeys: Set<String>,
    val selectedName: String?,
) {
    /**
     * Flat row index of the selected row across visible groups (counting
     * one slot per group header followed by rows in expanded groups). `-1`
     * when the selected row is not present or its group isn't expanded.
     */
    val selectedRowIndex: Int
        get() {
            if (selectedName == null) return -1
            var index = 0
            for (group in groups) {
                // Group header always takes one slot.
                if (group.key !in expandedKeys) {
                    index += 1
                    continue
                }
                index += 1
                for (row in group.rows) {
                    if (row.name == selectedName) return index
                    index += 1
                }
            }
            return -1
        }
}
