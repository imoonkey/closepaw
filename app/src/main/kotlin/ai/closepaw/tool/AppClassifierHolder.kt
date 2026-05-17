package ai.closepaw.tool

import android.content.Context
import android.util.Log
import ai.closepaw.app.AppSettingsStore

/**
 * Application-scoped [AppClassifier] singleton.
 *
 * Three previous construction sites (`AgentSession.create`, `AgentSession.reload`, and the
 * fallback in `SessionServices.create`) would each spin up their own classifier — and therefore
 * their own user-override StateFlow — so the settings UI, capsule, and live session could
 * diverge. Hoisting to the process keeps writes and reads coherent.
 *
 * Persistence: writes flow through `setOverride` → `onUserOverridesChanged` callback →
 * [AppSettingsStore.saveUserAppOverrides]. The callback is `suspend` and runs inside the
 * classifier's mutex; the store completes a synchronous `commit()` on [kotlinx.coroutines.Dispatchers.IO]
 * before the mutex releases, so emissions and disk writes stay in lock-step
 * (last-emitted == last-persisted).
 *
 * Single-writer rule: every override change (settings UI, capsule `ApprovalScope.ALWAYS`)
 * goes through `classifier.setOverride(...)`. The UI never writes the store directly.
 */
object AppClassifierHolder {
    private const val TAG = "AppClassifierHolder"

    @Volatile private var instance: AppClassifier? = null

    fun get(context: Context): AppClassifier {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): AppClassifier {
        val store = AppSettingsStore(appContext)
        val bundled = AppClassifier.loadBundledTiers(appContext.assets)
        val initialOverrides = store.loadUserAppOverrides()
        Log.i(
            TAG,
            "Bootstrapping AppClassifier: ${bundled.size} bundled tiers, ${initialOverrides.size} user overrides"
        )
        return AppClassifier(
            appTiers = bundled,
            initialUserOverrides = initialOverrides,
            onUserOverridesChanged = { snapshot ->
                store.saveUserAppOverrides(snapshot)
            }
        )
    }
}
