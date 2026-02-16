package com.moonkey.androidagent.platform.virtualdisplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

/**
 * ShizukuClient — Thin wrapper for Shizuku binder calls.
 *
 * Every public method is a direct proxy to a system service through ShizukuBinderWrapper. No
 * caching, no business logic, no cleverness.
 *
 * Uses reflection on the framework's own IDisplayManager/IInputManager stubs (via
 * ShizukuBinderWrapper) so transaction IDs always match the device's framework version. No custom
 * AIDL files needed.
 */
class ShizukuClient {

    companion object {
        private const val TAG = "ShizukuClient"

        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */
        const val INJECT_MODE_WAIT = 2
    }

    // ── Shizuku Status ──────────────────────────────────────────

    /** True if Shizuku binder is alive and responding. */
    fun isAvailable(): Boolean =
            try {
                Shizuku.pingBinder()
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku ping failed: ${e.message}")
                false
            }

    /** True if we have been granted Shizuku permission. */
    fun hasPermission(): Boolean =
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku permission check failed: ${e.message}")
                false
            }

    /** Request Shizuku permission from the user. */
    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    /** Register a listener for Shizuku binder death. */
    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.addBinderDeadListener(listener)
    }

    /** Remove a previously registered binder death listener. */
    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.removeBinderDeadListener(listener)
    }

    // ── Hidden API Bypass ───────────────────────────────────────

    /**
     * Exempt all hidden APIs for this process. Must be called before any reflection on framework
     * internals.
     */
    fun bypassHiddenApis() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
            Log.d(TAG, "Hidden API restrictions bypassed")
        }
    }

    // ── Display Management ──────────────────────────────────────

    private val shellExecutor = ShizukuShellExecutor()
    private val proxyProvider = ShizukuServiceProxyProvider()
    private val displayTransport = ShizukuDisplayTransport(proxyProvider)

    /**
     * Create a virtual display via IDisplayManager through Shizuku.
     *
     * @return displayId of the created virtual display, or -1 on failure
     */
    fun createVirtualDisplay(
            name: String,
            width: Int,
            height: Int,
            densityDpi: Int,
            surface: Surface,
            flags: Int
    ): Int {
        return displayTransport.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
    }

    /**
     * Switch the surface a virtual display renders to.
     *
     * Uses IDisplayManager.setVirtualDisplaySurface(callback, displayId, surface). The callback
     * token must match the one used in createVirtualDisplay.
     *
     * @return true if the surface was switched successfully
     */
    fun setVirtualDisplaySurface(displayId: Int, surface: Surface): Boolean {
        return displayTransport.setVirtualDisplaySurface(displayId, surface)
    }

    /** Release a virtual display. */
    fun releaseVirtualDisplay(displayId: Int) {
        displayTransport.releaseVirtualDisplay(displayId)
    }

    // ── Input Injection ─────────────────────────────────────────

    /**
     * Inject an input event via IInputManager through Shizuku.
     *
     * The event must have displayId set before calling this. Uses
     * INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH for synchronous delivery.
     *
     * @return true if injection succeeded
     */
    fun injectInputEvent(event: InputEvent, mode: Int = INJECT_MODE_WAIT): Boolean {
        return try {
            val proxy = getInputManagerProxy()
            val method =
                    proxy.javaClass.getMethod(
                            "injectInputEvent",
                            InputEvent::class.java,
                            Int::class.javaPrimitiveType
                    )
            method.invoke(proxy, event, mode) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject input event", e)
            false
        }
    }

    // ── App Launch ──────────────────────────────────────────────

    /**
     * Launch an activity on a specific display.
     *
     * Uses ActivityOptions.setLaunchDisplayId() which requires shell permission for non-default
     * displays.
     */
    fun launchOnDisplay(context: Context, intent: Intent, displayId: Int) {
        try {
            val optionsClass = Class.forName("android.app.ActivityOptions")
            val options = optionsClass.getMethod("makeBasic").invoke(null)
            optionsClass
                    .getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                    .invoke(options, displayId)
            val bundle = optionsClass.getMethod("toBundle").invoke(options) as android.os.Bundle
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), bundle)
            Log.d(TAG, "Launched activity on display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch on display $displayId", e)
        }
    }

    /**
     * Execute a shell command via Shizuku.
     *
     * Prefers Shizuku.newProcess() direct API when available; falls back to reflection for
     * compatibility. Logs non-zero exit codes and exceptions.
     *
     * @return Exit code of the command, or -1 on failure
     */
    fun executeShellCommand(command: Array<String>): Int {
        return shellExecutor.execute(command)
    }

    // ── Proxy Lifecycle ──────────────────────────────────────────

    /**
     * Clear cached binder proxies. Call during platform stop/cleanup. Safe to call even if proxies
     * were never created.
     */
    fun clearCachedProxies() {
        proxyProvider.clear()
        displayTransport.clear()
        Log.d(TAG, "Cleared cached binder proxies")
    }

    // ── Private: Binder Proxy Acquisition ───────────────────────

    private fun getInputManagerProxy(): Any {
        return proxyProvider.inputManagerProxy()
    }
}
