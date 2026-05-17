package ai.closepaw.tool

import android.content.res.AssetManager
import android.util.Log
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.protocol.AppTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Classifies Android packages into security tiers.
 *
 * Two layers:
 *  - [appTiers]            — bundled tiers from `assets/security/app_tiers.json` (immutable).
 *  - [userOverrides]       — per-package user overrides (mutated through [setOverride]).
 *
 * Effective tier:
 *  1. bundled[pkg] == BLOCKED → BLOCKED (safety floor, non-overridable).
 *  2. else overrides[pkg] ?: bundled[pkg] ?: CAUTIOUS.
 */
class AppClassifier(
    private val appTiers: Map<String, AppTier>,
    initialUserOverrides: Map<String, AppTier> = emptyMap(),
    private val onUserOverridesChanged: (suspend (Map<String, AppTier>) -> Unit)? = null
) {
    private val _userOverrides = MutableStateFlow(initialUserOverrides.toMap())
    val userOverrides: StateFlow<Map<String, AppTier>> = _userOverrides.asStateFlow()

    private val overrideMutex = Mutex()

    fun bundledTier(pkg: String?): AppTier? = pkg?.let { appTiers[it] }

    fun classify(pkg: String?): AppTier {
        if (pkg == null) return AppTier.CAUTIOUS
        val bundled = appTiers[pkg]
        if (bundled == AppTier.BLOCKED) return AppTier.BLOCKED
        return _userOverrides.value[pkg] ?: bundled ?: AppTier.CAUTIOUS
    }

    /**
     * Apply a user override. Serialized end-to-end (in-memory CAS + persistence)
     * by [overrideMutex] so on-disk state never diverges from [userOverrides].
     *
     * - bundled-BLOCKED with non-BLOCKED tier → [SetOverrideResult.RefusedBlocked] (not emitted, not persisted).
     * - tier matches bundled default          → entry removed → [SetOverrideResult.Removed].
     * - otherwise                              → entry written → [SetOverrideResult.Accepted].
     */
    suspend fun setOverride(pkg: String, tier: AppTier): SetOverrideResult = overrideMutex.withLock {
        if (appTiers[pkg] == AppTier.BLOCKED && tier != AppTier.BLOCKED) {
            return@withLock SetOverrideResult.RefusedBlocked
        }
        val bundledDefault = appTiers[pkg] ?: AppTier.CAUTIOUS
        val next = _userOverrides.updateAndGet { old ->
            if (tier == bundledDefault) old - pkg else old + (pkg to tier)
        }
        onUserOverridesChanged?.invoke(next)
        if (next.containsKey(pkg)) SetOverrideResult.Accepted else SetOverrideResult.Removed
    }

    /**
     * Returns a masked snapshot (empty elements, no image) if the package is BLOCKED,
     * otherwise returns the original snapshot unchanged.
     * Call this at every screen capture point to prevent BLOCKED app content from leaking.
     */
    fun maskIfBlocked(snapshot: ScreenSnapshot, packageName: String?): ScreenSnapshot {
        if (classify(packageName) != AppTier.BLOCKED) return snapshot
        return ScreenSnapshot(
            timestamp = snapshot.timestamp,
            elements = emptyList(),
            image = null
        )
    }

    companion object {
        private const val TAG = "AppClassifier"

        /**
         * Parse only the bundled tier map from assets. Used by [AppClassifierHolder]
         * which then layers in user overrides + the persistence callback.
         */
        fun loadBundledTiers(assets: AssetManager): Map<String, AppTier> {
            val json = try {
                assets.open("security/app_tiers.json")
                    .bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to load app_tiers.json: app safety tiers unavailable", e
                )
            }
            val obj = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Corrupt app_tiers.json: app safety tiers unavailable", e
                )
            }
            val apps = obj.getJSONObject("apps")
            val tiers = mutableMapOf<String, AppTier>()
            for (key in apps.keys()) {
                val tierStr = apps.getString(key)
                val tier = AppTier.fromString(tierStr)
                    ?: throw IllegalStateException("Unknown tier value '$tierStr' for package '$key' in app_tiers.json")
                tiers[key] = tier
            }
            Log.i(TAG, "Loaded ${tiers.size} app tier entries")
            return tiers
        }

        /**
         * Convenience for tests / non-singleton paths. Returns a classifier with no user
         * overrides and no persistence callback. Production code paths should go through
         * [AppClassifierHolder] so UI / capsule / agent observe the same StateFlow.
         */
        fun fromAssets(assets: AssetManager): AppClassifier =
            AppClassifier(loadBundledTiers(assets))
    }
}

sealed interface SetOverrideResult {
    /** Override written (or updated). */
    data object Accepted : SetOverrideResult

    /** Override matched the bundled default, so the entry was removed. */
    data object Removed : SetOverrideResult

    /** Bundled-BLOCKED is the safety floor; non-BLOCKED writes are refused. */
    data object RefusedBlocked : SetOverrideResult
}
