package ai.closepaw.tool

import android.content.res.AssetManager
import android.util.Log
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.protocol.AppTier
import org.json.JSONObject

/**
 * Classifies Android packages into security tiers.
 *
 * Lookup order: appTiers[pkg] → CAUTIOUS (unknown = cautious).
 */
class AppClassifier(
    private val appTiers: Map<String, AppTier>
) {
    fun classify(pkg: String?): AppTier {
        if (pkg == null) return AppTier.CAUTIOUS
        appTiers[pkg]?.let { return it }
        return AppTier.CAUTIOUS
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
                val tierStr = apps.getString(key)
                val tier = AppTier.fromString(tierStr)
                    ?: throw IllegalStateException("Unknown tier value '$tierStr' for package '$key' in app_tiers.json")
                tiers[key] = tier
            }
            Log.i(TAG, "Loaded ${tiers.size} app tier entries")
            return AppClassifier(tiers)
        }
    }
}
