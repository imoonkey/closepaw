package com.moonkey.androidagent.platform.virtualdisplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * ShizukuClient — Thin wrapper for Shizuku binder calls.
 *
 * Every public method is a direct proxy to a system service through
 * ShizukuBinderWrapper. No caching, no business logic, no cleverness.
 *
 * Uses reflection on the framework's own IDisplayManager/IInputManager stubs
 * (via ShizukuBinderWrapper) so transaction IDs always match the device's
 * framework version. No custom AIDL files needed.
 */
class ShizukuClient {

    companion object {
        private const val TAG = "ShizukuClient"

        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */
        const val INJECT_MODE_WAIT = 2

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        const val INJECT_MODE_ASYNC = 0
    }

    // ── Shizuku Status ──────────────────────────────────────────

    /** True if Shizuku binder is alive and responding. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        Log.w(TAG, "Shizuku ping failed: ${e.message}")
        false
    }

    /** True if we have been granted Shizuku permission. */
    fun hasPermission(): Boolean = try {
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
     * Exempt all hidden APIs for this process.
     * Must be called before any reflection on framework internals.
     */
    fun bypassHiddenApis() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
            Log.d(TAG, "Hidden API restrictions bypassed")
        }
    }

    // ── Display Management ──────────────────────────────────────

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
        return try {
            val proxy = getDisplayManagerProxy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                createVirtualDisplayApi33(proxy, name, width, height, densityDpi, surface, flags)
            } else {
                createVirtualDisplayLegacy(proxy, name, width, height, densityDpi, surface, flags)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display", e)
            -1
        }
    }

    /**
     * Release a virtual display.
     */
    fun releaseVirtualDisplay(displayId: Int) {
        if (displayId < 0) return
        try {
            val proxy = getDisplayManagerProxy()
            val method = proxy.javaClass.getMethod("releaseVirtualDisplay", Int::class.javaPrimitiveType)
            method.invoke(proxy, displayId)
            Log.d(TAG, "Released virtual display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release virtual display $displayId", e)
        }
    }

    // ── Input Injection ─────────────────────────────────────────

    /**
     * Inject an input event via IInputManager through Shizuku.
     *
     * The event must have displayId set before calling this.
     * Uses INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH for synchronous delivery.
     *
     * @return true if injection succeeded
     */
    fun injectInputEvent(event: InputEvent, mode: Int = INJECT_MODE_WAIT): Boolean {
        return try {
            val proxy = getInputManagerProxy()
            val method = proxy.javaClass.getMethod(
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
     * Uses ActivityOptions.setLaunchDisplayId() which requires shell permission
     * for non-default displays.
     */
    fun launchOnDisplay(context: Context, intent: Intent, displayId: Int) {
        try {
            val optionsClass = Class.forName("android.app.ActivityOptions")
            val options = optionsClass.getMethod("makeBasic").invoke(null)
            optionsClass.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                .invoke(options, displayId)
            val bundle = optionsClass.getMethod("toBundle").invoke(options) as android.os.Bundle

            // Use Shizuku's transactRemote or IActivityManager to start activity as shell
            // Fallback: context.startActivity with the options bundle
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), bundle)
            Log.d(TAG, "Launched activity on display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch on display $displayId", e)
        }
    }

    // ── Private: Binder Proxy Acquisition ───────────────────────

    private fun getDisplayManagerProxy(): Any {
        val binder = SystemServiceHelper.getSystemService("display")
        val wrapped = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        return stubClass.getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped)!!
    }

    private fun getInputManagerProxy(): Any {
        val binder = SystemServiceHelper.getSystemService("input")
        val wrapped = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
        return stubClass.getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped)!!
    }

    // ── Private: Version-specific Display Creation ──────────────

    /**
     * API 33+ (Tiramisu): Uses VirtualDisplayConfig parameter object.
     */
    private fun createVirtualDisplayApi33(
        proxy: Any,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int
    ): Int {
        // Build VirtualDisplayConfig via its Builder
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass.getConstructor(String::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(name, width, height, densityDpi)

        // Set surface
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        // Set flags
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)

        val config = builderClass.getMethod("build").invoke(builder)!!

        // IVirtualDisplayCallback stub — we don't need callbacks, pass null-safe stub
        val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        val callbackStubClass = Class.forName("android.hardware.display.IVirtualDisplayCallback\$Stub")
        val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
            callbackStubClass.classLoader,
            arrayOf(callbackClass)
        ) { _, _, _ -> null }

        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val method = proxy.javaClass.getMethod(
            "createVirtualDisplay",
            configClass,
            callbackClass,
            Class.forName("android.media.projection.IMediaProjection"),
            String::class.java
        )

        val displayId = method.invoke(proxy, config, callbackProxy, null, "com.moonkey.androidagent") as Int
        Log.d(TAG, "Created virtual display (API33+): displayId=$displayId")
        return displayId
    }

    /**
     * API 31-32: Legacy method with individual parameters.
     */
    private fun createVirtualDisplayLegacy(
        proxy: Any,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int
    ): Int {
        // The legacy signature varies. Try the most common one first.
        val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        val callbackStubClass = Class.forName("android.hardware.display.IVirtualDisplayCallback\$Stub")
        val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
            callbackStubClass.classLoader,
            arrayOf(callbackClass)
        ) { _, _, _ -> null }

        val projectionClass = Class.forName("android.media.projection.IMediaProjection")

        // Try: createVirtualDisplay(callback, projection, packageName, name, width, height, densityDpi, surface, flags, uniqueId)
        return try {
            val method = proxy.javaClass.getMethod(
                "createVirtualDisplay",
                callbackClass,
                projectionClass,
                String::class.java,  // packageName
                String::class.java,  // name
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
                Int::class.javaPrimitiveType,
                String::class.java   // uniqueId
            )
            val displayId = method.invoke(
                proxy, callbackProxy, null, "com.moonkey.androidagent",
                name, width, height, densityDpi, surface, flags, null
            ) as Int
            Log.d(TAG, "Created virtual display (legacy): displayId=$displayId")
            displayId
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Legacy createVirtualDisplay signature not found, trying alternative")
            createVirtualDisplayLegacyAlt(proxy, name, width, height, densityDpi, surface, flags,
                callbackProxy, callbackClass, projectionClass)
        }
    }

    /**
     * Alternative legacy signature for some API 31 builds.
     */
    private fun createVirtualDisplayLegacyAlt(
        proxy: Any,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int,
        callbackProxy: Any,
        callbackClass: Class<*>,
        projectionClass: Class<*>
    ): Int {
        val method = proxy.javaClass.getMethod(
            "createVirtualDisplay",
            callbackClass,
            projectionClass,
            String::class.java,  // packageName
            Surface::class.java,
            Int::class.javaPrimitiveType,  // flags
            String::class.java  // name
        )
        val displayId = method.invoke(
            proxy, callbackProxy, null, "com.moonkey.androidagent",
            surface, flags, name
        ) as Int
        Log.d(TAG, "Created virtual display (legacy-alt): displayId=$displayId")
        return displayId
    }
}
