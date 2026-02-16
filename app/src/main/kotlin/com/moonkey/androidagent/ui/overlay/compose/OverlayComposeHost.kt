package com.moonkey.androidagent.ui.overlay.compose

import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner

/**
 * Small utility for WindowManager overlays backed by ComposeView.
 */
class OverlayComposeHost(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner,
    private val windowManager: WindowManager,
    private val tag: String,
) {
    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    fun isShowing(): Boolean = composeView != null

    fun show(
        layoutParams: WindowManager.LayoutParams,
        content: @Composable () -> Unit,
    ) {
        if (composeView != null) return
        try {
            val view = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
                setContent(content)
            }
            windowManager.addView(view, layoutParams)
            composeView = view
            params = layoutParams
        } catch (e: Exception) {
            Log.e(tag, "Failed to show Compose overlay", e)
        }
    }

    fun hide() {
        val view = composeView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(tag, "Failed to remove Compose overlay", e)
        }
        composeView = null
        params = null
    }

    fun updateLayoutParams(update: (WindowManager.LayoutParams) -> Unit) {
        val view = composeView ?: return
        val layoutParams = params ?: return
        update(layoutParams)
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (e: Exception) {
            Log.w(tag, "Failed to update Compose overlay params", e)
        }
    }

    fun getWindowToken() = composeView?.windowToken

    fun dispose() {
        hide()
    }
}
