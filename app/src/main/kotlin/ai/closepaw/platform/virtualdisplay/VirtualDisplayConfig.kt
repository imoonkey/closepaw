package ai.closepaw.platform.virtualdisplay

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * VirtualDisplayConfig — Immutable configuration for the agent's virtual display.
 *
 * Specifies the resolution and density of the virtual display to create.
 * Mirrors the physical display's full dimensions (including nav bar and cutout)
 * so apps render at the same layout they would on the real screen.
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
         * Create a config matching the device's real display dimensions.
         *
         * Uses WindowManager.maximumWindowMetrics (API 31+) to get the full physical
         * display size including nav bar and display cutout, rather than the app content
         * area from Resources.displayMetrics which excludes system insets.
         */
        fun fromPhysicalDisplay(context: Context): VirtualDisplayConfig {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = context.resources.displayMetrics.density
            val densityDpi = context.resources.displayMetrics.densityDpi

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bounds = wm.maximumWindowMetrics.bounds
                return VirtualDisplayConfig(
                    width = bounds.width(),
                    height = bounds.height(),
                    densityDpi = densityDpi,
                    density = density
                )
            }

            // Pre-API 31 fallback: use getRealMetrics for full display size
            @Suppress("DEPRECATION")
            val realMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(realMetrics)
            return VirtualDisplayConfig(
                width = realMetrics.widthPixels,
                height = realMetrics.heightPixels,
                densityDpi = densityDpi,
                density = density
            )
        }
    }
}
