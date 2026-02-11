package com.moonkey.androidagent.platform.virtualdisplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.IVirtualDisplayCallback
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

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

    /**
     * Stored callbacks from createVirtualDisplay, keyed by displayId.
     * Required for setVirtualDisplaySurface on ROMs that validate the callback token.
     */
    private val displayCallbacks = ConcurrentHashMap<Int, IVirtualDisplayCallback>()

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
     * Switch the surface a virtual display renders to.
     *
     * Uses IDisplayManager.setVirtualDisplaySurface(callback, displayId, surface).
     * The callback token must match the one used in createVirtualDisplay.
     *
     * @return true if the surface was switched successfully
     */
    fun setVirtualDisplaySurface(displayId: Int, surface: Surface): Boolean {
        if (displayId < 0) return false
        return try {
            val proxy = getDisplayManagerProxy()
            val callback = displayCallbacks[displayId]
            val method = proxy.javaClass.getMethod(
                    "setVirtualDisplaySurface",
                    IVirtualDisplayCallback::class.java,
                    Int::class.javaPrimitiveType,
                    Surface::class.java
            )
            method.invoke(proxy, callback, displayId, surface)
            Log.d(TAG, "Set virtual display $displayId surface (callback=${callback != null})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set virtual display $displayId surface", e)
            false
        }
    }

    /** Release a virtual display. */
    fun releaseVirtualDisplay(displayId: Int) {
        if (displayId < 0) return
        try {
            val proxy = getDisplayManagerProxy()
            val method =
                    proxy.javaClass.getMethod("releaseVirtualDisplay", Int::class.javaPrimitiveType)
            method.invoke(proxy, displayId)
            displayCallbacks.remove(displayId)
            Log.d(TAG, "Released virtual display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release virtual display $displayId", e)
        }
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
        return try {
            val process = newProcessViaShizuku(command)
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroy()
                Log.e(TAG, "Shell command timed out: ${command.joinToString(" ")}")
                return -1
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().use { it.readText() }
                Log.w(
                        TAG,
                        "Shell command non-zero exit ($exitCode): ${command.joinToString(" ")}\n$error"
                )
            } else {
                Log.d(TAG, "Shell command success: ${command.joinToString(" ")}")
            }
            exitCode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute shell command: ${command.joinToString(" ")}", e)
            -1
        }
    }

    /**
     * Obtain a Process via Shizuku.
     *
     * Some Shizuku versions expose newProcess as a public method, while others keep it private.
     * Resolve public signature first, then fall back to declared/private method.
     */
    private fun newProcessViaShizuku(command: Array<String>): Process {
        val shizukuClass = Shizuku::class.java
        val method =
                runCatching {
                            shizukuClass.getMethod(
                                    "newProcess",
                                    Array<String>::class.java,
                                    Array<String>::class.java,
                                    String::class.java
                            )
                        }
                        .getOrNull()
                        ?: shizukuClass.getDeclaredMethod(
                                "newProcess",
                                Array<String>::class.java,
                                Array<String>::class.java,
                                String::class.java
                        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    // ── Proxy Lifecycle ──────────────────────────────────────────

    /**
     * Clear cached binder proxies. Call during platform stop/cleanup. Safe to call even if proxies
     * were never created.
     */
    fun clearCachedProxies() {
        cachedDisplayProxy = null
        cachedInputProxy = null
        displayCallbacks.clear()
        Log.d(TAG, "Cleared cached binder proxies")
    }

    // ── Private: Binder Proxy Acquisition ───────────────────────

    @Volatile private var cachedDisplayProxy: Any? = null
    @Volatile private var cachedInputProxy: Any? = null

    private fun getDisplayManagerProxy(): Any {
        cachedDisplayProxy?.let {
            return it
        }
        val binder =
                SystemServiceHelper.getSystemService("display")
                        ?: throw IllegalStateException("Cannot obtain display service binder")
        val wrapped = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        val proxy =
                stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
                        ?: throw IllegalStateException(
                                "IDisplayManager.Stub.asInterface returned null"
                        )
        cachedDisplayProxy = proxy
        return proxy
    }

    private fun getInputManagerProxy(): Any {
        cachedInputProxy?.let {
            return it
        }
        val binder =
                SystemServiceHelper.getSystemService("input")
                        ?: throw IllegalStateException("Cannot obtain input service binder")
        val wrapped = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
        val proxy =
                stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
                        ?: throw IllegalStateException(
                                "IInputManager.Stub.asInterface returned null"
                        )
        cachedInputProxy = proxy
        return proxy
    }

    // ── Private: Version-specific Display Creation ──────────────

    /** API 33+ (Tiramisu): Uses VirtualDisplayConfig parameter object. */
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
        val builder =
                builderClass
                        .getConstructor(
                                String::class.java,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType
                        )
                        .newInstance(name, width, height, densityDpi)

        // Set surface
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        // Set flags
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)

        val config =
                builderClass.getMethod("build").invoke(builder)
                        ?: throw IllegalStateException(
                                "VirtualDisplayConfig.Builder.build() returned null"
                        )

        val callback =
                object : IVirtualDisplayCallback.Stub() {
                    override fun onPaused() {}
                    override fun onResumed() {}
                    override fun onStopped() {}
                }

        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val method =
                proxy.javaClass.getMethod(
                        "createVirtualDisplay",
                        configClass,
                        IVirtualDisplayCallback::class.java,
                        Class.forName("android.media.projection.IMediaProjection"),
                        String::class.java
                )

        val displayId = method.invoke(proxy, config, callback, null, "com.android.shell") as Int
        if (displayId >= 0) {
            displayCallbacks[displayId] = callback
        }
        Log.d(TAG, "Created virtual display (API33+): displayId=$displayId")
        return displayId
    }

    /** API 31-32: Legacy method with individual parameters. */
    private fun createVirtualDisplayLegacy(
            proxy: Any,
            name: String,
            width: Int,
            height: Int,
            densityDpi: Int,
            surface: Surface,
            flags: Int
    ): Int {
        val callback =
                object : IVirtualDisplayCallback.Stub() {
                    override fun onPaused() {}
                    override fun onResumed() {}
                    override fun onStopped() {}
                }
        val projectionClass = Class.forName("android.media.projection.IMediaProjection")

        return try {
            val method =
                    proxy.javaClass.getMethod(
                            "createVirtualDisplay",
                            IVirtualDisplayCallback::class.java,
                            projectionClass,
                            String::class.java, // packageName
                            String::class.java, // name
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Surface::class.java,
                            Int::class.javaPrimitiveType,
                            String::class.java // uniqueId
                    )
            val displayId =
                    method.invoke(
                            proxy,
                            callback,
                            null,
                            "com.android.shell",
                            name,
                            width,
                            height,
                            densityDpi,
                            surface,
                            flags,
                            null
                    ) as
                            Int
            if (displayId >= 0) {
                displayCallbacks[displayId] = callback
            }
            Log.d(TAG, "Created virtual display (legacy): displayId=$displayId")
            displayId
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Legacy createVirtualDisplay signature not found, trying alternative")
            createVirtualDisplayLegacyAlt(
                    proxy,
                    name,
                    width,
                    height,
                    densityDpi,
                    surface,
                    flags,
                    callback,
                    projectionClass
            )
        }
    }

    /** Alternative legacy signature for some API 31 builds. */
    private fun createVirtualDisplayLegacyAlt(
            proxy: Any,
            name: String,
            width: Int,
            height: Int,
            densityDpi: Int,
            surface: Surface,
            flags: Int,
            callback: IVirtualDisplayCallback,
            projectionClass: Class<*>
    ): Int {
        val method =
                proxy.javaClass.getMethod(
                        "createVirtualDisplay",
                        IVirtualDisplayCallback::class.java,
                        projectionClass,
                        String::class.java, // packageName
                        Surface::class.java,
                        Int::class.javaPrimitiveType, // flags
                        String::class.java // name
                )
        val displayId =
                method.invoke(proxy, callback, null, "com.android.shell", surface, flags, name) as
                        Int
        if (displayId >= 0) {
            displayCallbacks[displayId] = callback
        }
        Log.d(TAG, "Created virtual display (legacy-alt): displayId=$displayId")
        return displayId
    }
}
