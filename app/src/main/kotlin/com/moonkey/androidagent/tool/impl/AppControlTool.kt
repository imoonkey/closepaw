package com.moonkey.androidagent.tool.impl

import android.content.Intent
import android.util.Log
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.tool.MultiActionTool
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolObservation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.DataQueryInvocation
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * AppControlTool - Tool for app discovery and launching.
 * 
 * Actions:
 * - list_apps: Get list of installed launchable apps (returns data)
 * - open_app: Launch an app by package name or display name (performs action)
 */
class AppControlTool : MultiActionTool() {
    
    companion object {
        private const val TAG = "AppControlTool"
    }
    
    override val name: String = "app_control"
    
    override val description: String = """
Control apps on the device.

Actions:
- list_apps: Get list of installed launchable apps. Use filter to search by name.
- open_app: Launch an app by package_name (e.g., 'com.google.android.gm') or app_name (e.g., 'Gmail'). Package name takes precedence if both provided.
""".trimIndent()
    
    override val actionHandlers: Map<String, ActionHandler> = mapOf(
        "list_apps" to ListAppsActionHandler(),
        "open_app" to OpenAppActionHandler()
    )
    
    override val parameterSchema: JSONObject by lazy {
        createActionSchema(
            actionDescription = "The action to perform",
            additionalProperties = mapOf(
                "package_name" to PropertySpec(
                    type = "string",
                    description = "Package name for open_app (e.g., 'com.google.android.gm' for Gmail)"
                ),
                "app_name" to PropertySpec(
                    type = "string",
                    description = "Display name for open_app (e.g., 'Gmail'). Case-insensitive fuzzy match."
                ),
                "filter" to PropertySpec(
                    type = "string",
                    description = "Filter for list_apps. Case-insensitive substring match on app name."
                )
            )
        )
    }
}

// =============================================================================
// Action Handlers
// =============================================================================

/**
 * List apps action - returns installed launchable apps.
 */
class ListAppsActionHandler : ActionHandler {
    
    companion object {
        private const val TAG = "ListAppsAction"
    }
    
    override val actionName = "list_apps"
    
    override fun validate(params: JSONObject): ValidationResult {
        // No required parameters
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val filter = params.optString("filter", "")
        val description = if (filter.isNotEmpty()) {
            "List apps matching '$filter'"
        } else {
            "List all installed apps"
        }
        
        return DataQueryInvocation(
            toolName = "app_control",
            params = params,
            description = description,
            queryFn = { context -> queryApps(context, filter) }
        )
    }
    
    private suspend fun queryApps(context: ToolExecutionContext, filter: String): String {
        val apps = context.platform.getInstalledApps()
        
        // Debug: log total apps and first few
        Log.d(TAG, "getInstalledApps returned ${apps.size} apps")
        apps.take(5).forEach { app ->
            Log.d(TAG, "  App: ${app.label} (${app.packageName})")
        }
        
        val filtered = if (filter.isNotEmpty()) {
            val searchTerm = filter.lowercase()
            
            // Well-known aliases for common search terms
            val knownAliases = mapOf(
                "map" to listOf("maps", "地图", "com.google.android.apps.maps"),
                "maps" to listOf("map", "地图", "com.google.android.apps.maps"),
                "browser" to listOf("chrome", "浏览器", "com.android.chrome"),
                "search" to listOf("google", "chrome", "browser"),
                "video" to listOf("youtube", "tiktok"),
                "email" to listOf("gmail", "mail"),
                "chat" to listOf("whatsapp", "wechat", "微信", "messenger"),
                "music" to listOf("spotify", "music", "yt music")
            )
            
            val aliasTerms = knownAliases[searchTerm] ?: emptyList()
            val allSearchTerms = listOf(searchTerm) + aliasTerms
            
            apps.filter { app ->
                allSearchTerms.any { term ->
                    app.label.contains(term, ignoreCase = true) ||
                    app.packageName.lowercase().contains(term)
                }
            }
        } else {
            apps
        }
        
        // Debug: log filter results
        if (filter.isNotEmpty()) {
            Log.d(TAG, "Filter '$filter' matched ${filtered.size} apps")
        }
        
        // Sort by label for consistent output
        val sorted = filtered.sortedBy { it.label.lowercase() }
        
        return JSONObject().apply {
            put("apps", JSONArray().apply {
                sorted.forEach { app ->
                    put(JSONObject().apply {
                        put("package_name", app.packageName)
                        put("label", app.label)
                    })
                }
            })
            put("count", sorted.size)
            if (filter.isNotEmpty()) {
                put("filter", filter)
            }
        }.toString(2)  // Pretty print for readability
    }
}

/**
 * Open app action - launches an app.
 */
class OpenAppActionHandler : ActionHandler {
    
    companion object {
        private const val TAG = "OpenAppActionHandler"
        private const val UI_SETTLE_DELAY_MS = 500L
    }
    
    override val actionName = "open_app"
    
    override fun validate(params: JSONObject): ValidationResult {
        val packageName = params.optString("package_name", "")
        val appName = params.optString("app_name", "")
        
        if (packageName.isEmpty() && appName.isEmpty()) {
            return ValidationResult.Invalid(
                "open_app requires either package_name or app_name"
            )
        }
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val packageName = params.optString("package_name", "")
        val appName = params.optString("app_name", "")
        
        val description = when {
            packageName.isNotEmpty() -> "Open app: $packageName"
            else -> "Open app: $appName"
        }
        
        return OpenAppInvocation(params, description, packageName, appName)
    }
}

/**
 * Custom invocation for opening apps.
 * 
 * This needs special handling because:
 * 1. It may need to resolve app_name to package_name
 * 2. It performs an intent launch, not a UIAction
 * 3. It should capture post-action screen state
 */
class OpenAppInvocation(
    override val params: JSONObject,
    private val description: String,
    private val packageName: String,
    private val appName: String
) : ToolInvocation {
    
    companion object {
        private const val TAG = "OpenAppInvocation"
        private const val UI_SETTLE_DELAY_MS = 800L  // Apps need more time to launch
    }
    
    override val toolName: String = "app_control"
    
    override fun getDescription(): String = description
    
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }
        
        // 1. Resolve package name
        val targetPackage = if (packageName.isNotEmpty()) {
            packageName
        } else {
            // Find by app name with multiple matching strategies
            val apps = context.platform.getInstalledApps()
            val searchTerm = appName.lowercase()
            
            // Strategy 1: Exact label match (case-insensitive)
            val match = apps.find { 
                it.label.equals(appName, ignoreCase = true) 
            } 
            // Strategy 2: Label contains search term
            ?: apps.find { 
                it.label.contains(appName, ignoreCase = true) 
            }
            // Strategy 3: Package name contains search term (handles "maps" -> "com.google.android.apps.maps")
            ?: apps.find {
                it.packageName.lowercase().contains(searchTerm)
            }
            // Strategy 4: Try well-known app aliases (handles "Google Maps" -> maps, "Chrome" etc.)
            ?: run {
                val knownAliases = mapOf(
                    "google maps" to "com.google.android.apps.maps",
                    "maps" to "com.google.android.apps.maps",
                    "chrome" to "com.android.chrome",
                    "google chrome" to "com.android.chrome",
                    "gmail" to "com.google.android.gm",
                    "youtube" to "com.google.android.youtube",
                    "play store" to "com.android.vending",
                    "google play" to "com.android.vending",
                    "files" to "com.google.android.apps.nbu.files",
                    "phone" to "com.android.dialer",
                    "dialer" to "com.android.dialer",
                    "camera" to "com.android.camera",
                    "settings" to "com.android.settings",
                    "messages" to "com.google.android.apps.messaging",
                    "sms" to "com.google.android.apps.messaging"
                )
                val aliasPackage = knownAliases[searchTerm]
                if (aliasPackage != null) {
                    apps.find { it.packageName == aliasPackage }
                } else {
                    null
                }
            }
            
            if (match == null) {
                Log.w(TAG, "App not found: '$appName'. Available apps: ${apps.map { "${it.label}(${it.packageName})" }.take(10)}")
                return ToolExecutionResult.Failure(
                    "App not found: '$appName'. Use list_apps to see available apps."
                )
            }
            Log.d(TAG, "Resolved '$appName' -> ${match.packageName} (${match.label})")
            match.packageName
        }
        
        // 2. Launch the app
        val result = context.platform.launchApp(targetPackage)
        
        return when (result) {
            is ActionResult.Success -> {
                // Wait for app to launch and capture screen
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
                        snapshot = it
                    )
                }
                
                ToolExecutionResult.Success(
                    output = "Launched app: $targetPackage",
                    observation = observation
                )
            }
            is ActionResult.Failure -> ToolExecutionResult.Failure(
                "Failed to launch app: ${result.reason}"
            )
            else -> ToolExecutionResult.Failure("Unexpected result: $result")
        }
    }
}
