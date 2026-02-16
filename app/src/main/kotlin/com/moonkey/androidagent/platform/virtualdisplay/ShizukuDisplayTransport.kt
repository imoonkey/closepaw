package com.moonkey.androidagent.platform.virtualdisplay

import android.hardware.display.IVirtualDisplayCallback
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/** Transport layer for virtual display lifecycle calls through IDisplayManager. */
internal class ShizukuDisplayTransport(
        private val proxyProvider: ShizukuServiceProxyProvider
) {
        companion object {
                private const val TAG = "ShizukuDisplayTrans"
        }

        /**
         * Stored callbacks from createVirtualDisplay, keyed by displayId.
         * Required for setVirtualDisplaySurface on ROMs that validate callback tokens.
         */
        private val displayCallbacks = ConcurrentHashMap<Int, IVirtualDisplayCallback>()

        fun clear() {
                displayCallbacks.clear()
        }

        fun createVirtualDisplay(
                name: String,
                width: Int,
                height: Int,
                densityDpi: Int,
                surface: Surface,
                flags: Int
        ): Int {
                return try {
                        val proxy = proxyProvider.displayManagerProxy()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                createVirtualDisplayApi33(
                                        proxy,
                                        name,
                                        width,
                                        height,
                                        densityDpi,
                                        surface,
                                        flags
                                )
                        } else {
                                createVirtualDisplayLegacy(
                                        proxy,
                                        name,
                                        width,
                                        height,
                                        densityDpi,
                                        surface,
                                        flags
                                )
                        }
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to create virtual display", e)
                        -1
                }
        }

        fun setVirtualDisplaySurface(displayId: Int, surface: Surface): Boolean {
                if (displayId < 0) return false
                return try {
                        val proxy = proxyProvider.displayManagerProxy()
                        val callback = displayCallbacks[displayId]
                        if (callback == null) {
                                Log.e(TAG, "No callback token for display $displayId")
                                return false
                        }
                        val method =
                                proxy.javaClass.getMethod(
                                        "setVirtualDisplaySurface",
                                        IVirtualDisplayCallback::class.java,
                                        Surface::class.java
                                )
                        method.invoke(proxy, callback, surface)
                        Log.d(TAG, "Set virtual display $displayId surface")
                        true
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to set virtual display $displayId surface", e)
                        false
                }
        }

        fun releaseVirtualDisplay(displayId: Int) {
                if (displayId < 0) return
                try {
                        val proxy = proxyProvider.displayManagerProxy()
                        val callback = displayCallbacks[displayId]
                        if (callback != null) {
                                val method =
                                        proxy.javaClass.getMethod(
                                                "releaseVirtualDisplay",
                                                IVirtualDisplayCallback::class.java
                                        )
                                method.invoke(proxy, callback)
                        } else {
                                Log.w(TAG, "No callback token for display $displayId, skipping release")
                        }
                        displayCallbacks.remove(displayId)
                        Log.d(TAG, "Released virtual display $displayId")
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to release virtual display $displayId", e)
                }
        }

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

                builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
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
                                        String::class.java,
                                        String::class.java,
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType,
                                        Surface::class.java,
                                        Int::class.javaPrimitiveType,
                                        String::class.java
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
                                ) as Int
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
                                String::class.java,
                                Surface::class.java,
                                Int::class.javaPrimitiveType,
                                String::class.java
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
