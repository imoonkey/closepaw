package com.moonkey.androidagent.platform

import com.moonkey.androidagent.domain.models.ScreenSnapshot

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
     * Capture the current screen state.
     * 
     * @return ScreenSnapshot containing UI elements and their properties
     */
    suspend fun captureScreen(): ScreenSnapshot
    
    /**
     * Perform a UI action on the device.
     * 
     * @param action The action to perform
     * @param snapshot Optional snapshot for element lookup (required for element-based actions)
     * @return ActionResult indicating success or failure
     */
    suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot? = null): ActionResult
    
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
}

/**
 * DisplayInfo - Information about the device display.
 */
data class DisplayInfo(
    val widthPixels: Int,
    val heightPixels: Int,
    val density: Float
)

