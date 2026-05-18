package ai.closepaw.ui.settings

import ai.closepaw.agent.cognition.skills.SkillFrontmatterParser
import ai.closepaw.memory.MemoryStore
import android.content.res.AssetManager
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Per-package content flags surfaced by the App Access page row. */
data class AppContentSummary(val hasMemory: Boolean, val hasSkill: Boolean) {
    companion object {
        val NONE = AppContentSummary(hasMemory = false, hasSkill = false)
    }
}

/**
 * Page-scoped preload index telling each App Access row whether a package has
 * memory and/or a bundled app skill.
 *
 * Built once via [load] on [Dispatchers.IO] when the page mounts; rows then read
 * via [summaryFor] (O(1), synchronous, safe to call during composition) or
 * observe the [summaries] flow. Save / delete handlers call [update] to keep
 * the index in sync without re-scanning the filesystem / asset tree.
 */
class AppAccessContentIndex(
    private val memoryPackages: PackageLister,
    private val skillPackages: PackageLister,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Lists package names that have content of a given kind. May do IO. */
    fun interface PackageLister {
        fun list(): Set<String>
    }

    private val _summaries = MutableStateFlow<Map<String, AppContentSummary>>(emptyMap())
    private val mutex = Mutex()

    /** Observable map: package → summary. Empty until [load] returns. */
    val summaries: StateFlow<Map<String, AppContentSummary>> = _summaries

    /** O(1) lookup. Unknown packages return [AppContentSummary.NONE]. */
    fun summaryFor(packageName: String): AppContentSummary =
        _summaries.value[packageName] ?: AppContentSummary.NONE

    /**
     * Single IO pass: lists memory + skill packages, merges into a map. Safe to
     * call repeatedly; later calls replace prior state. Serialized with
     * [update] via [mutex] so an in-flight scan cannot clobber a concurrent
     * row-level update.
     */
    suspend fun load(): Map<String, AppContentSummary> = withContext(ioDispatcher) {
        mutex.withLock {
            val memory = memoryPackages.list()
            val skills = skillPackages.list()
            val merged = (memory + skills).associateWith { pkg ->
                AppContentSummary(hasMemory = pkg in memory, hasSkill = pkg in skills)
            }
            _summaries.value = merged
            merged
        }
    }

    /**
     * Incremental update after a save / delete in the row editor. Passing
     * [AppContentSummary.NONE] drops the entry so it no longer surfaces chips.
     * Suspends to share [mutex] with [load].
     */
    suspend fun update(packageName: String, summary: AppContentSummary) {
        mutex.withLock {
            val current = _summaries.value
            _summaries.value = if (summary == AppContentSummary.NONE) {
                if (packageName in current) current - packageName else current
            } else {
                current + (packageName to summary)
            }
        }
    }

    companion object {
        private const val TAG = "AppAccessContentIdx"
        private const val APP_SKILLS_ROOT = "app_skills"

        /** Adapts [MemoryStore.listAppPackages] as a [PackageLister]. */
        fun memoryLister(store: MemoryStore): PackageLister =
            PackageLister { store.listAppPackages().toSet() }

        /**
         * Lists asset subdirectories under `app_skills/` whose `SKILL.md` parses
         * cleanly — matching the gating used by `AssetAppSkillRepository`, so
         * the index reports skill presence iff the repository would actually
         * load one.
         */
        fun assetSkillLister(assets: AssetManager): PackageLister = PackageLister {
            val entries = try {
                assets.list(APP_SKILLS_ROOT).orEmpty()
            } catch (e: IOException) {
                Log.w(TAG, "Failed to list $APP_SKILLS_ROOT assets", e)
                return@PackageLister emptySet()
            }
            entries.asSequence()
                .filter { entry ->
                    val path = "$APP_SKILLS_ROOT/$entry/SKILL.md"
                    val content = try {
                        assets.open(path).bufferedReader().use { it.readText() }
                    } catch (_: IOException) {
                        return@filter false
                    }
                    SkillFrontmatterParser.parse(content) != null
                }
                .toSet()
        }
    }
}
