package com.moonkey.androidagent.tool

import android.content.res.AssetManager
import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.AppTier
import org.json.JSONObject

/**
 * Classifies Android packages into security tiers.
 *
 * Lookup order: userOverrides[pkg] → appTiers[pkg] → CAUTIOUS (unknown = cautious).
 * User overrides can only tighten (not loosen) the tier.
 */
class AppClassifier(
    private val appTiers: Map<String, AppTier>
) {
    private val userOverrides = mutableMapOf<String, AppTier>()

    fun classify(pkg: String?): AppTier {
        if (pkg == null) return AppTier.CAUTIOUS
        userOverrides[pkg]?.let { return it }
        appTiers[pkg]?.let { return it }
        return AppTier.CAUTIOUS
    }

    /**
     * Add a user override. Only tightening is allowed:
     * NORMAL → CAUTIOUS or BLOCKED, CAUTIOUS → BLOCKED.
     * Returns true if the override was applied.
     */
    fun addUserOverride(pkg: String, tier: AppTier): Boolean {
        val current = appTiers[pkg] ?: AppTier.CAUTIOUS
        if (tier.ordinal >= current.ordinal) return false  // cannot loosen
        userOverrides[pkg] = tier
        return true
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

        fun fromAssets(assets: AssetManager): AppClassifier {
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
                AppTier.fromString(apps.getString(key))?.let { tiers[key] = it }
            }
            Log.i(TAG, "Loaded ${tiers.size} app tier entries")
            return AppClassifier(tiers)
        }
    }
}
