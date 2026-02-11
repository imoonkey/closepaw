package com.moonkey.androidagent.platform

import com.moonkey.androidagent.model.ScreenSnapshot

/**
 * AndroidPlatform - Abstraction for Android-specific operations.
 * 
 * This interface allows the orchestration logic to be tested without
 * requiring an actual Android device or accessibility service.
 * 
 * Implementations:
 * - AccessibilityPlatform: Real implementation using AccessibilityService
 * - MockPlatform: Test implementation with predefined responses
 */
interface AndroidPlatform {

    /**
     * Initialize platform resources.
     *
     * Called once before the first captureScreen/performAction.
     * AccessibilityPlatform: no-op (already ready when service is connected).
     * VirtualDisplayPlatform: creates the virtual display + ImageReader.
     */
    suspend fun start() {}

    /**
     * Release platform resources.
     *
     * Called during session cleanup. Must be idempotent.
     * AccessibilityPlatform: no-op.
     * VirtualDisplayPlatform: releases virtual display + ImageReader.
     */
    suspend fun stop() {}

    /**
     * Capture the current screen state.
     * 
     * @return ScreenSnapshot containing UI elements and their properties
     */
    suspend fun captureScreen(): ScreenSnapshot
    
    /**
     * Perform an atomic UI action on the device.
     *
     * Each UIAction variant maps to exactly one Android API call.
     * No fallback, no target resolution, no UI change detection.
     *
     * @param action The atomic action to perform
     * @return ActionResult indicating success or failure
     */
    suspend fun performAction(action: UIAction): ActionResult
    
    /**
     * Check if the platform has all required permissions.
     * 
     * @return true if all permissions are available
     */
    fun hasRequiredPermissions(): Boolean
    
    /**
     * Get the current package name of the foreground app.
     * 
     * @return Package name or null if unavailable
     */
    fun getCurrentPackageName(): String?
    
    /**
     * Get display metrics.
     * 
     * @return DisplayInfo containing screen dimensions
     */
    fun getDisplayInfo(): DisplayInfo
    
    // =========================================================================
    // Platform Capabilities
    // =========================================================================

    /**
     * Whether tap-to-focus fallback is safe for text input.
     *
     * VD mode returns false: tapping to focus triggers IME on the wrong display.
     * A11y mode returns true: existing behavior, tap-to-focus works normally.
     */
    fun allowTapToFocus(): Boolean = true

    // =========================================================================
    // App Management (P0)
    // =========================================================================
    
    /**
     * Get list of installed launchable apps.
     * 
     * @return List of AppInfo for apps with launcher activities
     */
    suspend fun getInstalledApps(): List<AppInfo>
    
    /**
     * Launch an app by package name.
     * 
     * @param packageName The package name of the app to launch
     * @return ActionResult indicating success or failure
     */
    suspend fun launchApp(packageName: String): ActionResult
}

/**
 * DisplayInfo - Information about the device display.
 */
data class DisplayInfo(
    val widthPixels: Int,
    val heightPixels: Int,
    val density: Float
)

/**
 * AppInfo - Information about an installed app.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false
)

