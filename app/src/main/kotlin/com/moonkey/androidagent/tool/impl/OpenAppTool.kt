package com.moonkey.androidagent.tool.impl

import android.util.Log
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolObservation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Well-known app aliases for name → package resolution.
 */
private object AppAliases {
    val PACKAGE_MAP = mapOf(
        "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "youtube" to "com.google.android.youtube",
        "play store" to "com.android.vending",
        "google play" to "com.android.vending",
        "files" to "com.google.android.apps.nbu.files",
        "phone" to "com.android.dialer",
        "dialer" to "com.android.dialer",
        "camera" to "com.android.camera",
        "settings" to "com.android.settings",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "contacts" to "com.android.contacts",
        "calendar" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "photos" to "com.google.android.apps.photos",
        "drive" to "com.google.android.apps.docs",
        "keep" to "com.google.android.keep",
        "google keep" to "com.google.android.keep"
    )
}

/**
 * OpenAppTool — Launch an app by name.
 *
 * Simplified from the former AppControlTool (which also had list_apps, package_name, filter).
 * Aligned with every reference repo: single `app_name` parameter, no agent-facing package names.
 *
 * Resolution strategy (ordered):
 * 1. Foreground check — skip if already open
 * 2. Exact label match (case-insensitive)
 * 3. Label contains search term
 * 4. Well-known alias → package
 * 5. Package-name-shaped input → direct launch
 * 6. Fuzzy suggestions on failure
 */
class OpenAppTool : ToolSpec {

    companion object {
        private const val TAG = "OpenAppTool"
        private const val UI_SETTLE_DELAY_MS = 800L
        private const val SUGGESTION_LIMIT = 5
    }

    override val name: String = "open_app"

    override val description: String = """
Launch an app by name. Always use this to open apps — do NOT navigate the app drawer or home screen manually.
If the app is not found, suggestions will be provided.
""".trimIndent()

    override val parameterSchema: JSONObject by lazy {
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("app_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the app to open (e.g., 'Gmail', 'Settings', 'Chrome'). Case-insensitive.")
                })
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for this action")
                })
            })
            put("required", JSONArray(listOf("app_name")))
            put("additionalProperties", false)
        }
    }

    override fun validate(params: JSONObject): ValidationResult {
        val appName = params.optString("app_name", "").trim()
        if (appName.isEmpty()) {
            return ValidationResult.Invalid("open_app requires app_name")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val appName = params.optString("app_name", "").trim()
        val agentThought = params.optString("agent_thought", "").trim()

        val desc = if (agentThought.isNotEmpty()) {
            "Open app: $appName (reason: $agentThought)"
        } else {
            "Open app: $appName"
        }

        return OpenAppInvocation(params, desc, appName)
    }
}

/**
 * Executable invocation for opening an app.
 *
 * Handles name resolution, foreground check, launch, and post-launch screen capture.
 */
private class OpenAppInvocation(
    override val params: JSONObject,
    private val description: String,
    private val appName: String
) : ToolInvocation {

    companion object {
        private const val TAG = "OpenAppInvocation"
        private const val UI_SETTLE_DELAY_MS = 800L
        private const val SUGGESTION_LIMIT = 5
    }

    override val toolName: String = "open_app"

    override fun getDescription(): String = description

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }

        val apps = context.platform.getInstalledApps()
        val searchTerm = appName.lowercase().trim()

        // --- Resolve package name ---

        // Strategy 1: Exact label match
        var match = apps.find { it.label.equals(appName, ignoreCase = true) }

        // Strategy 2: Label contains search term
        if (match == null) {
            match = apps.find { it.label.contains(appName, ignoreCase = true) }
        }

        // Strategy 3: Well-known alias → package
        if (match == null) {
            val aliasPackage = AppAliases.PACKAGE_MAP[searchTerm]
            if (aliasPackage != null) {
                match = apps.find { it.packageName == aliasPackage }
            }
        }

        // Strategy 4: Input looks like a package name (e.g. "com.google.android.gm")
        if (match == null && looksLikePackageName(searchTerm)) {
            match = apps.find { it.packageName.equals(searchTerm, ignoreCase = true) }
                ?: apps.find { it.packageName.contains(searchTerm, ignoreCase = true) }
        }

        if (match == null) {
            val suggestions = findSimilarApps(searchTerm, apps)
            val suggestionText = if (suggestions.isNotEmpty()) {
                "Similar apps: ${suggestions.joinToString(", ")}. Try again with the correct name."
            } else {
                "No similar apps found on this device."
            }
            Log.w(TAG, "App not found: '$appName'. $suggestionText")
            return ToolExecutionResult.Failure(
                "App not found: '$appName'. $suggestionText"
            )
        }

        val targetPackage = match.packageName
        Log.d(TAG, "Resolved '$appName' -> $targetPackage (${match.label})")

        // --- Foreground check: skip re-launch if already open ---
        val currentPackage = context.platform.getCurrentPackageName()
        if (currentPackage != null && currentPackage == targetPackage) {
            Log.d(TAG, "'${match.label}' is already in the foreground, skipping launch")
            return ToolExecutionResult.Success(
                output = "'${match.label}' is already in the foreground. No action needed."
            )
        }

        // --- Launch ---
        val result = context.platform.launchApp(targetPackage)

        return when (result) {
            is ActionResult.Success -> {
                delay(UI_SETTLE_DELAY_MS)

                val snapshot = try {
                    context.platform.captureScreen()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to capture screen after app launch", e)
                    null
                }

                val observation = snapshot?.let {
                    val tree = com.moonkey.androidagent.perception.Perceptor.toPromptJson(it)
                    ToolObservation.ScreenState(
                        accessibilityTree = tree,
                        elementCount = it.elements.size,
                        summary = it.toSummary(context.platform.getCurrentPackageName()),
                        snapshot = it
                    )
                }

                ToolExecutionResult.Success(
                    output = "Launched ${match.label} ($targetPackage)",
                    observation = observation
                )
            }
            is ActionResult.Failure -> ToolExecutionResult.Failure(
                "Failed to launch '${match.label}': ${result.reason}"
            )
            else -> ToolExecutionResult.Failure("Unexpected result: $result")
        }
    }

    // ---- Helpers ----

    /**
     * Check if input looks like a package name (e.g. "com.google.android.gm").
     */
    private fun looksLikePackageName(input: String): Boolean {
        return input.contains('.') && input.split('.').size >= 2
    }

    /**
     * Find similar app names for error suggestions.
     *
     * Uses simple heuristics: prefix match, substring overlap, character similarity.
     */
    private fun findSimilarApps(
        searchTerm: String,
        apps: List<com.moonkey.androidagent.platform.AppInfo>,
        limit: Int = SUGGESTION_LIMIT
    ): List<String> {
        val term = searchTerm.lowercase()
        val termChars = term.toSet()

        data class ScoredApp(val label: String, val score: Int)

        val scored = apps.mapNotNull { app ->
            val label = app.label.lowercase()
            val score = when {
                // Prefix match (e.g. "gma" → "gmail")
                label.startsWith(term) || term.startsWith(label) -> 4
                // Substring match
                label.contains(term) || term.contains(label) -> 3
                // Package name contains term
                app.packageName.lowercase().contains(term) -> 2
                // Character overlap > 50%
                termChars.isNotEmpty() &&
                    termChars.intersect(label.toSet()).size > termChars.size / 2 -> 1
                else -> 0
            }
            if (score > 0) ScoredApp(app.label, score) else null
        }

        return scored
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.label }
    }
}
