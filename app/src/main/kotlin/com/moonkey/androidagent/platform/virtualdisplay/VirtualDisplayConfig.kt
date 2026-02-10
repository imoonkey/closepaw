package com.moonkey.androidagent.platform.virtualdisplay

import android.content.Context

/**
 * VirtualDisplayConfig — Immutable configuration for the agent's virtual display.
 *
 * Specifies the resolution and density of the virtual display to create.
 * Typically mirrors the physical display to ensure apps render at the
 * same size they would on the real screen.
 */
data class VirtualDisplayConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val density: Float
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(densityDpi > 0) { "densityDpi must be positive, was $densityDpi" }
        require(density > 0f) { "density must be positive, was $density" }
    }

    companion object {
        /**
         * Create a config matching the device's physical display.
         *
         * This ensures apps on the virtual display render at the exact same
         * size and layout as on the real screen.
         */
        fun fromPhysicalDisplay(context: Context): VirtualDisplayConfig {
            val dm = context.resources.displayMetrics
            return VirtualDisplayConfig(
                width = dm.widthPixels,
                height = dm.heightPixels,
                densityDpi = dm.densityDpi,
                density = dm.density
            )
        }
    }
}
